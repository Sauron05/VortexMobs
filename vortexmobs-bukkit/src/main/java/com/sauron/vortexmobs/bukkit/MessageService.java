package com.sauron.vortexmobs.bukkit;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String prefix() {
        return color(messages.getString("prefix", "&8[&cVortexMobs&8] "));
    }

    public String get(String key) {
        return color(messages.getString(key, key));
    }

    public String format(String key, Map<String, String> placeholders) {
        String value = get(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(prefix() + get(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(prefix() + format(key, placeholders));
    }

    public String joinSignals(List<String> signals) {
        return signals == null || signals.isEmpty() ? get("brain-empty") : String.join(", ", signals);
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}