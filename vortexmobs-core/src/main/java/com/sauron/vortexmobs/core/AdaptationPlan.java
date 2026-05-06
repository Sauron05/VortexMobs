package com.sauron.vortexmobs.core;

import java.util.EnumSet;
import java.util.Set;

public final class AdaptationPlan {

    private final double speedMultiplier;
    private final double attackMultiplier;
    private final double knockbackResistance;
    private final double followRangeBonus;
    private final double leapStrength;
    private final int jumpBoostAmplifier;
    private final double packFocusBonus;
    private final double rangedPunishChance;
    private final double targetSwapUrgency;
    private final double threatLevel;
    private final Set<BossAbility> bossAbilities;

    public AdaptationPlan(
            double speedMultiplier,
            double attackMultiplier,
            double knockbackResistance,
            double followRangeBonus,
            double leapStrength,
            int jumpBoostAmplifier,
            double packFocusBonus,
            double rangedPunishChance,
            double targetSwapUrgency,
            double threatLevel,
            Set<BossAbility> bossAbilities
    ) {
        this.speedMultiplier = Math.max(1.0D, speedMultiplier);
        this.attackMultiplier = Math.max(1.0D, attackMultiplier);
        this.knockbackResistance = MathUtil.clamp(knockbackResistance, 0.0D, 1.0D);
        this.followRangeBonus = Math.max(0.0D, followRangeBonus);
        this.leapStrength = Math.max(0.0D, leapStrength);
        this.jumpBoostAmplifier = Math.max(0, jumpBoostAmplifier);
        this.packFocusBonus = Math.max(0.0D, packFocusBonus);
        this.rangedPunishChance = MathUtil.clamp01(rangedPunishChance);
        this.targetSwapUrgency = MathUtil.clamp01(targetSwapUrgency);
        this.threatLevel = Math.max(0.0D, threatLevel);
        this.bossAbilities = bossAbilities == null || bossAbilities.isEmpty()
                ? EnumSet.noneOf(BossAbility.class)
                : EnumSet.copyOf(bossAbilities);
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public double attackMultiplier() {
        return attackMultiplier;
    }

    public double knockbackResistance() {
        return knockbackResistance;
    }

    public double followRangeBonus() {
        return followRangeBonus;
    }

    public double leapStrength() {
        return leapStrength;
    }

    public int jumpBoostAmplifier() {
        return jumpBoostAmplifier;
    }

    public double packFocusBonus() {
        return packFocusBonus;
    }

    public double rangedPunishChance() {
        return rangedPunishChance;
    }

    public double targetSwapUrgency() {
        return targetSwapUrgency;
    }

    public double threatLevel() {
        return threatLevel;
    }

    public Set<BossAbility> bossAbilities() {
        return Set.copyOf(bossAbilities);
    }
}