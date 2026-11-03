package dev.leo.permapack.mixin;
import dev.leo.permapack.PermaPack;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
    // This drop call is a purely visual pickup animation, already marked fake by vanilla.
    // It must not enter the challenge's real-item recovery path.
    @Redirect(method="giveItem", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;", ordinal=1))
    private static ItemEntity noFakeReturn(ServerPlayer player, ItemStack stack, boolean random) {
        return PermaPack.forPlayer(player) == null ? player.drop(stack,random) : null;
    }
}
