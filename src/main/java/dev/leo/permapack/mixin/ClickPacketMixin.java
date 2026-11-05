package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ClickPacketMixin {
    @Shadow public ServerPlayer player;
    // Inside vanilla's loaded-client and server-thread checks, before either hand is changed.
    @Inject(method="handlePlayerAction",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"),cancellable=true)
    private void swapOffhand(ServerboundPlayerActionPacket packet,CallbackInfo ci) {
        SharedBag bag=PermaPack.forPlayer(player);
        if(bag!=null&&packet.getAction()==ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            bag.swapPersonal(player,player.getInventory().getSelectedSlot(),net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND);ci.cancel();
        }
    }
    // Run after vanilla has applied the client's predicted slot hashes and resumed synchronization.
    @Inject(method="handleContainerClick", at=@At("RETURN"))
    private void authoritativeResult(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        SharedBag bag=PermaPack.forPlayer(player);
        if(bag!=null) bag.resync(player);
    }
}
