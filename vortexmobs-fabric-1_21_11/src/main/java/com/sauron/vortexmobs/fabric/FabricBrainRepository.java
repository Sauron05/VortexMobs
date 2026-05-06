package com.sauron.vortexmobs.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sauron.vortexmobs.core.ServerBrain;
import com.sauron.vortexmobs.core.ServerGenome;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class FabricBrainRepository {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Logger logger;

    public FabricBrainRepository(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    public ServerBrain loadOrCreate(Supplier<ServerBrain> fallback) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                ServerBrain created = fallback.get();
                save(created);
                return created;
            }

            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Snapshot snapshot = GSON.fromJson(reader, Snapshot.class);
                if (snapshot == null || snapshot.serverId == null || snapshot.serverId.isBlank()) {
                    ServerBrain created = fallback.get();
                    save(created);
                    return created;
                }
                return snapshot.toBrain();
            }
        } catch (IOException exception) {
            logger.warn("Failed to load VortexMobs Fabric brain, using a fresh profile", exception);
            return fallback.get();
        }
    }

    public synchronized void save(ServerBrain brain) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(Snapshot.fromBrain(brain), writer);
            }
        } catch (IOException exception) {
            logger.warn("Failed to save VortexMobs Fabric brain", exception);
        }
    }

    private static final class Snapshot {

        private int dataVersion;
        private String serverId;
        private long createdAtEpochMillis;
        private long salt;
        private int encounters;
        private int playerKills;
        private double rangedPressure;
        private double meleePressure;
        private double kitingPressure;
        private double highGroundPressure;
        private double shieldingPressure;
        private double focusFirePressure;
        private double burstPressure;
        private double aggression;
        private double bossEvolution;
        private long lastUpdatedEpochMillis;

        private static Snapshot fromBrain(ServerBrain brain) {
            Snapshot snapshot = new Snapshot();
            snapshot.dataVersion = brain.dataVersion();
            snapshot.serverId = brain.genome().serverId();
            snapshot.createdAtEpochMillis = brain.genome().createdAtEpochMillis();
            snapshot.salt = brain.genome().salt();
            snapshot.encounters = brain.encounters();
            snapshot.playerKills = brain.playerKills();
            snapshot.rangedPressure = brain.rangedPressure();
            snapshot.meleePressure = brain.meleePressure();
            snapshot.kitingPressure = brain.kitingPressure();
            snapshot.highGroundPressure = brain.highGroundPressure();
            snapshot.shieldingPressure = brain.shieldingPressure();
            snapshot.focusFirePressure = brain.focusFirePressure();
            snapshot.burstPressure = brain.burstPressure();
            snapshot.aggression = brain.aggression();
            snapshot.bossEvolution = brain.bossEvolution();
            snapshot.lastUpdatedEpochMillis = brain.lastUpdatedEpochMillis();
            return snapshot;
        }

        private ServerBrain toBrain() {
            return new ServerBrain(
                    new ServerGenome(serverId, createdAtEpochMillis, salt),
                    dataVersion,
                    encounters,
                    playerKills,
                    rangedPressure,
                    meleePressure,
                    kitingPressure,
                    highGroundPressure,
                    shieldingPressure,
                    focusFirePressure,
                    burstPressure,
                    aggression,
                    bossEvolution,
                    lastUpdatedEpochMillis
            );
        }
    }
}