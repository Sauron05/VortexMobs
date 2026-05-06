package com.sauron.vortexmobs.core;

public final class CombatSnapshot {

    private final double distanceToTarget;
    private final double verticalDifference;
    private final int nearbyAllies;
    private final int nearbyPlayers;
    private final boolean targetUsingRanged;
    private final boolean targetBlocking;
    private final boolean lineOfSight;
    private final double currentHealthRatio;
    private final double recentIncomingDamage;

    public CombatSnapshot(
            double distanceToTarget,
            double verticalDifference,
            int nearbyAllies,
            int nearbyPlayers,
            boolean targetUsingRanged,
            boolean targetBlocking,
            boolean lineOfSight,
            double currentHealthRatio,
            double recentIncomingDamage
    ) {
        this.distanceToTarget = distanceToTarget;
        this.verticalDifference = verticalDifference;
        this.nearbyAllies = Math.max(0, nearbyAllies);
        this.nearbyPlayers = Math.max(0, nearbyPlayers);
        this.targetUsingRanged = targetUsingRanged;
        this.targetBlocking = targetBlocking;
        this.lineOfSight = lineOfSight;
        this.currentHealthRatio = MathUtil.clamp01(currentHealthRatio);
        this.recentIncomingDamage = Math.max(0.0D, recentIncomingDamage);
    }

    public double distanceToTarget() {
        return distanceToTarget;
    }

    public double verticalDifference() {
        return verticalDifference;
    }

    public int nearbyAllies() {
        return nearbyAllies;
    }

    public int nearbyPlayers() {
        return nearbyPlayers;
    }

    public boolean targetUsingRanged() {
        return targetUsingRanged;
    }

    public boolean targetBlocking() {
        return targetBlocking;
    }

    public boolean lineOfSight() {
        return lineOfSight;
    }

    public double currentHealthRatio() {
        return currentHealthRatio;
    }

    public double recentIncomingDamage() {
        return recentIncomingDamage;
    }
}