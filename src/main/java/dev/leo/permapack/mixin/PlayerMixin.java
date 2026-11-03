package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method="dropEquipment", at=@At("HEAD"), cancellable=true)
    private void death(ServerLevel level, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer((Player)(Object)this);
        if (bag != null) { bag.onDeath((ServerPlayer)(Object)this); ci.cancel(); }
    }
}
