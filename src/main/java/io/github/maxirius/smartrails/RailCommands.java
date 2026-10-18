package io.github.maxirius.smartrails;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.Map;

/** /srails station add|remove|list · junction add|remove|list · depart */
final class RailCommands {

    private RailCommands() {
    }


    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }

    private static final SuggestionProvider<CommandSourceStack> STATION_NAMES =
            (ctx, builder) -> {
                for (String n : NetworkState.INSTANCE.stationNames()) {
                    builder.suggest(n);
                }
                return builder.buildFuture();
            };

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("srails");

        // ---------------- station ----------------
        var station = Commands.literal("station");

        station.then(Commands.literal("add")
                .requires(RailCommands::isAdmin)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(RailCommands::stationAdd)));

        station.then(Commands.literal("addcargo")
                .requires(RailCommands::isAdmin)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(RailCommands::stationAddCargo)));

        station.then(Commands.literal("remove")
                .requires(RailCommands::isAdmin)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(STATION_NAMES)
                        .executes(RailCommands::stationRemove)));

        station.then(Commands.literal("list")
                .executes(RailCommands::stationList));

        root.then(station);

        // ---------------- junction ----------------
        var junction = Commands.literal("junction");

        junction.then(Commands.literal("add")
                .requires(RailCommands::isAdmin)
                .executes(RailCommands::junctionAdd));

        junction.then(Commands.literal("remove")
                .requires(RailCommands::isAdmin)
                .executes(RailCommands::junctionRemove));

        junction.then(Commands.literal("list")
                .executes(RailCommands::junctionList));

        root.then(junction);

        // ---------------- stick distribution ----------------
        root.then(Commands.literal("stick")
                .executes(RailCommands::stickGive));

        // ---------------- depart ----------------
        root.then(Commands.literal("depart")
                .requires(RailCommands::isAdmin)
                .then(Commands.argument("station", StringArgumentType.word())
                        .suggests(STATION_NAMES)
                        .executes(RailCommands::depart)));

        // ---------------- debug / headless testing ----------------
        var dbg = Commands.literal("dbg")
                .requires(RailCommands::isAdmin);

        dbg.then(Commands.literal("register")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .executes(RailCommands::dbgRegister))))));
        dbg.then(Commands.literal("registercargo")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .executes(RailCommands::dbgRegisterCargo))))));
        dbg.then(Commands.literal("select")
                .then(Commands.argument("station", StringArgumentType.word()).suggests(STATION_NAMES)
                        .then(Commands.argument("dest", StringArgumentType.word()).suggests(STATION_NAMES)
                                .executes(RailCommands::dbgSelect))));
        dbg.then(Commands.literal("graph").executes(RailCommands::dbgGraph));
        dbg.then(Commands.literal("shape")
                .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(RailCommands::dbgShape)))));
        dbg.then(Commands.literal("kind")
                .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .executes(RailCommands::dbgKind)))));

        root.then(dbg);

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static int stationAdd(CommandContext<CommandSourceStack> ctx) {
        return stationAddCommon(ctx, false);
    }

    private static int stationAddCargo(CommandContext<CommandSourceStack> ctx) {
        return stationAddCommon(ctx, true);
    }

    private static int stationAddCommon(CommandContext<CommandSourceStack> ctx, boolean cargo) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§6" + Messages.t("prefix") + "§r §c" + Messages.t("cmd.players-only")));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");

        if (!(lookAt(player) instanceof BlockHitResult bhr)) {
            fail(ctx, Messages.t("cmd.look.at-sign"));
            return 0;
        }
        BlockPos signPos = bhr.getBlockPos();
        if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity be)) {
            fail(ctx, Messages.t("cmd.not.a-sign"));
            return 0;
        }
        Direction facing = StationUi.facingOf(be);
        BlockPos anchor = StationUi.findAnchorRail(level, signPos);
        if (anchor == null) {
            fail(ctx, Messages.t("cmd.no-rail-near-sign"));
            return 0;
        }

        Station st = new Station(name, NetworkState.dimId(level), signPos, anchor, cargo);
        if (!NetworkState.INSTANCE.addStation(st)) {
            fail(ctx, Messages.t("cmd.station.taken"));
            return 0;
        }
        NetworkState.INSTANCE.setPending(st, null);
        StationUi.paint(level, st);
        NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());

        var reachable = new java.util.ArrayList<String>();
        for (String nm : Topology.reachable(NetworkState.INSTANCE.graph(level), name)) {
            Station goal = NetworkState.INSTANCE.stationByName(nm);
            if (goal != null && goal.cargo == cargo) {
                reachable.add(nm);
            }
        }
        ok(ctx, Messages.t(cargo ? "cmd.station.added.cargo" : "cmd.station.added", name,
                reachable.isEmpty() ? Messages.t("cmd.station.none-reachable") : reachable.toString()));
        return 1;
    }

    private static int stationRemove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Station st = NetworkState.INSTANCE.removeStation(name);
        if (st == null) {
            fail(ctx, Messages.t("cmd.unknown-station", name));
            return 0;
        }
        if (ctx.getSource().getServer() == null) {
            return 0;
        }
        ServerLevel level = levelOf(ctx, st.dim);
        if (level != null) {
            StationUi.clearSign(level, st.signPos());
        }
        ok(ctx, Messages.t("cmd.station.removed", st.name));
        return 1;
    }

    private static int stationList(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        for (Station st : NetworkState.INSTANCE.allStations()) {
            sb.append("\n§6").append(st.name).append("§r ")
              .append(st.dim).append(" ").append(fmt(st.signPos()))
              .append(Messages.t("cmd.station.list.line"))
              .append(st.selected == null ? "§7-" : "§a" + st.selected);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.isEmpty()
                ? Messages.t("cmd.station.list.none")
                : Messages.t("cmd.station.list.header", NetworkState.INSTANCE.allStations().size()) + sb), false);
        return 1;
    }

    private static int junctionAdd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§6" + Messages.t("prefix") + "§r §c" + Messages.t("cmd.players-only")));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        if (!(lookAt(player) instanceof BlockHitResult bhr)) {
            fail(ctx, Messages.t("cmd.junction.look"));
            return 0;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof BaseRailBlock)) {
            fail(ctx, Messages.t("cmd.junction.not-rail"));
            return 0;
        }
        if (NetworkState.INSTANCE.addJunction(new JunctionMarker(NetworkState.dimId(level), pos))) {
            NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());
            ok(ctx, Messages.t("cmd.junction.added", fmt(pos)));
            return 1;
        }
        fail(ctx, Messages.t("cmd.junction.dup"));
        return 0;
    }

    private static int junctionRemove(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        if (!(lookAt(player) instanceof BlockHitResult bhr)) {
            fail(ctx, Messages.t("cmd.junction.look"));
            return 0;
        }
        JunctionMarker jm = NetworkState.INSTANCE.removeJunction(NetworkState.dimId(level),
                bhr.getBlockPos());
        if (jm == null) {
            fail(ctx, Messages.t("cmd.junction.none-here"));
            return 0;
        }
        NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());
        ok(ctx, Messages.t("cmd.junction.removed"));
        return 1;
    }

    private static int junctionList(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        for (JunctionMarker jm : NetworkState.INSTANCE.allJunctions()) {
            sb.append("\n§6").append(jm.dim).append(" ").append(fmt(jm.pos()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.isEmpty()
                ? Messages.t("cmd.junction.list.none")
                : Messages.t("cmd.junction.list.header", NetworkState.INSTANCE.junctionCount()) + sb), false);
        return 1;
    }

    private static int stickGive(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("\u00a76" + Messages.t("prefix")
                    + "\u00a7r \u00a7c" + Messages.t("cmd.players-only")));
            return 0;
        }
        player.getInventory().add(SrItems.create());
        ok(ctx, Messages.t("cmd.stick.given"));
        return 1;
    }

    private static int depart(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "station");
        Station st = NetworkState.INSTANCE.stationByName(name);
        if (st == null) {
            fail(ctx, Messages.t("cmd.unknown-station", name));
            return 0;
        }
        if (st.selected == null) {
            fail(ctx, Messages.t("cmd.depart.no-dest", st.name));
            return 0;
        }
        TrainController.forceDepart(ctx.getSource().getServer(), st,
                ctx.getSource().getPlayer());
        ok(ctx, Messages.t("cmd.depart.triggered", st.name, st.selected));
        return 1;
    }

    // ------------------------------------------------------------------
    // Debug / headless-testing handlers
    // ------------------------------------------------------------------

    private static int dbgRegister(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z");
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        BlockPos anchor = new BlockPos(x, y, z);
        if (!RailShapes.isRail(level, anchor)) {
            fail(ctx, "(" + x + "," + y + "," + z + ") ist keine Schiene.");
            return 0;
        }
        Station st = new Station(name, NetworkState.dimId(level), anchor.above(), anchor);
        if (!NetworkState.INSTANCE.addStation(st)) {
            fail(ctx, Messages.t("cmd.station.taken"));
            return 0;
        }
        NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());
        var reachable = Topology.reachable(NetworkState.INSTANCE.graph(level), name);
        ok(ctx, Messages.t("dbg.register.added", name, fmt(anchor), reachable.toString()));
        return 1;
    }

    private static int dbgRegisterCargo(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z");
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        BlockPos anchor = new BlockPos(x, y, z);
        if (!RailShapes.isRail(level, anchor)) {
            fail(ctx, "(" + x + "," + y + "," + z + ") ist keine Schiene.");
            return 0;
        }
        Station st = new Station(name, NetworkState.dimId(level), anchor.above(), anchor, true);
        if (!NetworkState.INSTANCE.addStation(st)) {
            fail(ctx, Messages.t("cmd.station.taken"));
            return 0;
        }
        NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());
        var reachable = Topology.reachable(NetworkState.INSTANCE.graph(level), name);
        ok(ctx, Messages.t("dbg.register.added", name, fmt(anchor), reachable.toString()));
        return 1;
    }

    private static int dbgSelect(CommandContext<CommandSourceStack> ctx) {
        String station = StringArgumentType.getString(ctx, "station");
        String dest = StringArgumentType.getString(ctx, "dest");
        Station st = NetworkState.INSTANCE.stationByName(station);
        if (st == null) {
            fail(ctx, "Unbekannter Bahnhof '" + station + "'.");
            return 0;
        }
        if (NetworkState.INSTANCE.stationByName(dest) == null) {
            fail(ctx, Messages.t("cmd.unknown-dest", dest));
            return 0;
        }
        NetworkState.INSTANCE.setSelected(st, dest);
        NetworkState.INSTANCE.saveIfDirty(ctx.getSource().getServer());
        ok(ctx, Messages.t("dbg.select.set", st.name, dest));
        return 1;
    }

    private static int dbgGraph(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        Topology.Graph g = NetworkState.INSTANCE.graph(level);
        long stations = g.nodes.stream().filter(n -> n.kind == Topology.Kind.STATION).count();
        long junctions = g.nodes.stream().filter(n -> n.kind == Topology.Kind.JUNCTION).count();
        long termini = g.nodes.stream().filter(n -> n.kind == Topology.Kind.TERMINUS).count();
        long crossings = g.nodes.stream()
                .filter(n -> n.kind == Topology.Kind.JUNCTION)
                .filter(n -> Topology.isCrossingCell(level, n.pos))
                .count();
        StringBuilder ed = new StringBuilder();
        for (Topology.Edge e : g.edges) {
            Topology.Node f = g.nodes.get(e.from);
            Topology.Node to = g.nodes.get(e.to);
            ed.append("\n  ").append(f.kind).append(":").append(f.stationName != null ? f.stationName : fmt(f.pos))
              .append(" --").append(e.exit.name()).append("(").append(e.length).append(")--> ")
              .append(to.kind).append(":").append(to.stationName != null ? to.stationName : fmt(to.pos));
        }
        ok(ctx, Messages.t("dbg.graph.report", NetworkState.dimId(level), g.nodes.size(),
                stations, junctions, termini, g.edges.size())
                + "\n" + Messages.t("dbg.graph.crossings", crossings, crossingsInfo())
                + ed);
        return 1;
    }

    /** Enforcement state for the dbg report (German-visible copy). */
    private static String crossingsInfo() {
        return Topology.crossingsEnforce
                ? Messages.t("dbg.crossings.enforced")
                : Messages.t("dbg.crossings.off");
    }

    private static int dbgShape(CommandContext<CommandSourceStack> ctx) {
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z");
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        BlockPos pos = new BlockPos(x, y, z);
        var shape = RailShapes.shapeAt(level, pos);
        if (shape == null) {
            fail(ctx, "(" + x + "," + y + "," + z + ") ist keine Schiene.");
            return 0;
        }
        StringBuilder arms = new StringBuilder();
        Map<BlockPos, Station> anchorMap = new java.util.HashMap<>();
        for (Station s : NetworkState.INSTANCE.stationsIn(NetworkState.dimId(level))) {
            anchorMap.put(s.anchor(), s);
        }
        for (Topology.Arm a : Topology.armsAt(level, pos, anchorMap)) {
            if (!arms.isEmpty()) {
                arms.append(", ");
            }
            arms.append(a.dir().name()).append("(dy=").append(a.dy()).append(")");
        }
        String shapeLabel = Topology.isCrossingCell(level, pos)
                ? Messages.t("dbg.shape.crossing.tag") + "/" + shape.getName()
                : shape.getName();
        ok(ctx, Messages.t("dbg.shape.report", fmt(pos), shapeLabel, arms.toString()));
        return 1;
    }

    private static int dbgKind(CommandContext<CommandSourceStack> ctx) {
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z");
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        BlockPos pos = new BlockPos(x, y, z);
        var st = level.getBlockState(pos);
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        HighSpeed.Kind kind = HighSpeed.classify(level, pos);
        boolean poweredFlag = st.hasProperty(net.minecraft.world.level.block.PoweredRailBlock.POWERED)
                && st.getValue(net.minecraft.world.level.block.PoweredRailBlock.POWERED);
        ok(ctx, "kind @" + fmt(pos) + ": " + kind + " (block=" + id + ", powered=" + poweredFlag + ")");
        return 1;
    }

    // ------------------------------------------------------------------
    // Utils
    // ------------------------------------------------------------------

    private static HitResult lookAt(ServerPlayer player) {
        return player.pick(5.0, 1.0f, false);
    }

    private static ServerLevel levelOf(CommandContext<CommandSourceStack> ctx, String dim) {
        for (ServerLevel lv : ctx.getSource().getServer().getAllLevels()) {
            if (NetworkState.dimId(lv).equals(dim)) {
                return lv;
            }
        }
        return null;
    }

    private static String fmt(BlockPos p) {
        return "[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]";
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() ->
                Component.literal("§6" + Messages.t("prefix") + "§r " + msg), false);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal("§6" + Messages.t("prefix") + "§r §c" + msg));
    }
}
