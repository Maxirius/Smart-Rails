package io.github.maxirius.smartrails;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.state.properties.RailShape;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives controlled minecarts with a boarding-centred life cycle:
 *
 * 1. Destination confirmed on a station sign -> the mod ensures a minecart
 *    exists on the platform track (spawns one when none is present).
 * 2. A player boards -> after one second the trip departs.
 * 3. En route the controller cruises, takes junctions by force-steering the
 *    cart onto the planned branch, and brakes on arrival.
 * 4. At the destination the passengers disembark and the cart despawns.
 *
 * Nothing departs on its own: a placed cart without a rider stays put, and an
 * arriving cart is dissolved instead of shuttling back.
 */
final class TrainController {

    private static final org.slf4j.Logger LOGGER = SmartRails.LOGGER;

    private TrainController() {
    }

    private enum Phase {BOARD_WAIT, RUN, PAUSED, BRAKE}

    private static final class Trip {
        UUID cart;
        UUID ownerId;          // player who confirmed the destination (may be null)
        String dim;
        String station;
        String dest;
        BlockPos destAnchor;
        BlockPos originAnchor;        // validated at launch (boarding sanity)
        boolean cargo;                // freight trip: chest cart, cargo cruise
        Direction launchDir;
        List<Topology.Decision> plan;
        List<BlockPos> path;          // full physical cell path of the route
        int pathIdx;                  // furthest path cell the cart reached
        int rescueCd;                 // ticks before another rescue-shunt may fire
        int restoreCd;                // ticks before another direction-restore
        boolean descentMode;          // sticky: no charging, 8 m/s cap till next ascent
        long cargoCenter = -1;        // packed chunk coord of the freight window
        Vec3 lastKinPos;              // previous kinematic seat (servo baseline)
        double cruiseBpt = CRUISE_SPEED;   // per-trip cruise (freight slower)
        Phase phase = Phase.BOARD_WAIT;
        int boardCountdown;
        boolean seenPassenger;
        boolean nearedJunction;
        boolean capturing;
        int captureTicks;
        int stallTicks;
        double maxTravel;
        int unseenTicks;
        BlockPos lastPos;
        Direction lastDir;
        int depositGrace;
        double lastWaypointDist = Double.MAX_VALUE;
        int noProgressTicks;
        int cellsCredited;            // total rail cells credited (progress metric)
        int watchCheckpoint;          // cellsCredited at the last watchdog reset
        // High-speed regime state
        double hsSpeed = -1;          // governed speed in b/t; -1 = never boosted
        int hsHold;                   // plain-rail cells passed since last boost
        Vec3 hsPrevPos;               // centre position of the previous tick
        BlockPos hsLastCell;          // last cell credited (anti-double-count)
        int pauseTicksLeft;           // reboard window after mid-trip disembark
        boolean hsWasKinematic;       // previous tick transported kinematically
        Vec3 parkPos;                 // frozen parking pose during PAUSED
    }

    private static final Map<UUID, Trip> TRIPS = new ConcurrentHashMap<>();
    private static int rearmTimer = 0;

    // Tuning ---------------------------------------------------------------
    private static final double LAUNCH_SPEED = 0.35;
    private static final double MIN_JUNCTION_SPEED = 0.30;
    private static final double APPROACH_RADIUS = 2.2;
    private static final double MISSED_RADIUS = 2.9;
    private static final double BRAKE_START_DIST = 3.2;
    private static final double BRAKE_FACTOR = 0.62;
    private static final double STOP_SPEED = 0.05;
    private static final int STALL_LIMIT_TICKS = 100;
    private static final double CRUISE_SPEED = 0.32;
    private static final double HARD_SPEED_CAP = 0.85;
    private static final double JUNCTION_SPEED_CAP = 0.45;   // 9 m/s: capture window still ample
    private static final double JUNCTION_BRAKE_ZONE = 4.5;
    private static final int DEPOSIT_GRACE_TICKS = 10;
    private static final int PROGRESS_TIMEOUT_TICKS = 100;
    private static final int BOARD_DELAY_TICKS = 20;   // one second after boarding
    private static double CARGO_CRUISE_MS = 8.0;       // freight cruise (config)
    private static double CARGO_CRUISE_BPT = CARGO_CRUISE_MS / 20.0;

    /** Config hook: freight cruise speed in m/s. */
    static void setCargoCruise(double ms) {
        CARGO_CRUISE_MS = ms;
        CARGO_CRUISE_BPT = ms / 20.0;
    }

    private static int CARGO_WINDOW_CHUNKS = 2;   // rolling chunk window around freight carts

    /** Custom ticket: loads + simulates chunks, never expires (manual release). */
    private static final net.minecraft.server.level.TicketType CARGO_TICKET =
            new net.minecraft.server.level.TicketType(
                    net.minecraft.server.level.TicketType.NO_TIMEOUT,
                    net.minecraft.server.level.TicketType.FLAG_LOADING
                            | net.minecraft.server.level.TicketType.FLAG_SIMULATION);

    /** Config hook: chunk-load radius (in chunks) around a freight cart. */
    static void setCargoWindow(int chunks) {
        CARGO_WINDOW_CHUNKS = chunks;
    }

    /**
     * Keeps a square of {@code windowChunks} chunks around the freight cart
     * loaded (rolling window: tickets are added for the new centre and
     * released for the old one whenever the cart crosses a chunk border).
     * Without this, unmanned chest carts despawn into unloaded chunks.
     */
    private static void refreshCargoTickets(ServerLevel level, Trip t, AbstractMinecart cart) {
        int cx = cart.blockPosition().getX() >> 4;
        int cz = cart.blockPosition().getZ() >> 4;
        long center = ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
        if (center == t.cargoCenter) {
            return;
        }
        var cs = level.getChunkSource();
        int w = CARGO_WINDOW_CHUNKS;
        if (t.cargoCenter != -1) {
            int ox = (int) (t.cargoCenter >> 32);
            int oz = (int) t.cargoCenter;
            cs.removeTicketWithRadius(CARGO_TICKET, new net.minecraft.world.level.ChunkPos(ox, oz), w);
        }
        cs.addTicketWithRadius(CARGO_TICKET, new net.minecraft.world.level.ChunkPos(cx, cz), w);
        t.cargoCenter = center;
    }

    /** Releases the freight window when a trip ends (called once per torn-down trip). */
    private static void releaseCargoTickets(ServerLevel level, Trip t) {
        if (t.cargoCenter == -1) {
            return;
        }
        int ox = (int) (t.cargoCenter >> 32);
        int oz = (int) t.cargoCenter;
        level.getChunkSource().removeTicketWithRadius(CARGO_TICKET,
                new net.minecraft.world.level.ChunkPos(ox, oz), CARGO_WINDOW_CHUNKS);
        t.cargoCenter = -1;
    }
    private static final int REARM_INTERVAL_TICKS = 40;
    private static int REBOARD_SECONDS = 30;           // reboard window (config)
    private static int REBOARD_WINDOW_TICKS = REBOARD_SECONDS * 20;

    /** Config hook: reboard window after a mid-trip disembark, in seconds. */
    static void setReboardSeconds(int seconds) {
        REBOARD_SECONDS = seconds;
        REBOARD_WINDOW_TICKS = seconds * 20;
    }

    // ------------------------------------------------------------------
    // Trigger points
    // ------------------------------------------------------------------

    /** Called after a destination was confirmed on a station sign. */
    static void onDestinationSet(ServerLevel level, Station st, Player trigger) {
        if (st.cargo) {
            dispatchCargo(level, st, trigger);
            return;
        }
        if (hasTripForStation(st.name)) {
            return;
        }
        List<AbstractMinecart> staged = findStoppedCartsNear(level, st.anchor(), 4.0, 2.0);
        // Hygiene: more than one cart waiting (e.g. from repeated confirms with
        // a same-tick summon) - keep the first, dissolve the rest.
        if (staged.size() > 1) {
            for (AbstractMinecart extra : staged.subList(1, staged.size())) {
                extra.discard();
            }
        }
        AbstractMinecart cart = staged.isEmpty() ? null : staged.getFirst();
        if (cart == null) {
            cart = spawnCart(level, st.anchor(), st.cargo);
            if (cart == null) {
                warn(trigger, Messages.t("cart.spawn.failed"));
                return;
            }
        }
        Trip t = createTrip(level, st, cart, trigger);
        if (t != null) {
            info(trigger, Messages.t("cart.ready"));
        }
    }

