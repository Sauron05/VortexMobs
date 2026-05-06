package com.sauron.vortexmobs.core;

import java.util.Locale;

public enum MobArchetype {
    BRUTE,
    RANGER,
    AMBUSHER,
    SWARMER,
    BOSS,
    GENERALIST;

    public static MobArchetype fromEntityName(String entityName) {
        String normalized = entityName == null ? "" : entityName.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SKELETON", "STRAY", "BOGGED", "PILLAGER" -> RANGER;
            case "SPIDER", "CAVE_SPIDER", "ENDERMITE", "SILVERFISH" -> AMBUSHER;
            case "ZOMBIE", "HUSK", "DROWNED", "WITHER_SKELETON", "PIGLIN_BRUTE" -> BRUTE;
            case "SLIME", "MAGMA_CUBE", "ZOMBIFIED_PIGLIN" -> SWARMER;
            case "WITHER", "WARDEN", "ENDER_DRAGON" -> BOSS;
            default -> GENERALIST;
        };
    }
}