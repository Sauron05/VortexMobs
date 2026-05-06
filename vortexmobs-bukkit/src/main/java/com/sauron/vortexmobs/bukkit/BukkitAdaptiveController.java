package com.sauron.vortexmobs.bukkit;

import com.sauron.vortexmobs.core.AdaptationPlan;
import com.sauron.vortexmobs.core.BossAbility;
import com.sauron.vortexmobs.core.CombatSnapshot;
import com.sauron.vortexmobs.core.ServerBrain;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

public final class BukkitAdaptiveController implements Listener {

    private static final long LEAP_COOLDOWN_TICKS = 18L;
    private static final long DASH_COOLDOWN_TICKS = 60L;
    private static final long ROAR_COOLDOWN_TICKS = 120L;
    private static final long SUMMON_COOLDOWN_TICKS = 180L;

    private final VortexMobsPlugin plugin;
    private final ServerBrain brain;
    private final BukkitBrainRepository repository;
    private final SchedulerBridge scheduler;
    private final MessageService messages;
    private final Logger logger;
    private final NamespacedKey bossKey;
    private final Map<UUID, MobEncounterState> trackedMobs = new ConcurrentHashMap<>();
    private final Set<EntityType> whitelist = new HashSet<>();
    private final Set<EntityType> blacklist = new HashSet<>();
    private SchedulerBridge.ScheduledHandle saveHandle;
    private boolean adaptiveEnabled;
    private boolean autoTrackHostiles;
    private boolean autoTrackExisting;
    private boolean announceStages;
    private boolean summonMinions;
    private boolean debug;
    private int updateIntervalTicks;
    private int saveIntervalTicks;
    private int maxTrackedMobs;
    private double scanRadius;
    private double bossAcquireRange;
    private double bossBaseHealth;
    private String bossName;
    private EntityType bossEntityType;
    private BarColor bossBarColor;

    public BukkitAdaptiveController(
            VortexMobsPlugin plugin,
            ServerBrain brain,
            BukkitBrainRepository repository,
            SchedulerBridge scheduler,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.brain = brain;
        this.repository = repository;
        this.scheduler = scheduler;
        this.messages = messages;
        this.logger = plugin.getLogger();
        this.bossKey = new NamespacedKey(plugin, "adaptive_boss");
    }

    public void start() {
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduleAutosave();
        if (autoTrackExisting) {
            bootstrapExistingEntities();
        }
    }

    public void shutdown() {
        if (saveHandle != null) {
            saveHandle.cancel();
        }
        for (MobEncounterState state : trackedMobs.values()) {
            state.cancel();
        }
        trackedMobs.clear();
        repository.save(brain);
    }

    public void reload() {
        plugin.reloadConfig();
        messages.reload();
        reloadSettings();
        scheduleAutosave();
    }

    public ServerBrain getBrain() {
        return brain;
    }

    public void resetBrain() {
        brain.reset();
        repository.save(brain);
    }

