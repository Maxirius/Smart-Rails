package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the block each CARGO station sign hangs on: a RISING redstone
 * signal there dispatches the platform's chest cart (automation hook for
 * hoppers, timers and railway signalling).
 *
 * False-trigger shield: the selection buttons usually sit on the same
 * backing block as the sign, and their press pulse would read as a dispatch
 * signal - so every button interaction at a station arms a short immunity
 * window that comfortably outlasts the longest wooden-button pulse.
 */
final class RedstoneWatch {

    private RedstoneWatch() {
    }

    /** Master gate (config "cargo.redstoneTrigger", default on). */
    private static volatile boolean enabled = true;

    /** station name -> last game time a selection button was pressed */
    private static final Map<String, Long> LAST_SELECT = new ConcurrentHashMap<>();

    /** station name -> last observed power at the sign's attached block */
    private static final Map<String, Integer> LAST_POWER = new ConcurrentHashMap<>();

    /** Immunity window after a button press, in ticks (>= wooden pulse 30). */
    private static final long DEBOUNCE_TICKS = 45;

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Record a selection interaction at {@code st} (suppresses dispatch). */
    static void noteSelection(Station st, long gameTime) {
        LAST_SELECT.put(st.name.toLowerCase(Locale.ROOT), gameTime);
    }

    /** Per-tick sweep over every registered freight station. */
    static void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }
        for (var lvl : server.getAllLevels()) {
            String dim = NetworkState.dimId(lvl);
            for (Station st : List.copyOf(NetworkState.INSTANCE.stationsInKind(dim, true))) {
                BlockPos attached = attachedBlockOf(lvl, st.signPos());
                int power = lvl.getBestNeighborSignal(attached);
                String key = st.name.toLowerCase(Locale.ROOT);
                Integer prev = LAST_POWER.put(key, power);
                if (prev == null || prev != 0 || power <= 0) {
                    continue;   // first sighting arms the baseline; only rising edges dispatch
                }
                long now = lvl.getGameTime();
                Long sel = LAST_SELECT.get(key);
                if (sel != null && now - sel < DEBOUNCE_TICKS) {
                    continue;   // the pulse came from a selection button
                }
                TrainController.redstoneDispatch(lvl, st);
            }
        }
    }

    /**
     * The block a sign is mounted on: behind a wall sign, below a standing
     * sign, above a ceiling-hung sign. Fallback: below.
     */
    static BlockPos attachedBlockOf(net.minecraft.server.level.ServerLevel level,
                                    BlockPos signPos) {
        var state = level.getBlockState(signPos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.WallSignBlock) {
            var facing = state.getValue(net.minecraft.world.level.block.WallSignBlock.FACING);
            return signPos.relative(facing.getOpposite());
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.HangingSignBlock) {
            return signPos.above();
        }
        return signPos.below();
    }
}
