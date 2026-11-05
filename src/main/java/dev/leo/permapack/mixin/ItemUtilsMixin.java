package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemUtils.class)
public abstract class ItemUtilsMixin {
    @Inject(method="createFilledResult(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at=@At("HEAD"), cancellable=true)
    private static void claimConversion(ItemStack original, Player player, ItemStack result, boolean creative, CallbackInfoReturnable<ItemStack> cir) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag != null && !bag.canTransform((net.minecraft.server.level.ServerPlayer)player,original,result)) {
            bag.tell(player, "The converted item needs an unused permanent slot."); cir.setReturnValue(original);
        }
    }
}
