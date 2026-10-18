package io.github.maxirius.smartrails;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Central registry of stations and manual junction markers, persisted to
 * {@code <world>/smartrails/network.json}. Also owns the lazy per-dimension
 * topology graphs and the transient "pending" sign selections.
 */
public final class NetworkState {

    public static final NetworkState INSTANCE = new NetworkState();

    private static final Gson GSON = new Gson();

    /** Registered stations by name. Sorted views use newTreeMap(copy). */
    private final java.util.concurrent.ConcurrentNavigableMap<String, Station> stations =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    /** Manual junction markers, key = dim|x|y|z. */
    private final Map<String, JunctionMarker> junctions = new java.util.concurrent.ConcurrentHashMap<>();

    /** Transient: station name -> pending (not yet confirmed) destination. */
    private final Map<String, String> pending = new java.util.concurrent.ConcurrentHashMap<>();
    /** Lazily built topology graphs per dimension id. */
    private final Map<String, Topology.Graph> graphs = new java.util.concurrent.ConcurrentHashMap<>();

    private NetworkState() {
    }

    // ------------------------------------------------------------------
    // Keys & lookups
    // ------------------------------------------------------------------

    public static String key(String dim, BlockPos pos) {
        return dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    public static String dimId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public Station stationByName(String name) {
        return stations.get(name.toLowerCase());
    }

    /** Station whose sign sits at the given position, or null. */
    public Station stationBySign(String dim, BlockPos pos) {
        Station st = stationAtRaw(dim, pos);
        return st;
    }

    private Station stationAtRaw(String dim, BlockPos pos) {
        for (Station st : stations.values()) {
            if (st.dim.equals(dim) && st.sx == pos.getX() && st.sy == pos.getY() && st.sz == pos.getZ()) {
                return st;
            }
        }
        return null;
    }

    public List<Station> stationsIn(String dim) {
        List<Station> out = new ArrayList<>();
        for (Station st : stations.values()) {
            if (st.dim.equals(dim)) {
                out.add(st);
            }
        }
        return out;
    }

    /** Stations of one kind (person vs freight) in a dimension. */
    public List<Station> stationsInKind(String dim, boolean cargo) {
        List<Station> out = new ArrayList<>();
        for (Station st : stations.values()) {
            if (st.dim.equals(dim) && st.cargo == cargo) {
                out.add(st);
            }
        }
        return out;
    }

    public List<Station> allStations() {
        return new ArrayList<>(stations.values());
    }

    public java.util.Set<String> stationNames() {
        return stations.keySet();
    }

    public JunctionMarker junctionAt(String dim, BlockPos pos) {
        return junctions.get(key(dim, pos));
    }

    /** Anchor-cell map of a dimension (for arm views / scanners). */
    public Map<BlockPos, Station> anchorMap(String dim) {
        Map<BlockPos, Station> out = new java.util.HashMap<>();
        for (Station st : stationsIn(dim)) {
            out.put(st.anchor(), st);
        }
        return out;
    }

    /** Drops cached graphs of a dimension (e.g. after preference changes). */
    public void invalidateGraphs(String dim) {
        graphs.remove(dim);
    }

    public Collection<JunctionMarker> allJunctions() {
        return junctions.values();
    }

    public int junctionCount() {
        return junctions.size();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /** Registers a station; returns false when the name or sign spot is taken. */
    public boolean addStation(Station st) {
        String lower = st.name.toLowerCase();
        if (stations.containsKey(lower)) {
            return false;
        }
        if (stationAtRaw(st.dim, st.signPos()) != null) {
            return false;
        }
        stations.put(lower, st);
        graphs.remove(st.dim);
        dirty = true;
        publishNodes(st.dim);
        return true;
    }

    /** Removes a station; returns the removed one or null. */
    public Station removeStation(String name) {
        Station st = stations.remove(name.toLowerCase());
        if (st != null) {
            pending.remove(st.name);
            graphs.remove(st.dim);
            dirty = true;
            publishNodes(st.dim);
        }
        return st;
    }

    public boolean addJunction(JunctionMarker jm) {
        String k = key(jm.dim, jm.pos());
        if (junctions.containsKey(k)) {
            return false;
        }
        junctions.put(k, jm);
        graphs.remove(jm.dim);
        dirty = true;
        publishNodes(jm.dim);
        return true;
    }

    public JunctionMarker removeJunction(String dim, BlockPos pos) {
        JunctionMarker jm = junctions.remove(key(dim, pos));
        if (jm != null) {
            graphs.remove(dim);
            dirty = true;
            publishNodes(dim);
        }
        return jm;
    }

    public String pendingOf(Station st) {
        return pending.get(st.name);
    }

    public void setPending(Station st, String value) {
        if (value == null) {
            pending.remove(st.name);
        } else {
            pending.put(st.name, value);
        }
        dirty = true;
    }

    public void setSelected(Station st, String value) {
        st.selected = value;
        dirty = true;
    }

    // ------------------------------------------------------------------
    // Topology graphs
    // ------------------------------------------------------------------

    /** Graph for a dimension, built on demand and cached until registry changes. */
    public synchronized Topology.Graph graph(ServerLevel level) {
        String dim = dimId(level);
        Topology.Graph g = graphs.computeIfAbsent(dim, d -> Topology.build(level, stationsIn(d),
                junctionMarkersIn(d)));
        DbPublish.offerGraph(dim, g);
        return g;
    }

    /** Lightweight nodes-only publication feed for registry mutations. */
    private void publishNodes(String dim) {
        List<Object[]> rows = new ArrayList<>();
        int nid = 1;
        for (Station st : stationsIn(dim)) {
            rows.add(new Object[]{nid++, st.cargo ? "cargo" : "station", st.name, st.sx, st.sy, st.sz});
        }
        for (JunctionMarker jm : junctionMarkersIn(dim)) {
            BlockPos p = jm.pos();
            rows.add(new Object[]{nid++, "junction", null, p.getX(), p.getY(), p.getZ()});
        }
        DbPublish.queueNodes(dim, rows);
    }

    private List<JunctionMarker> junctionMarkersIn(String dim) {
        List<JunctionMarker> out = new ArrayList<>();
        for (JunctionMarker jm : junctions.values()) {
            if (jm.dim.equals(dim)) {
                out.add(jm);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private boolean dirty = false;

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty(MinecraftServer server) {
        if (dirty) {
            save(server);
        }
    }

    public synchronized void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray sts = new JsonArray();
        for (Station st : stations.values()) {
            JsonObject o = GSON.toJsonTree(st).getAsJsonObject();
            String p = pending.get(st.name);
            if (p != null) {
                o.addProperty("pending", p);
            }
            sts.add(o);
        }
        root.add("stations", sts);
        JsonArray jcs = new JsonArray();
        for (JunctionMarker jm : junctions.values()) {
            jcs.add(GSON.toJsonTree(jm));
        }
        root.add("junctions", jcs);

        Path file = filePath(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            SmartRails.LOGGER.error("Saving network.json failed", e);
        }
    }

    public synchronized void load(MinecraftServer server) {
        stations.clear();
        junctions.clear();
        pending.clear();
        graphs.clear();
        Path file = filePath(server);
        if (!Files.exists(file)) {
            SmartRails.LOGGER.info("[smartrails] No network.json yet - starting empty.");
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("stations")) {
                Station st = GSON.fromJson(el, Station.class);
                stations.put(st.name.toLowerCase(), st);
                JsonElement pe = el.getAsJsonObject().get("pending");
                if (pe != null && !pe.isJsonNull()) {
                    pending.put(st.name, pe.getAsString());
                }
            }
            if (root.has("junctions")) {
                for (JsonElement el : root.getAsJsonArray("junctions")) {
                    JunctionMarker jm = GSON.fromJson(el, JunctionMarker.class);
                    junctions.put(key(jm.dim, jm.pos()), jm);
                }
            }
            SmartRails.LOGGER.info("[smartrails] Loaded {} station(s), {} junction marker(s).",
                    stations.size(), junctions.size());
            java.util.TreeSet<String> dims = new java.util.TreeSet<>();
            for (Station st : stations.values()) {
                dims.add(st.dim);
            }
            for (JunctionMarker jm : junctions.values()) {
                dims.add(jm.dim);
            }
            for (String d : dims) {
                publishNodes(d);
            }
        } catch (Exception e) {
            SmartRails.LOGGER.error("Loading network.json failed - starting empty.", e);
        }
    }

    private static Path filePath(MinecraftServer server) {
        // Renamed with the mod (Smart Rails). Pre-rename worlds keep their
        // <world>/railscontrol/network.json -- read it in place instead of
        // silently starting with an empty registry.
        Path current = server.getWorldPath(new LevelResource("smartrails")).resolve("network.json");
        if (Files.exists(current)) {
            return current;
        }
        Path legacy = server.getWorldPath(new LevelResource("railscontrol")).resolve("network.json");
        return Files.exists(legacy) ? legacy : current;
    }
}
