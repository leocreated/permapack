package dev.leo.permapack.mixin;
import dev.leo.permapack.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @Shadow @Final private CraftingContainer craftSlots;
    @Inject(method="onTake", at=@At("RETURN"))
    private void claimRemainders(Player player, ItemStack taken, CallbackInfo ci) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag == null) return;
        bag.ensureClaim(taken);
        for (int n=0; n<craftSlots.getContainerSize(); n++) bag.ensureClaim(craftSlots.getItem(n));
    }
}
