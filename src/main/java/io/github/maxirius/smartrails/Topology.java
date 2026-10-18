package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Builds a logical graph of a rail network by PHYSICALLY walking the track
 * blocks, and computes routes over it.
 *
 * Nodes: stations (platform anchor rails), junctions (cells with more than one
 * forward exit for some arrival direction, plus manually declared markers) and
 * termini (dead ends / broken gaps). Edges are directed and carry the exit
 * direction chosen at their origin node, which doubles as the switch decision
 * a travelling train needs at that node.
 *
 * Reachability falls out of the graph automatically: a destination is offered
 * on a station sign exactly when a walk over real track reaches it.
 */
final class Topology {

    private Topology() {
    }

    enum Kind {STATION, JUNCTION, TERMINUS}

    static final class Node {
        final String key;
        final Kind kind;
        final BlockPos pos;
        final String stationName; // only for STATION
        final boolean cargo;      // only for STATION: freight platform

        Node(String key, Kind kind, BlockPos pos, String stationName, boolean cargo) {
            this.key = key;
            this.kind = kind;
            this.pos = pos;
            this.stationName = stationName;
            this.cargo = cargo;
        }
    }

    /** Directed edge: leaving {@code from} through {@code exit} eventually reaches {@code to}.
     *  {@code cells} is the walked cell sequence (first cell after the origin,
     *  last cell = the destination node's cell) - the physical path a train follows. */
    static final class Edge {
        final int from;
        final int to;
        final Direction exit;
        final int exitDy;
        final int length;
        final List<BlockPos> cells;

        Edge(int from, int to, Direction exit, int exitDy, int length, List<BlockPos> cells) {
            this.from = from;
            this.to = to;
            this.exit = exit;
            this.exitDy = exitDy;
            this.length = length;
            this.cells = List.copyOf(cells);
        }
    }

    static final class Graph {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        /** node index -> edge indices originating there */
        final Map<Integer, List<Integer>> out = new java.util.HashMap<>();
        final Map<String, Integer> byKey = new java.util.HashMap<>();
    }

    /** One switch decision handed to the train controller. */
    record Decision(BlockPos junctionPos, Direction exit, int exitDy) {
    }

    /** Full route: the complete cell path (physical track) plus the launch direction. */
    record Route(List<BlockPos> fullPath, List<Decision> decisions, Direction initialExit) {
    }

    /** A usable arm of a hub cell: direction, first rail cell, its Y offset. */
    record Arm(Direction dir, BlockPos firstCell, int dy) {
    }

    private static final int MAX_CELLS_PER_WALK = 16384;
    private static final int MAX_TOTAL_SCANS = 400000;
    private static final int ARM_PROBE_LENGTH = 12;

