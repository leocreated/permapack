package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class EquipmentMixin {
    @Inject(method="setItemSlot",at=@At("RETURN"))
    private void personalEquipment(EquipmentSlot slot,ItemStack stack,CallbackInfo ci) {
        if(slot!=EquipmentSlot.MAINHAND && (Object)this instanceof ServerPlayer player) {
            SharedBag bag=PermaPack.forPlayer(player);if(bag!=null)bag.personalChanged();
        }
    }
}
