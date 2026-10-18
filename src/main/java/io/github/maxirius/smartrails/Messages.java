package io.github.maxirius.smartrails;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Player-facing message localization. Language is set in
 * {@code config/smartrails.json} ({@code "lang": "de"} or {@code "en"});
 * defaults to German. Command names are English in both languages.
 */
final class Messages {

    private Messages() {
    }

    static String lang = "de";

    private static final Map<String, String> DE = Map.ofEntries(
            Map.entry("prefix", "[SRails]"),
            Map.entry("sign.protected", "Dieses Schild ist als Bahnhof registriert \u2013 erst \u00a7e/srails station remove %s\u00a7r, dann abbauen."),
            Map.entry("sign.text-locked", "Der Schildtext geh\u00f6rt zum Bahnhof \u00a76%s\u00a7r und ist gesch\u00fctzt \u2013 erst deregistrieren (\u00a7e/srails station remove %s\u00a7r)."),
            Map.entry("sign.target.label", "Ziel:"),
            Map.entry("sign.none", "-"),
            Map.entry("cycle.unreachable", "Von '%s' ist kein anderer Bahnhof erreichbar (Weichen/Strecken pr\u00fcfen)."),
            Map.entry("cycle.selected", "Ziel-Auswahl: %s (\u00a77Schild-Rechtsklick best\u00e4tigt)"),
            Map.entry("confirm.no-selection", "Erst mit den Kn\u00f6pfen ein Ziel w\u00e4hlen."),
            Map.entry("confirm.ok", "\u00a7aZiel best\u00e4tigt: %s"),
            Map.entry("cart.ready", "Minecart bereit am Bahnsteig \u2013 Bitte einsteigen."),
            Map.entry("cart.spawn.failed", "Konnte keine Lore am Bahnsteig spawnen (Block frei?)."),
            Map.entry("trip.no-route", "Keine Route von '%s' nach '%s' gefunden (Weichen/Strecke pr\u00fcfen, /srails dbg graph hilft)."),
            Map.entry("trip.depart", "\u00a76\uD83D\uDE86 \u00a7fAbfahrt \u00a76%s \u00a7f\u2192 \u00a7a%s"),
            Map.entry("trip.arrive", "\u00a7a\u2714 Angekommen in \u00a76%s\u00a7a."),
            Map.entry("trip.cancel.frame", "\u00a7c\u2716 Fahrt \u00a76%s \u00a7c\u2192 \u00a76%s \u00a7cabgebrochen: %s."),
            Map.entry("reason.disembarked", "Spieler ist unterwegs ausgestiegen"),
            Map.entry("reason.not-reboarded", "Niemand ist wieder eingestiegen"),
            Map.entry("trip.paused", "\u00a7ePause: du bist ausgestiegen. Steige innerhalb von %s Sekunden wieder ein, sonst wird das Minecart abgebaut."),
            Map.entry("trip.resume", "\u00a7aWillkommen zur\u00fcck \u2013 Weiterfahrt."),
            Map.entry("reason.switch-failed", "Lore konnte die Weiche nicht nehmen (Geometrie pr\u00fcfen)"),
            Map.entry("reason.off-route", "Lore ist von der Route abgekommen (Weiche falsch passiert)"),
            Map.entry("reason.stuck", "Lore steckt geblieben (Block auf dem Gleis?)"),
            Map.entry("reason.missed", "Lore hat den Zielbahnhof verpasst"),
            Map.entry("cmd.players-only", "Nur f\u00fcr Spieler."),
            Map.entry("cmd.look.at-sign", "Schau auf das Bahnhofsschild (innerhalb von 5 Bl\u00f6cken)."),
            Map.entry("cmd.not.a-sign", "Der Blickpunkt ist kein Schild."),
            Map.entry("cmd.need-buttons", "Vor dem Registrieren links und rechts neben das Schild je einen Knopf setzen."),
            Map.entry("cmd.no-rail-near-sign", "Keine Schiene in Sichtn\u00e4he des Schilds gefunden (Bahngleis direkt daneben legen)."),
            Map.entry("cmd.station.taken", "Name oder Schildposition ist schon registriert."),
            Map.entry("cmd.station.added", "Bahnhof \u00a76%s\u00a7r registriert. Erreichbare Ziele: %s"),
            Map.entry("cmd.station.added.cargo", "G\u00fcterbahnhof \u00a76%s\u00a7r registriert. Erreichbare Frachtziele: %s"),
            Map.entry("cargo.dispatch", "\u00a7aG\u00fcterlore startet nach \u00a76%s\u00a7r."),
            Map.entry("cargo.delivered", "\u00a7aG\u00fcterlore angekommen in \u00a76%s\u00a7r \u2013 Truhe bleibt am Bahnsteig."),
            Map.entry("cargo.no-cart", "Keine G\u00fcterlore am Bahnsteig \u2013 stelle eine Lore mit Truhe auf das Gleis und klicke das Schild erneut."),
            Map.entry("sign.freight.tag", "\u00a76[G\u00fcter]"),
            Map.entry("cmd.station.none-reachable", "\u00a7cnoch keine (Weichen/Strecken fehlen)"),
            Map.entry("cmd.station.removed", "Bahnhof \u00a76%s\u00a7r entfernt."),
            Map.entry("cmd.unknown-station", "Unbekannter Bahnhof '%s'."),
            Map.entry("cmd.unknown-dest", "Unbekannter Zielbahnhof '%s'."),
            Map.entry("cmd.station.list.header", "Bahnh\u00f6fe (%d):"),
            Map.entry("cmd.station.list.none", "Noch keine Bahnh\u00f6fe."),
            Map.entry("cmd.station.list.line", " \u2192 Ziel: "),
            Map.entry("cmd.junction.look", "Schau auf die Schiene der Weiche."),
            Map.entry("cmd.junction.not-rail", "Blickpunkt ist keine Schiene."),
            Map.entry("cmd.junction.added", "Weiche \u00a76%s\u00a7r registriert."),
            Map.entry("cmd.stick.given", "Weichenstab erhalten."),
            Map.entry("cmd.junction.dup", "Diese Position ist schon als Weiche registriert."),
            Map.entry("cmd.junction.removed", "Weiche entfernt."),
            Map.entry("cmd.junction.none-here", "Hier ist keine Weiche registriert."),
            Map.entry("cmd.junction.list.header", "Registrierte Weichen (%d):"),
            Map.entry("cmd.junction.list.none", "Keine Weichen registriert."),
            Map.entry("cmd.depart.triggered", "Abfahrt von \u00a76%s\u00a7r nach \u00a7a%s\u00a7r angesto\u00dfen."),
            Map.entry("cmd.depart.no-dest", "'%s' hat kein best\u00e4tigtes Ziel."),
            Map.entry("dbg.register.added", "DBG Bahnhof \u00a76%s\u00a7r @%s registriert; erreichbar: %s"),
            Map.entry("dbg.select.set", "DBG Ziel gesetzt: %s \u2192 %s"),
            Map.entry("dbg.graph.report", "Graph '%s': Knoten gesamt=%d (Stationen=%d, Weichen=%d, Endpunkte=%d), Kanten=%d"),
            Map.entry("dbg.shape.report", "Schiene @%s: Form=%s | Arme: %s"),
            Map.entry("item.junction_stick", "Weichenstock"),
            Map.entry("stick.not-rail", "Ziel ist keine Schiene \u2013 direkt anklicken oder den Block, auf dem sie liegt."),
            Map.entry("stick.no-arms", "Diese Weiche hat aktuell keine erkennbaren Ausfahrten."),
            Map.entry("stick.preferred", "Bevorzugte Ausfahrt: \u00a76%s\u00a7r \u00a77(Router-Tiebreak bei vergleichbaren Routen)"),
            Map.entry("dbg.graph.crossings", "Kreuzungen: %d (%s)"),
            Map.entry("dbg.crossings.enforced", "Rechtsverkehr erzwungen - Abbiegen in den Gegenkanal ist ausgeschlossen"),
            Map.entry("dbg.crossings.off", "Filter aus - alle Abbiegungen erlaubt"),
            Map.entry("dbg.shape.crossing.tag", "Kreuzung"));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("prefix", "[SRails]"),
            Map.entry("sign.protected", "This sign is registered as a station \u2013 run \u00a7e/srails station remove %s\u00a7r before breaking it."),
            Map.entry("sign.text-locked", "The sign text belongs to station \u00a76%s\u00a7r and is protected \u2013 deregister first (\u00a7e/srails station remove %s\u00a7r)."),
            Map.entry("sign.target.label", "Destination:"),
            Map.entry("sign.none", "-"),
            Map.entry("cycle.unreachable", "No other station is reachable from '%s' (check switches/tracks)."),
            Map.entry("cycle.selected", "Selected destination: %s (\u00a77right-click the sign to confirm)"),
            Map.entry("confirm.no-selection", "Pick a destination with the buttons first."),
            Map.entry("confirm.ok", "\u00a7aDestination confirmed: %s"),
            Map.entry("cart.ready", "Minecart ready at the platform \u2013 please board."),
            Map.entry("cart.spawn.failed", "Could not spawn a cart on the platform (clear the block?)."),
            Map.entry("trip.no-route", "No route from '%s' to '%s' found (check switches/tracks, /srails dbg graph helps)."),
            Map.entry("trip.depart", "\u00a76\uD83D\uDE86 \u00a7fDeparting \u00a76%s \u00a7f\u2192 \u00a7a%s"),
            Map.entry("trip.arrive", "\u00a7a\u2714 Arrived at \u00a76%s\u00a7a."),
            Map.entry("trip.cancel.frame", "\u00a7c\u2716 Trip \u00a76%s \u00a7c\u2192 \u00a76%s \u00a7ccancelled: %s."),
            Map.entry("reason.disembarked", "player disembarked en route"),
            Map.entry("reason.not-reboarded", "nobody reboarded in time"),
            Map.entry("trip.paused", "\u00a7ePaused: you disembarked. Board again within %s seconds or the minecart will be removed."),
            Map.entry("trip.resume", "\u00a7aWelcome back \u2013 resuming the trip."),
            Map.entry("reason.switch-failed", "cart could not take the switch (check geometry)"),
            Map.entry("reason.off-route", "cart went off route (wrong switch taken)"),
            Map.entry("reason.stuck", "cart got stuck (something blocking the track?)"),
            Map.entry("reason.missed", "cart missed the destination station"),
            Map.entry("cmd.players-only", "Players only."),
            Map.entry("cmd.look.at-sign", "Look at the station sign (within 5 blocks)."),
            Map.entry("cmd.not.a-sign", "You are not looking at a sign."),
            Map.entry("cmd.need-buttons", "Place one button to the left and one to the right of the sign before registering."),
            Map.entry("cmd.no-rail-near-sign", "No rail found near the sign (lay the platform track right next to it)."),
            Map.entry("cmd.station.taken", "Name or sign position is already registered."),
            Map.entry("cmd.station.added", "Station \u00a76%s\u00a7r registered. Reachable destinations: %s"),
            Map.entry("cmd.station.added.cargo", "Freight station \u00a76%s\u00a7r registered. Reachable freight destinations: %s"),
            Map.entry("cargo.dispatch", "\u00a7aCargo cart dispatched to \u00a76%s\u00a7r."),
            Map.entry("cargo.delivered", "\u00a7aCargo cart arrived at \u00a76%s\u00a7r \u2013 chest stays on the platform."),
            Map.entry("cargo.no-cart", "No chest cart on the platform \u2013 place a chest minecart on the rail and click the sign again."),
            Map.entry("sign.freight.tag", "\u00a76[Cargo]"),
            Map.entry("cmd.station.none-reachable", "\u00a7cnone yet (tracks/switches missing)"),
            Map.entry("cmd.station.removed", "Removed station \u00a76%s\u00a7r."),
            Map.entry("cmd.unknown-station", "Unknown station '%s'."),
            Map.entry("cmd.unknown-dest", "Unknown destination station '%s'."),
            Map.entry("cmd.station.list.header", "Stations (%d):"),
            Map.entry("cmd.station.list.none", "No stations yet."),
            Map.entry("cmd.station.list.line", " \u2192 destination: "),
            Map.entry("cmd.junction.look", "Look at the switch rail."),
            Map.entry("cmd.junction.not-rail", "Not looking at a rail."),
            Map.entry("cmd.junction.added", "Switch \u00a76%s\u00a7r registered."),
            Map.entry("cmd.stick.given", "Received the junction stick."),
            Map.entry("cmd.junction.dup", "This position is already registered as a switch."),
            Map.entry("cmd.junction.removed", "Switch removed."),
            Map.entry("cmd.junction.none-here", "No switch registered here."),
            Map.entry("cmd.junction.list.header", "Registered switches (%d):"),
            Map.entry("cmd.junction.list.none", "No switches registered."),
            Map.entry("cmd.depart.triggered", "Departure from \u00a76%s\u00a7r to \u00a7a%s\u00a7r triggered."),
            Map.entry("cmd.depart.no-dest", "'%s' has no confirmed destination."),
            Map.entry("dbg.register.added", "DBG station \u00a76%s\u00a7r @%s registered; reachable: %s"),
            Map.entry("dbg.select.set", "DBG destination set: %s \u2192 %s"),
            Map.entry("dbg.graph.report", "Graph '%s': nodes=%d (stations=%d, switches=%d, termini=%d), edges=%d"),
            Map.entry("dbg.shape.report", "Rail @%s: shape=%s | arms: %s"),
            Map.entry("item.junction_stick", "Junction Stick"),
            Map.entry("stick.not-rail", "Target is not a rail \u2013 click it directly, or the block it rests on."),
            Map.entry("stick.no-arms", "This junction currently has no recognisable exits."),
            Map.entry("stick.preferred", "Preferred exit: \u00a76%s\u00a7r \u00a77(router tiebreak on comparable routes)"),
            Map.entry("dbg.graph.crossings", "Crossings: %d (%s)"),
            Map.entry("dbg.crossings.enforced", "right-hand traffic enforced - merging into the oncoming lane is excluded"),
            Map.entry("dbg.crossings.off", "filter off - all turns allowed"),
            Map.entry("dbg.shape.crossing.tag", "crossing"));

    /** Translate/format; unknown keys fall back to the key name. */
    static String t(String key, Object... args) {
        Map<String, String> bundle = "en".equalsIgnoreCase(lang) ? EN : DE;
        String tpl = bundle.getOrDefault(key, key);
        return args.length == 0 ? tpl : String.format(tpl, args);
    }

    /** Loads config/smartrails.json (creates it with defaults on first run; a
     * pre-rename config/railscontrol.json is still honoured). */
    static void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("smartrails.json");
        Path legacy = FabricLoader.getInstance().getConfigDir().resolve("railscontrol.json");
        if (!Files.exists(file) && Files.exists(legacy)) {
            file = legacy;   // pre-rename installs keep their settings
        }
        try {
            if (Files.exists(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (o.has("lang")) {
                    lang = o.get("lang").getAsString();
                }
                if (o.has("highSpeed") && o.get("highSpeed").isJsonObject()) {
                    HighSpeedCfg.read(o.getAsJsonObject("highSpeed"));
                }
                if (o.has("reboardSeconds")) {
                    TrainController.setReboardSeconds(
                            Math.max(3, o.get("reboardSeconds").getAsInt()));
                }
                if (o.has("cargo") && o.get("cargo").isJsonObject()) {
                    JsonObject c = o.getAsJsonObject("cargo");
                    if (c.has("cruiseSpeedMs")) {
                        TrainController.setCargoCruise(
                                Math.clamp(c.get("cruiseSpeedMs").getAsDouble(), 2.0, 8.0));
                    }
                    if (c.has("windowChunks")) {
                        TrainController.setCargoWindow(
                                Math.clamp(c.get("windowChunks").getAsInt(), 1, 8));
                    }
                    if (c.has("redstoneTrigger")) {
                        RedstoneWatch.setEnabled(c.get("redstoneTrigger").getAsBoolean());
                    }
                }
                if (o.has("crossings") && o.get("crossings").isJsonObject()) {
                    JsonObject cx = o.getAsJsonObject("crossings");
                    if (cx.has("enforce")) {
                        Topology.crossingsEnforce = cx.get("enforce").getAsBoolean();
                    }
                }
                if (o.has("publish") && o.get("publish").isJsonObject()) {
                    PublishCfg.read(o.getAsJsonObject("publish"));
                }
            } else {
                JsonObject o = new JsonObject();
                o.addProperty("lang", "de");
                o.addProperty("reboardSeconds", 30);
                o.add("highSpeed", HighSpeedCfg.defaults());
                JsonObject cg = new JsonObject();
                cg.addProperty("cruiseSpeedMs", 8.0);
                cg.addProperty("windowChunks", 2);
                cg.addProperty("redstoneTrigger", true);
                o.add("cargo", cg);
                JsonObject cx = new JsonObject();
                cx.addProperty("enforce", Topology.crossingsEnforce);
                o.add("crossings", cx);
                JsonObject pb = new JsonObject();
                pb.addProperty("enabled", DbPublish.enabled);
                pb.addProperty("host", DbPublish.host);
                pb.addProperty("port", DbPublish.port);
                pb.addProperty("user", "");
                pb.addProperty("password", "");
                pb.addProperty("minIntervalSec", DbPublish.minIntervalSec);
                o.add("publish", pb);
                Files.createDirectories(file.getParent());
                Files.writeString(file, new Gson().toJson(o), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            SmartRails.LOGGER.warn("[smartrails] Config not readable, using German messages.", e);
        }
        SmartRails.LOGGER.info("[smartrails] Message language: {}", lang);
        SmartRails.LOGGER.info("[smartrails] HighSpeed {}: max {} m/s, {} powered rails to max, hold {} rails, decay {} m/s/rail",
                HighSpeed.enabled ? "enabled" : "DISABLED",
                HighSpeed.maxSpeedMs, HighSpeed.poweredRailsToMax,
                HighSpeed.holdRails, HighSpeed.decayMsPerRail);
        SmartRails.LOGGER.info("[smartrails] Crossings: right-hand enforcement {}", Topology.crossingsEnforce ? "ON" : "OFF");
        SmartRails.LOGGER.info("[smartrails] DB publish: {}",
                (DbPublish.enabled && !DbPublish.user.isEmpty())
                        ? "ENABLED -> " + DbPublish.host + ":" + DbPublish.port + "/smartrail (user '" + DbPublish.user + "')"
                        : "disabled (fill publish.user/password in config)");
    }
}

/** Reader for the "publish" config block feeding {@link DbPublish}. */
final class PublishCfg {

    private PublishCfg() {
    }

    static void read(JsonObject o) {
        if (o.has("enabled")) {
            DbPublish.enabled = o.get("enabled").getAsBoolean();
        }
        if (o.has("host")) {
            DbPublish.host = o.get("host").getAsString();
        }
        if (o.has("port")) {
            DbPublish.port = o.get("port").getAsInt();
        }
        if (o.has("user")) {
            DbPublish.user = o.get("user").getAsString();
        }
        if (o.has("password")) {
            DbPublish.password = o.get("password").getAsString();
        }
        if (o.has("minIntervalSec")) {
            DbPublish.minIntervalSec = o.get("minIntervalSec").getAsInt();
        }
    }
}

/** Mirrors the "highSpeed" config block onto the {@link HighSpeed} tuning knobs. */
final class HighSpeedCfg {

    private HighSpeedCfg() {
    }

    static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", HighSpeed.enabled);
        o.addProperty("maxSpeedMs", HighSpeed.maxSpeedMs);
        o.addProperty("poweredRailsToMax", HighSpeed.poweredRailsToMax);
        o.addProperty("holdRails", HighSpeed.holdRails);
        o.addProperty("decayMsPerRail", HighSpeed.decayMsPerRail);
        o.addProperty("brakeOnSlopes", HighSpeed.brakeOnSlopes);
        o.addProperty("vanillaSpeedMs", HighSpeed.vanillaSpeedMs);
        o.addProperty("lookaheadCells", HighSpeed.lookaheadCells);
        return o;
    }

    static void read(JsonObject o) {
        if (o.has("enabled")) {
            HighSpeed.enabled = o.get("enabled").getAsBoolean();
        }
        if (o.has("maxSpeedMs")) {
            HighSpeed.maxSpeedMs = o.get("maxSpeedMs").getAsDouble();
        }
        if (o.has("poweredRailsToMax")) {
            HighSpeed.poweredRailsToMax = o.get("poweredRailsToMax").getAsInt();
        }
        if (o.has("holdRails")) {
            HighSpeed.holdRails = o.get("holdRails").getAsInt();
        }
        if (o.has("decayMsPerRail")) {
            HighSpeed.decayMsPerRail = o.get("decayMsPerRail").getAsDouble();
        }
        if (o.has("brakeOnSlopes")) {
            HighSpeed.brakeOnSlopes = o.get("brakeOnSlopes").getAsBoolean();
        }
        if (o.has("vanillaSpeedMs")) {
            HighSpeed.vanillaSpeedMs = o.get("vanillaSpeedMs").getAsDouble();
        }
        if (o.has("lookaheadCells")) {
            HighSpeed.lookaheadCells = o.get("lookaheadCells").getAsInt();
        }
    }
}