    /**
     * Right-hand-traffic enforcement at crossings (config "crossings.enforce",
     * default on). When active, a crossing cell only exposes the turn that
     * follows the landing lane's inferred flow - turning into the oncoming
     * lane disappears from the graph AND from the junction stick.
     */
    static volatile boolean crossingsEnforce = true;

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    static Graph build(ServerLevel level, List<Station> stations, List<JunctionMarker> manuals) {
        Graph g = new Graph();

        Map<BlockPos, Station> anchors = new java.util.HashMap<>();
        for (Station st : stations) {
            anchors.put(st.anchor(), st);
        }

        int[] scans = {0};

        // Seeds: every station anchor and every manual junction.
        List<Object[]> seeds = new ArrayList<>(); // {Node, BlockPos}
        for (Station st : stations) {
            Node n = node(g, "S|" + st.name.toLowerCase(), Kind.STATION, st.anchor(), st.name,
                    st.cargo);
            seeds.add(new Object[]{n, st.anchor()});
        }
        for (JunctionMarker jm : manuals) {
            Node n = node(g, "M|" + keyStr(jm.pos()), Kind.JUNCTION, jm.pos(), null);
            seeds.add(new Object[]{n, jm.pos()});
        }

        Set<BlockPos> autoJunctions = new java.util.LinkedHashSet<>();
        for (Object[] seed : seeds) {
            Node origin = (Node) seed[0];
            BlockPos originPos = (BlockPos) seed[1];
            var arms = armsAt(level, originPos, anchors);
            SmartRails.LOGGER.info("[GRAPHDBG] seed {} {} arms={} dims={}",
                    origin.key, originPos.toShortString(), arms.size(),
                    arms.stream().map(a -> a.dir() + "@dy" + a.dy()).toList());
            // Tolerant seeding: a twisted anchor cell must not orphan a station.
            for (Arm arm : arms) {
                walk(level, g, anchors, origin, originPos, arm.dir(), arm.dy(),
                        autoJunctions, scans);
            }
        }
        SmartRails.LOGGER.info("[GRAPHDBG] build done: nodes={} edges={} junctions={}",
                g.nodes.size(), g.edges.size(), autoJunctions.size());

        // Second pass: junction cells need outgoing edges for EVERY arm -
        // including arms the current vanilla curve shape does not face (the
        // train controller steers carts onto those by force).
        for (BlockPos jp : autoJunctions) {
            Node origin = node(g, "J|" + keyStr(jp), Kind.JUNCTION, jp, null);
            for (Arm arm : armsAt(level, jp, anchors)) {
                walk(level, g, anchors, origin, jp, arm.dir(), arm.dy(),
                        autoJunctions, scans);
            }
        }

        int crossings = 0;
        for (BlockPos jp : autoJunctions) {
            if (isCrossingCell(level, jp)) {
                crossings++;
            }
        }
        SmartRails.LOGGER.info("[GRAPHDBG] crossings={} (enforce={}, twin-cluster "
                + "= 2x2 cells)", crossings, crossingsEnforce);
        return g;
    }

    // ------------------------------------------------------------------
    // Crossings (twin-track awareness)
    // ------------------------------------------------------------------

