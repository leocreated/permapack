package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @Shadow @Final public Player player;
    @Shadow public abstract int getSelectedSlot();
    @Shadow public abstract void setSelectedSlot(int slot);
    @Inject(method="getSelectedItem", at=@At("RETURN"), cancellable=true)
    private void hideGhostInHand(CallbackInfoReturnable<ItemStack> cir) {
        if (SharedBag.ghost(cir.getReturnValue())) cir.setReturnValue(ItemStack.EMPTY);
    }
    @Inject(method="add(ILnet/minecraft/world/item/ItemStack;)Z", at=@At("HEAD"), cancellable=true)
    private void insert(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && bag.bound((Inventory)(Object)this)) cir.setReturnValue(bag.insert(stack));
    }
    @Inject(method="getFreeSlot", at=@At("HEAD"), cancellable=true)
    private void free(CallbackInfoReturnable<Integer> cir) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && bag.bound((Inventory)(Object)this)) cir.setReturnValue(bag.ledger.freeSlot());
    }
    @Inject(method="setItem", at=@At("HEAD"), cancellable=true)
    private void write(int slot, ItemStack stack, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag == null || !bag.bound((Inventory)(Object)this)) return;
        if (slot < 36) { bag.write(slot, stack); ci.cancel(); }
        else if (SharedBag.real(stack)) bag.ensureClaim(stack);
    }
    @Inject(method="setSelectedItem", at=@At("HEAD"), cancellable=true)
    private void selected(ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag == null || !bag.bound((Inventory)(Object)this)) return;
        ItemStack previous = bag.items.get(getSelectedSlot());
        bag.write(getSelectedSlot(), stack);
        cir.setReturnValue(previous);
    }
    @Inject(method="placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at=@At("HEAD"), cancellable=true)
    private void returnItems(ItemStack stack, boolean send, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && bag.bound((Inventory)(Object)this)) { bag.returnStack(stack); ci.cancel(); }
    }
    @Inject(method="pickSlot", at=@At("HEAD"), cancellable=true)
    private void pick(int slot, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && bag.bound((Inventory)(Object)this)) { bag.swapSlots(slot, getSelectedSlot()); ci.cancel(); }
    }
}
