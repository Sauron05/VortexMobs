package com.sauron.vortexmobs.fabric;

import com.sauron.vortexmobs.core.EncounterSample;
import com.sauron.vortexmobs.core.MobArchetype;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;

public final class TrackedMobState {

    private final UUID entityId;
    private final MobArchetype archetype;
    private final boolean boss;
    private final long createdAtMillis;
    private final double baseSpeed;
    private final double baseAttackDamage;
    private final double baseFollowRange;
    private final double baseKnockbackResistance;
    private final double baseMaxHealth;
    private final Set<UUID> attackers = new HashSet<>();
    private UUID lastAttackerId;
    private double projectileDamage;
    private double meleeDamage;
    private double damageToPlayers;
    private double highestHit;
    private double distanceSum;
    private int distanceSamples;
    private double highestVerticalAdvantage;
    private int shieldBlocks;
    private int playerKills;
    private boolean lastHitProjectile;
    private boolean lastAttackerUsedRanged;
    private int nextLeapTick;
    private int nextDashTick;
    private int nextRoarTick;
    private int nextSummonTick;

    public TrackedMobState(LivingEntity entity, boolean boss) {
        this.entityId = entity.getUuid();
        this.archetype = boss
                ? MobArchetype.BOSS
                : MobArchetype.fromEntityName(Registries.ENTITY_TYPE.getId(entity.getType()).getPath().toUpperCase().replace('-', '_'));
        this.boss = boss;
        this.createdAtMillis = System.currentTimeMillis();
        this.baseSpeed = baseValue(entity, EntityAttributes.MOVEMENT_SPEED, 0.25D);
        this.baseAttackDamage = baseValue(entity, EntityAttributes.ATTACK_DAMAGE, 2.0D);
        this.baseFollowRange = baseValue(entity, EntityAttributes.FOLLOW_RANGE, 32.0D);
        this.baseKnockbackResistance = baseValue(entity, EntityAttributes.KNOCKBACK_RESISTANCE, 0.0D);
        this.baseMaxHealth = baseValue(entity, EntityAttributes.MAX_HEALTH, Math.max(20.0D, entity.getHealth()));
    }

    public UUID entityId() {
        return entityId;
    }

    public MobArchetype archetype() {
        return archetype;
    }

    public boolean boss() {
        return boss;
    }

    public double baseSpeed() {
        return baseSpeed;
    }

    public double baseAttackDamage() {
        return baseAttackDamage;
    }

    public double baseFollowRange() {
        return baseFollowRange;
    }

    public double baseKnockbackResistance() {
        return baseKnockbackResistance;
    }

    public double baseMaxHealth() {
        return baseMaxHealth;
    }

    public UUID lastAttackerId() {
        return lastAttackerId;
    }

    public boolean readyForLeap(int tick) {
        return tick >= nextLeapTick;
    }

    public void markLeap(int tick, int cooldown) {
        nextLeapTick = tick + cooldown;
    }

    public boolean readyForDash(int tick) {
        return tick >= nextDashTick;
    }

    public void markDash(int tick, int cooldown) {
        nextDashTick = tick + cooldown;
    }

    public boolean readyForRoar(int tick) {
        return tick >= nextRoarTick;
    }

    public void markRoar(int tick, int cooldown) {
        nextRoarTick = tick + cooldown;
    }

    public boolean readyForSummon(int tick) {
        return tick >= nextSummonTick;
    }

    public void markSummon(int tick, int cooldown) {
        nextSummonTick = tick + cooldown;
    }

    public void recordIncoming(ServerPlayerEntity attacker, float damage, boolean projectile, double distance, double verticalAdvantage, boolean attackerUsedRanged) {
        attackers.add(attacker.getUuid());
        lastAttackerId = attacker.getUuid();
        lastAttackerUsedRanged = attackerUsedRanged;
        lastHitProjectile = projectile;
        highestVerticalAdvantage = Math.max(highestVerticalAdvantage, verticalAdvantage);
        distanceSum += Math.max(0.0D, distance);
        distanceSamples++;
        if (projectile) {
            projectileDamage += damage;
        } else {
            meleeDamage += damage;
        }
        highestHit = Math.max(highestHit, damage);
    }

    public void recordOutgoing(float damage, boolean blocked) {
        damageToPlayers += Math.max(0.0D, damage);
        if (blocked) {
            shieldBlocks++;
        }
    }

    public void recordPlayerKill() {
        playerKills++;
    }

    public EncounterSample toSample() {
        double totalIncoming = projectileDamage + meleeDamage;
        double burstRatio = totalIncoming <= 0.0D ? 0.0D : Math.min(1.0D, highestHit / totalIncoming);
        return EncounterSample.builder(archetype)
                .bossFight(boss)
                .killedByProjectile(lastHitProjectile)
                .killerUsedRanged(lastAttackerUsedRanged)
                .playerWonQuickly(((System.currentTimeMillis() - createdAtMillis) / 50L) < 160L)
                .projectileDamageRatio(totalIncoming <= 0.0D ? 0.0D : projectileDamage / totalIncoming)
                .meleeDamageRatio(totalIncoming <= 0.0D ? 0.0D : meleeDamage / totalIncoming)
                .averageDistance(distanceSamples == 0 ? 0.0D : distanceSum / distanceSamples)
                .verticalAdvantage(highestVerticalAdvantage)
                .uniqueAttackers(attackers.size())
                .playerKills(playerKills)
                .shieldBlocks(shieldBlocks)
                .burstDamageRatio(burstRatio)
                .damageToPlayers(damageToPlayers)
                .fightDurationTicks(Math.max(1L, (System.currentTimeMillis() - createdAtMillis) / 50L))
                .build();
    }

    private static double baseValue(LivingEntity entity, RegistryEntry<EntityAttribute> attribute, double fallback) {
        EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
        return instance == null ? fallback : instance.getBaseValue();
    }
}