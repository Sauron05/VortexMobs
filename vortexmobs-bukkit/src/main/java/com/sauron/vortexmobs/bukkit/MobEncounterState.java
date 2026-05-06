package com.sauron.vortexmobs.bukkit;

import com.sauron.vortexmobs.core.EncounterSample;
import com.sauron.vortexmobs.core.MobArchetype;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class MobEncounterState {

    private final UUID entityId;
    private final EntityType entityType;
    private final MobArchetype archetype;
    private final boolean boss;
    private final long createdAtMillis;
    private final Set<UUID> attackers = new HashSet<>();
    private final double baseSpeed;
    private final double baseAttackDamage;
    private final double baseFollowRange;
    private final double baseKnockbackResistance;
    private final double baseMaxHealth;
    private final BossBar bossBar;
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
    private long nextLeapTick;
    private long nextDashTick;
    private long nextRoarTick;
    private long nextSummonTick;
    private SchedulerBridge.ScheduledHandle scheduledHandle;

    public MobEncounterState(LivingEntity entity, boolean boss, String bossName, BarColor barColor) {
        this.entityId = entity.getUniqueId();
        this.entityType = entity.getType();
        this.archetype = boss ? MobArchetype.BOSS : MobArchetype.fromEntityName(entity.getType().name());
        this.boss = boss;
        this.createdAtMillis = System.currentTimeMillis();
        this.baseSpeed = baseValue(entity, Attribute.MOVEMENT_SPEED, 0.25D);
        this.baseAttackDamage = baseValue(entity, Attribute.ATTACK_DAMAGE, 2.0D);
        this.baseFollowRange = baseValue(entity, Attribute.FOLLOW_RANGE, 32.0D);
        this.baseKnockbackResistance = baseValue(entity, Attribute.KNOCKBACK_RESISTANCE, 0.0D);
        this.baseMaxHealth = baseValue(entity, Attribute.MAX_HEALTH, Math.max(20.0D, entity.getHealth()));
        this.bossBar = boss ? Bukkit.createBossBar(ChatColor.translateAlternateColorCodes('&', bossName), barColor, BarStyle.SEGMENTED_10) : null;
    }

    public UUID entityId() {
        return entityId;
    }

    public EntityType entityType() {
        return entityType;
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

    public void setScheduledHandle(SchedulerBridge.ScheduledHandle scheduledHandle) {
        this.scheduledHandle = scheduledHandle;
    }

    public boolean readyForLeap(long tick) {
        return tick >= nextLeapTick;
    }

    public void markLeap(long tick, long cooldown) {
        nextLeapTick = tick + cooldown;
    }

    public boolean readyForDash(long tick) {
        return tick >= nextDashTick;
    }

    public void markDash(long tick, long cooldown) {
        nextDashTick = tick + cooldown;
    }

    public boolean readyForRoar(long tick) {
        return tick >= nextRoarTick;
    }

    public void markRoar(long tick, long cooldown) {
        nextRoarTick = tick + cooldown;
    }

    public boolean readyForSummon(long tick) {
        return tick >= nextSummonTick;
    }

    public void markSummon(long tick, long cooldown) {
        nextSummonTick = tick + cooldown;
    }

    public void recordIncoming(Player attacker, double damage, boolean projectile, double distance, double verticalAdvantage, boolean attackerUsedRanged) {
        attackers.add(attacker.getUniqueId());
        lastAttackerId = attacker.getUniqueId();
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

    public void recordOutgoing(double damage, boolean blocked) {
        damageToPlayers += Math.max(0.0D, damage);
        if (blocked) {
            shieldBlocks++;
        }
    }

    public void recordPlayerKill() {
        playerKills++;
    }

    public void updateBossBar(LivingEntity entity, Player currentTarget, String title) {
        if (bossBar == null) {
            return;
        }

        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
        double maxHealth = Math.max(1.0D, baseValue(entity, Attribute.MAX_HEALTH, baseMaxHealth));
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, entity.getHealth() / maxHealth)));
        if (currentTarget != null) {
            bossBar.addPlayer(currentTarget);
        }
    }

    public void cancel() {
        if (scheduledHandle != null) {
            scheduledHandle.cancel();
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
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

    private static double baseValue(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getBaseValue();
    }
}