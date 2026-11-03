package dev.leo.permapack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import java.util.*;

public final class PermaPack implements ModInitializer {
    private static final Map<MinecraftServer, SharedBag> STATES = new WeakHashMap<>();
    public static SharedBag get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, s -> s.getDataStorage().computeIfAbsent(SharedBag.TYPE));
    }
    public static SharedBag forPlayer(Player player) {
        if (!(player instanceof ServerPlayer p)) return null;
        SharedBag state = get(p.level().getServer());
        return state.member(player) && !state.internal ? state : null;
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(STATES::remove);
        ServerTickEvents.END_SERVER_TICK.register(s -> get(s).tick(s));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SharedBag bag = get(server); ServerPlayer p = handler.player;
            bag.retireMirror(p);
            if (bag.active && bag.members.contains(p.getUUID())) bag.join(p);
            else if (bag.active && bag.members.size() < 3 && p.getInventory().isEmpty() && p.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL) bag.join(p);
            if (bag.member(p)) p.sendSystemMessage(Component.literal("PermaPack joined: one shared bag, permanent slots. Use /permapack status."));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SharedBag bag = get(server); if (bag.member(handler.player)) bag.disconnect(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            SharedBag bag = get(newPlayer.level().getServer());
            if (!bag.member(newPlayer)) return;
            if (bag.keepOnDeath || alive) for (int n = 36; n < oldPlayer.getInventory().getContainerSize(); n++)
                newPlayer.getInventory().setItem(n, oldPlayer.getInventory().getItem(n).copy());
            bag.join(newPlayer);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> ConversionGuard.use(player, player.getItemInHand(hand), null, null));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> ConversionGuard.use(player, player.getItemInHand(hand), hit.getBlockPos(), null));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> ConversionGuard.use(player, player.getItemInHand(hand), null, entity));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("permapack")
                .then(Commands.literal("status").executes(ctx -> {
                    SharedBag bag = get(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("PermaPack: " + (bag.active ? "ACTIVE" : "OFF") +
                        ", slots " + bag.ledger.count() + "/36, players " + bag.members.size() + "/3, death=" +
                        (bag.keepOnDeath ? "keep" : "lose") + ", protected returns=" + bag.returns.size()), false); return 1;
                }))
                .then(Commands.literal("slots").executes(ctx -> {
                    SharedBag bag = get(ctx.getSource().getServer());
                    for (int n = 0; n < 36; n++) if (!bag.ledger.at(n).isEmpty()) {
                        final int slot = n; ctx.getSource().sendSuccess(() -> Component.literal((slot + 1) + ": " + bag.ledger.at(slot)), false);
                    }
                    return bag.ledger.count();
                }))
                .then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    var bag = get(ctx.getSource().getServer());
                    boolean success = bag.start(ctx.getSource().getServer().getPlayerList().getPlayers());
                    if (!success) { ctx.getSource().sendFailure(Component.literal("Start needs 1-3 online players with empty inventories, armor and crafting grids, and no active run.")); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.literal("PermaPack started. Friends joining with empty inventories will share this bag (maximum 3)."), true); return 1;
                }))
                .then(Commands.literal("join").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("players", EntityArgument.players()).executes(ctx -> {
                        var bag = get(ctx.getSource().getServer()); var players = EntityArgument.getPlayers(ctx, "players");
                        if (!bag.active) { ctx.getSource().sendFailure(Component.literal("Start PermaPack first.")); return 0; }
                        if (!bag.joinTeam(players)) {
                            ctx.getSource().sendFailure(Component.literal("Joining players need empty inventories and a free team place.")); return 0;
                        }
                        return players.size();
                    })))
                .then(Commands.literal("death").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("keep").executes(ctx -> { var b = get(ctx.getSource().getServer()); b.keepOnDeath = true; b.setDirty(); ctx.getSource().sendSuccess(() -> Component.literal("Team keeps items and reservations on death."), true); return 1; }))
                    .then(Commands.literal("lose").executes(ctx -> { var b = get(ctx.getSource().getServer()); b.keepOnDeath = false; b.setDirty(); ctx.getSource().sendSuccess(() -> Component.literal("A death empties the team's items. Reservations remain."), true); return 1; })))
                .then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> { ctx.getSource().sendFailure(Component.literal("/permapack reset confirm deletes the team's items, equipment and reservations. Use it only to restart the challenge.")); return 0; })
                    .then(Commands.literal("confirm").executes(ctx -> { get(ctx.getSource().getServer()).reset(); ctx.getSource().sendSuccess(() -> Component.literal("PermaPack items and reservations reset."), true); return 1; })))
                .then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    var bag = get(ctx.getSource().getServer());
                    if (!bag.active || !bag.stop(ctx.getSource().getPlayerOrException())) { ctx.getSource().sendFailure(Component.literal("Unable to stop: ensure the host is in the team or has an empty bag, and use waiting items to make room.")); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.literal("PermaPack stopped. One copy of the shared items was returned to the host."), true); return 1;
                }));
            var command = dispatcher.register(root);
            dispatcher.register(Commands.literal("pp").redirect(command));
        });
    }

}
