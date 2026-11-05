package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Equippable.class)
public abstract class EquippableMixin {
    @Inject(method="swapWithEquipmentSlot",at=@At("HEAD"),cancellable=true)
    private void preflight(ItemStack held,Player player,CallbackInfoReturnable<InteractionResult> cir) {
        SharedBag bag=PermaPack.forPlayer(player);if(bag==null)return;
        if(!bag.canEquipFromHand((ServerPlayer)player,held,((Equippable)(Object)this).slot())) {
            bag.tell(player,"The unequipped item needs room in the shared inventory.");cir.setReturnValue(InteractionResult.FAIL);
        } else bag.beginTransfer((ServerPlayer)player);
    }
    @Inject(method="swapWithEquipmentSlot",at=@At("RETURN"))
    private void finish(ItemStack held,Player player,CallbackInfoReturnable<InteractionResult> cir) {
        SharedBag bag=PermaPack.forPlayer(player);if(bag!=null)bag.endTransfer((ServerPlayer)player);
    }
}
