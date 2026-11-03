package dev.leo.permapack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;

/** Preflight common conversions before they consume an item, fluid or captured mob. */
public final class ConversionGuard {
    private ConversionGuard() {}
    public static InteractionResult use(Player player, ItemStack held, BlockPos clicked, Entity entity) {
        SharedBag bag = PermaPack.forPlayer(player);
        if (bag == null || !SharedBag.real(held)) return InteractionResult.PASS;
        if (SharedBag.nestedStorage(held)) return deny(bag, player, "Bundles and filled storage items can't bypass the shared bag.");
        var level = player.level();
        var state = clicked == null ? null : level.getBlockState(clicked);
        // Opening a chest or trading must still work while holding a full-budget consumable.
        if (state != null && !(state.is(Blocks.CAULDRON) || state.is(Blocks.WATER_CAULDRON) || state.is(Blocks.LAVA_CAULDRON) || state.is(Blocks.POWDER_SNOW_CAULDRON) || state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST))) return InteractionResult.PASS;
        if (entity != null && !(entity instanceof Bucketable && held.is(Items.WATER_BUCKET) || entity instanceof AbstractCow && held.is(Items.BUCKET) || entity instanceof MushroomCow && held.is(Items.BOWL))) return InteractionResult.PASS;
        var remainder = held.get(DataComponents.USE_REMAINDER);
        if (remainder != null && !bag.canIntroduce(remainder.convertInto().create())) return full(bag, player);
        ItemStack result = ItemStack.EMPTY;
        if (entity instanceof Bucketable bucketable && held.is(Items.WATER_BUCKET)) result = bucketable.getBucketItemStack();
        else if (entity instanceof AbstractCow && held.is(Items.BUCKET)) result = new ItemStack(Items.MILK_BUCKET);
        else if (entity instanceof MushroomCow && held.is(Items.BOWL)) {
            // Both stew variants are possible without consuming the cow's stored effect to inspect it.
            if (!bag.canIntroduce(new ItemStack(Items.MUSHROOM_STEW)) || !bag.canIntroduce(new ItemStack(Items.SUSPICIOUS_STEW))) return full(bag, player);
        } else if (held.is(Items.BUCKET)) {
            if (state != null && state.is(Blocks.WATER_CAULDRON)) result = new ItemStack(Items.WATER_BUCKET);
            else if (state != null && state.is(Blocks.LAVA_CAULDRON)) result = new ItemStack(Items.LAVA_BUCKET);
            else if (state != null && state.is(Blocks.POWDER_SNOW_CAULDRON)) result = new ItemStack(Items.POWDER_SNOW_BUCKET);
            else {
                var eye = player.getEyePosition();
                var hit = level.clip(new ClipContext(eye, eye.add(player.calculateViewVector(player.getXRot(), player.getYRot()).scale(player.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    var block = level.getBlockState(hit.getBlockPos());
                    result = block.is(Blocks.POWDER_SNOW) ? new ItemStack(Items.POWDER_SNOW_BUCKET) : new ItemStack(block.getFluidState().getType().getBucket());
                }
            }
        } else if (held.getItem() instanceof BucketItem || held.is(Items.POWDER_SNOW_BUCKET)) result = new ItemStack(Items.BUCKET);
        else if (held.is(Items.GLASS_BOTTLE)) {
            boolean breath = !level.getEntitiesOfClass(AreaEffectCloud.class, player.getBoundingBox().inflate(2), cloud -> cloud.isAlive() && cloud.getOwner() instanceof EnderDragon).isEmpty();
            result = new ItemStack(breath ? Items.DRAGON_BREATH : state != null && (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)) ? Items.HONEY_BOTTLE : Items.POTION);
        }
        if (!bag.canIntroduce(result)) return full(bag, player);
        return InteractionResult.PASS;
    }
    private static InteractionResult full(SharedBag bag, Player player) {
        return deny(bag, player, "This use creates a new item type, but all 36 slots are claimed.");
    }
    private static InteractionResult deny(SharedBag bag, Player player, String message) {
        bag.tell(player, message); return InteractionResult.FAIL;
    }
}
