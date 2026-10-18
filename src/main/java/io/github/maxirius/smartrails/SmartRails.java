package io.github.maxirius.smartrails;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * SmartRails - automated rail network control.
 *
 * Stations are vanilla signs flanked by two buttons; the sign shows the
 * station name and the selected destination. Junctions are derived from the
 * physical track layout (plus optional manual markers) and trains are steered
 * onto the branch matching the confirmed destination.
 */
public class SmartRails implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("smartrails");

    @Override
    public void onInitialize() {
        Messages.loadConfig();
        InteractionHook.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, buildCtx, selection) ->
                RailCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                NetworkState.INSTANCE.load(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            NetworkState.INSTANCE.saveIfDirty(server);
            TrainController.shutdown();
        });

        ServerTickEvents.END_SERVER_TICK.register(TrainController::tick);

        LOGGER.info("[smartrails] initialised");
    }
}
