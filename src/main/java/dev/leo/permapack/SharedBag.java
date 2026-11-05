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
import net.minecraft.world.entity.EquipmentSlot;

/** Server-thread-only inventory. Players share the actual backing list, never copies. */
public final class SharedBag extends SavedData {
    public static final Codec<SharedBag> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.BOOL.fieldOf("active").forGetter(s -> s.active),
        Codec.BOOL.optionalFieldOf("keep_on_death", true).forGetter(s -> s.keepOnDeath),
        Codec.STRING.listOf().fieldOf("reservations").forGetter(s -> s.ledger.snapshot()),
        ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(s -> new ArrayList<>(s.items)),
        Codec.STRING.listOf().fieldOf("members").forGetter(s -> s.members.stream().map(UUID::toString).toList()),
        Codec.STRING.listOf().optionalFieldOf("retired", List.of()).forGetter(s -> s.retired.stream().map(UUID::toString).toList()),
        ItemStack.CODEC.listOf().optionalFieldOf("returns", List.of()).forGetter(s -> s.returns),
        Codec.INT.optionalFieldOf("rules_version",0).forGetter(s -> 1),
        Codec.STRING.listOf().optionalFieldOf("equipment_migration",List.of()).forGetter(s -> s.equipmentMigration.stream().map(UUID::toString).toList())
    ).apply(i, SharedBag::new));
    public static final SavedDataType<SharedBag> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("permapack", "shared_inventory"), SharedBag::new, CODEC, null);

    public final SlotLedger ledger;
    public final NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Set<UUID> retired = new LinkedHashSet<>();
    public final List<ItemStack> returns = new ArrayList<>();
    private final Map<UUID, ServerPlayer> attached = new LinkedHashMap<>();
    private final Set<UUID> equipmentMigration=new LinkedHashSet<>();
    public static final class Origin {
        final SharedBag owner; final String family; boolean active=true;
        Origin(SharedBag owner,String family) { this.owner=owner;this.family=family; }
    }
    private final List<Origin> allocations=new ArrayList<>();
    private final Set<Container> processingContainers=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<MenuAction> actions=new ArrayDeque<>();
    private record PersonalCursor(int slot,ItemStack stack) {}
    private final Map<UUID,PersonalCursor> personalCursors=new HashMap<>();
    private static final class MenuAction {
        final ServerPlayer player;
        final List<ItemStack> bagRefs,bagCopies,personal;
        final ItemStack cursor;
        MenuAction(ServerPlayer player,SharedBag bag) {
            this.player=player;bagRefs=new ArrayList<>(bag.items);
            bagCopies=bag.items.stream().map(ItemStack::copy).toList();
            personal=new ArrayList<>();
            for(int n=36;n<41;n++)personal.add(player.getInventory().getItem(n).copy());
            cursor=player.containerMenu.getCarried().copy();
        }
    }
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
                      List<String> members, List<String> retired, List<ItemStack> pending, int rulesVersion, List<String> migration) {
        if (stacks.size() != 36) throw new IllegalArgumentException("Invalid PermaPack inventory length");
        this.active = active; this.keepOnDeath = keep; ledger = new SlotLedger(bindings);
        for (int n = 0; n < 36; n++) items.set(n, stacks.get(n));
        members.forEach(id -> this.members.add(UUID.fromString(id)));
        retired.forEach(id -> this.retired.add(UUID.fromString(id)));
        pending.forEach(s -> returns.add(s.copy()));
        (rulesVersion<1?members:migration).forEach(id -> equipmentMigration.add(UUID.fromString(id)));
    }

    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    public static String key(ItemStack stack) { return SlotLedger.allocationKey(id(stack)); }
    public static void origins(ItemStack stack,Set<Origin> origins) {
        if(real(stack))((StackOriginAccess)(Object)stack).permapack$origins(origins);
    }
    private Set<Origin> origins(ItemStack stack) {
        if(!real(stack))return Set.of();
        Set<Origin> result=new LinkedHashSet<>();
        for(Origin origin:((StackOriginAccess)(Object)stack).permapack$origins())
            if(origin.active && origin.owner==this && allocations.contains(origin) && origin.family.equals(key(stack)))result.add(origin);
        return result;
    }
    public static boolean ghost(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean("permapack_reserved").orElse(false);
    }
    public static boolean real(ItemStack stack) { return !stack.isEmpty() && !ghost(stack); }
    public static boolean emptyBag(Inventory inventory) {
        for(int n=0;n<36;n++)if(real(inventory.getItem(n)))return false;
        return true;
    }
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
        if(equipmentMigration.remove(player.getUUID())) {
            for(int n=36;n<41;n++) {
                ItemStack equipped=player.getInventory().getItem(n);
                // Old arbitrary stackable offhands cannot be distinguished from historic splits.
                if(real(equipped) && (n<40 || equipped.getMaxStackSize()==1) && ledger.reserved(key(equipped))>storedStacks(key(equipped)))
                    ledger.releaseOne(key(equipped));
                origins(equipped,Set.of());
            }
        }
        normalize(); sync(); setDirty();
    }
    public void disconnect(ServerPlayer player) {
        drags.remove(player.getUUID());
        // Vanilla closes the menu first; recover its cursor even for disconnected players.
        returnCursor(player);
        personalCursors.remove(player.getUUID());
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
        if(!actions.isEmpty())return;
        for (ServerPlayer p : players()) {
            if (p.connection == null) continue;
            p.inventoryMenu.broadcastChanges();
            if (p.containerMenu != p.inventoryMenu) p.containerMenu.broadcastChanges();
        }
    }
    public void resync(ServerPlayer player) {
        if(!actions.isEmpty())return;
        normalize();
        if (player.connection != null) { player.inventoryMenu.broadcastFullState(); player.containerMenu.broadcastFullState(); }
        sync();
    }
    public void tell(Player player, String message) {
        if (player instanceof ServerPlayer p && p.connection != null)
            p.sendOverlayMessage(Component.literal("PermaPack: " + message).withStyle(ChatFormatting.YELLOW));
    }

    public void beginTransfer(ServerPlayer player) {
        if(actions.isEmpty())normalize();
        observeProcessing(player);
        actions.push(new MenuAction(player,this));
    }
    public void endTransfer(ServerPlayer player) {
        if(!actions.isEmpty() && actions.peek().player==player) {
            MenuAction action=actions.pop();
            observeProcessing(player);
            ItemStack cursor=player.containerMenu.getCarried();
            int consumed=real(action.cursor)&&ItemStack.isSameItemSameComponents(action.cursor,cursor)?action.cursor.getCount()-cursor.getCount():action.cursor.getCount();
            if(consumed>0 && real(action.cursor))for(int n=0;n<5;n++) {
                ItemStack after=player.getInventory().getItem(36+n), before=action.personal.get(n);
                int previous=real(before)&&ItemStack.isSameItemSameComponents(before,after)?before.getCount():0;
                if(real(after)&&ItemStack.isSameItemSameComponents(after,action.cursor)&&after.getCount()>previous) {
                    Set<Origin> lineage=new LinkedHashSet<>(origins(after));lineage.addAll(origins(action.cursor));origins(after,lineage);
                }
            }
            if(!real(cursor))personalCursors.remove(player.getUUID());
            else {
                PersonalCursor previous=personalCursors.get(player.getUUID());
                int personalSource=-1;
                if(origins(cursor).isEmpty()) for(int n=0;n<5;n++) {
                    ItemStack before=action.personal.get(n), after=player.getInventory().getItem(36+n);
                    if(real(before) && ItemStack.isSameItemSameComponents(before,cursor) &&
                        (!real(after)||!ItemStack.isSameItemSameComponents(before,after)||after.getCount()<before.getCount()))personalSource=36+n;
                }
                if(personalSource>=0)personalCursors.put(player.getUUID(),new PersonalCursor(personalSource,cursor));
                else if(previous!=null && real(action.cursor) && ItemStack.isSameItemSameComponents(action.cursor,cursor))
                    personalCursors.put(player.getUUID(),new PersonalCursor(previous.slot,cursor));
                else personalCursors.remove(player.getUUID());
                boolean grew=!real(action.cursor)||!ItemStack.isSameItemSameComponents(action.cursor,cursor)||cursor.getCount()>action.cursor.getCount();
                if(grew && personalSource<0) {
                    Set<Origin> lineage=new LinkedHashSet<>(origins(cursor));
                    for(int n=0;n<36;n++) {
                        ItemStack before=action.bagCopies.get(n), reference=action.bagRefs.get(n);
                        boolean remains=items.stream().anyMatch(s->s==reference);
                        if(real(before) && ItemStack.isSameItemSameComponents(before,cursor) && (!remains||reference.getCount()<before.getCount()))
                            lineage.addAll(origins(before));
                    }
                    origins(cursor,lineage);
                }
            }
        }
        if(actions.isEmpty()){normalize();sync();setDirty();}
    }
    public void personalChanged() { normalize();setDirty(); }
    private void observeProcessing(ServerPlayer player) {
        for(AbstractContainerMenu menu:player.containerMenu==player.inventoryMenu?List.of(player.inventoryMenu):List.of(player.inventoryMenu,player.containerMenu))
            if(processing(menu))for(Slot slot:menu.slots)if(slot.container!=player.getInventory() && !(slot instanceof ResultSlot))processingContainers.add(slot.container);
    }
    private void invalidateAllocations() {
        allocations.forEach(o->o.active=false);allocations.clear();processingContainers.clear();
    }
    private boolean personalCursor(ServerPlayer player) {
        return personalCursors.containsKey(player.getUUID()) && origins(player.containerMenu.getCarried()).isEmpty();
    }
    private boolean originInUse(Origin origin,ItemStack ignored) {
        for(ItemStack stack:items) if(stack!=ignored && origins(stack).contains(origin))return true;
        for(ServerPlayer player:players()) {
            ItemStack cursor=player.containerMenu.getCarried();
            if(cursor!=ignored && origins(cursor).contains(origin))return true;
            for(AbstractContainerMenu menu:player.containerMenu==player.inventoryMenu?List.of(player.inventoryMenu):List.of(player.inventoryMenu,player.containerMenu))
                for(Slot slot:menu.slots) if(slot.container!=player.getInventory() && slot.getItem()!=ignored && origins(slot.getItem()).contains(origin))return true;
        }
        for(Container container:processingContainers)for(int n=0;n<container.getContainerSize();n++) {
            ItemStack stack=container.getItem(n);if(stack!=ignored&&origins(stack).contains(origin))return true;
        }
        return false;
    }
    private void releaseOrigin(Origin origin) {
        if(origin.active && ledger.releaseOne(origin.family))origin.active=false;
    }
    private void releaseEquipmentOrigins() {
        Set<Origin> equipped=new LinkedHashSet<>();
        for(ServerPlayer player:players())for(int n=36;n<41;n++)equipped.addAll(origins(player.getInventory().getItem(n)));
        for(Origin origin:equipped)if(!originInUse(origin,null))releaseOrigin(origin);
        for(ServerPlayer player:players())for(int n=36;n<41;n++)origins(player.getInventory().getItem(n),Set.of());
    }
    private void assignOrigins() {
        for(Origin origin:new ArrayList<>(allocations))if(origin.active) {
            long count=allocations.stream().filter(o->o.active&&o.family.equals(origin.family)).count();
            if(count>ledger.reserved(origin.family)) {
                Origin excess=allocations.stream().filter(o->o.active&&o.family.equals(origin.family)&&!originInUse(o,null)).findFirst().orElse(origin);
                excess.active=false;
            }
        }
        allocations.removeIf(o->!o.active);
        for(String family:new LinkedHashSet<>(ledger.snapshot()))if(!family.isEmpty()) {
            int have=(int)allocations.stream().filter(o->o.family.equals(family)).count();
            while(have++<ledger.reserved(family))allocations.add(new Origin(this,family));
        }
        Set<Origin> used=new HashSet<>();List<ItemStack> pending=new ArrayList<>();
        for(ItemStack stack:items)if(real(stack)) {
            Origin selected=origins(stack).stream().filter(o->!used.contains(o)).findFirst().orElse(null);
            if(selected==null)pending.add(stack);
            else {used.add(selected);origins(stack,Set.of(selected));}
        }
        for(ItemStack stack:pending) {
            Origin selected=allocations.stream().filter(o->o.active&&o.family.equals(key(stack))&&!used.contains(o))
                .filter(o->players().stream().noneMatch(p->origins(p.containerMenu.getCarried()).contains(o))).findFirst().orElse(null);
            if(selected==null)selected=allocations.stream().filter(o->o.active&&o.family.equals(key(stack))&&!used.contains(o)).findFirst().orElseThrow();
            used.add(selected);origins(stack,Set.of(selected));
        }
        List<ItemStack> projection=items.stream().filter(SharedBag::real).map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for(ServerPlayer player:players()) {
            ItemStack cursor=player.containerMenu.getCarried();
            if(!real(cursor)||personalCursor(player))continue;
            int remaining=cursor.getCount();Set<Origin> merged=new LinkedHashSet<>();
            for(ItemStack target:projection)if(ItemStack.isSameItemSameComponents(target,cursor)) {
                int amount=Math.min(remaining,target.getMaxStackSize()-target.getCount());
                if(amount>0){target.grow(amount);remaining-=amount;merged.addAll(origins(target));}
                if(remaining==0)break;
            }
            if(origins(cursor).isEmpty()) {
                if(remaining==0&&!merged.isEmpty())origins(cursor,merged);
                else {
                    Origin selected=allocations.stream().filter(o->o.active&&o.family.equals(key(cursor))&&!used.contains(o))
                        .filter(o->players().stream().noneMatch(p->p!=player&&origins(p.containerMenu.getCarried()).contains(o))).findFirst().orElse(null);
                    if(selected==null)selected=allocations.stream().filter(o->o.active&&o.family.equals(key(cursor))).findFirst().orElse(null);
                    if(selected!=null){origins(cursor,Set.of(selected));used.add(selected);}
                }
            }
            while(remaining>0){int amount=Math.min(remaining,cursor.getMaxStackSize());projection.add(cursor.copyWithCount(amount));remaining-=amount;}
        }
    }

    private boolean canSwapPersonal(ServerPlayer player,int bagSlot,int personalSlot) {
        ItemStack outgoing=actual(items.get(bagSlot)), incoming=actual(player.getInventory().getItem(personalSlot));
        if(nestedStorage(outgoing)||nestedStorage(incoming))return false;
        SlotLedger plan=new SlotLedger(ledger.snapshot());
        for(Origin origin:origins(outgoing))if(!originInUse(origin,outgoing))plan.releaseOne(origin.family);
        List<String> contents=contentIds();contents.set(bagSlot,real(incoming)?key(incoming):"");
        return plan.canArrange(contents);
    }
    public boolean swapPersonal(ServerPlayer player,int bagSlot,int personalSlot) {
        if(player.isSpectator()||!canSwapPersonal(player,bagSlot,personalSlot)) {resync(player);return false;}
        boolean own=actions.isEmpty();if(own)beginTransfer(player);
        try {
            ItemStack outgoing=actual(items.get(bagSlot)).copy(), incoming=actual(player.getInventory().getItem(personalSlot)).copy();
            origins(incoming,Set.of()); items.set(bagSlot,incoming);
            player.getInventory().setItem(personalSlot,outgoing);player.stopUsingItem();
        } finally {if(own)endTransfer(player);}
        return true;
    }
    public boolean canEquipFromHand(ServerPlayer player,ItemStack held,EquipmentSlot slot) {
        ItemStack old=player.getItemBySlot(slot);
        if(held==player.getOffhandItem())return held.getCount()==1||!real(old)||canIntroduce(old);
        if(held.getCount()>1)return !real(old)||canIntroduce(old);
        int personal=slot==EquipmentSlot.OFFHAND?40:36+slot.getIndex();
        return canSwapPersonal(player,player.getInventory().getSelectedSlot(),personal);
    }
    public boolean canTransform(ServerPlayer player,ItemStack held,ItemStack result) {
        if(!real(result))return true;
        if(nestedStorage(result))return false;
        if(held==player.getOffhandItem())return held.getCount()==1||canIntroduce(result);
        List<ItemStack> products=new ArrayList<>();products.add(result);
        if(held.getCount()>1)products.add(held.copyWithCount(held.getCount()-1));
        return canReserve(capacityPlan(products,held));
    }

    /** Only the exact stack created by the operator's command can bypass drop recovery. */
    public boolean permitsCommandDrop(ItemStack stack) { return stack == commandDrop; }
    public int dropHeld(ServerPlayer player, boolean wholeStack) {
        if (!member(player) || !bound(player.getInventory()) || !player.isAlive()) return 0;
        normalize();int selected=player.getInventory().getSelectedSlot();
        ItemStack held = items.get(selected);
        if(!real(held) && !ghost(held))return 0;
        String family=ledger.at(selected);
        if(ghost(held)) {
            Origin origin=allocations.stream().filter(o->o.active&&o.family.equals(family)&&!originInUse(o,null)).findFirst().orElse(null);
            if(origin==null)origin=allocations.stream().filter(o->o.active&&o.family.equals(family)).findFirst().orElse(null);
            ledger.release(selected);if(origin!=null)origin.active=false;
            items.set(selected,ItemStack.EMPTY);normalize();sync();setDirty();return 1;
        }
        int amount = held.getCount();
        ItemStack dropped = held.copy();
        boolean success = false;
        commandDrop = dropped;
        try {
            success = player.drop(dropped, false, true) != null;
            if(success) {
                Origin origin=origins(held).stream().findFirst().orElse(null);
                items.set(selected,ItemStack.EMPTY);
                ledger.release(selected);if(origin!=null)origin.active=false;
                origins(dropped,Set.of());
            }
            return success ? amount : 0;
        } finally {
            commandDrop = null;
            normalize(); sync(); setDirty();
        }
    }

    public void normalize() {
        if (normalizing || !active || !actions.isEmpty()) return;
        normalizing = true;
        try {
            releaseEquipmentOrigins();
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
            Map<String,Integer> needed=capacityPlan(List.of(),null);
            if(canReserve(needed))reserve(needed);
            assignOrigins();
            for(int n=0;n<36;n++) if(!real(items.get(n))) items.set(n,placeholder(n));
        } finally { normalizing = false; }
    }
    private List<String> contentIds() {
        List<String> contents=new ArrayList<>(36);
        for(ItemStack stack:items) contents.add(real(stack)?key(stack):"");
        return contents;
    }
    private int storedStacks(String item) {
        return (int)items.stream().filter(SharedBag::real).filter(s->key(s).equals(SlotLedger.allocationKey(item))).count();
    }
    private Map<String,Integer> capacityPlan(List<ItemStack> additions,ItemStack ignored) {
        List<ItemStack> simulated=new ArrayList<>();
        for(ItemStack stack:items) if(stack!=ignored && real(stack)) simulated.add(stack.copy());
        List<ItemStack> pending=new ArrayList<>(additions);
        for(ServerPlayer p:players()) {
            ItemStack cursor=p.containerMenu.getCarried();
            if(cursor!=ignored && real(cursor) && !personalCursor(p)) pending.add(cursor);
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
        for(ItemStack stack:simulated) needed.merge(key(stack),1,Integer::sum);
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
        List<String> contents=contentIds(); contents.set(slot,key(stack));
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
        String incomingId=key(incoming);
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
        returnStack(null,stack);
    }
    public void returnStack(ServerPlayer player,ItemStack stack) {
        if (!real(stack)) return;
        boolean old = returning; returning = true;
        try {
            insert(stack);
            PersonalCursor source=player==null?null:personalCursors.get(player.getUUID());
            if(real(stack)&&source!=null&&source.stack==stack) {
                var inv=player.getInventory();int menuSlot=player.inventoryMenu.findSlot(inv,source.slot).orElse(-1);
                ItemStack target=inv.getItem(source.slot);
                if(menuSlot>=0&&(!real(target)||ItemStack.isSameItemSameComponents(target,stack))&&player.inventoryMenu.getSlot(menuSlot).mayPlace(stack)) {
                    int room=player.inventoryMenu.getSlot(menuSlot).getMaxStackSize(stack)-(real(target)?target.getCount():0);
                    int amount=Math.min(stack.getCount(),room);
                    beginTransfer(player);
                    try {
                        if(amount>0) {
                            if(real(target)) {target.grow(amount);stack.shrink(amount);}
                            else inv.setItem(source.slot,stack.split(amount));
                        }
                    }
                    finally {endTransfer(player);}
                }
            }
            if (!stack.isEmpty()) { defer(stack); stack.setCount(0); }
            if(player!=null&&source!=null&&source.stack==stack)personalCursors.remove(player.getUUID());
        }
        finally { returning = old; }
    }
    public void returnCursor(ServerPlayer player) {
        AbstractContainerMenu menu=player.containerMenu;
        ItemStack cursor = menu.getCarried();
        returnStack(player,cursor);menu.setCarried(ItemStack.EMPTY);
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
            ItemStack write=carried.copyWithCount(previous+amount);
            if(real(actual(slot.getItem())) && slot.container==player.getInventory()&&slot.getContainerSlot()<36)origins(write,origins(actual(slot.getItem())));
            writes.put(slot,write); remaining-=amount;
            if(slot.container==player.getInventory() && slot.getContainerSlot()<36) contents.set(slot.getContainerSlot(),key(carried));
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
        endTransfer(player);
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
        boolean personalSlot=slot!=null&&slot.container==player.getInventory()&&slot.getContainerSlot()>=36;
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
        if(input==ContainerInput.SWAP && bagSlot && button==Inventory.SLOT_OFFHAND) {
            swapPersonal(player,slot.getContainerSlot(),Inventory.SLOT_OFFHAND);return true;
        }
        if(input==ContainerInput.SWAP && personalSlot && button>=0&&button<9) {
            ItemStack incoming=actual(items.get(button));
            if(slot.mayPickup(player)&&(!real(incoming)||slot.mayPlace(incoming)))swapPersonal(player,button,slot.getContainerSlot());
            return true;
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
        if (!bagSlot && (!personalSlot||input==ContainerInput.QUICK_MOVE) && real(target) && (input == ContainerInput.QUICK_MOVE || input == ContainerInput.SWAP || !real(carried) || slot instanceof ResultSlot)) {
            ItemStack taking=input==ContainerInput.PICKUP && button==1 && !real(carried)?target.copyWithCount((target.getCount()+1)/2):target;
            boolean fits=input==ContainerInput.QUICK_MOVE && !(slot instanceof ResultSlot)?ledger.canIntroduce(id(target)):canIntroduce(taking);
            if (!fits || !canCraftRemainders(menu, slot, player)) {
                tell(player, "All 36 slots are claimed. This new item type needs an unused slot."); resync(player); return true;
            }
        }
        // For external number-key pickups, reserve/move the destination before vanilla puts the item there.
        if (!bagSlot && !personalSlot && input == ContainerInput.SWAP && button >= 0 && button < 9 && real(target)) {
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
        returnCursor(player);
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
            items.clear(); returns.clear(); ledger.reset(); invalidateAllocations();personalCursors.clear();equipmentMigration.clear();
            for(ServerPlayer p:players())for(int n=36;n<41;n++)origins(p.getInventory().getItem(n),Set.of());
        } finally { internal = false; }
        sync(); setDirty();
    }
    public boolean start(Collection<ServerPlayer> players) {
        if (active || players.isEmpty() || players.size() > 3) return false;
        for (ServerPlayer p : players) { p.closeContainer(); if (!emptyBag(p.getInventory())) return false; }
        ledger.reset(); items.clear(); returns.clear(); members.clear(); attached.clear(); drags.clear();invalidateAllocations();personalCursors.clear();equipmentMigration.clear(); active = true;
        for (ServerPlayer p : players) { p.setGameMode(GameType.SURVIVAL); join(p); }
        setDirty(); return true;
    }
    public boolean joinTeam(Collection<ServerPlayer> candidates) {
        if (!active) return false;
        long newcomers = candidates.stream().map(ServerPlayer::getUUID).distinct().filter(id -> !members.contains(id)).count();
        if (members.size() + newcomers > 3) return false;
        for (ServerPlayer p : candidates) if (!member(p)) {
            p.closeContainer();
            if (!emptyBag(p.getInventory())) return false;
        }
        for (ServerPlayer p : candidates) { p.setGameMode(GameType.SURVIVAL); join(p); }
        return true;
    }
    public boolean stop(ServerPlayer recipient) {
        for (ServerPlayer p : players()) p.closeContainer();
        drainReturns(); if (!returns.isEmpty()) return false;
        if (!member(recipient) && !emptyBag(recipient.getInventory())) return false;
        NonNullList<ItemStack> delivered = NonNullList.withSize(36, ItemStack.EMPTY);
        for (int n = 0; n < 36; n++) delivered.set(n, actual(items.get(n)).copy());
        active = false;
        for (ServerPlayer p : players()) ((InventoryAccess) p.getInventory()).permapack$items(NonNullList.withSize(36, ItemStack.EMPTY));
        retired.addAll(members); retired.remove(recipient.getUUID());
        ((InventoryAccess) recipient.getInventory()).permapack$items(delivered);
        for (ServerPlayer p : players()) if (p.connection != null) p.inventoryMenu.broadcastFullState();
        for(ItemStack stack:delivered)origins(stack,Set.of());
        for(ServerPlayer p:players())for(int n=36;n<41;n++)origins(p.getInventory().getItem(n),Set.of());
        attached.clear(); members.clear(); items.clear(); ledger.reset(); drags.clear();invalidateAllocations();personalCursors.clear();equipmentMigration.clear(); setDirty(); return true;
    }
}
