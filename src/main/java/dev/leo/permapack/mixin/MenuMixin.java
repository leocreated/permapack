package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(AbstractContainerMenu.class)
public abstract class MenuMixin {
    @Inject(method="clicked", at=@At("HEAD"), cancellable=true)
    private void guard(int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && bag.beforeClick((AbstractContainerMenu)(Object)this, slot, button, input, (ServerPlayer)player)) ci.cancel();
    }
    @Inject(method="clicked", at=@At("RETURN"))
    private void after(int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player); if (bag != null) bag.afterMenu((ServerPlayer)player);
    }
    @Inject(method="dropOrPlaceInInventory", at=@At("HEAD"), cancellable=true)
    private static void recover(Player player, ItemStack stack, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player); if (bag != null) { bag.returnStack(stack); ci.cancel(); }
    }
    @Inject(method="moveItemStackTo", at=@At("HEAD"), cancellable=true)
    private void sharedQuickMove(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;
        if (start < 0 || end > menu.slots.size() || start >= end) return;
        Inventory inventory = null;
        for (int n = start; n < end; n++) {
            Slot slot = menu.slots.get(n);
            if (!(slot.container instanceof Inventory inv) || slot.getContainerSlot() >= 36) return;
            if (inventory != null && inventory != inv) return;
            inventory = inv;
        }
        if (inventory == null) return;
        SharedBag bag = PermaPack.forPlayer(inventory.player);
        if (bag == null || !bag.bound(inventory)) return;
        for (ItemStack current : bag.items) if (current == stack) { cir.setReturnValue(false); return; }
        cir.setReturnValue(bag.insert(stack));
    }
}