    public void spawnBoss(Player player) {
        if (!plugin.getConfig().getBoolean("boss.enabled", true)) {
            return;
        }

        EntityType spawnType = bossEntityType.getEntityClass() != null && LivingEntity.class.isAssignableFrom(bossEntityType.getEntityClass())
                ? bossEntityType
                : EntityType.HUSK;
        LivingEntity entity = (LivingEntity) player.getWorld().spawnEntity(player.getLocation(), spawnType);
        entity.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
        entity.setCustomName(MessageService.color(bossName));
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);

        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(bossBaseHealth);
        }
        entity.setHealth(Math.min(bossBaseHealth, entity.getHealth()));

        if (entity instanceof Mob mob) {
            mob.setTarget(player);
        }

        trackEntity(entity, true);
        Bukkit.broadcastMessage(messages.prefix() + messages.get("boss-spawned"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!adaptiveEnabled || !autoTrackHostiles || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }

        if (shouldTrack(event.getEntity(), false)) {
            trackEntity(event.getEntity(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player playerSource = resolvePlayerDamager(event.getDamager());
        if (playerSource != null && event.getEntity() instanceof LivingEntity living) {
            MobEncounterState state = trackedMobs.get(living.getUniqueId());
            if (state != null && playerSource.getWorld().equals(living.getWorld())) {
                boolean projectile = event.getDamager() instanceof Projectile;
                boolean attackerUsedRanged = projectile || isRangedWeapon(playerSource.getInventory().getItemInMainHand());
                double distance = playerSource.getLocation().distance(living.getLocation());
                double vertical = playerSource.getLocation().getY() - living.getLocation().getY();
                state.recordIncoming(playerSource, event.getFinalDamage(), projectile, distance, vertical, attackerUsedRanged);
            }
        }

        LivingEntity damageSource = resolveLivingDamager(event.getDamager());
        if (damageSource != null && event.getEntity() instanceof Player player) {
            MobEncounterState state = trackedMobs.get(damageSource.getUniqueId());
            if (state != null) {
                state.recordOutgoing(event.getFinalDamage(), player.isBlocking());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        MobEncounterState state = trackedMobs.remove(event.getEntity().getUniqueId());
        if (state == null) {
            return;
        }

        int previousStage = brain.evolutionStage();
        brain.recordEncounter(state.toSample());
        state.cancel();
        repository.save(brain);

        int nextStage = brain.evolutionStage();
        if (announceStages && nextStage > previousStage) {
            String signal = brain.dominantSignals().isEmpty() ? "baseline escalation" : brain.dominantSignals().get(0);
            Bukkit.broadcastMessage(messages.prefix() + messages.format(
                    "stage-up",
                    Map.of(
                            "stage", String.valueOf(nextStage),
                            "signal", signal
                    )
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!(event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent)) {
            return;
        }

        LivingEntity source = resolveLivingDamager(damageEvent.getDamager());
        if (source == null) {
            return;
        }

        MobEncounterState state = trackedMobs.get(source.getUniqueId());
        if (state != null) {
            state.recordPlayerKill();
        }
    }

    private void bootstrapExistingEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                boolean boss = entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
                if (shouldTrack(entity, boss)) {
                    trackEntity(entity, boss);
                }
            }
        }
    }

    private void scheduleAutosave() {
        if (saveHandle != null) {
            saveHandle.cancel();
        }
        saveHandle = scheduler.runRepeatingGlobal(() -> repository.save(brain), saveIntervalTicks, saveIntervalTicks);
    }

    private void reloadSettings() {
        ConfigurationSection adaptive = plugin.getConfig().getConfigurationSection("adaptive-learning");
        adaptiveEnabled = adaptive == null || adaptive.getBoolean("enabled", true);
        autoTrackHostiles = adaptive == null || adaptive.getBoolean("auto-track-hostile-spawns", true);
        autoTrackExisting = adaptive == null || adaptive.getBoolean("auto-track-existing-on-enable", true);
        updateIntervalTicks = adaptive == null ? 10 : Math.max(5, adaptive.getInt("update-interval-ticks", 10));
        saveIntervalTicks = adaptive == null ? 2400 : Math.max(200, adaptive.getInt("save-interval-ticks", 2400));
        maxTrackedMobs = adaptive == null ? 500 : Math.max(50, adaptive.getInt("max-tracked-mobs", 500));
        scanRadius = adaptive == null ? 14.0D : Math.max(6.0D, adaptive.getDouble("scan-radius", 14.0D));
        announceStages = adaptive == null || adaptive.getBoolean("announce-stage-upgrades", true);
        debug = plugin.getConfig().getBoolean("debug.log-adaptations", false);

        whitelist.clear();
        blacklist.clear();
        whitelist.addAll(parseEntityTypes(plugin.getConfig().getStringList("tracked-mobs.entity-whitelist")));
        blacklist.addAll(parseEntityTypes(plugin.getConfig().getStringList("tracked-mobs.entity-blacklist")));

        bossName = plugin.getConfig().getString("boss.name", "&4&lNeural Sovereign");
        bossEntityType = parseEntityType(plugin.getConfig().getString("boss.entity-type", "HUSK"), EntityType.HUSK);
        bossBaseHealth = Math.max(100.0D, plugin.getConfig().getDouble("boss.base-health", 300.0D));
        bossAcquireRange = Math.max(10.0D, plugin.getConfig().getDouble("boss.acquire-range", 28.0D));
        bossBarColor = parseBarColor(plugin.getConfig().getString("boss.bar-color", "RED"));
        summonMinions = plugin.getConfig().getBoolean("boss.summon-minions", true);
    }

    private MobEncounterState trackEntity(LivingEntity entity, boolean boss) {
        MobEncounterState existing = trackedMobs.get(entity.getUniqueId());
        if (existing != null) {
            return existing;
        }
        if (!boss && trackedMobs.size() >= maxTrackedMobs) {
            return null;
        }

        MobEncounterState state = new MobEncounterState(entity, boss, bossName, bossBarColor);
        trackedMobs.put(entity.getUniqueId(), state);
        state.setScheduledHandle(scheduler.runRepeatingEntity(entity, living -> tickEntity(living, state), updateIntervalTicks, updateIntervalTicks));

        if (debug) {
            logger.info("Tracking " + entity.getType() + " as " + state.archetype());
        }
        return state;
    }

    private void tickEntity(LivingEntity entity, MobEncounterState state) {
        if (!adaptiveEnabled || !entity.isValid() || entity.isDead()) {
            trackedMobs.remove(state.entityId());
            state.cancel();
            return;
        }

        Player target = resolveTarget(entity, state);
        CombatSnapshot snapshot = createSnapshot(entity, target);
        AdaptationPlan plan = brain.planFor(state.archetype(), snapshot, state.boss());
        applyPlan(entity, state, plan);

        if (target == null) {
            if (state.boss()) {
                state.updateBossBar(entity, null, bossBarTitle(plan));
            }
            return;
        }

        long tick = entity.getWorld().getFullTime();
        if (plan.targetSwapUrgency() > 0.5D && !scheduler.isFolia()) {
            Player weakerTarget = findLowerHealthNearbyPlayer(entity, target);
            if (weakerTarget != null && entity instanceof Mob mob) {
                mob.setTarget(weakerTarget);
                target = weakerTarget;
            }
        }

        if (plan.packFocusBonus() > 0.35D && !scheduler.isFolia()) {
            spreadTarget(entity, target);
        }

        if (plan.rangedPunishChance() > 0.25D && state.readyForLeap(tick) && shouldLeap(snapshot)) {
            launchToward(entity, target, 0.42D + (plan.leapStrength() * 0.26D), 0.24D + (plan.jumpBoostAmplifier() * 0.06D));
            state.markLeap(tick, LEAP_COOLDOWN_TICKS);
        }

        if (state.boss()) {
            handleBossAbilities(entity, target, state, plan, tick);
            state.updateBossBar(entity, target, bossBarTitle(plan));
        }
    }

    private CombatSnapshot createSnapshot(LivingEntity entity, Player target) {
        if (target == null) {
            return new CombatSnapshot(0.0D, 0.0D, 0, 0, false, false, true, 1.0D, 0.0D);
        }

        double distance = entity.getLocation().distance(target.getLocation());
        double verticalDifference = target.getLocation().getY() - entity.getLocation().getY();
        int nearbyAllies = scheduler.isFolia() ? 0 : countNearbyAllies(entity);
        int nearbyPlayers = scheduler.isFolia() ? 1 : Math.max(1, countNearbyPlayers(entity));
        AttributeInstance healthAttribute = target.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = healthAttribute == null ? 20.0D : Math.max(1.0D, healthAttribute.getValue());

        return new CombatSnapshot(
                distance,
                verticalDifference,
                nearbyAllies,
                nearbyPlayers,
                isRangedWeapon(target.getInventory().getItemInMainHand()),
                target.isBlocking(),
                entity.hasLineOfSight(target),
                target.getHealth() / maxHealth,
                0.0D
        );
    }

    private void applyPlan(LivingEntity entity, MobEncounterState state, AdaptationPlan plan) {
        setAttribute(entity, Attribute.MOVEMENT_SPEED, state.baseSpeed() * plan.speedMultiplier());
        setAttribute(entity, Attribute.ATTACK_DAMAGE, state.baseAttackDamage() * plan.attackMultiplier());
        setAttribute(entity, Attribute.FOLLOW_RANGE, state.baseFollowRange() + Math.min(18.0D, plan.followRangeBonus()));
        setAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0D, state.baseKnockbackResistance() + plan.knockbackResistance()));

        if (state.boss()) {
            setAttribute(entity, Attribute.MAX_HEALTH, Math.max(state.baseMaxHealth(), bossBaseHealth * (1.0D + ((plan.threatLevel() - 1.0D) * 0.1D))));
            AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && entity.getHealth() > maxHealth.getValue()) {
                entity.setHealth(maxHealth.getValue());
            }
        }

        if (plan.jumpBoostAmplifier() > 0) {
            entity.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, updateIntervalTicks + 10, plan.jumpBoostAmplifier() - 1, true, false, false));
        }
        if (plan.speedMultiplier() > 1.18D) {
            int amplifier = Math.min(2, (int) Math.floor((plan.speedMultiplier() - 1.0D) * 2.5D));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, updateIntervalTicks + 10, amplifier, true, false, false));
        }
    }

    private void handleBossAbilities(LivingEntity entity, Player target, MobEncounterState state, AdaptationPlan plan, long tick) {
        if (plan.bossAbilities().contains(BossAbility.PHASE_DASH) && state.readyForDash(tick) && target.getLocation().distance(entity.getLocation()) > 7.0D) {
            launchToward(entity, target, 0.95D, 0.30D);
            state.markDash(tick, DASH_COOLDOWN_TICKS);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 1.0F, 0.7F);
        }

        if (plan.bossAbilities().contains(BossAbility.ROAR_DISRUPTION) && state.readyForRoar(tick) && !scheduler.isFolia()) {
            for (Entity nearby : entity.getNearbyEntities(10.0D, 6.0D, 10.0D)) {
                if (nearby instanceof Player player) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, false, true));
                }
            }
            state.markRoar(tick, ROAR_COOLDOWN_TICKS);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0F, 0.8F);
        }

        if (plan.bossAbilities().contains(BossAbility.SHIELD_PUNISH) && target.isBlocking()) {
            target.setCooldown(Material.SHIELD, 60);
            target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 50, 0, true, false, false));
        }

        if (plan.bossAbilities().contains(BossAbility.HUNTER_SURGE)) {
            entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, updateIntervalTicks + 20, 0, true, false, false));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, updateIntervalTicks + 20, 0, true, false, false));
        }

        if (summonMinions && plan.bossAbilities().contains(BossAbility.SUMMON_INTERCEPTORS) && state.readyForSummon(tick)) {
            summonInterceptors(entity, target.getLocation());
            state.markSummon(tick, SUMMON_COOLDOWN_TICKS);
        }
    }

    private void summonInterceptors(LivingEntity source, Location targetLocation) {
        World world = source.getWorld();
        List<EntityType> minionTypes = List.of(EntityType.SPIDER, EntityType.ZOMBIE);
        for (int index = 0; index < minionTypes.size(); index++) {
            Location spawnLocation = source.getLocation().clone().add(index == 0 ? 1.5D : -1.5D, 0.0D, 0.5D);
            LivingEntity minion = (LivingEntity) world.spawnEntity(spawnLocation, minionTypes.get(index));
            if (minion instanceof Mob mob && targetLocation.getWorld() == world) {
                Player target = findClosestPlayer(world, targetLocation, bossAcquireRange);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
            trackEntity(minion, false);
        }
    }

    private Player resolveTarget(LivingEntity entity, MobEncounterState state) {
        if (entity instanceof Mob mob && mob.getTarget() instanceof Player target && isUsableTarget(target, entity.getWorld())) {
            return target;
        }

        if (state.lastAttackerId() != null) {
            Player player = Bukkit.getPlayer(state.lastAttackerId());
            if (isUsableTarget(player, entity.getWorld()) && player.getLocation().distance(entity.getLocation()) <= bossAcquireRange) {
                if (entity instanceof Mob mob) {
                    mob.setTarget(player);
                }
                return player;
            }
        }

        if (!scheduler.isFolia() && state.boss()) {
            Player player = findClosestPlayer(entity.getWorld(), entity.getLocation(), bossAcquireRange);
            if (player != null && entity instanceof Mob mob) {
                mob.setTarget(player);
                return player;
            }
        }

        return null;
    }

    private boolean isUsableTarget(Player player, World world) {
        return player != null
                && player.isOnline()
                && player.getWorld().equals(world)
                && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private Player findClosestPlayer(World world, Location origin, double radius) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : world.getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distance(origin);
            if (distance <= radius && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Player findLowerHealthNearbyPlayer(LivingEntity entity, Player currentTarget) {
        Player candidate = currentTarget;
        double lowestHealth = currentTarget.getHealth();
        for (Entity nearby : entity.getNearbyEntities(scanRadius, scanRadius / 2.0D, scanRadius)) {
            if (nearby instanceof Player player && player.getGameMode() != GameMode.SPECTATOR && !player.isDead() && player.getHealth() < lowestHealth) {
                lowestHealth = player.getHealth();
                candidate = player;
            }
        }
        return candidate;
    }

    private int countNearbyAllies(LivingEntity entity) {
        int count = 0;
        for (Entity nearby : entity.getNearbyEntities(scanRadius, scanRadius / 2.0D, scanRadius)) {
            if (nearby instanceof Monster && trackedMobs.containsKey(nearby.getUniqueId())) {
                count++;
            }
        }
        return count;
    }

    private int countNearbyPlayers(LivingEntity entity) {
        int count = 0;
        for (Entity nearby : entity.getNearbyEntities(scanRadius, scanRadius / 2.0D, scanRadius)) {
            if (nearby instanceof Player player && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR) {
                count++;
            }
        }
        return count;
    }

    private void spreadTarget(LivingEntity entity, Player target) {
        for (Entity nearby : entity.getNearbyEntities(scanRadius, scanRadius / 2.0D, scanRadius)) {
            if (nearby instanceof Mob mob && nearby != entity && trackedMobs.containsKey(nearby.getUniqueId())) {
                mob.setTarget(target);
            }
        }
    }

    private void launchToward(LivingEntity entity, Player target, double forward, double upward) {
        Vector velocity = target.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize().multiply(forward);
        velocity.setY(Math.max(upward, entity.getVelocity().getY()));
        entity.setVelocity(velocity);
    }

    private boolean shouldLeap(CombatSnapshot snapshot) {
        return snapshot.distanceToTarget() > 5.0D || snapshot.verticalDifference() > 1.5D || snapshot.targetUsingRanged();
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private String bossBarTitle(AdaptationPlan plan) {
        return MessageService.color(bossName)
                + ChatColor.GRAY + " • Stage " + brain.evolutionStage()
                + ChatColor.DARK_GRAY + " • Threat " + String.format(Locale.US, "%.2f", plan.threatLevel());
    }

    private boolean shouldTrack(LivingEntity entity, boolean boss) {
        if (!adaptiveEnabled || entity instanceof Player) {
            return false;
        }
        if (boss) {
            return true;
        }
        if (!(entity instanceof Monster)) {
            return false;
        }
        if (!whitelist.isEmpty() && !whitelist.contains(entity.getType())) {
            return false;
        }
        return !blacklist.contains(entity.getType());
    }

    private Set<EntityType> parseEntityTypes(List<String> values) {
        Set<EntityType> result = new HashSet<>();
        for (String value : values) {
            try {
                result.add(EntityType.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    private EntityType parseEntityType(String value, EntityType fallback) {
        try {
            return EntityType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private BarColor parseBarColor(String value) {
        try {
            return BarColor.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BarColor.RED;
        }
    }

    private LivingEntity resolveLivingDamager(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private boolean isRangedWeapon(ItemStack item) {
        if (item == null) {
            return false;
        }
        Material type = item.getType();
        return type == Material.BOW || type == Material.CROSSBOW || type == Material.TRIDENT;
    }
}