package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ClickPacketMixin {
    @Shadow public ServerPlayer player;
    // Run after vanilla has applied the client's predicted slot hashes and resumed synchronization.
    @Inject(method="handleContainerClick", at=@At("RETURN"))
    private void authoritativeResult(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        SharedBag bag=PermaPack.forPlayer(player);
        if(bag!=null) bag.resync(player);
    }
}
