package io.github.maxirius.smartrails;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Turns clicks on station signs and their flanking buttons into UI actions:
 *
 * - press LEFT button  -> previous destination
 * - press RIGHT button -> next destination
 * - right-click the sign -> CONFIRM the pending destination (starts the train)
 * - sneak + right-click the sign -> vanilla behaviour (edit the text)
 */
final class InteractionHook {

    private InteractionHook() {
    }

    static void register() {
        UseBlockCallback.EVENT.register(InteractionHook::onUseBlock);
        AttackBlockCallback.EVENT.register(InteractionHook::onAttackBlock);
    }

    /**
     * Registered station signs are protected: breaking one would orphan the
     * station (name reserved, sign blank). Deny the swing until the station
     * is deregistered.
     */
    private static InteractionResult onAttackBlock(Player player, Level level,
                                                   InteractionHand hand, BlockPos pos,
                                                   net.minecraft.core.Direction direction) {
        if (level.isClientSide() || !(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        var state = server.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock)) {
            return InteractionResult.PASS;
        }
        Station st = NetworkState.INSTANCE.stationBySign(NetworkState.dimId(server), pos);
        if (st == null) {
            return InteractionResult.PASS;
        }
        if (!player.isShiftKeyDown()) {
            warn(player, Messages.t("sign.protected", st.name));
            return InteractionResult.FAIL;
        }
        // Sneak-breaking is the sanctioned removal gesture: delete the
        // station record first, then let vanilla break the sign.
        NetworkState.INSTANCE.removeStation(st.name);
        NetworkState.INSTANCE.invalidateGraphs(NetworkState.dimId(server));
        NetworkState.INSTANCE.saveIfDirty(server.getServer());
        warn(player, Messages.t("cmd.station.removed", st.name));
        return InteractionResult.PASS;
    }

    private static InteractionResult onUseBlock(Player player, Level level,
                                                InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        var state = server.getBlockState(pos);

        // ---- Junction stick (marked vanilla stick) ----------------------------
        if (SrItems.isMarked(player.getItemInHand(hand))) {
            return JunctionStick.use(player, server, hand, hit);
        }

        // ---- Flanking buttons -------------------------------------------------
        if (state.getBlock() instanceof ButtonBlock) {
            Station st = owningStation(server, pos);
            if (st == null) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown()) {
                // Vanilla press slips through for circuit tinkerers - but its
                // pulse must NOT read as a freight dispatch signal.
                RedstoneWatch.noteSelection(st, server.getGameTime());
                return InteractionResult.PASS;
            }
            var beOpt = server.getBlockEntity(st.signPos());
            if (!(beOpt instanceof SignBlockEntity signBe)) {
                return InteractionResult.PASS;
            }
            var facing = StationUi.facingOf(signBe);
            BlockPos left = StationUi.leftCell(st.signPos(), facing);
            BlockPos right = StationUi.rightCell(st.signPos(), facing);
            int dir = pos.equals(left) ? -1 : pos.equals(right) ? +1 : 0;
            if (dir != 0) {
                RedstoneWatch.noteSelection(st, server.getGameTime());
                cycleDestination(server, st, player, dir);
                return InteractionResult.FAIL; // swallow the redstone pulse
            }
            return InteractionResult.PASS;
        }

        // ---- The station sign itself ------------------------------------------
        if (server.getBlockEntity(pos) instanceof SignBlockEntity) {
            Station st = NetworkState.INSTANCE.stationBySign(NetworkState.dimId(server), pos);
            if (st == null) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown()) {
                // Sneak + right-click = cycle to the NEXT destination - the
                // button-free way to browse stations (the sign text is owned
                // by the station record and gets repainted on every use, so
                // the vanilla editor stays pointless).
                cycleDestination(server, st, player, +1);
                return InteractionResult.FAIL;
            }
            confirmDestination(server, st, player);
            return InteractionResult.FAIL; // suppress the sign edit screen
        }

        return InteractionResult.PASS;
    }

    /** Station whose sign or flanking button was clicked. */
    private static Station owningStation(ServerLevel server, BlockPos clicked) {
        NetworkState ns = NetworkState.INSTANCE;
        // Iterate over COPIES: the graph rebuild triggered by the click can
        // mutate the backing collections (ConcurrentModificationException
        // killed every button press after a station re-registration).
        for (var st : java.util.List.copyOf(ns.stationsIn(NetworkState.dimId(server)))) {
            if (clicked.equals(st.signPos())) {
                return st;
            }
        }
        // Buttons sit directly next to the sign block.
        for (var st : java.util.List.copyOf(ns.stationsIn(NetworkState.dimId(server)))) {
            for (var nb : new BlockPos[]{
                    clicked.north(), clicked.south(), clicked.west(), clicked.east(),
                    clicked.above(), clicked.below()}) {
                if (nb.equals(st.signPos())) {
                    return st;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    static void cycleDestination(ServerLevel server, Station st, Player player, int dir) {
        NetworkState ns = NetworkState.INSTANCE;
        // Goal isolation: person stations offer person goals, freight offers
        // freight - the shared track graph is fine, the timetables are not.
        var reachableFiltered = new java.util.ArrayList<String>();
        for (String nm : Topology.reachable(ns.graph(server), st.name)) {
            Station goal = ns.stationByName(nm);
            if (goal != null && goal.cargo == st.cargo) {
                reachableFiltered.add(nm);
            }
        }
        if (reachableFiltered.isEmpty()) {
            warn(player, Messages.t("cycle.unreachable", st.name));
            return;
        }
        var names = reachableFiltered;
        String shown = ns.pendingOf(st) != null ? ns.pendingOf(st) : st.selected;
        int idx = shown == null ? -1 : names.indexOf(shown);
        int n = names.size();
        int next = idx < 0 ? (dir > 0 ? 0 : n - 1) : Math.floorMod(idx + dir, n);
        ns.setPending(st, names.get(next));
        StationUi.paint(server, st);
        StationUi.chime(server, st.signPos(), SoundEvents.EXPERIENCE_ORB_PICKUP);
        // No chat spam: the selection is visible on the sign itself.
    }

    static void confirmDestination(ServerLevel server, Station st, Player player) {
        NetworkState ns = NetworkState.INSTANCE;
        String pending = ns.pendingOf(st);
        // No pending selection? A green sign (previous destination still set)
        // re-confirms as-is, so a returning player can depart with a single
        // right-click.
        String target = pending != null ? pending : st.selected;
        if (target == null) {
            info(player, Messages.t("confirm.no-selection"));
            return;
        }
        if (pending != null) {
            ns.setSelected(st, pending);
            ns.setPending(st, null);
        }
        StationUi.paint(server, st);
        StationUi.pling(server, st.signPos());
        info(player, Messages.t("confirm.ok", target));
        TrainController.onDestinationSet(server, st, player);
    }

    private static void info(Player p, String msg) {
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76" + Messages.t("prefix") + "\u00a7r " + msg));
    }

    private static void warn(Player p, String msg) {
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76" + Messages.t("prefix") + "\u00a7r \u00a7c" + msg));
    }
}