    /**
     * Freight dispatch: the chest cart is PLAYER PROPERTY - no auto-spawn,
     * no discarding. A chest minecart must sit on the platform (filled by
     * hand or hopper); the confirm (sign click or redstone pulse) sends it
     * off. Without a cart the destination stays armed and a reminder fires.
     */
    private static void dispatchCargo(ServerLevel level, Station st, Player trigger) {
        if (hasTripForStation(st.name)) {
            return;
        }
        List<AbstractMinecart> staged = findStoppedCartsNear(level, st.anchor(), 4.0, 2.0);
        // Freight stations only ever dispatch chest carts - a stray rider
        // minecart must not be hijacked for goods duty.
        staged.removeIf(c -> !c.getType().toString().toLowerCase(java.util.Locale.ROOT).contains("chest"));
        if (staged.isEmpty()) {
            if (trigger != null) {
                warn(trigger, Messages.t("cargo.no-cart"));
            }
            return;
        }
        Trip t = createTrip(level, st, staged.getFirst(), trigger);
        if (t != null) {
            t.phase = Phase.RUN;   // freight rolls as soon as it is dispatched
            String msg = Messages.t("cargo.dispatch", st.selected);
            if (trigger != null) {
                info(trigger, msg);
            } else {
                // Redstone dispatch: inform whoever stands nearby.
                double sx = st.signPos().getX() + 0.5;
                double sy = st.signPos().getY() + 0.5;
                double sz = st.signPos().getZ() + 0.5;
                for (var p : level.players()) {
                    if (p.distanceToSqr(sx, sy, sz) < 24 * 24) {
                        info(p, msg);
                    }
                }
            }
        }
    }

    /** Redstone-triggered freight dispatch (no player involved). */
    static void redstoneDispatch(ServerLevel level, Station st) {
        dispatchCargo(level, st, null);
    }

    /** Force-departure via /srails depart <station> (admin test, no boarding needed). */
    static void forceDepart(MinecraftServer server, Station st, ServerPlayer trigger) {
        ServerLevel level = levelFor(server, st.dim);
        if (level == null) {
            return;
        }
        if (hasTripForStation(st.name)) {
            return;
        }
        List<AbstractMinecart> staged = findStoppedCartsNear(level, st.anchor(), 4.0, 2.0);
        AbstractMinecart cart = staged.isEmpty() ? null : staged.getFirst();
        if (cart == null) {
            cart = spawnCart(level, st.anchor(), st.cargo);
            if (cart == null) {
                warn(trigger, Messages.t("cart.spawn.failed"));
                return;
            }
        }
        Trip t = createTrip(level, st, cart, trigger);
        if (t != null) {
            t.phase = Phase.RUN;   // admin override: leave immediately
        }
    }

    private static Trip createTrip(ServerLevel level, Station st, AbstractMinecart cart,
                                   Player trigger) {
        Topology.Graph g = NetworkState.INSTANCE.graph(level);
        Topology.Route route = Topology.route(g, st.name, st.selected);
        if (route == null) {
            LOGGER.info("[smartrails] createTrip: NO ROUTE {} -> {}", st.name, st.selected);
            if (trigger != null) {
    warn(trigger, Messages.t("trip.no-route", st.name, st.selected));
            }
            return null;
        }
        Trip t = new Trip();
        t.cart = cart.getUUID();
        t.ownerId = trigger != null ? trigger.getUUID() : null;
        t.dim = st.dim;
        t.station = st.name;
        t.dest = st.selected;
        Station dst = NetworkState.INSTANCE.stationByName(st.selected);
        t.destAnchor = dst != null ? dst.anchor() : st.anchor();
        t.originAnchor = st.anchor();
        t.cargo = st.cargo;
        if (st.cargo) {
            t.cruiseBpt = CARGO_CRUISE_BPT;   // freight rolls calmer (config)
        }
        t.plan = route.decisions();
        t.path = route.fullPath();
        t.pathIdx = -1;               // the cart is not on the path yet
        t.launchDir = route.initialExit();
        t.lastDir = route.initialExit();
        t.lastPos = cart.blockPosition();
        t.hsPrevPos = cart.position();
        // Hygiene: one active trip per OWNER - a stale armed trip (leftover
        // selection from an abandoned confirm) must not hijack later rides.
        if (t.ownerId != null) {
            TRIPS.values().removeIf(other ->
                    other != t && t.ownerId.equals(other.ownerId));
        }
        TRIPS.put(t.cart, t);
        LOGGER.info("[smartrails] Trip armed: {} -> {} ({} decisions, cart={}, phase={})",
                st.name, st.selected, route.decisions().size(), t.cart, t.phase);
        return t;
    }

    private static AbstractMinecart spawnCart(ServerLevel level, BlockPos anchor, boolean cargo) {
        // EntityTypes (plural) is the 26.x holder class; CHEST_MINECART is a
        // typed constant - no registry/string lookup needed.
        net.minecraft.world.entity.EntityType type;
        if (cargo) {
            type = net.minecraft.world.entity.EntityTypes.CHEST_MINECART;
        } else {
            type = net.minecraft.world.entity.EntityTypes.MINECART;
        }
        AbstractMinecart cart = (AbstractMinecart) type.create(
                level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        cart.setPos(anchor.getX() + 0.5, anchor.getY() + 0.2, anchor.getZ() + 0.5);
        level.addFreshEntity(cart);
        LOGGER.debug("[SPAWN] created cart uuid={} cargo={} at {}",
                cart.getStringUUID(), cargo, anchor);
        return cart;
    }

    // ------------------------------------------------------------------
    // Tick engine
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (!TRIPS.isEmpty() || server.getTickCount() % 100 == 0) {
            LOGGER.debug("[HEARTBEAT] tick={} trips={}", server.getTickCount(), TRIPS.size());
        }
        // Redstone freight triggers run BEFORE the empty-trips early-outs -
        // they are exactly the path that CREATES the first trip.
        RedstoneWatch.tick(server);
        // Slow registry-honesty sweep: dead junction markers / station
        // anchors (rail mined away) leave the registry every 2 s.
        if (server.getTickCount() % 40 == 0) {
            MaintSweep.tick(server);
        }
        if (TRIPS.isEmpty()) {
            if (++rearmTimer < REARM_INTERVAL_TICKS) {
                return;
            }
            rearmTimer = 0;
            reArmAfterRestart(server);   // boarded carts that lost their trip to a restart
            if (TRIPS.isEmpty()) {
                return;
            }
        }

        List<UUID> finished = new ArrayList<>();

        // Resolve each cart with a SMALL AABB query around its last known
        // position (getEntity(UUID) proved unreliable in 26.3; full-dimension
        // scans every tick cost measurable TPS).
        var typeTest = net.minecraft.world.level.entity.EntityTypeTest
                .<net.minecraft.world.entity.Entity, AbstractMinecart>forClass(AbstractMinecart.class);
        for (Trip t : TRIPS.values()) {
            ServerLevel level = levelFor(server, t.dim);
            if (level == null) {
                finished.add(t.cart);
                continue;
            }
            AABB hunt = new AABB(t.lastPos).inflate(3.5, 3.5, 3.5);
            List<AbstractMinecart> near = new ArrayList<>();
            level.getEntities(typeTest, hunt,
                    c -> c.getUUID().equals(t.cart), near);
            AbstractMinecart cart = near.isEmpty() ? null : near.getFirst();
            if (cart == null) {
                // Freshly added entities can lag a few ticks behind the section
                // store - grant a grace period before declaring the cart lost.
                if (++t.unseenTicks <= 40) {
                    continue;
                }
                LOGGER.warn("[smartrails] Trip lost: cart gone ({} -> {}) wanted={}",
                        t.station, t.dest, t.cart);
                finished.add(t.cart);
                continue;
            }
            t.unseenTicks = 0;
            t.lastPos = cart.blockPosition();
            if (!advance(server, level, t, cart)) {
                finished.add(t.cart);
            }
        }
        for (UUID id : finished) {
            Trip rt = TRIPS.get(id);
            if (rt != null && rt.cargo) {
                ServerLevel rl = levelFor(server, rt.dim);
                if (rl != null) {
                    releaseCargoTickets(rl, rt);
                }
            }
            TRIPS.remove(id);
        }
    }

