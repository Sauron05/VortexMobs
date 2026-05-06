package com.sauron.vortexmobs.bukkit;

import com.sauron.vortexmobs.core.ServerBrain;
import com.sauron.vortexmobs.core.ServerGenome;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class VortexMobsPlugin extends JavaPlugin {

    private MessageService messageService;
    private BukkitBrainRepository brainRepository;
    private SchedulerBridge schedulerBridge;
    private BukkitAdaptiveController adaptiveController;
    private ServerBrain serverBrain;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        messageService = new MessageService(this);
        brainRepository = new BukkitBrainRepository(getDataFolder().toPath().resolve("server-brain.json"), getLogger());
        serverBrain = brainRepository.loadOrCreate(() -> new ServerBrain(ServerGenome.create()));
        schedulerBridge = new SchedulerBridge(this);
        adaptiveController = new BukkitAdaptiveController(this, serverBrain, brainRepository, schedulerBridge, messageService);
        adaptiveController.start();

        PluginCommand command = getCommand("vortexmobs");
        if (command == null) {
            throw new IllegalStateException("Command 'vortexmobs' is missing from plugin.yml");
        }

        VortexMobsCommand handler = new VortexMobsCommand(adaptiveController, messageService);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    @Override
    public void onDisable() {
        if (adaptiveController != null) {
            adaptiveController.shutdown();
        }
        if (brainRepository != null && serverBrain != null) {
            brainRepository.save(serverBrain);
        }
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public ServerBrain getServerBrain() {
        return serverBrain;
    }
}