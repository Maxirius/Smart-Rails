package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Locale;

/**
 * A manually declared junction/turnout ({@code /srails junction add} or the
 * junction stick), looked at / clicked while declaring. Automatic detection
 * during topology scanning finds divergent cells on its own; manual
 * declarations pin down spots the scanner should treat as routable nodes
 * even when they currently have a single visible exit.
 *
 * Optionally nominates a preferred exit ({@code prefExit}); the router
 * rewards that exit when comparing comparable routes (soft tiebreaker,
 * cycled by further junction-stick clicks).
 */
public final class JunctionMarker {
    public String dim;
    public int x, y, z;

    /** Nullable preferred exit, stored as lowercase direction name ("east"). */
    public String prefExit;

    public JunctionMarker() {
    }

    public JunctionMarker(String dim, BlockPos pos) {
        this.dim = dim;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** Parses {@link #prefExit}; returns null when unset or unreadable. */
    public Direction prefDir() {
        if (prefExit == null) {
            return null;
        }
        try {
            return Direction.valueOf(prefExit.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Stores the preference (null clears it). */
    public void setPref(Direction d) {
        prefExit = d == null ? null : d.getName();
    }
}
