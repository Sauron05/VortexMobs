package com.sauron.vortexmobs.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerBrain {

    private static final double SIGNAL_CAP = 12.0D;
    private static final int DATA_VERSION = 1;

    private final ServerGenome genome;
    private int dataVersion;
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

    public ServerBrain(ServerGenome genome) {
        this(genome, DATA_VERSION, 0, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, System.currentTimeMillis());
    }

    public ServerBrain(
            ServerGenome genome,
            int dataVersion,
            int encounters,
            int playerKills,
            double rangedPressure,
            double meleePressure,
            double kitingPressure,
            double highGroundPressure,
            double shieldingPressure,
            double focusFirePressure,
            double burstPressure,
            double aggression,
            double bossEvolution,
            long lastUpdatedEpochMillis
    ) {
        this.genome = genome == null ? ServerGenome.create() : genome;
        this.dataVersion = dataVersion;
        this.encounters = encounters;
        this.playerKills = playerKills;
        this.rangedPressure = rangedPressure;
        this.meleePressure = meleePressure;
        this.kitingPressure = kitingPressure;
        this.highGroundPressure = highGroundPressure;
        this.shieldingPressure = shieldingPressure;
        this.focusFirePressure = focusFirePressure;
        this.burstPressure = burstPressure;
        this.aggression = aggression;
        this.bossEvolution = bossEvolution;
        this.lastUpdatedEpochMillis = lastUpdatedEpochMillis;
    }

    public synchronized ServerGenome genome() {
        return genome;
    }

    public synchronized int dataVersion() {
        return dataVersion;
    }

    public synchronized int encounters() {
        return encounters;
    }

    public synchronized int playerKills() {
        return playerKills;
    }

    public synchronized long lastUpdatedEpochMillis() {
        return lastUpdatedEpochMillis;
    }

    public synchronized void reset() {
        dataVersion = DATA_VERSION;
        encounters = 0;
        playerKills = 0;
        rangedPressure = 0.0D;
        meleePressure = 0.0D;
        kitingPressure = 0.0D;
        highGroundPressure = 0.0D;
        shieldingPressure = 0.0D;
        focusFirePressure = 0.0D;
        burstPressure = 0.0D;
        aggression = 0.0D;
        bossEvolution = 0.0D;
        lastUpdatedEpochMillis = System.currentTimeMillis();
    }

    public synchronized void recordEncounter(EncounterSample sample) {
        long now = System.currentTimeMillis();
        applyTimeDecay(now);

        encounters++;
        playerKills += sample.playerKills();

        double intensity = sample.encounterIntensity();
        rangedPressure = addSignal(rangedPressure, (sample.projectileDamageRatio() * 1.4D) + (sample.killedByProjectile() ? 1.2D : 0.0D), intensity);
        meleePressure = addSignal(meleePressure, sample.meleeDamageRatio() * 0.9D, intensity);
        kitingPressure = addSignal(
                kitingPressure,
                (MathUtil.clamp(sample.averageDistance() / 11.0D, 0.0D, 1.45D) * 1.1D) + (sample.killerUsedRanged() ? 0.35D : 0.0D),
                intensity
        );
        highGroundPressure = addSignal(highGroundPressure, MathUtil.clamp(sample.verticalAdvantage() / 4.5D, 0.0D, 1.4D), intensity);
        shieldingPressure = addSignal(shieldingPressure, Math.min(1.5D, sample.shieldBlocks() * 0.22D), intensity);
        focusFirePressure = addSignal(focusFirePressure, Math.min(1.6D, sample.focusTeamPressure() * 0.4D), intensity);
        burstPressure = addSignal(burstPressure, sample.burstDamageRatio() * 1.25D, intensity);

        aggression = MathUtil.clamp(
                aggression
                        + (sample.playerWonQuickly() ? 0.6D : 0.18D)
                        + (sample.killerUsedRanged() ? 0.22D : 0.0D)
                        + (sample.playerKills() > 0 ? 0.4D : 0.0D),
                0.0D,
                SIGNAL_CAP
        );

        double bossDelta = (sample.bossFight() ? 0.9D : 0.35D)
                + (sample.projectileDamageRatio() * 0.55D)
                + (sample.focusTeamPressure() * 0.35D)
                + (sample.playerWonQuickly() ? 0.25D : 0.0D);
        bossEvolution = MathUtil.clamp(bossEvolution + bossDelta, 0.0D, SIGNAL_CAP * 2.0D);

        lastUpdatedEpochMillis = now;
    }

    public synchronized AdaptationPlan planFor(MobArchetype archetype, CombatSnapshot snapshot, boolean boss) {
        applyTimeDecay(System.currentTimeMillis());

        double distanceFactor = MathUtil.clamp(snapshot.distanceToTarget() / 12.0D, 0.0D, 1.2D);
        double heightFactor = MathUtil.clamp(Math.max(0.0D, snapshot.verticalDifference()) / 5.0D, 0.0D, 1.1D);
        double rangedFactor = snapshot.targetUsingRanged() ? 1.0D : 0.0D;
        double shieldingFactor = snapshot.targetBlocking() ? 1.0D : 0.0D;
        double allyFactor = MathUtil.clamp(snapshot.nearbyAllies() / 5.0D, 0.0D, 1.0D);
        double pressureFactor = MathUtil.clamp(snapshot.recentIncomingDamage() / 10.0D, 0.0D, 1.0D);

        double gapCloseUrgency = MathUtil.clamp01(
                (rangedPressure * 0.065D)
                        + (kitingPressure * 0.085D)
                        + (distanceFactor * 0.55D)
                        + (rangedFactor * 0.35D)
                        + (snapshot.lineOfSight() ? 0.15D : -0.05D)
        );

        double antiTowerUrgency = MathUtil.clamp01((highGroundPressure * 0.08D) + (heightFactor * 0.75D));
        double packFocusBonus = MathUtil.clamp01((focusFirePressure * 0.08D) + (allyFactor * 0.55D));
        double shieldBreakIntent = MathUtil.clamp01((shieldingPressure * 0.11D) + (shieldingFactor * 0.55D));

        double archetypeSpeedBias = switch (archetype) {
            case RANGER -> 0.03D;
            case AMBUSHER -> 0.08D;
            case SWARMER -> 0.05D;
            case BOSS -> 0.1D;
            default -> 0.0D;
        };
        double archetypeDamageBias = switch (archetype) {
            case BRUTE, BOSS -> 0.12D;
            case AMBUSHER -> 0.06D;
            default -> 0.0D;
        };

        double threat = threatLevel();
        double speedMultiplier = 1.0D
                + Math.min(0.65D, (aggression * 0.035D) + (gapCloseUrgency * 0.28D) + archetypeSpeedBias);
        double attackMultiplier = 1.0D
                + Math.min(0.8D, (meleePressure * 0.03D) + (shieldBreakIntent * 0.22D) + (pressureFactor * 0.18D) + archetypeDamageBias);
        double knockbackResistance = MathUtil.clamp((aggression * 0.035D) + (burstPressure * 0.04D), 0.0D, boss ? 0.85D : 0.65D);
        double followRangeBonus = Math.min(20.0D, (threat * 2.8D) + (gapCloseUrgency * 8.0D));
        double leapStrength = Math.min(1.55D, (gapCloseUrgency * 0.95D) + (antiTowerUrgency * 0.55D));
        int jumpBoostAmplifier = antiTowerUrgency > 0.78D ? 2 : antiTowerUrgency > 0.44D ? 1 : 0;
        double rangedPunishChance = MathUtil.clamp01((rangedPressure * 0.09D) + (gapCloseUrgency * 0.35D) + (rangedFactor * 0.2D));
        double targetSwapUrgency = MathUtil.clamp01((focusFirePressure * 0.06D) + (snapshot.nearbyPlayers() > 1 ? 0.32D : 0.0D));

        Set<BossAbility> bossAbilities = EnumSet.noneOf(BossAbility.class);
        if (boss) {
            int evolutionStage = evolutionStage();
            if (evolutionStage >= 2) {
                bossAbilities.add(BossAbility.PHASE_DASH);
            }
            if (evolutionStage >= 3 && packFocusBonus > 0.35D) {
                bossAbilities.add(BossAbility.ROAR_DISRUPTION);
            }
            if (evolutionStage >= 4 && rangedPunishChance > 0.45D) {
                bossAbilities.add(BossAbility.SUMMON_INTERCEPTORS);
            }
            if (evolutionStage >= 3 && shieldBreakIntent > 0.5D) {
                bossAbilities.add(BossAbility.SHIELD_PUNISH);
            }
            if (evolutionStage >= 5 && gapCloseUrgency > 0.65D) {
                bossAbilities.add(BossAbility.HUNTER_SURGE);
            }
        }

        return new AdaptationPlan(
                speedMultiplier,
                attackMultiplier,
                knockbackResistance,
                followRangeBonus,
                leapStrength,
                jumpBoostAmplifier,
                packFocusBonus,
                rangedPunishChance,
                targetSwapUrgency,
                threat,
                bossAbilities
        );
    }

    public synchronized int evolutionStage() {
        double score = bossEvolution + (encounters * 0.15D) + (playerKills * 0.35D);
        if (score >= 18.0D) {
            return 5;
        }
        if (score >= 13.0D) {
            return 4;
        }
        if (score >= 8.0D) {
            return 3;
        }
        if (score >= 4.0D) {
            return 2;
        }
        return 1;
    }

    public synchronized double threatLevel() {
        return MathUtil.clamp(
                1.0D
                        + (encounters * 0.03D)
                        + (rangedPressure * 0.12D)
                        + (kitingPressure * 0.12D)
                        + (highGroundPressure * 0.08D)
                        + (focusFirePressure * 0.1D)
                        + (aggression * 0.08D),
                1.0D,
                5.0D
        );
    }

    public synchronized List<String> dominantSignals() {
        Map<String, Double> signals = new LinkedHashMap<>();
        signals.put("anti-archer gap closing", rangedPressure + kitingPressure);
        signals.put("high-ground punishment", highGroundPressure);
        signals.put("shield cracking", shieldingPressure);
        signals.put("focus-fire disruption", focusFirePressure);
        signals.put("burst survival", burstPressure);
        signals.put("raw aggression", aggression);

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(signals.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());

        List<String> summary = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            Map.Entry<String, Double> entry = sorted.get(i);
            if (entry.getValue() > 0.35D) {
                summary.add(entry.getKey());
            }
        }
        return summary;
    }

    public synchronized String shortSummary() {
        List<String> signals = dominantSignals();
        String signalText = signals.isEmpty() ? "still profiling your players" : String.join(", ", signals);
        return "Threat " + String.format("%.2f", threatLevel())
                + " | Stage " + evolutionStage()
                + " | Signals: " + signalText;
    }

    public synchronized double rangedPressure() {
        return rangedPressure;
    }

    public synchronized double meleePressure() {
        return meleePressure;
    }

    public synchronized double kitingPressure() {
        return kitingPressure;
    }

    public synchronized double highGroundPressure() {
        return highGroundPressure;
    }

    public synchronized double shieldingPressure() {
        return shieldingPressure;
    }

    public synchronized double focusFirePressure() {
        return focusFirePressure;
    }

    public synchronized double burstPressure() {
        return burstPressure;
    }

    public synchronized double aggression() {
        return aggression;
    }

    public synchronized double bossEvolution() {
        return bossEvolution;
    }

    private double addSignal(double current, double delta, double intensity) {
        return MathUtil.clamp(current + (delta * Math.max(0.45D, intensity * 0.22D)), 0.0D, SIGNAL_CAP);
    }

    private void applyTimeDecay(long now) {
        if (lastUpdatedEpochMillis <= 0L) {
            lastUpdatedEpochMillis = now;
            return;
        }

        long elapsedMillis = Math.max(0L, now - lastUpdatedEpochMillis);
        if (elapsedMillis < 60_000L) {
            return;
        }

        double hours = elapsedMillis / 3_600_000.0D;
        double decayFactor = Math.pow(0.988D, hours);

        rangedPressure *= decayFactor;
        meleePressure *= MathUtil.lerp(1.0D, decayFactor, 0.75D);
        kitingPressure *= decayFactor;
        highGroundPressure *= decayFactor;
        shieldingPressure *= decayFactor;
        focusFirePressure *= decayFactor;
        burstPressure *= decayFactor;
        aggression *= MathUtil.lerp(1.0D, decayFactor, 0.6D);
        bossEvolution *= MathUtil.lerp(1.0D, decayFactor, 0.4D);
        lastUpdatedEpochMillis = now;
    }
}