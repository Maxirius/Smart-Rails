package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Keeps the persistent registry honest: manual junction markers and station
 * anchors whose underlying rail has been mined away are swept out on a slow
 * interval. Ghost markers would otherwise keep steering carts at cells where
 * no track exists any more.
 *
 * Unloaded chunks are never judged - the rail may exist outside the loaded
 * window, so a sweep only fires where the block state is actually readable.
 */
final class MaintSweep {

    private MaintSweep() {
    }

    static void tick(MinecraftServer server) {
        for (ServerLevel lvl : server.getAllLevels()) {
            String dim = NetworkState.dimId(lvl);
            boolean dirty = false;

            for (JunctionMarker jm : List.copyOf(NetworkState.INSTANCE.allJunctions())) {
                if (!jm.dim.equals(dim)) {
                    continue;
                }
                BlockPos p = jm.pos();
                if (!lvl.isLoaded(p) || RailShapes.isRail(lvl, p)) {
                    continue;
                }
                NetworkState.INSTANCE.removeJunction(dim, p);
                dirty = true;
                SmartRails.LOGGER.info("[smartrails] Swept dead junction marker @{}",
                        p.toShortString());
            }

            for (Station st : List.copyOf(NetworkState.INSTANCE.stationsIn(dim))) {
                BlockPos anchor = st.anchor();
                if (!lvl.isLoaded(anchor) || RailShapes.isRail(lvl, anchor)) {
                    continue;
                }
                NetworkState.INSTANCE.removeStation(st.name);
                dirty = true;
                SmartRails.LOGGER.info("[smartrails] Swept dead station '{}' (anchor rail gone @{})",
                        st.name, anchor.toShortString());
            }

            if (dirty) {
                NetworkState.INSTANCE.invalidateGraphs(dim);
                NetworkState.INSTANCE.saveIfDirty(server);
            }
        }
    }
}