    /**
     * After a server restart trips are gone, but a player may already sit in a
     * platform cart whose station has a confirmed destination - re-arm those.
     */
    private static void reArmAfterRestart(MinecraftServer server) {
        NetworkState ns = NetworkState.INSTANCE;
        for (Station st : ns.allStations()) {
            if (st.selected == null || hasTripForStation(st.name)) {
                continue;
            }
            ServerLevel level = levelFor(server, st.dim);
            if (level == null) {
                continue;
            }
            List<AbstractMinecart> staged = findStoppedCartsNear(level, st.anchor(), 4.0, 2.0);
            if (!staged.isEmpty() && staged.getFirst().getFirstPassenger() != null) {
                createTrip(level, st, staged.getFirst(), null);
            }
        }
        ns.saveIfDirty(server);
    }

    private static boolean advance(MinecraftServer server, ServerLevel level, Trip t,
                                   AbstractMinecart cart) {
        Vec3 pos = cart.position();
        Vec3 vel = cart.getDeltaMovement();
        double speed = vel.horizontalDistance();
        // Hard governor: vanilla's new cart behaviour can AMPLIFY externally
        // set velocities (observed runaway 0.3 -> 2.7 b/t saturating); clamp
        // every tick so no feedback loop can ever run away.
        double ceiling = HighSpeed.enabled
                ? Math.max(HARD_SPEED_CAP, HighSpeed.maxBpt() * 1.1)
                : HARD_SPEED_CAP;
        if (speed > ceiling) {
            double scale = ceiling / speed;
            vel = new Vec3(vel.x * scale, vel.y, vel.z * scale);
            cart.setDeltaMovement(vel);
            speed = ceiling;
        }
        double dd = distTo(t.destAnchor, pos);
        t.maxTravel = Math.max(t.maxTravel, dd);

        LOGGER.debug("[ADVANCE] {} phase={} pos={} speed={}", t.station, t.phase, cart.blockPosition(), speed);
        switch (t.phase) {
            case BOARD_WAIT -> {
                // Platform lock: a freshly spawned/waiting cart must not
                // wander off on ambient rails (powered segments, grades)
                // before anyone boards.
                cart.setDeltaMovement(new Vec3(0, Math.min(vel.y, 0), 0));
                if (cart.getFirstPassenger() != null) {
                    if (!t.seenPassenger) {
                        t.seenPassenger = true;
                        t.boardCountdown = BOARD_DELAY_TICKS;
                        level.playSound(null, cart.blockPosition(),
                                SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.7f, 1.4f);
                    }
                    if (--t.boardCountdown <= 0) {
                        // Boarding sanity: the ridden cart must actually sit
                        // at the origin platform - a stray cart mounting
                        // elsewhere would launch a nonsensical route.
                        if (distTo(t.originAnchor, pos) > 8) {
                            TRIPS.remove(t.cart);
                            abort(server, t, cart, "Lore ist nicht am Startbahnhof '"
                                    + t.station + "' - Fahrt verworfen");
                            return false;
                        }
                        t.phase = Phase.RUN;
                        Vec3 kick = new Vec3(t.launchDir.getStepX() * LAUNCH_SPEED, vel.y,
                                t.launchDir.getStepZ() * LAUNCH_SPEED);
                        cart.setDeltaMovement(kick);
announceOwner(server, t, cart, Messages.t("trip.depart", t.station, t.dest));
                    }
                } else {
                    t.seenPassenger = false;   // rider jumped out again before launch
                }
                return true;
            }
            case RUN -> {
                if (LOGGER.isDebugEnabled() && (t.stallTicks + t.captureTicks) % 10 == 0) {
                    LOGGER.debug("[TRACE] {} pos={} vel={}", t.station,
                            cart.blockPosition(), cart.getDeltaMovement());
                }

                // Rider bailed out mid-route: park the cart and give them a
                // reboard window instead of killing the trip outright.
                if (t.seenPassenger && cart.getFirstPassenger() == null) {
                    t.phase = Phase.PAUSED;
                    t.pauseTicksLeft = REBOARD_WINDOW_TICKS;
                    t.parkPos = cart.position();
                    cart.setDeltaMovement(Vec3.ZERO);
                    announceOwner(server, t, cart, Messages.t("trip.paused", REBOARD_SECONDS));
                    return true;
                }

                if (t.depositGrace > 0) {
                    t.depositGrace--;
                }
                if (t.cargo) {
                    refreshCargoTickets(level, t, cart);
                }

                // ---- High-speed governor -------------------------------
                // Charged speed climbs on powered rails, holds on plain
                // rails, bleeds after the hold, collapses on unpowered
                // boosters, and yields to vanilla pace on curves / detector /
                // activator rails / slopes - the lookahead slows the cart
                // down BEFORE the hazard and lets it resume afterwards.
                double target = t.cruiseBpt;
                boolean hsKinematic = false;
                // Cell bookkeeping (the watchdog's odometer) runs REGARDLESS
                // of the high-speed module: disabling the boost unplugged the
                // meter and healthy rides died on a fake 5-second stall.
                hsSweep(level, t, pos);
                if (HighSpeed.enabled && !t.capturing && t.depositGrace <= 0 && cart.isOnRails()) {
                    if (t.hsSpeed >= 0) {
                        target = t.hsSpeed;
                        // Planned junction ahead: brake zone must dampen the
                        // TARGET (the kinematic transport obeys targets, not
                        // velocity clamps downstream).
                        if (!t.plan.isEmpty()) {
                            BlockPos nj = t.plan.getFirst().junctionPos();
                            double ndx = (nj.getX() + 0.5) - pos.x;
                            double ndz = (nj.getZ() + 0.5) - pos.z;
                            double ndist = Math.sqrt(ndx * ndx + ndz * ndz);
                            if (ndist < JUNCTION_BRAKE_ZONE) {
                                target = Math.min(target, JUNCTION_SPEED_CAP);
                            }
                        }
                        HighSpeed.Kind here =
                                HighSpeed.classify(level, cart.blockPosition());
                        int[] haz = HighSpeed.isHazardOrSlope(here)
                                ? new int[]{0, here.ordinal()}
                                : HighSpeed.hazardAhead(level, cart.blockPosition(),
                                        t.lastDir == null ? Direction.NORTH : t.lastDir,
                                        HighSpeed.lookaheadCells);
                        if (haz[0] >= 0) {
                            if (haz[1] == HighSpeed.Kind.SLOPE.ordinal()) {
                                // STRICT spec (user decision): ramps AND their
                                // approach run at vanilla pace - no blended
                                // falloff, hard cap well before the gradient.
                                target = HighSpeed.floorBpt();
                                t.descentMode = true;   // freeze charging early
                            } else {
                                double baseFloor = HighSpeed.floorBpt();
                                double frac = Math.clamp(
                                        haz[0] / (double) HighSpeed.lookaheadCells, 0, 1);
                                double env = baseFloor
                                        + (target - baseFloor) * frac * frac;
                                if (env < target) {
                                    target = env;
                                }
                            }
                        }
                        if (t.descentMode) {
                            // Whole descent stretch at vanilla pace (spec):
                            // no kinetic bursts on the flats in between.
                            target = Math.min(target, HighSpeed.floorBpt());
                        }
                        if (speed > target) {
                            double sc = target / speed;
                            cart.setDeltaMovement(new Vec3(vel.x * sc, vel.y, vel.z * sc));
                            vel = cart.getDeltaMovement();
                            speed = target;
                        }
                        // 26.x vanilla clamps cart MOTION to 0.4 b/t inside the
                        // cart's own tick (old behaviour bakes the literal) -
                        // velocity alone can never express high speed. On flat
                        // straight cells the mod transports the cart itself.
                        hsKinematic = target > 0.05
                                && hsKinematicStep(level, t, cart, pos, target);
                        if (!hsKinematic && t.hsWasKinematic) {
                            // Leaving kinematic mode (hazard/slope/blocker
                            // ahead): hand the momentum over to vanilla so
                            // the cart does not die in place.
                            Direction hd = t.lastDir;
                            if (hd != null) {
                                double hv = Math.min(target, HighSpeed.floorBpt());
                                cart.setDeltaMovement(new Vec3(
                                        hd.getStepX() * hv, vel.y, hd.getStepZ() * hv));
                            }
                        }
                        t.hsWasKinematic = hsKinematic;
                    }
                }

                // Rider telemetry (action bar) + sparse train trace.
                if (server.getTickCount() % 5 == 0
                        && cart.getFirstPassenger() instanceof ServerPlayer rider) {
                    rider.sendOverlayMessage(Component.literal(String.format(
                            "\u00a7e\u26a1 %.1f m/s \u00a77| \u00a7f%s\u00a77 | \u00a77%d m/s Soll",
                            speed * 20.0, t.phase.name(),
                            (target > 0 ? (int) Math.round(target * 20.0) : 0))));
                }
                if (server.getTickCount() % 20 == 0) {
                    LOGGER.info("[TRAIN] {}>{} pos={} spd={} tgt={} hs={} kin={} dir={} phase={}",
                            t.station, t.dest, cart.blockPosition(),
                            String.format("%.2f", speed), String.format("%.2f", target),
                            String.format("%.2f", t.hsSpeed), hsKinematic, t.lastDir, t.phase);
                }

                // ---- Path following: the route IS the track ---------------
                // Advance the path cursor and aim at a look-ahead cell. This
                // replaces greedy goal bearings (which oscillate on detours).
                if (t.path != null && !t.path.isEmpty()) {
                    BlockPos bc = cart.blockPosition();
                    while (t.pathIdx + 1 < t.path.size() && t.path.get(t.pathIdx + 1).equals(bc)) {
                        t.pathIdx++;
                    }
                    // Backward recognition: zigzag/shuttle legs legitimately
                    // revisit earlier path cells - a forward-only cursor
                    // strands the reference and starves the path-preference
                    // (the stale straight-match wins, restoring fights it,
                    // the cart shuttles the whole stretch).
                    for (int k = Math.min(3, t.pathIdx); k >= 1; k--) {
                        if (t.path.get(t.pathIdx - k).equals(bc)) {
                            t.pathIdx -= k;
                            break;
                        }
                    }
                    for (int k = 1; k <= 3 && t.pathIdx + k < t.path.size(); k++) {
                        if (t.path.get(t.pathIdx + k).equals(bc)) {
                            t.pathIdx += k;
                            break;
                        }
                    }
                    // Off-path rescue: vanilla physics occasionally rejects a
                    // tight U-turn (corner bounce) and throws the cart onto a
                    // parallel wrong-way arm - the rider experiences an
                    // endless loop. When the cart is neither on the path nor
                    // heading towards it, shunt it gently back onto the
                    // nearest routed cell (<=0.9 blk/tick, aligned motion).
                    // Cooldown prevents thrash if a shunt does not settle.
                    if (!t.capturing) {
                        if (t.rescueCd > 0) {
                            t.rescueCd--;
                        }
                        int pi = -1;
                        for (int k = Math.max(0, t.pathIdx - 2);
                             k <= Math.min(t.path.size() - 1, t.pathIdx + 6); k++) {
                            if (t.path.get(k).equals(bc)) {
                                pi = k;
                                break;
                            }
                        }
                        if (pi >= 0) {
                            t.pathIdx = pi;
                        } else {
                            int best = -1;
                            double bd = Double.MAX_VALUE;
                            for (int k = Math.max(t.pathIdx, 0);   // forward-only: a rewind would yank the cart back
                                 k <= Math.min(t.path.size() - 1, t.pathIdx + 20); k++) {
                                BlockPos pc = t.path.get(k);
                                double dx = (pc.getX() + 0.5) - pos.x;
                                double dz = (pc.getZ() + 0.5) - pos.z;
                                double dy = (pc.getY() - pos.y) * 4;
                                double d = dx * dx + dz * dz + dy * dy;
                                if (d < bd) {
                                    bd = d;
                                    best = k;
                                }
                            }
                            if (best >= 0 && bd < 256 && t.rescueCd <= 0) {   // sane reach: 16 blocks
                                BlockPos tc = t.path.get(best);
                                double sx = (tc.getX() + 0.5) - pos.x;
                                double sz = (tc.getZ() + 0.5) - pos.z;
                                double sd = Math.sqrt(sx * sx + sz * sz);
                                double fleeDot = sx * vel.x + sz * vel.z;
                                if (sd > 1.5 && fleeDot < 0) {
                                    double mv = Math.min(sd, 0.9);
                                    // Conservative y: never lift/drop more than
                                    // one level blindly - stacking terrains made
                                    // blind seatings pop the cart out again.
                                    double ty = Math.abs(tc.getY() - pos.y) <= 1
                                            ? pos.y : tc.getY() + 0.35;
                                    cart.setPos(pos.x + sx / sd * mv, ty,
                                            pos.z + sz / sd * mv);
                                    cart.setDeltaMovement(new Vec3(
                                            sx / sd * 0.4, 0, sz / sd * 0.4));
                                    t.hsLastCell = tc;
                                    t.pathIdx = best;
                                    t.rescueCd = 10;
                                    LOGGER.info("[KIN] rescue-shunt to {}",
                                            tc.toShortString());
                                }
                            }
                        }
                    }
                    // On-path direction guard: vanilla physics can reject a
                    // slope-entry corner and hurl the cart back down the very
                    // rails it came up - the cart stays ON the path but
                    // travels the wrong way (endless yo-yo). Restore motion
                    // along the path heading whenever it clearly opposes it.
                    // STRAIGHTS ONLY: at curve cells the vector-to-next-cell
                    // cuts the corner and shoves the cart INTO the bend -
                    // the observed self-perpetuating tug-of-war. Curves are
                    // the kinematic link-picker's jurisdiction.
                    RailShape guardShape = RailShapes.shapeAt(level, cart.blockPosition());
                    boolean guardStraight = guardShape != null
                            && (guardShape == RailShape.NORTH_SOUTH
                            || guardShape == RailShape.EAST_WEST
                            || guardShape.name().startsWith("ASCENDING"));
                    if (guardStraight && t.pathIdx + 1 < t.path.size()) {
                        if (t.restoreCd > 0) {
                            t.restoreCd--;   // throttle: no per-tick hammering
                        } else {
                        BlockPos nb = t.path.get(t.pathIdx + 1);
                        double wx = (nb.getX() + 0.5) - pos.x;
                        double wz = (nb.getZ() + 0.5) - pos.z;
                        double wd = Math.sqrt(wx * wx + wz * wz);
                        if (wd > 0.001) {
                            wx /= wd;
                            wz /= wd;
                            double vmag = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                            if (vmag > 0.05 && (wx * vel.x + wz * vel.z) / vmag < -0.25) {
                                double rv = Math.min(vmag, HighSpeed.floorBpt());
                                cart.setDeltaMovement(new Vec3(wx * rv, vel.y, wz * rv));
                                vel = cart.getDeltaMovement();
                                t.restoreCd = 5;
                                LOGGER.info("[KIN] direction restored along path");
                            }
                        }
                        }
                    }
                }
                BlockPos wpCell = (t.path != null && !t.path.isEmpty()
                        && t.pathIdx + 1 < t.path.size())
                        ? t.path.get(Math.min(t.pathIdx + 4, t.path.size() - 1))
                        : null;

                // Progress watchdog: progress = CREDITED RAIL CELLS (the
                // sweep counts every newly entered cell). Waypoint distances
                // mislead once the kinematic transport takes curves by track
                // layout - a cell-flow stall is the honest "not moving".
                if (t.cellsCredited != t.watchCheckpoint) {
                    t.watchCheckpoint = t.cellsCredited;
                    t.noProgressTicks = 0;
                } else if (++t.noProgressTicks > PROGRESS_TIMEOUT_TICKS && !t.capturing) {
                    abort(server, t, cart, "kommt nicht weiter (Weg blockiert oder Geometrie unklar)");
                    return false;
                }

                // Junction approach: slam the brakes so the manoeuvre happens at
                // switch speed, not at powered-rail speed (0.8 b/t blasts
                // straight through any deposit logic).
                if (!t.capturing && !t.plan.isEmpty()) {
                    BlockPos nj = t.plan.getFirst().junctionPos();
                    double ndx = (nj.getX() + 0.5) - pos.x;
                    double ndz = (nj.getZ() + 0.5) - pos.z;
                    double ndist = Math.sqrt(ndx * ndx + ndz * ndz);
                    if (ndist < JUNCTION_BRAKE_ZONE && speed > JUNCTION_SPEED_CAP) {
                        double scale = JUNCTION_SPEED_CAP / speed;
                        cart.setDeltaMovement(new Vec3(vel.x * scale, vel.y, vel.z * scale));
                    }
                }

                // Cruise control: the mod supplies propulsion so networks do not
                // need powered rails on every metre (26.x rail drag is severe).
                // SLOPES ARE EXCLUDED: the cruise's goal-bearing hijacked carts
                // mid-climb (bearing pointed against the gradient - the tug of
                // war with the kinematic handover stalled ascents entirely).
                RailShape cruiseCellShape = RailShapes.shapeAt(level, cart.blockPosition());
                boolean onClimbCell = cruiseCellShape != null
                        && cruiseCellShape.name().startsWith("ASCENDING");
                if (!t.capturing && t.depositGrace <= 0 && !hsKinematic && !onClimbCell && cart.isOnRails()) {
                    Vec3 wp = wpCell != null
                            ? new Vec3(wpCell.getX() + 0.5, wpCell.getY(), wpCell.getZ() + 0.5)
                            : (t.plan.isEmpty()
                                    ? new Vec3(t.destAnchor.getX() + 0.5, t.destAnchor.getY(),
                                            t.destAnchor.getZ() + 0.5)
                                    : new Vec3(t.plan.getFirst().junctionPos().getX() + 0.5,
                                            t.plan.getFirst().junctionPos().getY(),
                                            t.plan.getFirst().junctionPos().getZ() + 0.5));
                    double wdx = wp.x - pos.x;
                    double wdz = wp.z - pos.z;
                    double wl = Math.sqrt(wdx * wdx + wdz * wdz);
                    if (wl > 0.5) {
                        double ux = wdx / wl;
                        double uz = wdz / wl;
                        // Track-guided steering: follow the TRACK, not the
                        // bearing. Straight ahead wins; a single remaining
                        // exit (curve/forced turn) is taken as-is; only at a
                        // real divergence does the waypoint bearing decide.
                        // Greedy bearings oscillate on detours (the cart must
                        // sometimes travel AWAY from the goal first).
                        Direction opp = t.lastDir == null ? null : t.lastDir.getOpposite();
                        Direction chosen = null;
                        // PATH-FIRST: the routed next cell dictates the heading
                        // (ring-runners circled because "straight ahead" beat
                        // the route). Track heuristics only as fallback.
                        if (t.path != null && t.pathIdx + 1 < t.path.size()) {
                            BlockPos pn = t.path.get(t.pathIdx + 1);
                            int pdx = pn.getX() - cart.blockPosition().getX();
                            int pdz = pn.getZ() - cart.blockPosition().getZ();
                            for (Direction d : new Direction[]{
                                    Direction.EAST, Direction.WEST,
                                    Direction.SOUTH, Direction.NORTH}) {
                                if (d.getStepX() == Integer.signum(pdx)
                                        && d.getStepZ() == Integer.signum(pdz)) {
                                    chosen = d;
                                    break;
                                }
                            }
                        }
                        java.util.ArrayList<Direction> opts = new java.util.ArrayList<>();
                        if (chosen == null) {
                            for (RailShapes.Link l : RailShapes.connections(level, cart.blockPosition())) {
                                if (l.dir() == opp) {
                                    continue;
                                }
                                opts.add(l.dir());
                                if (l.dir() == t.lastDir) {
                                    chosen = l.dir();
                                    break;
                                }
                            }
                        }
                        if (chosen == null) {
                            if (opts.size() == 1) {
                                chosen = opts.getFirst();          // forced curve/exit
                            } else if (!opts.isEmpty()) {
                                double ubx = wl > 0.001 ? wdx / wl : 0;
                                double ubz = wl > 0.001 ? wdz / wl : 0;
                                double bestDot = -2;
                                for (Direction cand : opts) {
                                    double dot = cand.getStepX() * ubx + cand.getStepZ() * ubz;
                                    if (dot > bestDot) {
                                        bestDot = dot;
                                        chosen = cand;
                                    }
                                }
                            } else {
                                chosen = opp;                       // dead end: turn around
                            }
                        }
                        double fx = chosen.getStepX();
                        double fz = chosen.getStepZ();
                        t.lastDir = chosen;
                        double along = vel.x * fx + vel.z * fz;
                        double nv;
                        if (along < -0.1) {
                            nv = 0.25;                       // wrong way - turn around
                        } else if (speed < 0.05) {
                            nv = 0.25;                       // restarted from standstill
                        } else if (speed < target) {
                            // High-speed servo: vanilla drag (x2/3 per tick)
                            // defeats gentle blending at 1.7 b/t - PIN the
                            // velocity to the governed target each tick.
                            nv = target > CRUISE_SPEED * 1.5
                                    ? target
                                    : Math.min(target, speed + Math.max(0.03, (target - speed) * 0.3));
                        } else {
                            nv = -1;                          // fast enough (or downhill)
                        }
                        if (nv > 0) {
                            cart.setDeltaMovement(new Vec3(fx * nv, vel.y, fz * nv));
                        }
                    }
                }

                // Gradient anti-stall: vanilla's slope mount hesitates when a
                // cart enters a gradient too slowly (hang + rearward creep at
                // the second rail). Firm push along the travel heading until
                // it is rolling again - but never inside a junction approach
                // zone (there the deliberate slow-down must win).
                boolean juncNear = false;
                if (!t.plan.isEmpty()) {
                    BlockPos nj2 = t.plan.getFirst().junctionPos();
                    juncNear = Math.abs(nj2.getX() - pos.x) + Math.abs(nj2.getZ() - pos.z) < 6;
                }
                BlockPos aheadCell = t.lastDir == null
                        ? cart.blockPosition() : cart.blockPosition().relative(t.lastDir);
                RailShape aheadShape = RailShapes.shapeAt(level, aheadCell);
                boolean slopeTouch = (cruiseCellShape != null
                        && cruiseCellShape.name().startsWith("ASCENDING"))
                        || (aheadShape != null && aheadShape.name().startsWith("ASCENDING"));
                if (slopeTouch
                        && !t.capturing && t.depositGrace <= 0 && !juncNear
                        && (speed < 0.3 || t.noProgressTicks > 10)
                        && t.lastDir != null) {
                    // Treadmill killer: a cart can roll 0.8 m/s against a slope
                    // mount that refuses entry - fast AND going nowhere. Fire
                    // on low speed OR stalled cell-flow alike.
                    cart.setDeltaMovement(new Vec3(t.lastDir.getStepX() * 0.4, vel.y,
                            t.lastDir.getStepZ() * 0.4));
                    LOGGER.info("[GRAD] anti-stall push at {} spd={} stall={}",
                            cart.blockPosition().toShortString(), String.format("%.2f", speed),
                            t.noProgressTicks);
                }

                if (t.plan.isEmpty()) {
                    // Past the last junction: glide until the platform zone, then brake.
                    if (dd < BRAKE_START_DIST && t.maxTravel > 3) {
                        t.phase = Phase.BRAKE;
                    }
                    return true;
                }
                Topology.Decision d = t.plan.getFirst();
                BlockPos jp = d.junctionPos();
                Vec3 jc = new Vec3(jp.getX() + 0.5, jp.getY(), jp.getZ() + 0.5);
                double dx = pos.x - jc.x;
                double dy = pos.y - (jc.y + 0.5);
                double dz = pos.z - jc.z;
                double dist = Math.sqrt(dx * dx + dz * dz + dy * dy * 2);

                // -------- capture mode: drag the cart onto the planned branch --------
                if (t.capturing) {
                    if (t.captureTicks == 0) {
                        LOGGER.info("[CAPTURE] {} junction={} exit={} exitDy={} opens={} hubShape={}",
                                t.station, jp, d.exit(), d.exitDy(),
                                Topology.opensTowards(level, jp, d.exit()),
                                RailShapes.shapeAt(level, jp));
                    }
                    double sp = Math.clamp(speed, MIN_JUNCTION_SPEED, HARD_SPEED_CAP);
                    Vec3 ex = new Vec3(d.exit().getStepX(), 0, d.exit().getStepZ());
                    cart.setDeltaMovement(new Vec3(ex.x * sp, vel.y, ex.z * sp));

                    // Closed face? The hub's vanilla shape refuses this exit
                    // (e.g. curve points the other way) - soft steering cannot
                    // win against the rail channel; transfer the cart onto the
                    // branch cell directly (TrainCarts-style).
                    BlockPos branchCell = jp.relative(d.exit()).above(d.exitDy());
                    boolean sameColumn = cart.blockPosition().getX() == jp.getX()
                            && cart.blockPosition().getZ() == jp.getZ()
                            && Math.abs(cart.blockPosition().getY() - jp.getY()) <= 1;
                    if (sameColumn && !Topology.opensTowards(level, jp, d.exit())) {
                        cart.setPos(branchCell.getX() + 0.5, branchCell.getY() + 0.3,
                                branchCell.getZ() + 0.5);
                        cart.setDeltaMovement(new Vec3(ex.x * 0.3, 0, ex.z * 0.3));
                    }
                    // Slide horizontally onto the lane that runs from the junction
                    // centre into the branch cell.
                    double tproj = (pos.x - jc.x) * ex.x + (pos.z - jc.z) * ex.z;
                    tproj = Math.clamp(tproj, -2.0, 2.0);
                    double lx = jc.x + ex.x * tproj;
                    double lz = jc.z + ex.z * tproj;
                    double ty = jc.y + 0.5;
                    double ny = Math.abs(pos.y - ty) > 0.3
                            ? pos.y + (ty - pos.y) * 0.2
                            : pos.y;
                    cart.setPos(pos.x + (lx - pos.x) * 0.5, ny, pos.z + (lz - pos.z) * 0.5);

                    // Consume the decision once deposited (branch cell, one past
                    // it) or after a short grace - cruising then shepherds the
                    // cart onward and self-corrects instead of aborting.
                    BlockPos releaseCell = branchCell.relative(d.exit());
                    boolean onBranch = cart.blockPosition().getX() == branchCell.getX()
                            && cart.blockPosition().getZ() == branchCell.getZ();
                    boolean pastBranch = cart.blockPosition().getX() == releaseCell.getX()
                            && cart.blockPosition().getZ() == releaseCell.getZ();
                    if (onBranch || pastBranch || ++t.captureTicks > 8) {
                        t.capturing = false;
                        t.plan.removeFirst();
                        t.nearedJunction = false;
                        t.stallTicks = 0;
                        t.lastDir = d.exit();
                        // Verify the deposit: cart must now sit on the branch.
                        if (!onBranch && !pastBranch) {
                            cart.setPos(branchCell.getX() + 0.5, branchCell.getY() + 0.3,
                                    branchCell.getZ() + 0.5);
                            cart.setDeltaMovement(new Vec3(ex.x * 0.25, 0, ex.z * 0.25));
                        }
                        t.depositGrace = DEPOSIT_GRACE_TICKS;
                        t.lastWaypointDist = Double.MAX_VALUE;
                        t.noProgressTicks = 0;
                    }
                    return true;
                }

                if (dist < APPROACH_RADIUS) {
                    // Engage capture shortly before the junction cell.
                    t.capturing = true;
                    t.captureTicks = 0;
                    return true;
                }
                if (t.nearedJunction && dist > MISSED_RADIUS) {
                    abort(server, t, cart, Messages.t("reason.off-route"));
                    return false;
                }
                if (dist < MISSED_RADIUS) {
                    t.nearedJunction = true;
                }

                // Stall watchdog.
                if (speed < 0.02) {
                    if (++t.stallTicks > STALL_LIMIT_TICKS) {
                        abort(server, t, cart, Messages.t("reason.stuck"));
                        return false;
                    }
                } else {
                    t.stallTicks = 0;
                }
                return true;
            }
            case PAUSED -> {
                // Waiting for the disembarked rider to come back. The cart is
                // a statue: vanilla slope-slides would otherwise let it roll
                // away (powered ramps!) and out of the tracker.
                if (t.parkPos != null) {
                    cart.setPos(t.parkPos.x, t.parkPos.y, t.parkPos.z);
                }
                cart.setDeltaMovement(Vec3.ZERO);
                if (cart.getFirstPassenger() != null) {
                    t.phase = Phase.RUN;
                    t.stallTicks = 0;
                    t.noProgressTicks = 0;
                    t.lastWaypointDist = Double.MAX_VALUE;
                    t.parkPos = null;
                    announceOwner(server, t, cart, Messages.t("trip.resume"));
                    return true;
                }
                if (--t.pauseTicksLeft <= 0) {
                    // Window expired: dismantle politely.
                    for (var passenger : List.copyOf(cart.getPassengers())) {
                        passenger.stopRiding();
                    }
                    cart.discard();
                    announceOwner(server, t, null, Messages.t("trip.cancel.frame",
                            t.station, t.dest, Messages.t("reason.not-reboarded")));
                    return false;
                }
                return true;
            }
            case BRAKE -> {
                double dist = distTo(t.destAnchor, pos);

                double hs = vel.horizontalDistance();
                boolean stopped = !Double.isFinite(hs) || hs < STOP_SPEED;
                if (stopped) {
                    // Came to a halt: arrival when reasonably near the platform
                    // (slight overrun tolerated), otherwise the target was missed.
                    if (dist < 8) {
                        // Park neatly on the platform anchor before dissolving,
                        // so passengers disembark ON the platform.
                        cart.setPos(t.destAnchor.getX() + 0.5, t.destAnchor.getY() + 0.3,
                                t.destAnchor.getZ() + 0.5);
                        cart.setDeltaMovement(Vec3.ZERO);
                        level.playSound(null, cart.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(),
                                SoundSource.BLOCKS, 0.9f, 1.2f);
                        for (var passenger : List.copyOf(cart.getPassengers())) {
                            passenger.stopRiding();
                        }
                        if (t.cargo) {
                            // Freight: the chest cart STAYS (goods delivery) -
                            // discarding it would vaporise the shipment.
                            announceOwner(server, t, null, Messages.t("cargo.delivered", t.dest));
                        } else {
                            cart.discard();
                            announceOwner(server, t, null, Messages.t("trip.arrive", t.dest));
                        }
                        return false; // done
                    }
                    abort(server, t, cart, Messages.t("reason.missed"));
                    return false;
                }
                if (dist < BRAKE_START_DIST && t.maxTravel > 3) {
                    cart.setDeltaMovement(new Vec3(vel.x * BRAKE_FACTOR, vel.y, vel.z * BRAKE_FACTOR));
                } else if (dist > 14 && t.maxTravel > 3) {
                    abort(server, t, cart, Messages.t("reason.missed"));
                    return false;
                }
                return true;
            }
        }
        return true;
    }

