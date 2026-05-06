package com.sauron.vortexmobs.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VortexMobsFabric implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("VortexMobs");

    private final FabricAdaptiveRuntime runtime = new FabricAdaptiveRuntime(LOGGER);

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(runtime::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(runtime::onServerTick);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(runtime::onAfterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(runtime::onAfterDeath);
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(runtime::onAfterKilledOtherEntity);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> runtime.registerCommands(dispatcher));
    }
}