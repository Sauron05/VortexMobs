package com.sauron.vortexmobs.core;

public final class EncounterSample {

    private final MobArchetype archetype;
    private final boolean bossFight;
    private final boolean killedByProjectile;
    private final boolean killerUsedRanged;
    private final boolean playerWonQuickly;
    private final double projectileDamageRatio;
    private final double meleeDamageRatio;
    private final double averageDistance;
    private final double verticalAdvantage;
    private final int uniqueAttackers;
    private final int playerKills;
    private final int shieldBlocks;
    private final double burstDamageRatio;
    private final double damageToPlayers;
    private final long fightDurationTicks;

    private EncounterSample(Builder builder) {
        this.archetype = builder.archetype;
        this.bossFight = builder.bossFight;
        this.killedByProjectile = builder.killedByProjectile;
        this.killerUsedRanged = builder.killerUsedRanged;
        this.playerWonQuickly = builder.playerWonQuickly;
        this.projectileDamageRatio = MathUtil.clamp01(builder.projectileDamageRatio);
        this.meleeDamageRatio = MathUtil.clamp01(builder.meleeDamageRatio);
        this.averageDistance = Math.max(0.0D, builder.averageDistance);
        this.verticalAdvantage = builder.verticalAdvantage;
        this.uniqueAttackers = Math.max(1, builder.uniqueAttackers);
        this.playerKills = Math.max(0, builder.playerKills);
        this.shieldBlocks = Math.max(0, builder.shieldBlocks);
        this.burstDamageRatio = MathUtil.clamp01(builder.burstDamageRatio);
        this.damageToPlayers = Math.max(0.0D, builder.damageToPlayers);
        this.fightDurationTicks = Math.max(1L, builder.fightDurationTicks);
    }

    public static Builder builder(MobArchetype archetype) {
        return new Builder(archetype);
    }

    public MobArchetype archetype() {
        return archetype;
    }

    public boolean bossFight() {
        return bossFight;
    }

    public boolean killedByProjectile() {
        return killedByProjectile;
    }

    public boolean killerUsedRanged() {
        return killerUsedRanged;
    }

    public boolean playerWonQuickly() {
        return playerWonQuickly;
    }

    public double projectileDamageRatio() {
        return projectileDamageRatio;
    }

    public double meleeDamageRatio() {
        return meleeDamageRatio;
    }

    public double averageDistance() {
        return averageDistance;
    }

    public double verticalAdvantage() {
        return verticalAdvantage;
    }

    public int uniqueAttackers() {
        return uniqueAttackers;
    }

    public int playerKills() {
        return playerKills;
    }

    public int shieldBlocks() {
        return shieldBlocks;
    }

    public double burstDamageRatio() {
        return burstDamageRatio;
    }

    public double damageToPlayers() {
        return damageToPlayers;
    }

    public long fightDurationTicks() {
        return fightDurationTicks;
    }

    public double encounterIntensity() {
        double durationFactor = MathUtil.clamp(fightDurationTicks / 240.0D, 0.35D, 2.4D);
        double teamworkFactor = 1.0D + ((uniqueAttackers - 1) * 0.18D);
        double pressureFactor = 1.0D + (projectileDamageRatio * 0.55D) + (burstDamageRatio * 0.35D);
        return durationFactor * teamworkFactor * pressureFactor;
    }

    public double focusTeamPressure() {
        return Math.max(0.0D, uniqueAttackers - 1);
    }

    public static final class Builder {

        private final MobArchetype archetype;
        private boolean bossFight;
        private boolean killedByProjectile;
        private boolean killerUsedRanged;
        private boolean playerWonQuickly;
        private double projectileDamageRatio;
        private double meleeDamageRatio;
        private double averageDistance;
        private double verticalAdvantage;
        private int uniqueAttackers = 1;
        private int playerKills;
        private int shieldBlocks;
        private double burstDamageRatio;
        private double damageToPlayers;
        private long fightDurationTicks = 1L;

        private Builder(MobArchetype archetype) {
            this.archetype = archetype == null ? MobArchetype.GENERALIST : archetype;
        }

        public Builder bossFight(boolean bossFight) {
            this.bossFight = bossFight;
            return this;
        }

        public Builder killedByProjectile(boolean killedByProjectile) {
            this.killedByProjectile = killedByProjectile;
            return this;
        }

        public Builder killerUsedRanged(boolean killerUsedRanged) {
            this.killerUsedRanged = killerUsedRanged;
            return this;
        }

        public Builder playerWonQuickly(boolean playerWonQuickly) {
            this.playerWonQuickly = playerWonQuickly;
            return this;
        }

        public Builder projectileDamageRatio(double projectileDamageRatio) {
            this.projectileDamageRatio = projectileDamageRatio;
            return this;
        }

        public Builder meleeDamageRatio(double meleeDamageRatio) {
            this.meleeDamageRatio = meleeDamageRatio;
            return this;
        }

        public Builder averageDistance(double averageDistance) {
            this.averageDistance = averageDistance;
            return this;
        }

        public Builder verticalAdvantage(double verticalAdvantage) {
            this.verticalAdvantage = verticalAdvantage;
            return this;
        }

        public Builder uniqueAttackers(int uniqueAttackers) {
            this.uniqueAttackers = uniqueAttackers;
            return this;
        }

        public Builder playerKills(int playerKills) {
            this.playerKills = playerKills;
            return this;
        }

        public Builder shieldBlocks(int shieldBlocks) {
            this.shieldBlocks = shieldBlocks;
            return this;
        }

        public Builder burstDamageRatio(double burstDamageRatio) {
            this.burstDamageRatio = burstDamageRatio;
            return this;
        }

        public Builder damageToPlayers(double damageToPlayers) {
            this.damageToPlayers = damageToPlayers;
            return this;
        }

        public Builder fightDurationTicks(long fightDurationTicks) {
            this.fightDurationTicks = fightDurationTicks;
            return this;
        }

        public EncounterSample build() {
            return new EncounterSample(this);
        }
    }
}