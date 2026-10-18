package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the rail network topology to the operator's MariaDB so the
 * website (and the BlueMap marker feed, regenerated on the host side) can
 * consume it. Writes run exclusively on a daemon executor — database
 * latency never touches the server tick.
 *
 * <p>Data model: per dimension, {@code nodes(nid,dim,kind,name,x,y,z)} and
 * {@code edges(eid,dim,frm,dst,exit_arm,len,cells)}. Cell sequences are
 * delta-encoded ("x,y,z;dx,dy,dz;..."). Publication is a per-dimension
 * transaction: delete that dimension's rows, insert the fresh snapshot.
 *
 * <p>Two feeds converge on the same per-dim payload: registry mutations
 * queue a lightweight nodes-only snapshot (no world access), legitimate
 * graph builds queue the full graph (nodes + edges + derived termini/
 * crossings). A full payload supersedes a pending nodes-only one.
 */
public final class DbPublish {

    // ------------------------------------------------------------ config --
    public static boolean enabled = true;
    public static String host = "127.0.0.1";
    public static int port = 33306;
    public static String user = "";
    public static String password = "";

    /** Minimum seconds between two write attempts (throttle + retry beat). */
    public static int minIntervalSec = 10;

    private DbPublish() {
    }

    // ------------------------------------------------------------- rows --

    /** SQL-ready tuple set for one dimension. Immutable after build. */
    private static final class DimRows {
        final String dim;
        final Object[][] nodes;   // {nid, kind, name|null, x, y, z}
        final Object[][] edges;   // {eid, frm, dst, exitArm, len, cells}

        DimRows(String dim, Object[][] nodes, Object[][] edges) {
            this.dim = dim;
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    /**
     * Latest unpublished payloads keyed by dimension. Swapped wholesale:
     * producers build a merged map, consumers take it away and null it.
     */
    private static final AtomicReference<LinkedHashMap<String, DimRows>> QUEUE =
            new AtomicReference<>();

    private static final AtomicInteger FAIL_STREAK = new AtomicInteger();

    private static final ScheduledExecutorService PUMP;

    static {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "smartrail-db-publish");
            t.setDaemon(true);
            return t;
        };
        PUMP = Executors.newSingleThreadScheduledExecutor(tf);
        PUMP.scheduleAtFixedRate(DbPublish::pumpOnce, 5, Math.max(5, minIntervalSec), TimeUnit.SECONDS);
    }

    // ----------------------------------------------------------- queues --

    /** Queues a nodes-only snapshot (called from registry mutation paths). */
    public static void queueNodes(String dim, List<Object[]> nodeRows) {
        if (!effectiveEnabled() || dim == null) {
            return;
        }
        merge(dim, new DimRows(dim, nodeRows.toArray(Object[][]::new), new Object[0][]));
    }

    /**
     * Queues the full graph snapshot (called right after a topology build;
     * the caller pays for the scan anyway, copying rows here is trivial).
     */
    public static void offerGraph(String dim, Topology.Graph g) {
        if (!effectiveEnabled() || dim == null || g == null) {
            return;
        }
        List<Object[]> ns = new ArrayList<>();
        for (int i = 0; i < g.nodes.size(); i++) {
            Topology.Node n = g.nodes.get(i);
            ns.add(new Object[]{
                    i + 1, n.kind.name().toLowerCase(),
                    n.kind == Topology.Kind.STATION ? n.stationName : null,
                    n.pos.getX(), n.pos.getY(), n.pos.getZ()});
        }
        List<Object[]> es = new ArrayList<>();
        for (Topology.Edge e : g.edges) {
            es.add(new Object[]{
                    es.size() + 1, e.from + 1, e.to + 1,
                    e.exit.getName().substring(0, 1).toUpperCase(),
                    e.length, encodeCells(e.cells)});
        }
        merge(dim, new DimRows(dim, ns.toArray(Object[][]::new), es.toArray(Object[][]::new)));
    }

    /**
     * Merges {@code rows} into the queue: a FULL payload (edges present)
     * replaces whatever waited for that dimension; a NODES-only payload
     * yields to an already waiting FULL one.
     */
    private static void merge(String dim, DimRows rows) {
        LinkedHashMap<String, DimRows> cur = QUEUE.get();
        LinkedHashMap<String, DimRows> next = new LinkedHashMap<>();
        if (cur != null) {
            next.putAll(cur);
        }
        DimRows prev = next.get(dim);
        boolean incomingFull = rows.edges.length > 0;
        boolean prevFull = prev != null && prev.edges.length > 0;
        if (!(prevFull && !incomingFull)) {
            next.put(dim, rows);
        }
        QUEUE.set(next);
    }

    private static boolean effectiveEnabled() {
        return enabled && user != null && !user.isEmpty();
    }

    // -------------------------------------------------------------- io --

