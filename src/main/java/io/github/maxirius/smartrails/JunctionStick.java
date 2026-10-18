package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.Map;

/**
 * The junction stick: right-click a rail (or the block a rail rests on) to
 * declare a junction; click an existing junction to CYCLE its preferred
 * exit (the router favours that exit when routes are comparable); sneak +
 * right-click removes the declaration.
 */
final class JunctionStick {

    private JunctionStick() {
    }

    private static final Map<Direction, String> DE_DIR = Map.of(
            Direction.NORTH, "Norden", Direction.SOUTH, "S\u00fcden",
            Direction.WEST, "Westen", Direction.EAST, "Osten");
    private static final Map<Direction, String> EN_DIR = Map.of(
            Direction.NORTH, "north", Direction.SOUTH, "south",
            Direction.WEST, "west", Direction.EAST, "east");

    static InteractionResult use(Player player, ServerLevel server,
                                 InteractionHand hand, BlockHitResult hit) {
        BlockPos target = resolveRail(server, hit.getBlockPos());
        if (target == null) {
            warn(player, Messages.t("stick.not-rail"));
            return InteractionResult.SUCCESS;
        }
        NetworkState ns = NetworkState.INSTANCE;
        String dim = NetworkState.dimId(server);
        JunctionMarker jm = ns.junctionAt(dim, target);

        // ---- sneak: remove ------------------------------------------------
        if (player.isShiftKeyDown()) {
            if (jm == null) {
                warn(player, Messages.t("cmd.junction.none-here"));
                return InteractionResult.SUCCESS;
            }
            ns.removeJunction(dim, target);
            sparkle(server, target, ParticleTypes.SMOKE);
            chime(server, target, SoundEvents.EXPERIENCE_ORB_PICKUP);
            info(player, Messages.t("cmd.junction.removed"));
            return InteractionResult.SUCCESS;
        }

        // ---- plain rail: declare ------------------------------------------
        if (jm == null) {
            ns.addJunction(new JunctionMarker(dim, target));
            sparkle(server, target, ParticleTypes.ELECTRIC_SPARK);
            info(player, Messages.t("cmd.junction.added", fmt(target)));
            return InteractionResult.SUCCESS;
        }

        // ---- existing junction: cycle preferred exit -----------------------
        List<Topology.Arm> arms = Topology.armsFor(server, target);
        if (arms.isEmpty()) {
            warn(player, Messages.t("stick.no-arms"));
            return InteractionResult.SUCCESS;
        }
        Direction cur = jm.prefDir();
        int idx = -1;
        for (int i = 0; i < arms.size(); i++) {
            if (arms.get(i).dir() == cur) {
                idx = i;
                break;
            }
        }
        Direction next = arms.get(Math.floorMod(idx + 1, arms.size())).dir();
        jm.setPref(next);
        ns.markDirty();
        ns.invalidateGraphs(dim);   // preference participates in routing costs
        sparkle(server, target, ParticleTypes.NOTE);
        info(player, Messages.t("stick.preferred", dirLabel(next)) + "\n" + armOverview(arms, next));

        return InteractionResult.SUCCESS;
    }

    /**
     * Rails have no collision box, so the ray usually hits the block below or
     * beside them. Accept the clicked cell, the cell above it, and the four
     * horizontal neighbours.
     */
    private static BlockPos resolveRail(ServerLevel level, BlockPos clicked) {
        for (BlockPos cand : new BlockPos[]{
                clicked, clicked.above(),
                clicked.north(), clicked.south(), clicked.east(), clicked.west()}) {
            BlockState st = level.getBlockState(cand);
            if (st.getBlock() instanceof BaseRailBlock) {
                return cand.immutable();
            }
        }
        return null;
    }

    private static String armOverview(List<Topology.Arm> arms, Direction preferred) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arms.size(); i++) {
            Direction d = arms.get(i).dir();
            sb.append(d == preferred ? "\u00a7a\u25b6 " : "\u00a77\u0020 ")
                    .append("\u00a7f").append(dirLabel(d));
            if (i < arms.size() - 1) {
                sb.append("\u00a7r   ");
            }
        }
        return sb.toString();
    }

    private static String dirLabel(Direction d) {
        return "en".equalsIgnoreCase(Messages.lang)
                ? EN_DIR.getOrDefault(d, d.getName())
                : DE_DIR.getOrDefault(d, d.getName());
    }

    private static String fmt(BlockPos p) {
        return p.getX() + "|" + p.getY() + "|" + p.getZ();
    }

    private static void sparkle(ServerLevel server, BlockPos pos,
                                net.minecraft.core.particles.SimpleParticleType type) {
        server.sendParticles(type, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                12, 0.3, 0.3, 0.3, 0.01);
    }

    private static void chime(ServerLevel server, BlockPos pos,
                              net.minecraft.sounds.SoundEvent evt) {
        if (evt != null) {
            server.playSound(null, pos, evt, SoundSource.PLAYERS, 0.7f, 1.2f);
        }
    }

    private static void info(Player p, String msg) {
        p.sendSystemMessage(Component.literal("\u00a76" + Messages.t("prefix")
                + "\u00a7r " + msg));
    }

    private static void warn(Player p, String msg) {
        p.sendSystemMessage(Component.literal("\u00a76" + Messages.t("prefix")
                + "\u00a7r \u00a7c" + msg));
    }
}
