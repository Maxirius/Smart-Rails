package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry helpers for vanilla rails, built on a CONTACT-HEIGHT model.
 *
 * Two rail cells are connected iff the rail-plane height where cell A faces
 * direction D equals the height where cell B (the neighbour in direction D)
 * faces back. Heights: flat/corner cells contact at their own Y; ascending
 * cells contact at Y+1 towards their high side and at Y towards their low
 * side. This reproduces vanilla slopes exactly (including descents from a
 * plateau onto a ramp one level below, which a naive per-shape exit table
 * gets wrong).
 *
 * Crucially, connections() only asks the NEIGHBOUR whether it is willing to
 * connect back - the current cell's own shape is deliberately NOT required to
 * face the neighbour. That is what makes switches work: a junction block
 * whose curve currently points at branch B still recognizes the branch-A
 * feeder rail next to it, and the train controller steers carts onto that
 * arm by force (velocity override), just like TrainCarts does.
 */
final class RailShapes {

    private static final int NO_CONTACT = Integer.MIN_VALUE;

    private RailShapes() {
    }

    static boolean isRail(Level level, BlockPos pos) {
        return BaseRailBlock.isRail(level, pos);
    }

    /** @return the rail shape at {@code pos}, or {@code null} when no rail is there. */
    static RailShape shapeAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BaseRailBlock rail)) {
            return null;
        }
        return state.getValue(rail.getShapeProperty());
    }

    /**
     * Absolute Y of the rail plane where {@code pos}'s shape faces {@code d},
     * or {@link #NO_CONTACT} when the shape has no opening towards {@code d}.
     */
    private static int contactHeight(BlockPos pos, RailShape shape, Direction d) {
        int y = pos.getY();
        return switch (shape) {
            case NORTH_SOUTH -> (d == Direction.NORTH || d == Direction.SOUTH) ? y : NO_CONTACT;
            case EAST_WEST -> (d == Direction.EAST || d == Direction.WEST) ? y : NO_CONTACT;
            case ASCENDING_NORTH -> d == Direction.NORTH ? y + 1
                    : d == Direction.SOUTH ? y : NO_CONTACT;
            case ASCENDING_SOUTH -> d == Direction.SOUTH ? y + 1
                    : d == Direction.NORTH ? y : NO_CONTACT;
            case ASCENDING_EAST -> d == Direction.EAST ? y + 1
                    : d == Direction.WEST ? y : NO_CONTACT;
            case ASCENDING_WEST -> d == Direction.WEST ? y + 1
                    : d == Direction.EAST ? y : NO_CONTACT;
            case SOUTH_EAST -> (d == Direction.SOUTH || d == Direction.EAST) ? y : NO_CONTACT;
            case SOUTH_WEST -> (d == Direction.SOUTH || d == Direction.WEST) ? y : NO_CONTACT;
            case NORTH_WEST -> (d == Direction.NORTH || d == Direction.WEST) ? y : NO_CONTACT;
            case NORTH_EAST -> (d == Direction.NORTH || d == Direction.EAST) ? y : NO_CONTACT;
        };
    }

    /** Target cell when leaving {@code pos} towards {@code dir} with vertical delta {@code dy}. */
    static BlockPos step(BlockPos pos, Direction dir, int dy) {
        return pos.relative(dir).above(dy);
    }

    /** A connection: neighbour cell + the horizontal direction from {@code origin} towards it. */
    record Link(BlockPos pos, Direction dir, int dy) {
    }

    /**
     * All rail cells BILATERALLY connected from {@code origin}: the origin's
     * own shape must open towards the neighbour AND the neighbour's shape must
     * open back, with EQUAL absolute contact heights - EXCEPT the vanilla
     * forgiveness at ramp shoulders: an AXIS-ALIGNED ascender neighbour whose
     * shoulder towards us differs by exactly one block still connects (vanilla
     * carts snap onto the slope plane when entering, so ramp-top crowns and
     * feet link to flat cells one block off). This mirrors vanilla cart
     * behaviour; without it, reshaped ramp crowns strand the topology walk.
     */
    static List<Link> connections(Level level, BlockPos origin) {
        List<Link> out = new ArrayList<>(4);
        RailShape os = shapeAt(level, origin);
        if (os == null) {
            return out;
        }
        // Vanilla CROSSING cells open along BOTH axes at their own Y - the
        // blockstate shows only one axis (26.3 keeps the shape property
        // locked), the perpendicular connections live in vanilla's runtime
        // state. Model a detected crossing as a fully-open hub; neighbour
        // reciprocity and the ramp-shoulder forgiveness stay in force.
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && Topology.isCrossingCell(sl, origin)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dyProbe = -1; dyProbe <= 1; dyProbe++) {
                    BlockPos p = origin.relative(d).above(dyProbe);
                    RailShape ps = shapeAt(level, p);
                    if (ps == null) {
                        continue;
                    }
                    int contactThere = contactHeight(p, ps, d.getOpposite());
                    if (contactThere == NO_CONTACT) {
                        continue;
                    }
                    boolean equal = contactThere == origin.getY();
                    boolean shoulderForgiveness = !equal && isAscending(ps)
                            && Math.abs(contactThere - origin.getY()) == 1
                            && ascenderAxisMatches(ps, d);
                    if (equal || shoulderForgiveness) {
                        out.add(new Link(p.immutable(), d, p.getY() - origin.getY()));
                        break; // one cell per direction
                    }
                }
            }
            return out;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int contactHere = contactHeight(origin, os, d);
            if (contactHere == NO_CONTACT) {
                continue;
            }
            for (int dyProbe = -1; dyProbe <= 1; dyProbe++) {
                BlockPos p = origin.relative(d).above(dyProbe);
                RailShape ps = shapeAt(level, p);
                if (ps == null) {
                    continue;
                }
                int contactThere = contactHeight(p, ps, d.getOpposite());
                if (contactThere == NO_CONTACT) {
                    continue;
                }
                boolean equal = contactThere == contactHere;
                boolean shoulderForgiveness = !equal && isAscending(ps)
                        && Math.abs(contactThere - contactHere) == 1
                        && ascenderAxisMatches(ps, d);
                if (equal || shoulderForgiveness) {
                    out.add(new Link(p.immutable(), d, p.getY() - origin.getY()));
                    break; // one cell per direction
                }
            }
        }
        return out;
    }

    private static boolean isAscending(RailShape shape) {
        return shape.name().startsWith("ASCENDING");
    }

    /**
     * Continuous rail-plane height inside {@code pos}'s cell for travel in
     * direction {@code d}: linear interpolation between the entry and exit
     * shoulder heights, {@code prog} = 0 (entry edge) .. 1 (exit edge).
     * Flat cells and curves have equal shoulders (the cell's own Y); ramps
     * interpolate across the full block. This is the plane vanilla carts
     * actually ride - seating a cart anywhere else sinks it under the rail.
     */
    static double planeHeight(BlockPos pos, RailShape shape, Direction d, double prog) {
        double hOut = contactHeight(pos, shape, d);
        double hIn = contactHeight(pos, shape, d.getOpposite());
        if (hOut == NO_CONTACT) {
            hOut = hIn == NO_CONTACT ? pos.getY() : hIn;
        }
        if (hIn == NO_CONTACT) {
            hIn = hOut;
        }
        return hIn + (hOut - hIn) * Math.clamp(prog, 0, 1);
    }

    /** Does the ascender's slope run along the travel axis {@code d}? */
    private static boolean ascenderAxisMatches(RailShape shape, Direction d) {
        return switch (shape) {
            case ASCENDING_EAST, ASCENDING_WEST -> d.getAxis() == Direction.Axis.X;
            case ASCENDING_NORTH, ASCENDING_SOUTH -> d.getAxis() == Direction.Axis.Z;
            default -> false;
        };
    }
}
