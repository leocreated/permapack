package dev.leo.permapack.mixin;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Inventory.class)
public interface InventoryAccess {
    @Mutable @Accessor("items") void permapack$items(NonNullList<ItemStack> items);
}
