package com.sauron.vortexmobs.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

public final class FabricConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int updateIntervalTicks = 10;
    public int saveIntervalTicks = 2400;
    public int maxTrackedMobs = 400;
    public double scanRadius = 16.0D;
    public double verticalScanRadius = 8.0D;
    public double bossBaseHealth = 300.0D;
    public double bossAcquireRange = 28.0D;
    public boolean summonMinions = true;
    public boolean announceStageUpgrades = true;
    public boolean debugLogging = false;

    public static FabricConfig loadOrCreate(Path path, Logger logger) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                FabricConfig config = new FabricConfig();
                config.save(path);
                return config.normalized();
            }

            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                FabricConfig config = GSON.fromJson(reader, FabricConfig.class);
                if (config == null) {
                    config = new FabricConfig();
                }
                return config.normalized();
            }
        } catch (IOException exception) {
            logger.warn("Failed to load VortexMobs Fabric config, using defaults", exception);
            return new FabricConfig();
        }
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    private FabricConfig normalized() {
        updateIntervalTicks = Math.max(5, updateIntervalTicks);
        saveIntervalTicks = Math.max(200, saveIntervalTicks);
        maxTrackedMobs = Math.max(50, maxTrackedMobs);
        scanRadius = Math.max(8.0D, scanRadius);
        verticalScanRadius = Math.max(4.0D, verticalScanRadius);
        bossBaseHealth = Math.max(100.0D, bossBaseHealth);
        bossAcquireRange = Math.max(12.0D, bossAcquireRange);
        return this;
    }
}