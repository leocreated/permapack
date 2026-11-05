package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow @Final public Container container;
    @Shadow public abstract int getContainerSlot();
    @Shadow public abstract ItemStack getItem();
    @Inject(method="mayPickup", at=@At("HEAD"), cancellable=true)
    private void pickup(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (SharedBag.ghost(getItem())) cir.setReturnValue(false);
    }
    @Inject(method="mayPlace", at=@At("HEAD"), cancellable=true)
    private void place(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!(container instanceof Inventory inv)) return;
        SharedBag bag = PermaPack.forPlayer(inv.player);
        if (bag != null && (getContainerSlot() < 36 ? !bag.permits(getContainerSlot(), stack) : SharedBag.nestedStorage(stack))) cir.setReturnValue(false);
    }
}
