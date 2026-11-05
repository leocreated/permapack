package dev.leo.permapack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import dev.leo.permapack.mixin.InventoryAccess;

/** Server-thread-only inventory. Players share the actual backing list, never copies. */
public final class SharedBag extends SavedData {
    public static final Codec<SharedBag> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.BOOL.fieldOf("active").forGetter(s -> s.active),
        Codec.BOOL.optionalFieldOf("keep_on_death", true).forGetter(s -> s.keepOnDeath),
        Codec.STRING.listOf().fieldOf("reservations").forGetter(s -> s.ledger.snapshot()),
        ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(s -> new ArrayList<>(s.items)),
        Codec.STRING.listOf().fieldOf("members").forGetter(s -> s.members.stream().map(UUID::toString).toList()),
        Codec.STRING.listOf().optionalFieldOf("retired", List.of()).forGetter(s -> s.retired.stream().map(UUID::toString).toList()),
        ItemStack.CODEC.listOf().optionalFieldOf("returns", List.of()).forGetter(s -> s.returns)
    ).apply(i, SharedBag::new));
    public static final SavedDataType<SharedBag> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("permapack", "shared_inventory"), SharedBag::new, CODEC, null);

    public final SlotLedger ledger;
    public final NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Set<UUID> retired = new LinkedHashSet<>();
    public final List<ItemStack> returns = new ArrayList<>();
    private final Map<UUID, ServerPlayer> attached = new LinkedHashMap<>();
    private final Map<UUID, Drag> drags = new HashMap<>();
    private static final class Drag {
        final AbstractContainerMenu menu;
        final int type;
        final ItemStack original;
        final Set<Integer> slots=new LinkedHashSet<>();
        Drag(AbstractContainerMenu menu,int type,ItemStack original) { this.menu=menu;this.type=type;this.original=original.copy(); }
    }
    public boolean active;
    public boolean keepOnDeath = true;
    private boolean returning;
    private boolean normalizing;
    private ItemStack commandDrop;
    public boolean internal;

    public SharedBag() { ledger = new SlotLedger(); }
    private SharedBag(boolean active, boolean keep, List<String> bindings, List<ItemStack> stacks,
                      List<String> members, List<String> retired, List<ItemStack> pending) {
        if (stacks.size() != 36) throw new IllegalArgumentException("Invalid PermaPack inventory length");
        this.active = active; this.keepOnDeath = keep; ledger = new SlotLedger(bindings);
        for (int n = 0; n < 36; n++) items.set(n, stacks.get(n));
        members.forEach(id -> this.members.add(UUID.fromString(id)));
        retired.forEach(id -> this.retired.add(UUID.fromString(id)));
        pending.forEach(s -> returns.add(s.copy()));
    }

    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    public static boolean ghost(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean("permapack_reserved").orElse(false);
    }
    public static boolean real(ItemStack stack) { return !stack.isEmpty() && !ghost(stack); }
    public static ItemStack actual(ItemStack stack) { return ghost(stack) ? ItemStack.EMPTY : stack; }
    public boolean member(Player player) { return active && members.contains(player.getUUID()); }
    public boolean bound(Inventory inventory) { return inventory.getNonEquipmentItems() == items; }
    public List<ServerPlayer> players() { return List.copyOf(attached.values()); }

    public ItemStack placeholder(int slot) {
        String item = ledger.at(slot);
        if (item.isEmpty()) return ItemStack.EMPTY;
        var tag = new CompoundTag(); tag.putBoolean("permapack_reserved", true); tag.putString("item", item);
        ItemStack stack = new ItemStack(Items.BARRIER);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved: ").append(display(item)).withStyle(ChatFormatting.GRAY));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("One permanent allocation for this item.").withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("Items can move freely; this marker moves aside.").withStyle(ChatFormatting.DARK_GRAY))));
        return stack;
    }
    public static Component display(String item) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item))).getHoverName();
    }

    public void join(ServerPlayer player) {
        members.add(player.getUUID()); retired.remove(player.getUUID());
        attached.put(player.getUUID(), player);
        ((InventoryAccess) player.getInventory()).permapack$items(items);
        normalize(); sync(); setDirty();
    }
    public void disconnect(ServerPlayer player) {
        drags.remove(player.getUUID());
        // Vanilla closes the menu first; recover its cursor even for disconnected players.
        returnCursor(player.containerMenu);
        attached.remove(player.getUUID()); setDirty();
    }
    public void retireMirror(ServerPlayer player) {
        if (!members.contains(player.getUUID()) && retired.remove(player.getUUID())) {
            ((InventoryAccess) player.getInventory()).permapack$items(NonNullList.withSize(36, ItemStack.EMPTY));
            player.inventoryMenu.setCarried(ItemStack.EMPTY); setDirty();
        }
    }
    public void tick(MinecraftServer server) {
        if (!active) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (members.contains(p.getUUID()) && !bound(p.getInventory())) join(p);
        }
        normalize(); drainReturns(); sync();
        // Stack counts/durability mutate in place inside vanilla, not just through setItem.
        setDirty();
    }
    public void sync() {
        for (ServerPlayer p : players()) {
            if (p.connection == null) continue;
            p.inventoryMenu.broadcastChanges();
            if (p.containerMenu != p.inventoryMenu) p.containerMenu.broadcastChanges();
        }
    }
    public void resync(ServerPlayer player) {
        normalize();
        if (player.connection != null) { player.inventoryMenu.broadcastFullState(); player.containerMenu.broadcastFullState(); }
        sync();
    }
    public void tell(Player player, String message) {
        if (player instanceof ServerPlayer p && p.connection != null)
            p.sendOverlayMessage(Component.literal("PermaPack: " + message).withStyle(ChatFormatting.YELLOW));
    }

    /** Only the exact stack created by the operator's command can bypass drop recovery. */
    public boolean permitsCommandDrop(ItemStack stack) { return stack == commandDrop; }
    public int dropHeld(ServerPlayer player, boolean wholeStack) {
        if (!member(player) || !bound(player.getInventory()) || !player.isAlive()) return 0;
        ItemStack held = items.get(player.getInventory().getSelectedSlot());
        if (!real(held)) return 0;
        int amount = wholeStack ? held.getCount() : 1;
        ItemStack dropped = held.split(amount);
        boolean success = false;
        commandDrop = dropped;
        try {
            success = player.drop(dropped, false, true) != null;
            return success ? amount : 0;
        } finally {
            commandDrop = null;
            if (!success) held.grow(amount);
            normalize(); sync(); setDirty();
        }
    }

    public void normalize() {
        if (normalizing || !active) return;
        normalizing = true;
        try {
            List<String> contents=contentIds();
            while(!ledger.arrange(contents)) {
                // Recover an unexpected extra stack from another mod without releasing any allocation.
                int excess=-1;
                for(int n=35;n>=0;n--) {
                    String item=contents.get(n);
                    if(!item.isEmpty() && Collections.frequency(contents,item)>ledger.reserved(item)) { excess=n;break; }
                }
                if(excess<0) throw new IllegalStateException("Unable to arrange permanent allocations");
                defer(items.get(excess)); items.set(excess,ItemStack.EMPTY); contents.set(excess,"");
            }
            for(int n=0;n<36;n++) if(!real(items.get(n))) items.set(n,placeholder(n));
        } finally { normalizing = false; }
    }
    private List<String> contentIds() {
        List<String> contents=new ArrayList<>(36);
        for(ItemStack stack:items) contents.add(real(stack)?id(stack):"");
        return contents;
    }
    private int storedStacks(String item) {
        return (int)items.stream().filter(SharedBag::real).filter(s->id(s).equals(item)).count();
    }
    private Map<String,Integer> capacityPlan(List<ItemStack> additions,ItemStack ignored) {
        List<ItemStack> simulated=new ArrayList<>();
        for(ItemStack stack:items) if(stack!=ignored && real(stack)) simulated.add(stack.copy());
        List<ItemStack> pending=new ArrayList<>(additions);
        for(ServerPlayer p:players()) {
            ItemStack cursor=p.containerMenu.getCarried();
            if(cursor!=ignored && real(cursor)) pending.add(cursor);
        }
        for(ItemStack stack:pending) if(real(stack)) {
            int remaining=stack.getCount();
            for(ItemStack target:simulated) if(ItemStack.isSameItemSameComponents(target,stack)) {
                int amount=Math.min(remaining,target.getMaxStackSize()-target.getCount());
                if(amount>0){target.grow(amount);remaining-=amount;}
                if(remaining==0)break;
            }
            while(remaining>0) {
                int amount=Math.min(remaining,stack.getMaxStackSize());
                simulated.add(stack.copyWithCount(amount));remaining-=amount;
            }
        }
        Map<String,Integer> needed=new LinkedHashMap<>();
        for(ItemStack stack:simulated) needed.merge(id(stack),1,Integer::sum);
        return needed;
    }
    private boolean canReserve(Map<String,Integer> needed) {
        return needed.entrySet().stream().mapToInt(e->Math.max(0,e.getValue()-ledger.reserved(e.getKey()))).sum()<=36-ledger.count();
    }
    private void reserve(Map<String,Integer> needed) {
        if(!canReserve(needed)) throw new IllegalStateException("Unreserved inventory capacity");
        for(var entry:needed.entrySet()) while(ledger.reserved(entry.getKey())<entry.getValue())
            ledger.claim(ledger.freeSlot(),entry.getKey());
    }
    public boolean canIntroduce(ItemStack stack) {
        return !real(stack) || (!nestedStorage(stack) && canReserve(capacityPlan(List.of(stack),stack)));
    }
    public static boolean nestedStorage(ItemStack stack) {
        // Bundles can create hidden extra storage through right-click interactions.
        return stack.has(DataComponents.BUNDLE_CONTENTS) ||
            stack.getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyItemCopyStream().findAny().isPresent();
    }
    public boolean ensureClaim(ItemStack stack) {
        if (!real(stack)) return true;
        if (nestedStorage(stack)) return false;
        normalize();
        if (ledger.contains(id(stack))) return true;
        int slot = ledger.freeSlot();
        if (slot < 0 || nestedStorage(stack)) return false;
        ledger.claim(slot, id(stack)); normalize(); setDirty(); return true;
    }
    public boolean permits(int slot, ItemStack stack) {
        if(slot<0 || slot>=36) return false;
        if(!real(stack)) return true;
        if(nestedStorage(stack)) return false;
        List<String> contents=contentIds(); contents.set(slot,id(stack));
        return ledger.canArrange(contents);
    }
    public void write(int slot, ItemStack stack) {
        if (!real(stack)) { items.set(slot,ItemStack.EMPTY); normalize(); return; }
        if (permits(slot, stack)) { items.set(slot,stack); normalize(); }
        else { ItemStack remaining=stack.copy(); insert(remaining); if(!remaining.isEmpty()) defer(remaining); }
        setDirty();
    }
    /** Used by pickups, item remainders and menu returns. Mutates only accepted counts. */
    public boolean insert(ItemStack incoming) {
        if (!real(incoming) || nestedStorage(incoming)) return false;
        normalize();
        int before = incoming.getCount();
        int accepted=before;
        if(!canReserve(capacityPlan(List.of(incoming),incoming))) {
            int low=0,high=before;
            while(low<high) {
                int middle=low+(high-low+1)/2;
                if(canReserve(capacityPlan(List.of(incoming.copyWithCount(middle)),incoming))) low=middle;
                else high=middle-1;
            }
            accepted=low;
        }
        if(accepted==0)return false;
        reserve(capacityPlan(List.of(incoming.copyWithCount(accepted)),incoming));
        int toPlace=accepted;
        for (int n = 0; n < 36 && toPlace>0; n++) {
            ItemStack target = items.get(n);
            if (real(target) && ItemStack.isSameItemSameComponents(target, incoming)) {
                int amount = Math.min(toPlace, target.getMaxStackSize() - target.getCount());
                if (amount > 0) { target.grow(amount); incoming.shrink(amount); toPlace-=amount; }
            }
        }
        String incomingId=id(incoming);
        for (int pass = 0; pass < 2 && toPlace>0; pass++) {
            for (int n = 0; n < 36 && toPlace>0; n++) {
                if (real(items.get(n))) continue;
                if(pass==0 ? !ledger.at(n).equals(incomingId) : ledger.at(n).equals(incomingId)) continue;
                if(ledger.reserved(incomingId)<=storedStacks(incomingId)) {
                    int free=ledger.freeSlot(); if(free<0) continue;
                    ledger.claim(free,incomingId);
                }
                if(!permits(n,incoming)) continue;
                int amount=Math.min(toPlace,incoming.getMaxStackSize());
                items.set(n,incoming.split(amount));toPlace-=amount;
                normalize();
            }
        }
        if (incoming.getCount() != before) { setDirty(); return true; }
        return false;
    }
    public void defer(ItemStack stack) {
        if (real(stack)) { returns.add(stack.copy()); setDirty(); }
    }
    public void returnStack(ItemStack stack) {
        if (!real(stack)) return;
        boolean old = returning; returning = true;
        try { insert(stack); if (!stack.isEmpty()) { defer(stack); stack.setCount(0); } }
        finally { returning = old; }
    }
    public void returnCursor(AbstractContainerMenu menu) {
        ItemStack cursor = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        returnStack(cursor);
    }
    private void drainReturns() {
        boolean old = returning; returning = true;
        try { for (Iterator<ItemStack> i = returns.iterator(); i.hasNext();) { ItemStack s = i.next(); insert(s); if (s.isEmpty()) i.remove(); } }
        finally { returning = old; }
    }
    public void swapSlots(int first, int second) {
        if (first == second) return;
        ItemStack a = items.get(first); items.set(first, items.get(second)); items.set(second, a);
        ledger.swap(first, second); normalize(); setDirty();
    }
    public boolean preparePlace(int target, ItemStack carried) {
        if (!real(carried)) return true;
        if (!permits(target, carried)) return false;
        if (ghost(items.get(target))) items.set(target, ItemStack.EMPTY);
        return true;
    }
    /** A shift-move relocates the existing stack, rather than claiming a second allocation for it. */
    public boolean moveWithinBag(ItemStack stack,List<Integer> destinations) {
        if(!real(stack)) return false;
        int before=stack.getCount();
        for(int n:destinations) {
            ItemStack target=items.get(n);
            if(target!=stack && real(target) && ItemStack.isSameItemSameComponents(target,stack)) {
                int moved=Math.min(stack.getCount(),target.getMaxStackSize()-target.getCount());
                if(moved>0) { target.grow(moved);stack.shrink(moved); }
            }
            if(stack.isEmpty()) break;
        }
        if(!stack.isEmpty()) for(int n:destinations) if(!real(items.get(n))) {
            ItemStack moved=stack.copy(); stack.setCount(0); items.set(n,moved); break;
        }
        normalize(); setDirty(); return before!=stack.getCount();
    }

    /** Handle the native drag packet sequence atomically, including short single-slot gestures. */
    private boolean drag(AbstractContainerMenu menu,int index,int button,ServerPlayer player) {
        int header=AbstractContainerMenu.getQuickcraftHeader(button), type=AbstractContainerMenu.getQuickcraftType(button);
        ItemStack carried=menu.getCarried();
        if(header==0) {
            drags.remove(player.getUUID());
            if(real(carried) && !nestedStorage(carried) && (type==0||type==1)) drags.put(player.getUUID(),new Drag(menu,type,carried));
            return true;
        }
        Drag drag=drags.get(player.getUUID());
        if(drag==null || drag.menu!=menu || drag.type!=type || !ItemStack.isSameItemSameComponents(drag.original,carried)) {
            drags.remove(player.getUUID()); resync(player); return true;
        }
        if(header==1) {
            if(index>=0 && index<menu.slots.size()) {
                Slot slot=menu.slots.get(index); boolean bagSlot=slot.container==player.getInventory() && slot.getContainerSlot()<36;
                ItemStack target=actual(slot.getItem());
                if((bagSlot||processing(menu)) && slot.mayPlace(carried) && (!real(target)||ItemStack.isSameItemSameComponents(target,carried))) drag.slots.add(index);
            }
            return true;
        }
        drags.remove(player.getUUID());
        if(header!=2 || drag.slots.isEmpty()) { resync(player);return true; }
        List<Slot> targets=drag.slots.stream().map(menu.slots::get).filter(s-> {
            ItemStack target=actual(s.getItem());
            return s.mayPlace(carried) && (!real(target)||ItemStack.isSameItemSameComponents(target,carried));
        }).toList();
        if(targets.isEmpty()) { resync(player);return true; }
        int portion=type==0?carried.getCount()/targets.size():1, remaining=carried.getCount();
        Map<Slot,ItemStack> writes=new LinkedHashMap<>(); List<String> contents=contentIds();
        for(Slot slot:targets) {
            int previous=actual(slot.getItem()).getCount();
            int amount=Math.min(remaining,Math.min(portion,Math.min(carried.getMaxStackSize(),slot.getMaxStackSize(carried))-previous));
            if(amount<=0) continue;
            writes.put(slot,carried.copyWithCount(previous+amount)); remaining-=amount;
            if(slot.container==player.getInventory() && slot.getContainerSlot()<36) contents.set(slot.getContainerSlot(),id(carried));
        }
        if(!ledger.arrange(contents)) {
            tell(player,"Splitting into more stacks needs another unused permanent allocation.");resync(player);return true;
        }
        menu.setCarried(remaining==0?ItemStack.EMPTY:carried.copyWithCount(remaining));
        for(var entry:writes.entrySet()) if(entry.getKey().container==player.getInventory() && entry.getKey().getContainerSlot()<36)
            items.set(entry.getKey().getContainerSlot(),entry.getValue());
        for(var entry:writes.entrySet()) if(!(entry.getKey().container==player.getInventory() && entry.getKey().getContainerSlot()<36))
            entry.getKey().setByPlayer(entry.getValue());
        player.getInventory().setChanged(); normalize();resync(player);setDirty();return true;
    }
    public void afterMenu(ServerPlayer player) {
        ensureClaim(player.containerMenu.getCarried());
        Map<String,Integer> needed=capacityPlan(List.of(),null);
        if(canReserve(needed)) reserve(needed);
        normalize(); sync(); setDirty();
    }
    public boolean processing(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu || menu instanceof AbstractCraftingMenu ||
            menu instanceof AbstractFurnaceMenu || menu instanceof AnvilMenu || menu instanceof SmithingMenu ||
            menu instanceof EnchantmentMenu || menu instanceof BrewingStandMenu || menu instanceof StonecutterMenu ||
            menu instanceof GrindstoneMenu || menu instanceof CartographyTableMenu || menu instanceof MerchantMenu ||
            menu instanceof LoomMenu;
    }

    /** @return true when vanilla must not execute the click. */
    public boolean beforeClick(AbstractContainerMenu menu, int index, int button, ContainerInput input, ServerPlayer player) {
        Slot slot = index >= 0 && index < menu.slots.size() ? menu.slots.get(index) : null;
        boolean bagSlot = slot != null && slot.container == player.getInventory() && slot.getContainerSlot() < 36;
        if (input == ContainerInput.QUICK_CRAFT) {
            return drag(menu,index,button,player);
        }
        drags.remove(player.getUUID());
        if (input == ContainerInput.SWAP && !(button >= 0 && button < 9 || button == Inventory.SLOT_OFFHAND)) {
            resync(player); return true;
        }
        if (input == ContainerInput.THROW || input == ContainerInput.CLONE || index == -999) {
            resync(player); return true;
        }
        // Number-key swaps are fully atomic, including both permanent reservations.
        if (input == ContainerInput.SWAP && bagSlot && button >= 0 && button < 9) {
            swapSlots(slot.getContainerSlot(), button); resync(player); return true;
        }
        if (slot == null) return false;
        ItemStack carried = menu.getCarried(); ItemStack target = slot.getItem();
        if (nestedStorage(carried) || nestedStorage(target)) { tell(player, "Bundles and filled storage items can't bypass the shared bag."); resync(player); return true; }
        boolean storage = !processing(menu);
        if (storage && ((input == ContainerInput.QUICK_MOVE && bagSlot) ||
            (input == ContainerInput.PICKUP && !bagSlot && real(carried)) ||
            (input == ContainerInput.SWAP && !bagSlot && real(player.getInventory().getItem(button))))) {
            tell(player, "You can take loot, but can't store items outside the shared bag."); resync(player); return true;
        }
        if (ghost(target)) {
            if (input != ContainerInput.PICKUP || !real(carried) || !bagSlot) { resync(player); return true; }
        }
        if (bagSlot && input == ContainerInput.PICKUP && real(carried) && !preparePlace(slot.getContainerSlot(), carried)) {
            tell(player, "Creating another stored stack needs an unused permanent allocation."); resync(player); return true;
        }
        if (!bagSlot && real(target) && (input == ContainerInput.QUICK_MOVE || input == ContainerInput.SWAP || !real(carried) || slot instanceof ResultSlot)) {
            ItemStack taking=input==ContainerInput.PICKUP && button==1 && !real(carried)?target.copyWithCount((target.getCount()+1)/2):target;
            boolean fits=input==ContainerInput.QUICK_MOVE && !(slot instanceof ResultSlot)?ledger.canIntroduce(id(target)):canIntroduce(taking);
            if (!fits || !canCraftRemainders(menu, slot, player)) {
                tell(player, "All 36 slots are claimed. This new item type needs an unused slot."); resync(player); return true;
            }
        }
        // For external number-key pickups, reserve/move the destination before vanilla puts the item there.
        if (!bagSlot && input == ContainerInput.SWAP && button >= 0 && button < 9 && real(target)) {
            if (real(player.getInventory().getItem(button)) || !preparePlace(button, target)) {
                tell(player, "Take that item with a normal click first, then swap inventory slots."); resync(player); return true;
            }
        }
        return false;
    }
    private boolean canCraftRemainders(AbstractContainerMenu menu, Slot source, ServerPlayer player) {
        if (!(source instanceof ResultSlot result)) return true;
        CraftingContainer grid = ((dev.leo.permapack.mixin.ResultSlotAccess) result).permapack$craftSlots();
        var input = grid.asPositionedCraftInput().input();
        var recipe = player.level().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, player.level());
        if (recipe.isEmpty()) return true;
        List<ItemStack> products=new ArrayList<>();products.add(source.getItem());
        for (ItemStack rem : recipe.get().value().getRemainingItems(input))
            if (real(rem)) products.add(rem);
        return canReserve(capacityPlan(products,null));
    }

    public void onDeath(ServerPlayer player) {
        drags.clear();
        returnCursor(player.containerMenu);
        if (!keepOnDeath) {
            for (ServerPlayer p : players()) p.closeContainer();
            for (ServerPlayer p : players()) { p.containerMenu.setCarried(ItemStack.EMPTY); p.getInventory().clearContent(); }
            returns.clear();
        }
        normalize(); sync(); setDirty();
    }
    public void reset() {
        drags.clear();
        internal = true;
        try {
            for (ServerPlayer p : players()) { p.closeContainer(); p.containerMenu.setCarried(ItemStack.EMPTY); p.getInventory().clearContent(); }
            items.clear(); returns.clear(); ledger.reset();
        } finally { internal = false; }
        sync(); setDirty();
    }
    public boolean start(Collection<ServerPlayer> players) {
        if (active || players.isEmpty() || players.size() > 3) return false;
        for (ServerPlayer p : players) { p.closeContainer(); if (!p.getInventory().isEmpty()) return false; }
        ledger.reset(); items.clear(); returns.clear(); members.clear(); attached.clear(); drags.clear(); active = true;
        for (ServerPlayer p : players) { p.setGameMode(GameType.SURVIVAL); join(p); }
        setDirty(); return true;
    }
    public boolean joinTeam(Collection<ServerPlayer> candidates) {
        if (!active) return false;
        long newcomers = candidates.stream().map(ServerPlayer::getUUID).distinct().filter(id -> !members.contains(id)).count();
        if (members.size() + newcomers > 3) return false;
        for (ServerPlayer p : candidates) if (!member(p)) {
            p.closeContainer();
            if (!p.getInventory().isEmpty()) return false;
        }
        for (ServerPlayer p : candidates) { p.setGameMode(GameType.SURVIVAL); join(p); }
        return true;
    }
    public boolean stop(ServerPlayer recipient) {
        for (ServerPlayer p : players()) p.closeContainer();
        drainReturns(); if (!returns.isEmpty()) return false;
        if (!member(recipient) && !recipient.getInventory().isEmpty()) return false;
        NonNullList<ItemStack> delivered = NonNullList.withSize(36, ItemStack.EMPTY);
        for (int n = 0; n < 36; n++) delivered.set(n, actual(items.get(n)).copy());
        active = false;
        for (ServerPlayer p : players()) ((InventoryAccess) p.getInventory()).permapack$items(NonNullList.withSize(36, ItemStack.EMPTY));
        retired.addAll(members); retired.remove(recipient.getUUID());
        ((InventoryAccess) recipient.getInventory()).permapack$items(delivered);
        for (ServerPlayer p : players()) if (p.connection != null) p.inventoryMenu.broadcastFullState();
        attached.clear(); members.clear(); items.clear(); ledger.reset(); drags.clear(); setDirty(); return true;
    }
}
