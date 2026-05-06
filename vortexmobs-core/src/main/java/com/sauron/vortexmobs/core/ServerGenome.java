package com.sauron.vortexmobs.core;

import java.security.SecureRandom;
import java.util.UUID;

public final class ServerGenome {

    private final String serverId;
    private final long createdAtEpochMillis;
    private final long salt;

    public ServerGenome(String serverId, long createdAtEpochMillis, long salt) {
        this.serverId = serverId;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.salt = salt;
    }

    public static ServerGenome create() {
        SecureRandom random = new SecureRandom();
        return new ServerGenome(UUID.randomUUID().toString(), System.currentTimeMillis(), random.nextLong());
    }

    public String serverId() {
        return serverId;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long salt() {
        return salt;
    }
}