    private static void abort(MinecraftServer server, Trip t, AbstractMinecart cart, String why) {
        cart.setDeltaMovement(Vec3.ZERO);
        announceOwner(server, t, cart, Messages.t("trip.cancel.frame", t.station, t.dest,
                why + " @ " + cart.blockPosition().toShortString()));
        LOGGER.debug("[smartrails] Trip aborted: {}", why);
    }

    private static void announceOwner(MinecraftServer server, Trip t, AbstractMinecart cart, String msg) {
        LOGGER.info("[smartrails] {}", msg.replaceAll("(\u00a7.)", ""));
        ServerPlayer p = t.ownerId != null ? server.getPlayerList().getPlayer(t.ownerId) : null;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
            return;
        }
        if (cart != null) {
            // Owner unknown/offline - brief whoever is riding.
            for (var passenger : cart.getPassengers()) {
                if (passenger instanceof ServerPlayer rider) {
                    rider.sendSystemMessage(Component.literal(msg));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // High-speed sweeping
    // ------------------------------------------------------------------

    /**
     * Kinematic high-speed transport along a flat, straight rail cell.
     *
     * Vanilla's cart tick clamps motion to 0.4 b/t (the old behaviour bakes
     * the literal into bytecode), so a governed speed above that can only be
     * realised by positioning the cart directly. Preconditions: current cell
     * is a straight rail, the next cell along the travel direction is rail on
     * the same level, and the path is free of obstacles. Returns false to
     * leave the cart in vanilla physics for this tick (hazards, slopes, ends
     * of track, blockers) - the governor's clamp keeps it civil meanwhile.
     */
    private static boolean hsKinematicStep(ServerLevel level, Trip t, AbstractMinecart cart,
                                           Vec3 pos, double target) {
        Direction dir = t.lastDir;
        if (dir == null || dir.getAxis() == Direction.Axis.Y) {
            kinFail("nodir");
            return false;
        }
        BlockPos cell = cart.blockPosition();
        // Forward link: the ROUTED path cell wins (the route IS the track);
        // otherwise straight match, otherwise the curve exit (anything that is
        // not the way we came).
        BlockPos nextPath = (t.path != null && !t.path.isEmpty()
                && t.pathIdx + 1 < t.path.size()) ? t.path.get(t.pathIdx + 1) : null;
        RailShapes.Link fwd = null;
        RailShapes.Link alt = null;
        String fwdSrc = "none";
        for (RailShapes.Link l : RailShapes.connections(level, cell)) {
            if (l.dir() == dir.getOpposite()) {
                continue;
            }
            // Never step back into the cell we just left - on a curve cell the
            // "straight" link points at the approach cell and would reverse us.
            if (l.pos().equals(t.hsLastCell)) {
                continue;
            }
            if (nextPath != null && l.pos().equals(nextPath)) {
                fwd = l;
                fwdSrc = "path";
                break;
            }
            // Tolerant path match: the routed cell and the contact-derived
            // link can disagree by one (walk steps vs curve shoulders) - a
            // near-miss must still win over the dumb straight heuristic,
            // otherwise the cart takes the wrong arm at bends.
            if (nextPath != null
                    && Math.abs(l.pos().getX() - nextPath.getX())
                    + Math.abs(l.pos().getY() - nextPath.getY())
                    + Math.abs(l.pos().getZ() - nextPath.getZ()) <= 1) {
                fwd = l;
                fwdSrc = "path~1";
                break;
            }
            if (l.dir() == dir) {
                fwd = l;
                fwdSrc = "straight";
                break;
            }
            if (alt == null) {
                alt = l;
                fwdSrc = "alt";
            }
        }
        if (fwd == null) {
            fwd = alt;
        }
        if (fwd == null) {
            kinFail("no-forward-link");
            return false;
        }
        // Planned switch cell: OBEY the routed decision (consume it), never
        // the mere track layout - the graph knows which branch is correct.
        if (!t.plan.isEmpty()) {
            Topology.Decision d = t.plan.getFirst();
            if (d.junctionPos().equals(cell)) {
                RailShapes.Link obey = null;
                for (RailShapes.Link l : RailShapes.connections(level, cell)) {
                    if (l.dir() == d.exit() && l.dy() == d.exitDy()) {
                        obey = l;
                        break;
                    }
                }
                if (obey != null) {
                    fwd = obey;
                } else {
                    // Geometry refuses the exit: force the branch cell
                    // (TrainCarts-style transfer, same as the capture does).
                    BlockPos branch = cell.relative(d.exit()).above(d.exitDy());
                    fwd = new RailShapes.Link(branch.immutable(), d.exit(), d.exitDy());
                }
                t.plan.removeFirst();
                t.nearedJunction = false;
                t.lastWaypointDist = Double.MAX_VALUE;
                t.noProgressTicks = 0;
                t.stallTicks = 0;
                LOGGER.info("[KIN] took planned switch {} exit {} (remaining decisions: {})",
                        cell.toShortString(), d.exit(), t.plan.size());
            }
        }
        // Slow passages: corners, detector/activator cells at vanilla pace;
        // slopes at the climbing allowance (a full-speed diagonal step would
        // be a catapult).
        double eff = target;
        HighSpeed.Kind ck = HighSpeed.classify(level, cell);
        if (ck == HighSpeed.Kind.CURVE) {
            // Curves belong to vanilla: its corner navigation is smooth, and
            // the lookahead env-brake guarantees a cold entry (the historic
            // bounce was a hot-entry artefact). Kinematic hops through the
            // serpentine curve rows (7-8 cells!) were pure jerk.
            kinFail("curve-to-vanilla");
            return false;
        }
        if (ck == HighSpeed.Kind.CURVE || ck == HighSpeed.Kind.DETECTOR
                || ck == HighSpeed.Kind.ACTIVATOR) {
            eff = Math.min(eff, HighSpeed.floorBpt());
        }
        RailShape cs = RailShapes.shapeAt(level, cell);
        boolean onSlope = cs != null && cs.name().startsWith("ASCENDING");
        // Ramp speed cap: vanilla pace on ALL ramps (spec) - the kinematic
        // ascent gets no extra allowance.
        if (fwd.dy() != 0 || onSlope) {
            eff = Math.min(eff, HighSpeed.floorBpt());
        }
        // Split ramp policy: ASCENTS (dy=+1) are driven kinematically with
        // vanilla-identical per-cell y-staging - handing an ascended cart to
        // vanilla at the ramp foot seats it BELOW the rising rail and it
        // falls through. DESCENTS (dy<0) stay in vanilla physics: gravity
        // rolls them flawlessly and relative y-tracking buries the cart.
        if (fwd.dy() < 0 || (onSlope && fwd.dy() != 1)) {
            kinFail("ramp-policy");
            return false;
        }
        // Displace along the CHOSEN LINK's direction - on curves that is the
        // exit, not the incoming travel direction (using the latter shoved
        // the cart off the rail end and froze it in the air).
        Direction md = fwd.dir();
        t.lastDir = md;
        if (kinDecideThrottle++ % 40 == 0) {
            LOGGER.info("[KIN] cell={} md={} eff={} hs={} src={}",
                    cell.toShortString(), md, String.format("%.2f", eff),
                    String.format("%.2f", t.hsSpeed),
                    fwdSrc);
        }
        // HYBRID motion: vanilla's tick will carry min(eff, 0.4) along the
        // delta-movement we set (rolling sound + smooth client interpolation
        // stay alive); the kinematic step tops up only the remainder, so the
        // total displacement per tick is exactly eff with no overshoot.
        double vanillaPart = Math.min(eff, VANILLA_CART_STEP);
        double kinAdv = Math.max(0, eff - vanillaPart);
        // Climb servo: vanilla slope gravity eats more of our dm than the
        // flat-budget predicts - carts slid rearward 0.01 b/t DESPITE the
        // direction-restore hammering every tick. Measure the ACTUAL advance
        // since the previous kinematic seat and top up the remainder, so the
        // average climb rate equals eff regardless of vanilla's tax.
        boolean climbing = fwd.dy() == 1 || onSlope;
        if (climbing && t.lastKinPos != null) {
            double adv = (pos.x - t.lastKinPos.x) * md.getStepX()
                    + (pos.z - t.lastKinPos.z) * md.getStepZ();
            kinAdv = Math.max(0, eff - Math.max(0, adv));
        }
        // Stride cap: the step must NOT carry the cart past the current
        // cell's boundary. Beyond it the single-cell plane model no longer
        // describes reality - the cart would seat under the next cell's rail
        // (rising or falling) and vanilla ejects it (yo-yos, buryings). Any
        // surplus speed simply carries into the next tick, where the fresh
        // cell's own plane model governs.
        double posAlongCap = md.getAxis() == Direction.Axis.X ? pos.x : pos.z;
        double cellMinCap = md.getAxis() == Direction.Axis.X ? cell.getX() : cell.getZ();
        boolean posDir = md.getStepX() + md.getStepZ() > 0;
        double bound = posDir ? (cellMinCap + 1 - posAlongCap) : (posAlongCap - cellMinCap);
        kinAdv = Math.min(kinAdv, Math.max(bound - 1e-3, 0));
        // Obstacle sweep over the distance about to be covered.
        double ex = pos.x + md.getStepX() * (eff + 0.6);
        double ez = pos.z + md.getStepZ() * (eff + 0.6);
        double minY = Math.min(pos.y, pos.y + fwd.dy()) - 0.2;
        double maxY = Math.max(pos.y, pos.y + fwd.dy()) + 1.6;
        double minX = Math.min(pos.x, ex) - 0.6;
        double maxX = Math.max(pos.x, ex) + 0.6;
        double minZ = Math.min(pos.z, ez) - 0.6;
        double maxZ = Math.max(pos.z, ez) + 0.6;
        AABB path = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        boolean blocked = !level.getEntities(cart, path,
                e -> !(e.getVehicle() != null && e.getVehicle() == cart)).isEmpty();
        if (blocked) {
            kinFail("blocked");
            return false;
        }
        double nx = pos.x + md.getStepX() * kinAdv;
        double nz = pos.z + md.getStepZ() * kinAdv;
        // Snap to the rail lane centre on the perpendicular axis.
        if (md.getAxis() == Direction.Axis.X) {
            nz = cell.getZ() + 0.5;
        } else {
            nx = cell.getX() + 0.5;
        }
        // Height: VANILLA-IDENTICAL cell staging - the cart rides at its
        // current cell's integer level, preserving only the fractional seat.
        // A continuous plane interpolation felt smoother but vanilla's slope
        // mounter ejects carts seated between its discrete checkpoints
        // (observed on prod: instant snap-back two cells down the line).
        double seatFrac = pos.y - Math.floor(pos.y);
        double pxBefore = pos.x, pzBefore = pos.z, pyBefore = pos.y;
        cart.setPos(nx, cell.getY() + seatFrac, nz);
        t.lastKinPos = new Vec3(nx, cell.getY() + seatFrac, nz);
        // TEMP [RAMP] forensic probe: every decision on slopes/climbs.
        if (fwd.dy() != 0 || onSlope) {
            LOGGER.info("[RAMP] cell={} md={} eff={} kinAdv={} pre=({},{},{}) post=({},{},{})",
                    cell.toShortString(), md, String.format("%.2f", eff),
                    String.format("%.2f", kinAdv),
                    String.format("%.2f", pxBefore), String.format("%.2f", pyBefore),
                    String.format("%.2f", pzBefore),
                    String.format("%.2f", nx), String.format("%.2f", cell.getY() + seatFrac),
                    String.format("%.2f", nz));
        }
        // Credit the cells the step just covered (the tick-start sweep only
        // saw the pre-step position).
        hsSweep(level, t, cart.position());
        // Full governed speed in the delta: the vanilla tick consumes its
        // clamped share next tick (already accounted for in kinAdv).
        cart.setDeltaMovement(new Vec3(md.getStepX() * eff, 0, md.getStepZ() * eff));
        return true;
    }

    /** What vanilla's cart tick moves per tick along a set delta (baked 0.4 clamp). */
    private static final double VANILLA_CART_STEP = 0.4;

    private static int kinFailThrottle = 0;
    private static int kinDecideThrottle = 0;

    private static void kinFail(String why) {
        if (kinFailThrottle == 0) {
            LOGGER.info("[HSDbg] kinematic declined: {}", why);
        }
        kinFailThrottle = (kinFailThrottle + 1) % 200;
    }

    /**
     * Sample the cells crossed between the previous and the current position.
     * At high speed the cart hops over whole cells each tick - without the
     * sweep, boost segments and hazards would be missed outright.
     */
    private static void hsSweep(ServerLevel level, Trip t, Vec3 now) {
        Vec3 from = t.hsPrevPos;
        t.hsPrevPos = now;
        if (from == null) {
            return;
        }
        double dx = now.x - from.x;
        double dy = now.y - from.y;
        double dz = now.z - from.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(len / 0.35);
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            BlockPos cell = BlockPos.containing(
                    from.x + dx * f, from.y + dy * f, from.z + dz * f);
            if (!seen.add(cell.asLong())) {
                continue;
            }
            // Tick boundaries split the path: the first sample of this sweep
            // often sits in the cell already credited by the previous sweep.
            if (cell.equals(t.hsLastCell)) {
                continue;
            }
            t.hsLastCell = cell;
            hsEnterCell(level, t, cell);
        }
    }

    /** Reaction to a single newly-entered rail cell. */
    private static void hsEnterCell(ServerLevel level, Trip t, BlockPos cell) {
        HighSpeed.Kind k = HighSpeed.classify(level, cell);
        switch (k) {
            case BOOST_ON -> {
                t.cellsCredited++;
                // Riding a powered slope DOWNHILL: no charge accrual. Gravity
                // does the work; a hot arrival wrecked the end-curve (survey:
                // six powered AW cells feeding an unpowered 90-degree curve).
                RailShape sh = RailShapes.shapeAt(level, cell);
                boolean descentLeg = sh != null && sh.name().startsWith("ascending_")
                        && ((sh.name().endsWith("west") && t.lastDir == Direction.EAST)
                        || (sh.name().endsWith("east") && t.lastDir == Direction.WEST));
                boolean ascentLeg = sh != null && sh.name().startsWith("ascending_")
                        && ((sh.name().endsWith("west") && t.lastDir == Direction.WEST)
                        || (sh.name().endsWith("east") && t.lastDir == Direction.EAST));
                if (ascentLeg) {
                    t.descentMode = false;   // climbing again: economy resumes
                }
                if (descentLeg) {
                    t.descentMode = true;    // sticky till the next ascent
                    if (t.hsSpeed > HighSpeed.floorBpt() + 0.01) {
                        LOGGER.info("[HS] descent-boost ignored (cell {})",
                                cell.toShortString());
                    }
                    break;
                }
                if (t.descentMode) {
                    // Inside a descent stretch: powered flats do not refill
                    // the tank (the sawtooth profile - 34 on flats, 8 on
                    // slopes - caused every hot slope entry).
                    break;
                }
                double base = t.hsSpeed < 0 ? HighSpeed.floorBpt() : t.hsSpeed;
                double ns = Math.min(HighSpeed.maxBpt(), base + HighSpeed.gainPerBoost());
                if (ns != t.hsSpeed) {
                    LOGGER.info("[HS] charge {} -> {} (cell {})",
                            fmtBpt(t.hsSpeed), fmtBpt(ns), cell.toShortString());
                }
                t.hsSpeed = ns;
                t.hsHold = 0;
            }
            case BOOST_OFF -> {
                t.cellsCredited++;
                if (t.hsSpeed > HighSpeed.floorBpt() + 0.01) {
                    LOGGER.info("[HS] discharged to vanilla (cell {})", cell.toShortString());
                }
                t.hsSpeed = HighSpeed.floorBpt();
                t.hsHold = 0;
            }
            case DETECTOR, ACTIVATOR, CURVE, SLOPE -> {
                t.cellsCredited++;
                // Yielding happens through the governor's clamp; log the pass.
                if (t.hsSpeed > HighSpeed.floorBpt() + 0.01) {
                    LOGGER.info("[HS] yielding at {} (kind {})", cell.toShortString(), k);
                }
            }
            case NORMAL -> {
                t.cellsCredited++;
                t.hsHold++;
                if (t.hsSpeed > HighSpeed.floorBpt() + 0.01
                        && t.hsHold > HighSpeed.holdRails) {
                    double ns = Math.max(HighSpeed.floorBpt(),
                            t.hsSpeed - HighSpeed.decayBpt());
                    if (ns != t.hsSpeed) {
                        LOGGER.info("[HS] decay {} -> {} (hold {})",
                                fmtBpt(t.hsSpeed), fmtBpt(ns), t.hsHold);
                    }
                    t.hsSpeed = ns;
                }
            }
            default -> {
            }
        }
    }

    private static String fmtBpt(double bpt) {
        return bpt < 0 ? "-" : String.format("%.2f", bpt * 20.0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean hasTripForStation(String station) {
        for (Trip t : TRIPS.values()) {
            if (t.station.equalsIgnoreCase(station)) {
                return true;
            }
        }
        return false;
    }

    private static List<AbstractMinecart> findStoppedCartsNear(ServerLevel level, BlockPos anchor,
                                                               double hr, double vr) {
        var typeTest = net.minecraft.world.level.entity.EntityTypeTest
                .<net.minecraft.world.entity.Entity, AbstractMinecart>forClass(AbstractMinecart.class);
        List<AbstractMinecart> found = new ArrayList<>();
        AABB box = new AABB(anchor).inflate(hr, vr, hr);
        level.getEntities(typeTest, box, cart -> {
            Vec3 p = cart.position();
            double dy = p.y - anchor.getY();
            if (Math.abs(dy) > vr) {
                return false;
            }
            return cart.getDeltaMovement().horizontalDistance() <= 0.08;
        }, found);
        return found;
    }

    private static double distTo(BlockPos p, Vec3 pos) {
        double dx = (p.getX() + 0.5) - pos.x;
        double dy = (p.getY() + 0.5) - pos.y;
        double dz = (p.getZ() + 0.5) - pos.z;
        return Math.sqrt(dx * dx + dz * dz + dy * dy * 2);
    }

    private static ServerLevel levelFor(MinecraftServer server, String dim) {
        for (ServerLevel lv : server.getAllLevels()) {
            if (NetworkState.dimId(lv).equals(dim)) {
                return lv;
            }
        }
        return null;
    }

    static void shutdown() {
        TRIPS.clear();
    }

    private static void info(Player p, String msg) {
        p.sendSystemMessage(Component.literal("\u00a76" + Messages.t("prefix") + "\u00a7r " + msg));
    }

    private static void warn(Player p, String msg) {
        p.sendSystemMessage(Component.literal("\u00a76" + Messages.t("prefix") + "\u00a7r \u00a7c" + msg));
    }
}
