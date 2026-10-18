package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;

/**
 * A registered railway station. Anchored by a (vanilla) sign that was claimed
 * with {@code /srails station add <name>} and the rail cell the mod considers
 * the platform track ({@code anchor}).
 *
 * Persistence-friendly primitives only; serialized with Gson.
 */
public final class Station {
    public String name;
    /** Dimension id, e.g. "minecraft:overworld". */
    public String dim;
    /** Position of the sign block (information board). */
    public int sx, sy, sz;
    /** Rail cell serving as platform / departure point. */
    public int ax, ay, az;
    /** Confirmed destination station name, or null. */
    public String selected;
    /** Freight station: dispatches unmanned chest-carts, freight goals only. */
    public boolean cargo;

    public Station() {
    }

    public Station(String name, String dim, BlockPos sign, BlockPos anchor) {
        this(name, dim, sign, anchor, false);
    }

    public Station(String name, String dim, BlockPos sign, BlockPos anchor, boolean cargo) {
        this.name = name;
        this.dim = dim;
        this.cargo = cargo;
        this.sx = sign.getX();
        this.sy = sign.getY();
        this.sz = sign.getZ();
        this.ax = anchor.getX();
        this.ay = anchor.getY();
        this.az = anchor.getZ();
    }

    public BlockPos signPos() {
        return new BlockPos(sx, sy, sz);
    }

    public BlockPos anchor() {
        return new BlockPos(ax, ay, az);
    }
}
