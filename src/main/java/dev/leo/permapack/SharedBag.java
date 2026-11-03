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
    public boolean active;
    public boolean keepOnDeath = true;
    private boolean returning;
    private boolean normalizing;
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
            Component.literal("This slot stays claimed after use.").withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("Refill with the same item type.").withStyle(ChatFormatting.DARK_GRAY))));
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

    public void normalize() {
        if (normalizing || !active) return;
        normalizing = true;
        try {
            for (int n = 0; n < 36; n++) {
                ItemStack s = items.get(n);
                if (!real(s)) { items.set(n, placeholder(n)); continue; }
                if (ledger.permits(n, id(s))) ledger.claim(n, id(s));
                else {
                    items.set(n, placeholder(n));
                    defer(s); // Preserve unexpected returns from an item transformation or other mod.
                }
            }
        } finally { normalizing = false; }
    }
    public boolean canIntroduce(ItemStack stack) {
        return !real(stack) || (!nestedStorage(stack) && ledger.canIntroduce(id(stack)));
    }
    public static boolean nestedStorage(ItemStack stack) {
        // Bundles can create hidden extra storage through right-click interactions.
        return stack.has(DataComponents.BUNDLE_CONTENTS) ||
            stack.getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyItemCopyStream().findAny().isPresent();
    }
    public boolean ensureClaim(ItemStack stack) {
        if (!real(stack)) return true;
        if (nestedStorage(stack)) return false;
        if (ledger.contains(id(stack))) return true;
        int slot = ledger.freeSlot();
        if (slot < 0 || nestedStorage(stack)) return false;
        ledger.claim(slot, id(stack)); items.set(slot, placeholder(slot)); setDirty(); return true;
    }
    public boolean permits(int slot, ItemStack stack) {
        return !real(stack) || (slot >= 0 && slot < 36 && !nestedStorage(stack) && ledger.permits(slot, id(stack)));
    }
    public void write(int slot, ItemStack stack) {
        if (!real(stack)) { items.set(slot, placeholder(slot)); return; }
        if (permits(slot, stack)) { ledger.claim(slot, id(stack)); items.set(slot, stack); }
        else { items.set(slot, placeholder(slot)); ItemStack remaining = stack.copy(); insert(remaining); if (!remaining.isEmpty()) defer(remaining); }
        setDirty();
    }
    /** Used by pickups, item remainders and menu returns. Mutates only accepted counts. */
    public boolean insert(ItemStack incoming) {
        if (!real(incoming) || nestedStorage(incoming)) return false;
        normalize();
        int before = incoming.getCount();
        for (int n = 0; n < 36 && !incoming.isEmpty(); n++) {
            ItemStack target = items.get(n);
            if (real(target) && ItemStack.isSameItemSameComponents(target, incoming)) {
                int amount = Math.min(incoming.getCount(), target.getMaxStackSize() - target.getCount());
                if (amount > 0) { target.grow(amount); incoming.shrink(amount); }
            }
        }
        for (int pass = 0; pass < 2 && !incoming.isEmpty(); pass++) {
            for (int n = 0; n < 36 && !incoming.isEmpty(); n++) {
                if (real(items.get(n))) continue;
                boolean allowed = pass == 0 ? ledger.at(n).equals(id(incoming)) : ledger.at(n).isEmpty();
                if (!allowed || (!returning && cursorLeased(n))) continue;
                ledger.claim(n, id(incoming));
                items.set(n, incoming.split(Math.min(incoming.getCount(), incoming.getMaxStackSize())));
            }
        }
        if (incoming.getCount() != before) { setDirty(); return true; }
        return false;
    }
    private boolean cursorLeased(int index) {
        if (ledger.at(index).isEmpty()) return false;
        Map<String, Integer> held = new HashMap<>();
        for (ServerPlayer p : players()) {
            ItemStack cursor = p.containerMenu.getCarried();
            if (real(cursor)) held.merge(id(cursor), 1, Integer::sum);
        }
        int remaining = held.getOrDefault(ledger.at(index), 0);
        for (int n = 0; n <= index; n++) {
            if (!real(items.get(n)) && ledger.at(n).equals(ledger.at(index))) {
                if (n == index) return remaining > 0;
                remaining--;
            }
        }
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
        if (!ledger.at(target).equals(id(carried))) {
            int origin = -1;
            for (int n = 0; n < 36; n++) if (n != target && !real(items.get(n)) && ledger.at(n).equals(id(carried))) { origin = n; break; }
            if (origin >= 0) {
                ledger.swap(origin, target); items.set(origin, placeholder(origin));
            } else if (!ledger.at(target).isEmpty()) return false;
        }
        if (!permits(target, carried)) return false;
        if (ghost(items.get(target))) items.set(target, ItemStack.EMPTY);
        return true;
    }
    public void afterMenu(ServerPlayer player) {
        ensureClaim(player.containerMenu.getCarried()); normalize(); sync(); setDirty();
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
            int header = AbstractContainerMenu.getQuickcraftHeader(button);
            // Vanilla uses -999 for drag start/end, including short gestures into one crafting cell.
            // Permit the native state machine for workstation inputs; bag distribution stays disabled.
            if (header == 0 || header == 2) return false;
            if (slot == null || bagSlot || !processing(menu) || ghost(slot.getItem()) || nestedStorage(menu.getCarried())) {
                resync(player); return true;
            }
            return false;
        }
        if (input == ContainerInput.SWAP && !(button >= 0 && button < 9 || button == Inventory.SLOT_OFFHAND)) {
            resync(player); return true;
        }
        if (input == ContainerInput.THROW || input == ContainerInput.CLONE || index == -999) {
            tell(player, "You can't drop items during this challenge."); resync(player); return true;
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
            tell(player, "That slot is permanently reserved for another item. Use a hotbar number key to swap slots."); resync(player); return true;
        }
        if (!bagSlot && real(target) && (input == ContainerInput.QUICK_MOVE || input == ContainerInput.SWAP || !real(carried))) {
            if (!canIntroduce(target) || !canCraftRemainders(menu, slot, player)) {
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
        Set<String> needed = new HashSet<>();
        if (!ledger.contains(id(source.getItem()))) needed.add(id(source.getItem()));
        for (ItemStack rem : recipe.get().value().getRemainingItems(input))
            if (real(rem) && !ledger.contains(id(rem))) needed.add(id(rem));
        return needed.size() <= 36 - ledger.count();
    }

    public void onDeath(ServerPlayer player) {
        returnCursor(player.containerMenu);
        if (!keepOnDeath) {
            for (ServerPlayer p : players()) p.closeContainer();
            for (ServerPlayer p : players()) { p.containerMenu.setCarried(ItemStack.EMPTY); p.getInventory().clearContent(); }
            returns.clear();
        }
        normalize(); sync(); setDirty();
    }
    public void reset() {
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
        ledger.reset(); items.clear(); returns.clear(); members.clear(); attached.clear(); active = true;
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
        attached.clear(); members.clear(); items.clear(); ledger.reset(); setDirty(); return true;
    }
}
