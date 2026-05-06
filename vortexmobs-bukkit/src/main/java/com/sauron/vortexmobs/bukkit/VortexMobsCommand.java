package com.sauron.vortexmobs.bukkit;

import com.sauron.vortexmobs.core.ServerBrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public final class VortexMobsCommand implements CommandExecutor, TabCompleter {

    private final BukkitAdaptiveController controller;
    private final MessageService messages;

    public VortexMobsCommand(BukkitAdaptiveController controller, MessageService messages) {
        this.controller = controller;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "usage");
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subCommand) {
            case "brain" -> {
                if (!sender.hasPermission("vortexmobs.use")) {
                    messages.send(sender, "no-permission");
                    yield true;
                }
                sendBrain(sender, controller.getBrain());
                yield true;
            }
            case "spawnboss" -> {
                if (!sender.hasPermission("vortexmobs.admin")) {
                    messages.send(sender, "no-permission");
                    yield true;
                }
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "no-console-boss");
                    yield true;
                }
                controller.spawnBoss(player);
                yield true;
            }
            case "resetbrain" -> {
                if (!sender.hasPermission("vortexmobs.admin")) {
                    messages.send(sender, "no-permission");
                    yield true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    messages.send(sender, "reset-confirm");
                    yield true;
                }
                controller.resetBrain();
                messages.send(sender, "brain-reset");
                yield true;
            }
            case "reload" -> {
                if (!sender.hasPermission("vortexmobs.admin")) {
                    messages.send(sender, "no-permission");
                    yield true;
                }
                controller.reload();
                messages.send(sender, "reload-complete");
                yield true;
            }
            default -> {
                messages.send(sender, "usage");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("brain", "spawnboss", "resetbrain", "reload"), completions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resetbrain")) {
            StringUtil.copyPartialMatches(args[1], List.of("confirm"), completions);
        }
        return completions;
    }

    private void sendBrain(CommandSender sender, ServerBrain brain) {
        sender.sendMessage(messages.prefix() + messages.format(
                "brain-header",
                Map.of(
                        "serverId", brain.genome().serverId(),
                        "threat", String.format(Locale.US, "%.2f", brain.threatLevel()),
                        "stage", String.valueOf(brain.evolutionStage())
                )
        ));
        sender.sendMessage(messages.prefix() + messages.format(
                "brain-signals",
                Map.of("signals", messages.joinSignals(brain.dominantSignals()))
        ));
    }
}