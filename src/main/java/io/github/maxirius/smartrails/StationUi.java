package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.ChatFormatting;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/**
 * Painting and interpreting station signs.
 *
 * Layout (front side):
 *   line 1: station name (bold white) - doubles as the destination marker
 *   line 2: (blank)
 *   line 3: "Ziel:"
 *   line 4: selected destination - grey "-" (none), yellow (picked, awaiting
 *           confirmation), green (confirmed)
 */
final class StationUi {

    private StationUi() {
    }

    /** Which cardinal direction the sign's front text faces. */
    static Direction facingOf(SignBlockEntity be) {
        BlockState state = be.getBlockState();
        if (state.getBlock() instanceof WallSignBlock) {
            return state.getValue(WallSignBlock.FACING);
        }
        if (state.getBlock() instanceof StandingSignBlock) {
            IntegerProperty rotProp = StandingSignBlock.ROTATION;
            int rot = state.getValue(rotProp);
            // Vanilla rotation: 0 = south, 4 = west, 8 = north, 12 = east (steps of 45deg).
            Direction[] cardinals = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
            return cardinals[Math.floorDiv(Math.round(rot / 4.0f), 4)];
        }
        return Direction.SOUTH;
    }

    /** Cell to the viewer's LEFT of the sign front. */
    static BlockPos leftCell(BlockPos signPos, Direction facing) {
        return signPos.relative(facing.getClockWise());
    }

    /** Cell to the viewer's RIGHT of the sign front. */
    static BlockPos rightCell(BlockPos signPos, Direction facing) {
        return signPos.relative(facing.getCounterClockWise());
    }

    /** True when both flanking cells hold a button block. */
    static boolean buttonsPlaced(ServerLevel level, BlockPos signPos, Direction facing) {
        return isButton(level, leftCell(signPos, facing)) && isButton(level, rightCell(signPos, facing));
    }

    static boolean isButton(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof ButtonBlock;
    }

    /**
     * Nearest rail cell to the sign (platform track). Candidates are scored:
     * well-connected track (more adjoining rails) beats loose stubs, distance
     * breaks ties - prevents anchoring a station on a dead side rail.
     */
    static BlockPos findAnchorRail(ServerLevel level, BlockPos signPos) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int dys = -2; dys <= 1; dys++) {
            BlockPos base = signPos.above(dys);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos p = base.offset(dx, 0, dz);
                    if (!RailShapes.isRail(level, p)) {
                        continue;
                    }
                    int dist = Math.abs(dx) + Math.abs(dz) + Math.abs(dys) * 2;
                    int degree = RailShapes.connections(level, p).size();
                    int score = degree * 100 - dist;
                    if (score > bestScore) {
                        bestScore = score;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Rewrites the sign's front text to reflect current selection state.
     */
    static void paint(ServerLevel level, Station st) {
        if (!(level.getBlockEntity(st.signPos()) instanceof SignBlockEntity be)) {
            return;
        }
        String pending = NetworkState.INSTANCE.pendingOf(st);
        String selected = st.selected;

        Component target;
        if (pending != null) {
            target = Component.literal(pending).withStyle(ChatFormatting.YELLOW);
        } else if (selected != null) {
            target = Component.literal(selected).withStyle(ChatFormatting.GREEN);
        } else {
            target = Component.literal(Messages.t("sign.none")).withStyle(ChatFormatting.GRAY);
        }

        be.updateText(text -> {
            var mut = text.asMutable();
            mut.setLine(0, Component.literal(st.cargo ? "\uD83D\uDED2 " + st.name : st.name)
                    .withStyle(st.cargo ? ChatFormatting.GOLD : ChatFormatting.WHITE,
                            ChatFormatting.BOLD));
            mut.setLine(1, Component.literal(st.cargo
                    ? Messages.t("sign.freight.tag") : Component.empty().getString()));
            mut.setLine(2, Component.literal(Messages.t("sign.target.label")).withStyle(ChatFormatting.GRAY));
            mut.setLine(3, target);
            return mut.asImmutable();
        }, SignTextSlot.FRONT);
        be.setChanged();
        level.getChunkSource().blockChanged(st.signPos());
    }

    static void clearSign(ServerLevel level, BlockPos signPos) {
        if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity be)) {
            return;
        }
        be.updateText(text -> {
            var mut = text.asMutable();
            for (int i = 0; i < 4; i++) {
                mut.setLine(i, Component.empty());
            }
            return mut.asImmutable();
        }, SignTextSlot.FRONT);
        be.setChanged();
        level.getChunkSource().blockChanged(signPos);
    }

    static void chime(ServerLevel level, BlockPos pos, SoundEvent sound) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.7f, 1.2f);
    }

    static void pling(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.8f, 1.5f);
    }
}
