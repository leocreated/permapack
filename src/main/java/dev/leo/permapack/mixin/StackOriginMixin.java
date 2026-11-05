package dev.leo.permapack.mixin;

import dev.leo.permapack.SharedBag;
import dev.leo.permapack.StackOriginAccess;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class StackOriginMixin implements StackOriginAccess {
    @Unique private Set<SharedBag.Origin> permapack$origins=Set.of();
    @Override public Set<SharedBag.Origin> permapack$origins() { return permapack$origins; }
    @Override public void permapack$origins(Set<SharedBag.Origin> origins) { permapack$origins=Set.copyOf(origins); }
    @Inject(method="copy",at=@At("RETURN"))
    private void copyOrigin(CallbackInfoReturnable<ItemStack> cir) {
        ItemStack copy=cir.getReturnValue();
        if(!copy.isEmpty()) ((StackOriginAccess)(Object)copy).permapack$origins(permapack$origins);
    }
}