    private static void pumpOnce() {
        LinkedHashMap<String, DimRows> batch = QUEUE.getAndSet(null);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, DimRows>> it = batch.entrySet().iterator();
        while (it.hasNext()) {
            DimRows rows = it.next().getValue();
            try {
                writeDim(rows);
                FAIL_STREAK.set(0);
            } catch (Exception e) {
                // keep the payload queued for the next beat
                merge(rows.dim, rows);
                int severity = failureSeverity(e);
                if (severity >= 0 && (severity == 1 || severity % 15 == 0)) {
                    SmartRails.LOGGER.warn("[Smarterails] DB publish '{}' failed ({})",
                            rows.dim, e.getMessage());
                }
            }
        }
    }

    private static int failureSeverity(Exception e) {
        if (e instanceof InterruptedException) {
            return -1;
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root.getMessage() != null && root.getMessage().contains("Access denied")) {
            return -1; // misconfiguration: complain once, then silence
        }
        return FAIL_STREAK.incrementAndGet();
    }

    private static Connection connect(long timeoutMs) throws Exception {
        Class.forName("org.mariadb.jdbc.Driver");
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password == null ? "" : password);
        p.setProperty("connectTimeout", String.valueOf(timeoutMs));
        p.setProperty("socketTimeout", String.valueOf(timeoutMs * 2));
        p.setProperty("autoReconnect", "false");
        return DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/" + dbName(), p);
    }

    private static String dbName() {
        return "smartrail";
    }

    private static void writeDim(DimRows rows) throws Exception {
        // A payload carrying edges is a FULL graph snapshot and authoritative
        // for the whole dimension. A registry-only payload (server start, or a
        // station/junction mutation) only knows the station+junction nodes --
        // it must NOT touch `edges`, otherwise every mutation would wipe the
        // published geometry until the next full graph build.
        boolean full = rows.edges.length > 0;
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), 3000);
        }
        try (Connection c = connect(5000)) {
            c.setAutoCommit(false);
            try (PreparedStatement delE = c.prepareStatement(
                    "DELETE FROM edges WHERE dim=?");
                 PreparedStatement delN = c.prepareStatement(
                         "DELETE FROM nodes WHERE dim=?");
                 PreparedStatement insN = c.prepareStatement(
                         "INSERT INTO nodes (nid,dim,kind,name,x,y,z) VALUES (?,?,?,?,?,?,?)");
                 PreparedStatement insE = c.prepareStatement(
                         "INSERT INTO edges (eid,dim,frm,dst,exit_arm,len,cells) VALUES (?,?,?,?,?,?,?)");
                 PreparedStatement log = c.prepareStatement(
                         "INSERT INTO publog (ts_utc,dims,nnodes,nedges,kind) VALUES (?,?,?,?,?)")) {
                if (full) {
                    delE.setString(1, rows.dim);
                    delE.executeUpdate();
                }
                delN.setString(1, rows.dim);
                delN.executeUpdate();
                for (Object[] n : rows.nodes) {
                    insN.setInt(1, (Integer) n[0]);
                    insN.setString(2, rows.dim);
                    insN.setString(3, (String) n[1]);
                    insN.setString(4, (String) n[2]);
                    insN.setInt(5, (Integer) n[3]);
                    insN.setInt(6, (Integer) n[4]);
                    insN.setInt(7, (Integer) n[5]);
                    insN.addBatch();
                }
                insN.executeBatch();
                if (full) {
                    for (Object[] e : rows.edges) {
                        insE.setInt(1, (Integer) e[0]);
                        insE.setString(2, rows.dim);
                        insE.setInt(3, (Integer) e[1]);
                        insE.setInt(4, (Integer) e[2]);
                        insE.setString(5, (String) e[3]);
                        insE.setInt(6, (Integer) e[4]);
                        insE.setString(7, (String) e[5]);
                        insE.addBatch();
                    }
                    insE.executeBatch();
                }
                log.setLong(1, System.currentTimeMillis() / 1000L);
                log.setString(2, rows.dim);
                log.setInt(3, rows.nodes.length);
                log.setInt(4, rows.edges.length);   // rows actually written
                log.setString(5, full ? "graph" : "registry");
                log.executeUpdate();
                c.commit();
            } catch (Exception inner) {
                try {
                    c.rollback();
                } catch (Exception ignore) {
                    // rolled back or already broken; retry next beat
                }
                throw inner;
            }
        }
    }

    // -------------------------------------------------------- encoding --

    /** Encodes a cell path as "x,y,z;dx,dy,dz;..." (first cell absolute). */
    static String encodeCells(List<BlockPos> cells) {
        StringBuilder sb = new StringBuilder();
        int px = 0, py = 0, pz = 0;
        boolean first = true;
        for (BlockPos p : cells) {
            if (first) {
                px = p.getX();
                py = p.getY();
                pz = p.getZ();
                sb.append(px).append(',').append(py).append(',').append(pz);
                first = false;
            } else {
                int dx = p.getX() - px, dy = p.getY() - py, dz = p.getZ() - pz;
                sb.append(';').append(dx).append(',').append(dy).append(',').append(dz);
                px += dx;
                py += dy;
                pz += dz;
            }
        }
        return sb.toString();
    }
}
