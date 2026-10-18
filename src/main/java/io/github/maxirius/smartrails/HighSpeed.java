package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * High-speed regime configuration and track classification.
 *
 * Carts reach elevated speeds only by traversing powered rails: every powered
 * rail passed raises the governed speed by a fixed slice until the configured
 * maximum is reached. Special cells tame the ride:
 *
 * - an UNPOWERED booster drops the cart back to vanilla speed instantly,
 * - detector rails, activator rails and curves force vanilla pace while on
 *   them (shortly before them, thanks to the lookahead), restoring the
 *   charged speed afterwards,
 * - slopes are treated like hazards when {@link #brakeOnSlopes} is set -
 *   vanilla gravity otherwise piles extra momentum on descents,
 * - plain rails hold the charged speed for {@link #holdRails} cells, then
 *   bleed {@link #decayMsPerRail} metres per second per cell down to the
 *   vanilla floor.
 *
 * Block identities are resolved through the block REGISTRY PATH (rail /
 * powered_rail / detector_rail / activator_rail) instead of class identity -
 * resilient against mapping drift between Minecraft versions.
 */
final class HighSpeed {

    private HighSpeed() {
    }

    // Tuning (defaults; overridden by config/smartrails.json "highSpeed") ---
    static boolean enabled = true;
    static double maxSpeedMs = 34.0;        // top governed speed, metres/second
    static int poweredRailsToMax = 32;      // powered rails needed to climb from floor to max
    static int holdRails = 20;              // plain-rail cells keeping full speed
    static double decayMsPerRail = 1.0;     // speed bleed per plain-rail cell after the hold
    static boolean brakeOnSlopes = true;    // treat ascents/descents as hazards
    static double vanillaSpeedMs = 8.0;     // the floor everything relaxes to
    static int lookaheadCells = 6;          // cells scanned ahead for upcoming hazards

    static double maxBpt() {
        return maxSpeedMs / 20.0;
    }

    static double floorBpt() {
        return vanillaSpeedMs / 20.0;
    }

    /** Governed-speed increase granted per powered rail traversed. */
    static double gainPerBoost() {
        return (maxBpt() - floorBpt()) / Math.max(1, poweredRailsToMax);
    }

    /** Decay per plain-rail cell, in blocks per tick. */
    static double decayBpt() {
        return decayMsPerRail / 20.0;
    }

    // Classification ---------------------------------------------------------

    enum Kind {NONE, NORMAL, BOOST_ON, BOOST_OFF, DETECTOR, ACTIVATOR, CURVE, SLOPE}

    static Kind classify(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof BaseRailBlock)) {
            return Kind.NONE;
        }
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        switch (id) {
            case "powered_rail": {
                Boolean p = st.hasProperty(PoweredRailBlock.POWERED)
                        ? st.getValue(PoweredRailBlock.POWERED) : null;
                return (p != null && p) ? Kind.BOOST_ON : Kind.BOOST_OFF;
            }
            case "detector_rail":
                return Kind.DETECTOR;
            case "activator_rail":
                return Kind.ACTIVATOR;
        }
        RailShape sh = RailShapes.shapeAt(level, pos);
        if (sh == null) {
            return Kind.NONE;
        }
        if (isCurve(sh)) {
            return Kind.CURVE;
        }
        return sh.name().startsWith("ASCENDING") ? Kind.SLOPE : Kind.NORMAL;
    }

    static boolean isCurve(RailShape sh) {
        return switch (sh) {
            case SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST -> true;
            default -> false;
        };
    }

    /** Cells demanding vanilla pace while the cart is on/near them. */
    static boolean isHazard(Kind k) {
        return switch (k) {
            case DETECTOR, ACTIVATOR, CURVE -> true;
            default -> false;
        };
    }

    static boolean isHazardOrSlope(Kind k) {
        return isHazard(k) || (k == Kind.SLOPE && brakeOnSlopes);
    }

    /**
     * Walk ahead along the track from {@code start} (currently travelled in
     * direction {@code travel}) up to {@code maxDepth} cells.
     *
     * @return {{@code dist, kindOrdinal}} of the first hazard-or-slope cell
     *         (dist = cells ahead, 0 = standing on it), or {@code {-1, -1}}
     *         when the stretch ahead is clear.
     */
    static int[] hazardAhead(Level level, BlockPos start, Direction travel, int maxDepth) {
        BlockPos cur = start;
        Direction dir = travel;
        for (int d = 1; d <= maxDepth; d++) {
            RailShapes.Link next = null;
            double bestDot = -2;
            for (RailShapes.Link l : RailShapes.connections(level, cur)) {
                if (l.dir() == dir.getOpposite()) {
                    continue;
                }
                double dot = l.dir().getStepX() * dir.getStepX() + l.dir().getStepZ() * dir.getStepZ();
                if (dot > bestDot) {
                    bestDot = dot;
                    next = l;
                }
            }
            if (next == null) {
                return new int[]{-1, -1};
            }
            cur = next.pos();
            dir = next.dir();
            Kind k = classify(level, cur);
            if (isHazardOrSlope(k)) {
                return new int[]{d, k.ordinal()};
            }
        }
        return new int[]{-1, -1};
    }
}