    /**
     * True when {@code pos} is a vanilla CROSSING cell: a straight rail whose
     * neighbours open back towards it along BOTH axes at the SAME height.
     * 26.3 keeps the blockstate locked to one axis (the shape property never
     * shows the crossing), so the perpendicular passage is only visible
     * through the neighbourhood - hence this structural fingerprint.
     */
    static boolean isCrossingCell(ServerLevel level, BlockPos pos) {
        RailShape s = RailShapes.shapeAt(level, pos);
        if (s != RailShape.NORTH_SOUTH && s != RailShape.EAST_WEST) {
            return false;
        }
        boolean ew = false;
        boolean ns = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos nb = pos.relative(d);
            RailShape nsh = RailShapes.shapeAt(level, nb);
            if (nsh == null) {
                continue;
            }
            if (contactAbs(nb, nsh, d.getOpposite()) == pos.getY()) {
                if (d.getAxis() == Direction.Axis.X) {
                    ew = true;
                } else {
                    ns = true;
                }
            }
        }
        return ew && ns;
    }

    /**
     * Travel direction of the lane through {@code laneCell} along {@code axis},
     * inferred from the sister lane of a double-track road: facing the flow,
     * the sister lane lies on the LEFT (right-hand traffic). Empty when no
     * sister lane exists within the usual spacing (single-track road - no
     * constraint derivable, turns stay open).
     */
    static java.util.Optional<Direction> laneFlow(ServerLevel level, BlockPos laneCell,
                                                  Direction.Axis axis) {
        Direction lat = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Integer sisterSign = null;
        int bestDist = Integer.MAX_VALUE;
        for (int k : new int[]{-4, -3, -2, 2, 3, 4}) {
            // Sample the candidate parallel line 3 cells wide centred on the
            // lane cell's along-coordinate: at crossing columns the sister
            // lane's own cell is occluded by the perpendicular line, so a
            // single-cell probe would miss it exactly where it matters.
            boolean found = false;
            for (int s = -1; s <= 1 && !found; s++) {
                BlockPos cand = laneCell.relative(lat, k).relative(along, s);
                RailShape cs = RailShapes.shapeAt(level, cand);
                if (cs == null || cs.name().startsWith("ASCENDING")) {
                    continue;
                }
                found = axis == Direction.Axis.X
                        ? cs == RailShape.EAST_WEST
                        : cs == RailShape.NORTH_SOUTH;
            }
            if (found && Math.abs(k) < bestDist) {
                bestDist = Math.abs(k);
                sisterSign = k > 0 ? 1 : -1;
            }
        }
        if (sisterSign == null) {
            return java.util.Optional.empty();
        }
        // Facing D, left = rotate CCW (compass): E->N(z-), S->E(x+),
        // W->S(z+), N->W(x-). The flow is the direction whose LEFT side
        // holds the sister lane.
        if (axis == Direction.Axis.X) {
            // lat = SOUTH: sisterSign +1 = sister south of us (z+).
            return java.util.Optional.of(sisterSign > 0 ? Direction.WEST : Direction.EAST);
        }
        // lat = EAST: sisterSign +1 = sister east of us (x+).
        return java.util.Optional.of(sisterSign > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * May a cart arriving at crossing {@code crossing} turn into
     * {@code turnDir}? The landing lane is the perpendicular track through the
     * crossing; the turn is legal iff it follows that lane's inferred flow
     * (no sister lane = unconstrained). Applied to EVERY arm direction -
     * straight continuations included - so wrong-way passages are excluded
     * with the same rigidity.
     */
    static boolean turnAllowedAt(ServerLevel level, BlockPos crossing, Direction turnDir) {
        var flow = laneFlow(level, crossing, turnDir.getAxis());
        return flow.isEmpty() || flow.get() == turnDir;
    }

    // ------------------------------------------------------------------
    // Arm detection
    // ------------------------------------------------------------------

    /**
     * Public arm view for the junction stick - cycling needs the SAME
     * ordering the graph builder uses, so this wraps {@link #armsAt}.
     */
    static List<Arm> armsFor(ServerLevel level, BlockPos hub) {
        Map<BlockPos, Station> anchors = new java.util.HashMap<>();
        for (Station st : NetworkState.INSTANCE.stationsIn(NetworkState.dimId(level))) {
            anchors.put(st.anchor(), st);
        }
        return armsAt(level, hub, anchors);
    }

    /**
     * Usable arms of {@code hub}: for each horizontal direction, the adjacent
     * rail cell (dy -1..+1) qualifies when within the arm's first two cells
     * one rail opens back towards the hub side AND a short probe-walk (never
     * heading back) progresses into living track, diverges or hits a station.
     * Filters both parallel-track phantoms (no reciprocity) and stubs/decorative
     * ends (no progression).
     */
    static List<Arm> armsAt(ServerLevel level, BlockPos hub, Map<BlockPos, Station> anchors) {
        List<Arm> out = new ArrayList<>(4);
        // Crossing hub: EVERY arm (straight or turn) must follow its axis'
        // inferred lane flow - a wrong-way straight passage is excluded with
        // the same rigidity as an illegal turn.
        boolean hubCrossing = crossingsEnforce && isCrossingCell(level, hub);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (hubCrossing && !turnAllowedAt(level, hub, d)) {
                continue;
            }
            for (int dyProbe = -1; dyProbe <= 1; dyProbe++) {
                BlockPos p = hub.relative(d).above(dyProbe);
                if (RailShapes.shapeAt(level, p) == null) {
                    continue;
                }
                int dy = p.getY() - hub.getY();
                if (armValid(level, hub, p, d, anchors)) {
                    out.add(new Arm(d, p.immutable(), dy));
                    break; // one arm per direction
                }
            }
        }
        return out;
    }

    private static boolean armValid(ServerLevel level, BlockPos hub, BlockPos firstCell,
                                    Direction dir, Map<BlockPos, Station> anchors) {
        // Reciprocity within the first two cells of the arm.
        if (!reciprocates(level, firstCell, dir.getOpposite(), hub.getY())) {
            BlockPos second = firstCell.relative(dir);
            boolean secondOk = false;
            for (int dyProbe = -1; dyProbe <= 1 && !secondOk; dyProbe++) {
                BlockPos p2 = second.above(dyProbe);
                if (RailShapes.shapeAt(level, p2) != null
                        && reciprocates(level, p2, dir.getOpposite(), firstCell.getY())) {
                    secondOk = true;
                }
            }
            if (!secondOk) {
                return false;   // e.g. parallel track that never opens towards us
            }
        }
        // Probe-walk: must progress into real track (divergence, station or
        // sustained run), never counting the way back to the hub. Deliberately
        // LIGHTWEIGHT (strict links + raw-adjacency rescue, NO nested arm
        // validation) - nested probes would branch exponentially and blow the
        // stack.
        BlockPos cur = firstCell;
        Direction trav = dir;
        for (int step = 0; step < ARM_PROBE_LENGTH; step++) {
            if (anchors.containsKey(cur)) {
                return true;
            }
            List<RailShapes.Link> strict = new ArrayList<>();
            for (RailShapes.Link l : RailShapes.connections(level, cur)) {
                if (!l.dir().equals(trav.getOpposite())) {
                    strict.add(l);
                }
            }
            if (strict.size() > 1) {
                return true;    // divergence ahead = living network
            }
            if (strict.size() == 1) {
                cur = strict.getFirst().pos();
                trav = strict.getFirst().dir();
                continue;
            }
            // Strict-blind cell: raw adjacent rails (any shape) as rescue.
            List<BlockPos> raw = new ArrayList<>();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (d.equals(trav.getOpposite())) {
                    continue;
                }
                for (int dyProbe = -1; dyProbe <= 1; dyProbe++) {
                    BlockPos p = cur.relative(d).above(dyProbe);
                    if (RailShapes.shapeAt(level, p) != null) {
                        raw.add(p);
                        break;
                    }
                }
            }
            if (raw.size() == 1) {
                BlockPos p = raw.getFirst();
                trav = Direction.getNearest(p.getX() - cur.getX(), 0, p.getZ() - cur.getZ(), Direction.SOUTH);
                cur = p;
                continue;
            }
            return raw.size() > 1;   // several raw exits: count as divergence
        }
        return true;   // sustained single-track run counts as an arm
    }

    /** Does the rail at {@code pos} geometrically open towards {@code d} at all? */
    static boolean opensTowards(ServerLevel level, BlockPos pos, Direction d) {
        RailShape shape = RailShapes.shapeAt(level, pos);
        return shape != null && contactAbs(pos, shape, d) != Integer.MIN_VALUE;
    }

    /** Does the rail at {@code pos} open towards {@code towards} at a height meeting {@code targetY}? */
    private static boolean reciprocates(ServerLevel level, BlockPos pos, Direction towards,
                                        int targetY) {
        RailShape shape = RailShapes.shapeAt(level, pos);
        if (shape == null) {
            return false;
        }
        int contact = contactAbs(pos, shape, towards);
        return contact == targetY || contact == targetY + 1;
    }

    /** Outcome of one walking step. */
    record StepOutcome(BlockPos pos, Direction dir, boolean junction) {
    }

    /**
     * ONE unified per-cell step decision, shared by the main walk and the arm
     * probe-walks: junction detection via the tolerant arm view, continuation
     * via strict bilateral connections, with a singleton-arm rescue for
     * severed/twisted cells (faces vanilla currently refuses to open).
     */
    private static StepOutcome decideStep(ServerLevel level, BlockPos cur, Direction travelled,
                                          Map<BlockPos, Station> anchors, Set<BlockPos> junctionSink) {
        List<Arm> arms = armsAt(level, cur, anchors);
        List<Arm> fwdArms = new ArrayList<>();
        for (Arm a : arms) {
            if (!a.dir().equals(travelled.getOpposite())) {
                fwdArms.add(a);
            }
        }
        List<RailShapes.Link> strict = new ArrayList<>();
        for (RailShapes.Link l : RailShapes.connections(level, cur)) {
            if (!l.dir().equals(travelled.getOpposite())) {
                strict.add(l);
            }
        }

        if (fwdArms.size() > 1 || strict.size() > 1) {
            junctionSink.add(cur.immutable());
            return new StepOutcome(null, null, true);
        }
        if (!strict.isEmpty()) {
            RailShapes.Link next = strict.getFirst();
            return new StepOutcome(next.pos(), next.dir(), false);
        }
        if (!fwdArms.isEmpty()) {
            // Twist/sever rescue: exactly one tolerant arm squeezes through.
            Arm next = fwdArms.getFirst();
            return new StepOutcome(next.firstCell(), next.dir(), false);
        }
        // Raw-adjacency rescue (SYMMETRIC): exactly one neighbouring rail cell
        // (any shape, dy -1..+1) continues the walk. Covers reshaped ramp-top
        // corners (vanilla turns the corner north_south, which strictly opens
        // no eastern arm) and severed cells the tolerant arm view rejects from
        // one side only. Singleton-only keeps phantom parallel links out.
        BlockPos rawNext = null;
        int rawCount = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d.equals(travelled.getOpposite())) {
                continue;
            }
            for (int dyProbe = -1; dyProbe <= 1; dyProbe++) {
                BlockPos p = cur.relative(d).above(dyProbe);
                if (RailShapes.shapeAt(level, p) != null) {
                    rawCount++;
                    if (rawNext == null) {
                        rawNext = p;
                    }
                    break;
                }
            }
            if (rawCount > 1) {
                break;
            }
        }
        if (rawCount == 1 && rawNext != null) {
            Direction nd = Direction.getNearest(
                    rawNext.getX() - cur.getX(), 0, rawNext.getZ() - cur.getZ(), travelled);
            return new StepOutcome(rawNext, nd, false);
        }
        return null;   // dead end
    }

    /** Absolute contact height of a shape opening towards {@code d} ({@code Integer.MIN_VALUE} = none). */
    private static int contactAbs(BlockPos pos, RailShape shape, Direction d) {
        int y = pos.getY();
        return switch (shape) {
            case NORTH_SOUTH -> (d == Direction.NORTH || d == Direction.SOUTH) ? y : Integer.MIN_VALUE;
            case EAST_WEST -> (d == Direction.EAST || d == Direction.WEST) ? y : Integer.MIN_VALUE;
            case ASCENDING_NORTH -> d == Direction.NORTH ? y + 1
                    : d == Direction.SOUTH ? y : Integer.MIN_VALUE;
            case ASCENDING_SOUTH -> d == Direction.SOUTH ? y + 1
                    : d == Direction.NORTH ? y : Integer.MIN_VALUE;
            case ASCENDING_EAST -> d == Direction.EAST ? y + 1
                    : d == Direction.WEST ? y : Integer.MIN_VALUE;
            case ASCENDING_WEST -> d == Direction.WEST ? y + 1
                    : d == Direction.EAST ? y : Integer.MIN_VALUE;
            case SOUTH_EAST -> (d == Direction.SOUTH || d == Direction.EAST) ? y : Integer.MIN_VALUE;
            case SOUTH_WEST -> (d == Direction.SOUTH || d == Direction.WEST) ? y : Integer.MIN_VALUE;
            case NORTH_WEST -> (d == Direction.NORTH || d == Direction.WEST) ? y : Integer.MIN_VALUE;
            case NORTH_EAST -> (d == Direction.NORTH || d == Direction.EAST) ? y : Integer.MIN_VALUE;
        };
    }

    /**
     * Walks the track from {@code origin} leaving through {@code exitDir}/
     * {@code exitDy} until the next node (station, junction, terminus) or a
     * revisit, recording one directed edge.
     */
    private static void walk(ServerLevel level, Graph g, Map<BlockPos, Station> anchors,
                             Node origin, BlockPos originPos,
                             Direction exitDir, int exitDy,
                             Set<BlockPos> autoJunctions, int[] scans) {
        BlockPos cur = RailShapes.step(originPos, exitDir, exitDy);
        Direction travelled = exitDir;
        Set<Long> localVisited = new HashSet<>();
        List<BlockPos> seq = new ArrayList<>();
        seq.add(cur);
        int len = 1;

        while (scans[0]++ < MAX_TOTAL_SCANS && len <= MAX_CELLS_PER_WALK) {
            // Reached a registered station platform?
            Station hit = anchors.get(cur);
            if (hit != null) {
                edge(g, origin, exitDir, exitDy, len, node(g, "S|" + hit.name.toLowerCase(),
                        Kind.STATION, cur, hit.name, hit.cargo), seq);
                return;
            }

            if (RailShapes.shapeAt(level, cur) == null) {
                // Track ends abruptly (gap / destroyed rail) - terminus.
                edge(g, origin, exitDir, exitDy, len, node(g, "T|" + keyStr(cur), Kind.TERMINUS, cur, null), seq);
                return;
            }

            // Flow gate: entering a CROSSING against its lane's inferred flow
            // culls the whole walk - the lawful counterpart walk covers the
            // lane from the other end, so the edge simply ceases to exist and
            // the router can never produce a wrong-way passage.
            if (crossingsEnforce && isCrossingCell(level, cur)) {
                var arrivalFlow = laneFlow(level, cur, travelled.getAxis());
                if (arrivalFlow.isPresent() && arrivalFlow.get() != travelled) {
                    return;
                }
            }

            StepOutcome out = decideStep(level, cur, travelled, anchors, autoJunctions);
            if (out == null) {
                // Dead end (buffer stop).
                edge(g, origin, exitDir, exitDy, len, node(g, "T|" + keyStr(cur), Kind.TERMINUS, cur, null), seq);
                return;
            }
            if (out.junction()) {
                // Divergence ahead: this cell behaves as a junction node.
                edge(g, origin, exitDir, exitDy, len, node(g, "J|" + keyStr(cur), Kind.JUNCTION, cur, null), seq);
                return;
            }
            if (!localVisited.add(out.pos().asLong())) {
                // Closed loop without nodes - treat as terminus to stay finite.
                edge(g, origin, exitDir, exitDy, len, node(g, "T|" + keyStr(cur), Kind.TERMINUS, cur, null), seq);
                return;
            }
            travelled = out.dir();
            cur = out.pos();
            seq.add(cur);
            len++;
        }
    }

    private static Node node(Graph g, String key, Kind kind, BlockPos pos, String stationName) {
        return node(g, key, kind, pos, stationName, false);
    }

    private static Node node(Graph g, String key, Kind kind, BlockPos pos, String stationName,
                             boolean cargo) {
        Integer idx = g.byKey.get(key);
        if (idx != null) {
            return g.nodes.get(idx);
        }
        Node n = new Node(key, kind, pos.immutable(), stationName, cargo);
        g.byKey.put(key, g.nodes.size());
        g.nodes.add(n);
        return n;
    }

    private static void edge(Graph g, Node from, Direction exit, int exitDy, int len, Node to, List<BlockPos> cells) {
        int fi = g.byKey.get(from.key);
        int ti = g.byKey.get(to.key);
        Edge e = new Edge(fi, ti, exit, exitDy, len, cells);
        g.out.computeIfAbsent(fi, k -> new ArrayList<>()).add(g.edges.size());
        g.edges.add(e);
    }

    private static String keyStr(BlockPos pos) {
        return pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** All station names reachable from {@code fromStation} (exclusive). Sorted. */
    static java.util.TreeSet<String> reachable(Graph g, String fromStation) {
        Integer start = g.byKey.get("S|" + fromStation.toLowerCase());
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        if (start == null) {
            return out;
        }
        boolean[] seen = new boolean[g.nodes.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        seen[start] = true;
        stack.push(start);
        while (!stack.isEmpty()) {
            int u = stack.pop();
            List<Integer> outs = g.out.get(u);
            if (outs == null) {
                continue;
            }
            for (int ei : outs) {
                int v = g.edges.get(ei).to;
                if (seen[v]) {
                    continue;
                }
                seen[v] = true;
                Node n = g.nodes.get(v);
                if (n.kind == Kind.STATION && !n.key.equalsIgnoreCase("S|" + fromStation.toLowerCase())) {
                    out.add(n.stationName);
                }
                stack.push(v);
            }
        }
        return out;
    }

    /**
     * Shortest route (fewest track cells) between two stations.
     *
     * @return the route, or null when unreachable/disconnected.
     */
    static Route route(Graph g, String fromStation, String toStation) {
        Integer start = g.byKey.get("S|" + fromStation.toLowerCase());
        Integer goal = g.byKey.get("S|" + toStation.toLowerCase());
        if (start == null || goal == null) {
            return null;
        }

        int n = g.nodes.size();
        double[] dist = new double[n];
        int[] prevEdge = new int[n];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(prevEdge, -1);
        dist[start] = 0;

        // Preferred-exit soft bias: junction markers may nominate an exit;
        // comparable routes then favour it (half cost, invariant-safe).
        java.util.Map<BlockPos, net.minecraft.core.Direction> prefs = new java.util.HashMap<>();
        for (JunctionMarker jm : NetworkState.INSTANCE.allJunctions()) {
            net.minecraft.core.Direction pd = jm.prefDir();
            if (pd != null) {
                prefs.put(jm.pos(), pd);
            }
        }

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        pq.add(new long[]{start, Double.doubleToLongBits(0)});
        boolean[] settled = new boolean[n];

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0];
            if (settled[u]) {
                continue;
            }
            settled[u] = true;
            if (u == goal) {
                break;
            }
            List<Integer> outs = g.out.get(u);
            if (outs == null) {
                continue;
            }
            for (int ei : outs) {
                Edge e = g.edges.get(ei);
                double cost = Math.max(1, e.length);
                net.minecraft.core.Direction pd = prefs.get(g.nodes.get(e.from).pos);
                if (pd != null && pd == e.exit) {
                    cost *= 0.5;
                }
                double nd = dist[u] + cost;
                if (nd < dist[e.to]) {
                    dist[e.to] = nd;
                    prevEdge[e.to] = ei;
                    pq.add(new long[]{e.to, Double.doubleToLongBits(nd)});
                }
            }
        }

        if (!settled[goal]) {
            return null;
        }

        // Reconstruct backwards, then emit decisions for junction hops and
        // stitch the FULL cell path (the physical track the train follows).
        List<Edge> path = new ArrayList<>();
        int cur = goal;
        while (cur != start) {
            int ei = prevEdge[cur];
            if (ei < 0) {
                return null;
            }
            path.add(g.edges.get(ei));
            cur = g.edges.get(ei).from;
        }
        List<BlockPos> fullPath = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (ListIterator<Edge> it = path.listIterator(path.size()); it.hasPrevious(); ) {
            Edge e = it.previous();
            Node from = g.nodes.get(e.from);
            if (from.kind == Kind.JUNCTION) {
                decisions.add(new Decision(from.pos, e.exit, e.exitDy));
            }
            for (BlockPos c : e.cells) {
                if (!fullPath.isEmpty() && fullPath.getLast().equals(c)) {
                    continue;   // dedupe at edge seams (junction/node cells)
                }
                fullPath.add(c);
            }
        }
        // Backwards reconstruction appended goal->start, so the LAST entry is
        // the edge leaving the origin station.
        Direction initialExit = path.getLast().exit;
        return new Route(fullPath, decisions, initialExit);
    }
}
