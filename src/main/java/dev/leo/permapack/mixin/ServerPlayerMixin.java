package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method="drop(Z)V", at=@At("HEAD"), cancellable=true)
    private void noDropKey(boolean all, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer((ServerPlayer)(Object)this);
        if (bag != null) { bag.tell((ServerPlayer)(Object)this, "You can't drop items during this challenge."); bag.resync((ServerPlayer)(Object)this); ci.cancel(); }
    }
    @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at=@At("HEAD"), cancellable=true)
    private void recoverDrop(ItemStack stack, boolean scatter, boolean retainOwner, CallbackInfoReturnable<ItemEntity> cir) {
        SharedBag bag = PermaPack.forPlayer((ServerPlayer)(Object)this);
        if (bag != null) { bag.returnStack(stack); cir.setReturnValue(null); }
    }
}
