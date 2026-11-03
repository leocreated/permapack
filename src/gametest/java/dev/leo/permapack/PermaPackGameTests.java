package dev.leo.permapack;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Exercises the real mixed-in inventory and vanilla menus with three connected server players. */
public final class PermaPackGameTests {
    private static void fillBudget(SharedBag bag, int target, Set<net.minecraft.world.item.Item> excluded) {
        for (var item : BuiltInRegistries.ITEM) {
            if (bag.ledger.count()==target) break;
            ItemStack probe = new ItemStack(item);
            if (probe.isEmpty() || excluded.contains(item) || SharedBag.nestedStorage(probe)) continue;
            if (!bag.ledger.contains(SharedBag.id(probe))) bag.ledger.claim(bag.ledger.freeSlot(), SharedBag.id(probe));
        }
        bag.normalize();
    }
    private static ServerPlayer player(GameTestHelper h, String name) {
        var server = h.getLevel().getServer();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(server, h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
    private static int bagSlot(AbstractContainerMenu menu, ServerPlayer p, int slot) {
        return menu.findSlot(p.getInventory(), slot).orElseThrow();
    }
    private static int count(SharedBag bag, net.minecraft.world.item.Item item) {
        return bag.items.stream().filter(SharedBag::real).filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static int itemSlot(SharedBag bag,net.minecraft.world.item.Item item) {
        for(int n=0;n<36;n++) if(SharedBag.real(bag.items.get(n)) && bag.items.get(n).is(item)) return n;
        throw new IllegalStateException("Missing fixture item "+item);
    }
    private static void drag(ServerPlayer player,int type,int... slots) {
        click(player,-999,AbstractContainerMenu.getQuickcraftMask(0,type),ContainerInput.QUICK_CRAFT);
        for(int slot:slots) click(player,slot,AbstractContainerMenu.getQuickcraftMask(1,type),ContainerInput.QUICK_CRAFT);
        click(player,-999,AbstractContainerMenu.getQuickcraftMask(2,type),ContainerInput.QUICK_CRAFT);
    }
    private static void packetClick(ServerPlayer player,int slot,int button,ContainerInput input) {
        player.connection.handleContainerClick(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
            player.containerMenu.containerId,player.containerMenu.getStateId(),(short)slot,(byte)button,input,
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<net.minecraft.network.HashedStack>(),net.minecraft.network.HashedStack.EMPTY));
    }
    private static void packetDrag(ServerPlayer player,int type,int... slots) {
        packetClick(player,-999,AbstractContainerMenu.getQuickcraftMask(0,type),ContainerInput.QUICK_CRAFT);
        for(int slot:slots) packetClick(player,slot,AbstractContainerMenu.getQuickcraftMask(1,type),ContainerInput.QUICK_CRAFT);
        packetClick(player,-999,AbstractContainerMenu.getQuickcraftMask(2,type),ContainerInput.QUICK_CRAFT);
    }
    private static void check(GameTestHelper h, boolean value, String message) {
        h.assertTrue(value, message);
    }
    private static void click(ServerPlayer player, int slot, int button, ContainerInput input) {
        player.containerMenu.clicked(slot, button, input, player);
    }
    private static void fresh(SharedBag bag, ServerPlayer recipient) {
        bag.reset(); if (bag.active && !bag.stop(recipient)) throw new IllegalStateException("Test cleanup could not stop previous fixture");
        recipient.getInventory().clearContent(); bag.keepOnDeath=true;
    }

    @GameTest(maxTicks = 200)
    public void threePlayerInventoryAndMenus(GameTestHelper h) {
        var server = h.getLevel().getServer();
        SharedBag bag = PermaPack.get(server);
        ServerPlayer a = player(h, "PermaTestA"), b = player(h, "PermaTestB"), c = player(h, "PermaTestC");
        try {
            fresh(bag,a);
            check(h, bag.start(List.of(a,b,c)), "Run starts with three empty inventories");
            check(h, a.getInventory().getNonEquipmentItems() == b.getInventory().getNonEquipmentItems(), "A and B share backing inventory");
            check(h, c.getInventory().getNonEquipmentItems() == bag.items, "C shares the authoritative bag");
            ItemStack logs = new ItemStack(Items.OAK_LOG, 4);
            check(h, a.getInventory().add(logs) && logs.isEmpty(), "A picks up four logs");
            check(h, b.getInventory().getItem(0).getCount() == 4 && c.getInventory().getItem(0).getCount() == 4, "All players immediately see the same logs");
            check(h, bag.ledger.count() == 1, "One physical slot claimed");

            // Native 2x2 crafting, including the cursor and the result slot.
            click(a, bagSlot(a.containerMenu,a,0), 0, ContainerInput.PICKUP);
            click(a, 1, 1, ContainerInput.PICKUP);
            click(a, bagSlot(a.containerMenu,a,0), 0, ContainerInput.PICKUP);
            check(h, a.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "Vanilla crafting preview still works");
            click(a, 0, 0, ContainerInput.PICKUP);
            check(h, a.containerMenu.getCarried().is(Items.OAK_PLANKS), "Crafted planks are on the cursor");
            check(h, bag.ledger.count() == 2, "Crafted output claims its own permanent slot");
            int plankSlot = bag.ledger.snapshot().indexOf("minecraft:oak_planks");
            click(a, bagSlot(a.containerMenu,a,plankSlot), 0, ContainerInput.PICKUP);
            check(h, count(bag,Items.OAK_LOG) == 3 && count(bag,Items.OAK_PLANKS) == 4, "Crafting conserves ingredients and output across all players");

            // Two players reach for the same slot in successive server actions.
            click(b, bagSlot(b.containerMenu,b,0), 0, ContainerInput.PICKUP);
            click(c, bagSlot(c.containerMenu,c,0), 0, ContainerInput.PICKUP);
            check(h, b.containerMenu.getCarried().getCount() == 3 && c.containerMenu.getCarried().isEmpty(), "Concurrent grabs cannot duplicate a stack");
            check(h, SharedBag.ghost(bag.items.get(0)), "The empty slot remains visibly reserved");
            b.inventoryMenu.removed(b);
            check(h, b.containerMenu.getCarried().isEmpty() && count(bag,Items.OAK_LOG) == 3, "Closing an inventory returns cursor items once");
            check(h, bag.ledger.count() == 2, "Cursor movement does not release or invent reservations");
            a.getInventory().setSelectedSlot(0); a.drop(false);
            check(h, count(bag,Items.OAK_LOG) == 3, "Q cannot drop or destroy the selected item");
            click(a, bagSlot(a.containerMenu,a,0), 0, ContainerInput.THROW);
            check(h, count(bag,Items.OAK_LOG) == 3, "Q inside a menu cannot drop items");

            SimpleContainer chest = new SimpleContainer(27);
            a.containerMenu = ChestMenu.threeRows(77, a.getInventory(), chest);
            click(a, bagSlot(a.containerMenu,a,0), 0, ContainerInput.QUICK_MOVE);
            check(h, chest.isEmpty() && count(bag,Items.OAK_LOG) == 3, "Shift-click cannot deposit into storage");
            click(a, bagSlot(a.containerMenu,a,0), 0, ContainerInput.PICKUP);
            click(a, 0, 0, ContainerInput.PICKUP);
            click(a, -999, 0, ContainerInput.PICKUP);
            check(h, chest.isEmpty() && a.containerMenu.getCarried().getCount() == 3, "Manual deposits and outside clicks preserve the cursor");
            a.containerMenu.removed(a); a.containerMenu = a.inventoryMenu;
            chest.setItem(0, new ItemStack(Items.DIAMOND,1));
            a.containerMenu = ChestMenu.threeRows(78, a.getInventory(), chest);
            click(a,0,0,ContainerInput.QUICK_MOVE);
            check(h, chest.isEmpty() && count(bag,Items.DIAMOND) == 1, "Shift-click can take loot into the shared inventory");
            a.containerMenu.removed(a); a.containerMenu = a.inventoryMenu;

            int diamondSlot = bag.ledger.snapshot().indexOf("minecraft:diamond");
            click(c, bagSlot(c.containerMenu,c,diamondSlot), 0, ContainerInput.SWAP);
            check(h, bag.items.get(0).is(Items.DIAMOND) && bag.ledger.at(0).equals("minecraft:diamond"), "Hotbar number keys move the item and its reservation atomically");
            check(h, count(bag,Items.OAK_LOG)==3 && bag.ledger.count()==3, "Reordering conserves quantities and budget");
            c.getInventory().removeItem(0, 1); bag.normalize();
            check(h, SharedBag.ghost(bag.items.get(0)) && bag.ledger.count()==3, "Consumption leaves a permanent ghost slot");
            ItemStack diamond = new ItemStack(Items.DIAMOND,1); b.getInventory().add(diamond);
            check(h, diamond.isEmpty() && bag.ledger.count()==3, "A matching type refills its old slot without spending a new one");

            var ops = RegistryOps.create(NbtOps.INSTANCE, h.getLevel().registryAccess());
            var encoded = SharedBag.CODEC.encodeStart(ops, bag).getOrThrow();
            SharedBag restored = SharedBag.CODEC.parse(ops, encoded).getOrThrow();
            check(h, restored.active && restored.keepOnDeath && restored.members.equals(bag.members), "Saved state preserves run settings and membership");
            check(h, restored.ledger.snapshot().equals(bag.ledger.snapshot()) && ItemStack.listMatches(restored.items,bag.items), "Saved state round-trips all item components and reservations");
            var oldList = a.getInventory().getNonEquipmentItems();
            bag.disconnect(a); bag.join(a);
            check(h, a.getInventory().getNonEquipmentItems()==oldList && count(bag,Items.DIAMOND)==1, "Reconnect rebinds without merging a second copy");

            // Full budget protects both pickups and recipe output without consuming ingredients.
            for (var item : BuiltInRegistries.ITEM) {
                if (bag.ledger.count()==36) break;
                ItemStack probe = new ItemStack(item);
                if (probe.isEmpty() || item==Items.STICK || item==Items.NETHER_STAR || SharedBag.nestedStorage(probe)) continue;
                if (!bag.ledger.contains(SharedBag.id(probe))) bag.ledger.claim(bag.ledger.freeSlot(),SharedBag.id(probe));
            }
            bag.normalize();
            ItemStack rejected = new ItemStack(Items.NETHER_STAR);
            check(h, !b.getInventory().add(rejected) && rejected.getCount()==1 && bag.ledger.count()==36, "Full budget rejects a new type without deleting the incoming item");
            a.inventoryMenu.getSlot(1).setByPlayer(new ItemStack(Items.OAK_PLANKS));
            a.inventoryMenu.getSlot(3).setByPlayer(new ItemStack(Items.OAK_PLANKS));
            check(h, a.inventoryMenu.getSlot(0).getItem().is(Items.STICK), "Stick recipe preview is valid at full budget");
            click(a,0,0,ContainerInput.PICKUP);
            check(h, a.inventoryMenu.getCarried().isEmpty() && a.inventoryMenu.getSlot(1).getItem().getCount()==1, "Full budget blocks crafting a new type before spending ingredients");
            a.inventoryMenu.getSlot(1).setByPlayer(ItemStack.EMPTY); a.inventoryMenu.getSlot(3).setByPlayer(ItemStack.EMPTY);

            bag.keepOnDeath=true;
            a.hurtServer(h.getLevel(),a.damageSources().genericKill(),1000000f);
            check(h, count(bag,Items.DIAMOND)==1 && bag.ledger.count()==36, "Actual player death preserves shared items and reservations by default");
            ServerPlayer respawned=server.getPlayerList().respawn(a,false,Entity.RemovalReason.KILLED);
            a=respawned;
            check(h, bag.bound(a.getInventory()) && a.getInventory().getNonEquipmentItems()==b.getInventory().getNonEquipmentItems(), "Actual respawn rejoins the same shared bag");
            bag.keepOnDeath=false; bag.onDeath(b);
            check(h, bag.items.stream().noneMatch(SharedBag::real) && bag.ledger.count()==36, "Hard mode loses items but never releases reservations");
            bag.reset();
            check(h, bag.ledger.count()==0 && bag.items.stream().allMatch(ItemStack::isEmpty), "Explicit reset is the only operation that clears reservations");
            a.getInventory().add(new ItemStack(Items.OAK_LOG,2));
            click(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            click(a,-999,AbstractContainerMenu.getQuickcraftMask(0,0),ContainerInput.QUICK_CRAFT);
            click(a,1,AbstractContainerMenu.getQuickcraftMask(1,0),ContainerInput.QUICK_CRAFT);
            click(a,-999,AbstractContainerMenu.getQuickcraftMask(2,0),ContainerInput.QUICK_CRAFT);
            check(h,a.containerMenu.getCarried().isEmpty() && a.containerMenu.getSlot(1).getItem().getCount()==2 && a.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS),"Native single-cell drag gesture places the stack and updates its real crafting preview");
            click(a,0,0,ContainerInput.QUICK_MOVE);
            check(h,count(bag,Items.OAK_PLANKS)==8 && count(bag,Items.OAK_LOG)==0 && bag.ledger.count()==2,"Shift-crafting after a drag gesture produces exactly eight planks and keeps the spent log reservation");
            bag.reset();
            a.getInventory().add(new ItemStack(Items.DIAMOND,2));
            check(h, bag.stop(a), "Stop exits the challenge safely");
            check(h, a.getInventory().contains(new ItemStack(Items.DIAMOND)) && b.getInventory().isEmpty() && c.getInventory().isEmpty(), "Stop delivers one copy to host and clears the other mirrors");
            System.out.println("PERMAPACK_SMOKE_PASS: three players, crafting, cursor race, drops, storage, loot, reorder, limits, codec, reconnect, death, respawn, hard mode, reset, stop");
            h.succeed();
        } finally {
            if(bag.active){bag.reset();bag.stop(a);}
            for (ServerPlayer p : List.of(a,b,c)) { if (bag.member(p)) bag.disconnect(p); server.getPlayerList().remove(p); }
        }
    }

    @GameTest(maxTicks=200)
    public void conversionsAndTeamLimit(GameTestHelper h) {
        var server=h.getLevel().getServer(); SharedBag bag=PermaPack.get(server);
        var a=player(h,"ConvertA"); var b=player(h,"ConvertB"); var c=player(h,"ConvertC"); var d=player(h,"ConvertD");
        try {
            fresh(bag,a);
            check(h,bag.start(List.of(a,b)),"Start conversion fixture");
            check(h,!bag.joinTeam(List.of(c,d)) && bag.members.size()==2,"Bulk join cannot exceed three members");
            check(h,bag.joinTeam(List.of(c)) && bag.members.size()==3,"One empty third player can join");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"give "+a.getScoreboardName()+" minecraft:emerald 2");
            check(h,count(bag,Items.EMERALD)==2 && bag.returns.isEmpty(),"Give command's fake pickup animation cannot create an extra real item: count="+count(bag,Items.EMERALD)+", pending="+bag.returns);
            bag.reset();
            a.getInventory().add(new ItemStack(Items.BUCKET));
            a.getInventory().setSelectedSlot(0);
            BlockPos source=h.absolutePos(new BlockPos(2,2,2));
            a.setPos(source.getX()+.5,source.getY(),source.getZ()+2.5); a.setYRot(180); a.setXRot(30);
            h.getLevel().setBlockAndUpdate(source,Blocks.WATER.defaultBlockState());
            a.gameMode.useItem(a,h.getLevel(),a.getMainHandItem(),InteractionHand.MAIN_HAND);
            check(h,count(bag,Items.WATER_BUCKET)==1 && h.getLevel().getFluidState(source).isEmpty(),"Actual bucket use collects water into the shared bag");
            check(h,bag.ledger.count()==2 && bag.ledger.contains("minecraft:bucket"),"Bucket conversion keeps both permanent reservations");
            check(h,b.getInventory().getNonEquipmentItems()==bag.items && bag.returns.isEmpty(),"Conversion neither duplicates nor queues its output");
            a.getInventory().add(new ItemStack(Items.BUCKET)); a.getInventory().setSelectedSlot(itemSlot(bag,Items.BUCKET));
            fillBudget(bag,36,Set.of(Items.LAVA_BUCKET,Items.NETHER_STAR));
            h.getLevel().setBlockAndUpdate(source,Blocks.LAVA.defaultBlockState());
            var denied=ConversionGuard.use(a,a.getMainHandItem(),null,null);
            check(h,denied==InteractionResult.FAIL,"New lava-bucket type is rejected before use at full budget: held="+a.getMainHandItem()+", claimed="+bag.ledger.count()+", ray="+h.getLevel().clip(new net.minecraft.world.level.ClipContext(a.getEyePosition(),a.getEyePosition().add(a.getViewVector(1f).scale(a.blockInteractionRange())),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY,a)).getBlockPos()+", source="+source);
            a.gameMode.useItem(a,h.getLevel(),a.getMainHandItem(),InteractionHand.MAIN_HAND);
            check(h,h.getLevel().getBlockState(source).is(Blocks.LAVA) && count(bag,Items.LAVA_BUCKET)==0 && count(bag,Items.BUCKET)==1,"Server interaction leaves lava and bucket intact when rejected");
            check(h,ConversionGuard.use(a,a.getMainHandItem(),source.below(),null)==InteractionResult.PASS,"An unrelated block interaction is not blocked by a held consumable");
            bag.reset();
            a.getInventory().add(new ItemStack(Items.DIAMOND,2));
            check(h,bag.stop(a),"Conversion fixture stops");
            // Simulate a saved offline mirror from the previous run arriving during a new run.
            a.getInventory().clearContent();
            check(h,bag.start(List.of(a)),"Start next run");
            b.getInventory().setItem(0,new ItemStack(Items.DIAMOND,2));
            bag.retireMirror(b);
            check(h,b.getInventory().isEmpty(),"Retired mirrors are discarded even when a later run is active");
            bag.reset(); check(h,bag.stop(a),"Clean conversion fixture");
            System.out.println("PERMAPACK_CONVERSIONS_PASS: real water pickup, full-budget lava refusal, team limit, retired reconnect");
            h.succeed();
        } finally {
            if (bag.active) { bag.reset(); bag.stop(a); }
            for (ServerPlayer p:List.of(a,b,c,d)) { if(bag.member(p)) bag.disconnect(p); server.getPlayerList().remove(p); }
        }
    }

    @GameTest(maxTicks=200)
    public void craftingRemaindersReserveImmediately(GameTestHelper h) {
        var server=h.getLevel().getServer(); SharedBag bag=PermaPack.get(server); var a=player(h,"CakeTest");
        try {
            fresh(bag,a);
            check(h,bag.start(List.of(a)),"Start cake fixture");
            var table=h.absolutePos(new BlockPos(1,1,1)); h.getLevel().setBlockAndUpdate(table,Blocks.CRAFTING_TABLE.defaultBlockState());
            a.containerMenu=new CraftingMenu(91,a.getInventory(),ContainerLevelAccess.create(h.getLevel(),table));
            net.minecraft.world.item.Item[] recipe={Items.MILK_BUCKET,Items.MILK_BUCKET,Items.MILK_BUCKET,Items.SUGAR,Items.EGG,Items.SUGAR,Items.WHEAT,Items.WHEAT,Items.WHEAT};
            for(int n=0;n<9;n++) { ItemStack ingredient=new ItemStack(recipe[n]); bag.ensureClaim(ingredient); a.containerMenu.getSlot(n+1).setByPlayer(ingredient); }
            fillBudget(bag,35,Set.of(Items.CAKE,Items.BUCKET));
            check(h,a.containerMenu.getSlot(0).getItem().is(Items.CAKE),"Cake preview uses the real recipe");
            click(a,0,0,ContainerInput.PICKUP);
            check(h,a.containerMenu.getCarried().isEmpty() && a.containerMenu.getSlot(1).getItem().is(Items.MILK_BUCKET),"One free slot cannot fit both cake and its new bucket remainder");
            // Rebuild the same fixture with two free reservations.
            a.containerMenu.removed(a); a.containerMenu=a.inventoryMenu; bag.reset();
            a.containerMenu=new CraftingMenu(92,a.getInventory(),ContainerLevelAccess.create(h.getLevel(),table));
            for(int n=0;n<9;n++) { ItemStack ingredient=new ItemStack(recipe[n]); bag.ensureClaim(ingredient); a.containerMenu.getSlot(n+1).setByPlayer(ingredient); }
            fillBudget(bag,34,Set.of(Items.CAKE,Items.BUCKET));
            click(a,0,0,ContainerInput.PICKUP);
            check(h,a.containerMenu.getCarried().is(Items.CAKE) && bag.ledger.count()==36 && bag.ledger.contains("minecraft:bucket"),"Cake and all bucket remainders reserve their types immediately");
            a.containerMenu.removed(a); a.containerMenu=a.inventoryMenu; bag.normalize();
            check(h,count(bag,Items.CAKE)==1 && count(bag,Items.BUCKET)==3 && bag.returns.isEmpty(),"Closing returns exactly one cake and three empty buckets");
            bag.reset(); check(h,bag.stop(a),"Clean cake fixture");
            System.out.println("PERMAPACK_REMAINDERS_PASS: new-type budget, immediate reservations, exact cake and bucket returns");
            h.succeed();
        } finally { if(bag.active){bag.reset();bag.stop(a);} if(bag.member(a))bag.disconnect(a);server.getPlayerList().remove(a); }
    }

    @GameTest(maxTicks=200)
    public void freelyMovablePermanentAllocations(GameTestHelper h) {
        var server=h.getLevel().getServer();SharedBag bag=PermaPack.get(server);
        var a=player(h,"MoveA");var b=player(h,"MoveB");var c=player(h,"MoveC");
        try {
            fresh(bag,a);check(h,bag.start(List.of(a,b,c)),"Start three-player movement fixture");
            a.getInventory().add(new ItemStack(Items.DIRT,64));a.getInventory().add(new ItemStack(Items.IRON_SWORD));a.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            packetDrag(a,0,bagSlot(a.containerMenu,a,15));
            check(h,bag.items.get(15).is(Items.DIRT) && bag.items.get(0).isEmpty() && bag.ledger.count()==3,"Moving a whole stack frees its old screen position without spending an allocation");
            packetClick(b,bagSlot(b.containerMenu,b,1),0,ContainerInput.PICKUP);
            packetClick(b,bagSlot(b.containerMenu,b,0),0,ContainerInput.PICKUP);
            check(h,bag.items.get(0).is(Items.IRON_SWORD) && c.getInventory().getItem(0).is(Items.IRON_SWORD) && bag.ledger.count()==3,"Sword can occupy the original dirt position and all three players share it");
            packetClick(c,bagSlot(c.containerMenu,c,0),0,ContainerInput.PICKUP);
            packetClick(c,bagSlot(c.containerMenu,c,15),0,ContainerInput.PICKUP);
            packetClick(c,bagSlot(c.containerMenu,c,0),0,ContainerInput.PICKUP);
            check(h,bag.items.get(15).is(Items.IRON_SWORD) && bag.items.get(0).is(Items.DIRT) && c.containerMenu.getCarried().isEmpty() && bag.ledger.count()==3,"Ordinary cursor swaps move both allocations without creating capacity");

            bag.items.get(0).setCount(60);
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            ItemStack mined=new ItemStack(Items.DIRT);
            check(h,b.getInventory().add(mined) && mined.isEmpty() && bag.ledger.count()==3,"Mining while another player holds 60 dirt does not spend a redundant allocation for one more dirt");
            a.inventoryMenu.removed(a);
            check(h,count(bag,Items.DIRT)==61 && bag.ledger.count()==3 && bag.returns.isEmpty(),"The held and mined dirt recombine into the original allocation");
            bag.items.get(itemSlot(bag,Items.DIRT)).setCount(64);

            packetClick(a,bagSlot(a.containerMenu,a,0),1,ContainerInput.PICKUP);
            packetDrag(a,0,bagSlot(a.containerMenu,a,20));
            check(h,bag.items.get(0).getCount()==32 && bag.items.get(20).getCount()==32 && bag.ledger.reserved("minecraft:dirt")==2 && bag.ledger.count()==4,"Splitting into two stored stacks spends exactly one additional allocation");
            packetClick(a,bagSlot(a.containerMenu,a,20),0,ContainerInput.PICKUP);
            packetDrag(a,0,bagSlot(a.containerMenu,a,21));
            check(h,bag.items.get(21).getCount()==32 && bag.ledger.count()==4,"Moving a split stack does not spend a third allocation");
            packetClick(a,bagSlot(a.containerMenu,a,21),0,ContainerInput.PICKUP);
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            check(h,count(bag,Items.DIRT)==64 && bag.ledger.reserved("minecraft:dirt")==2 && bag.ledger.count()==4,"Merging conserves items and does not release an already spent allocation");

            bag.reset();a.getInventory().add(new ItemStack(Items.DIRT,64));a.getInventory().add(new ItemStack(Items.IRON_SWORD));
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            packetDrag(a,0,bagSlot(a.containerMenu,a,15),bagSlot(a.containerMenu,a,16),bagSlot(a.containerMenu,a,17));
            check(h,count(bag,Items.DIRT)==63 && a.containerMenu.getCarried().getCount()==1 && bag.ledger.reserved("minecraft:dirt")==3 && bag.ledger.count()==4,"Left drag distributes exact quantities and spends only the extra stored-stack allocations");
            a.inventoryMenu.removed(a);
            check(h,count(bag,Items.DIRT)==64 && bag.returns.isEmpty(),"Closing returns the drag remainder exactly once");

            bag.reset();a.getInventory().add(new ItemStack(Items.DIRT,8));
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            packetDrag(a,1,bagSlot(a.containerMenu,a,20),bagSlot(a.containerMenu,a,21),bagSlot(a.containerMenu,a,22));
            check(h,count(bag,Items.DIRT)==3 && a.containerMenu.getCarried().getCount()==5 && bag.ledger.count()==3,"Right drag distributes one per cell without creating items");
            a.inventoryMenu.removed(a);
            SimpleContainer chest=new SimpleContainer(27);
            a.containerMenu=ChestMenu.threeRows(81,a.getInventory(),chest);
            packetClick(a,bagSlot(a.containerMenu,a,itemSlot(bag,Items.DIRT)),0,ContainerInput.PICKUP);
            int held=a.containerMenu.getCarried().getCount();packetDrag(a,0,0);
            check(h,chest.isEmpty() && a.containerMenu.getCarried().getCount()==held,"Dragging still cannot deposit into a chest");
            a.containerMenu.removed(a);a.containerMenu=a.inventoryMenu;
            int beforeShift=bag.ledger.count();
            packetClick(a,bagSlot(a.containerMenu,a,itemSlot(bag,Items.DIRT)),0,ContainerInput.QUICK_MOVE);
            check(h,bag.items.get(0).is(Items.DIRT) && count(bag,Items.DIRT)==8 && bag.ledger.count()==beforeShift,"Shift-click moves between main inventory and hotbar without spending capacity");

            bag.reset();a.getInventory().add(new ItemStack(Items.DIRT,64));a.getInventory().add(new ItemStack(Items.IRON_SWORD));
            fillBudget(bag,36,Set.of(Items.NETHER_STAR));
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            ItemStack rejectedMining=new ItemStack(Items.DIRT);
            check(h,!b.getInventory().add(rejectedMining) && rejectedMining.getCount()==1 && bag.ledger.count()==36,"Full budget refuses a pickup that would require an extra stack after returning the cursor");
            packetDrag(a,0,bagSlot(a.containerMenu,a,35));
            packetClick(b,bagSlot(b.containerMenu,b,1),0,ContainerInput.PICKUP);
            packetClick(b,bagSlot(b.containerMenu,b,0),0,ContainerInput.PICKUP);
            check(h,bag.items.get(35).is(Items.DIRT) && bag.items.get(0).is(Items.IRON_SWORD) && bag.ledger.count()==36,"Dirt and sword can be repositioned even when every permanent allocation has been spent");
            packetClick(a,bagSlot(a.containerMenu,a,35),1,ContainerInput.PICKUP);
            packetDrag(a,0,bagSlot(a.containerMenu,a,20));
            check(h,a.containerMenu.getCarried().getCount()==32 && bag.items.get(35).getCount()==32 && SharedBag.ghost(bag.items.get(20)) && bag.ledger.count()==36,"Full budget atomically refuses an extra split stack without losing the cursor");
            packetClick(a,bagSlot(a.containerMenu,a,35),0,ContainerInput.PICKUP);
            for(int n=0;n<36;n++) if(!SharedBag.real(bag.items.get(n)))
                bag.items.set(n,new ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(bag.ledger.at(n)))));
            bag.normalize();
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            packetClick(a,bagSlot(a.containerMenu,a,35),0,ContainerInput.PICKUP);
            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            check(h,bag.items.get(35).is(Items.IRON_SWORD) && bag.items.get(0).is(Items.DIRT) && count(bag,Items.DIRT)==64 && bag.ledger.count()==36 && bag.returns.isEmpty(),"Swapping two occupied cells in a completely full bag neither duplicates nor allocates");

            packetClick(a,bagSlot(a.containerMenu,a,0),0,ContainerInput.PICKUP);
            packetClick(a,-999,AbstractContainerMenu.getQuickcraftMask(0,0),ContainerInput.QUICK_CRAFT);
            packetClick(a,bagSlot(a.containerMenu,a,0),AbstractContainerMenu.getQuickcraftMask(1,0),ContainerInput.QUICK_CRAFT);
            packetClick(b,bagSlot(b.containerMenu,b,35),0,ContainerInput.SWAP);
            packetClick(a,-999,AbstractContainerMenu.getQuickcraftMask(2,0),ContainerInput.QUICK_CRAFT);
            check(h,bag.items.get(0).is(Items.IRON_SWORD) && a.containerMenu.getCarried().getCount()==64 && bag.ledger.count()==36,"A second player's hotbar swap cannot be overwritten by a stale drag gesture");
            packetClick(a,bagSlot(a.containerMenu,a,35),0,ContainerInput.PICKUP);
            var ops=RegistryOps.create(NbtOps.INSTANCE,h.getLevel().registryAccess());
            SharedBag restored=SharedBag.CODEC.parse(ops,SharedBag.CODEC.encodeStart(ops,bag).getOrThrow()).getOrThrow();restored.normalize();
            check(h,restored.ledger.count()==36 && restored.ledger.reserved("minecraft:dirt")==1 && ItemStack.listMatches(restored.items,bag.items),"The existing save format preserves movable allocations and quantities");
            bag.reset();check(h,bag.stop(a),"Clean movement fixture");
            System.out.println("PERMAPACK_MOVEMENT_PASS: real click packets, free positions, cursor swaps, split/merge budgets, left/right drag, storage guard, shift move, full bag and concurrent gestures");
            h.succeed();
        } finally {
            if(bag.active){bag.reset();bag.stop(a);}
            for(ServerPlayer p:List.of(a,b,c)){if(bag.member(p))bag.disconnect(p);server.getPlayerList().remove(p);}
        }
    }
}
