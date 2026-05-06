package com.sauron.vortexmobs.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.sauron.vortexmobs.core.AdaptationPlan;
import com.sauron.vortexmobs.core.BossAbility;
import com.sauron.vortexmobs.core.CombatSnapshot;
import com.sauron.vortexmobs.core.ServerBrain;
import com.sauron.vortexmobs.core.ServerGenome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

public final class FabricAdaptiveRuntime {

    private static final String BOSS_TAG = "vortexmobs_boss";
    private static final int LEAP_COOLDOWN_TICKS = 18;
    private static final int DASH_COOLDOWN_TICKS = 60;
    private static final int ROAR_COOLDOWN_TICKS = 120;
    private static final int SUMMON_COOLDOWN_TICKS = 180;

    private final Logger logger;
    private final Path baseDir;
    private final Map<UUID, TrackedMobState> trackedMobs = new ConcurrentHashMap<>();
    private MinecraftServer server;
    private FabricConfig config;
    private FabricBrainRepository repository;
    private ServerBrain brain;
    private int tickCounter;

    public FabricAdaptiveRuntime(Logger logger) {
        this.logger = logger;
        this.baseDir = FabricLoader.getInstance().getConfigDir().resolve("vortexmobs");
    }

    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        this.config = FabricConfig.loadOrCreate(baseDir.resolve("fabric-runtime.json"), logger);
        this.repository = new FabricBrainRepository(baseDir.resolve("server-brain.json"), logger);
        this.brain = repository.loadOrCreate(() -> new ServerBrain(ServerGenome.create()));
        this.tickCounter = 0;
        trackedMobs.clear();
        logger.info("VortexMobs Fabric ready: {}", brain.shortSummary());
    }

    public void onServerStopping(MinecraftServer server) {
        if (repository != null && brain != null) {
            repository.save(brain);
        }
        trackedMobs.clear();
        this.server = null;
    }

    public void onServerTick(MinecraftServer server) {
        if (brain == null || config == null) {
            return;
        }

        tickCounter++;
        if (tickCounter % config.updateIntervalTicks == 0) {
            scanNearbyHostiles(server);
            tickTrackedMobs(server);
        }
        if (tickCounter % config.saveIntervalTicks == 0) {
            repository.save(brain);
        }
    }

    public void onAfterDamage(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked) {
        if (brain == null) {
            return;
        }

        if (entity instanceof HostileEntity hostile) {
            ServerPlayerEntity attacker = resolvePlayerAttacker(source);
            if (attacker != null) {
                TrackedMobState state = trackEntity(hostile, hostile.getCommandTags().contains(BOSS_TAG));
                if (state != null) {
                    boolean projectile = source.getSource() != null && source.getSource() != attacker;
                    boolean attackerUsedRanged = projectile || isRangedWeapon(attacker.getMainHandStack());
                    double distance = attacker.squaredDistanceTo(hostile) <= 0.0D ? 0.0D : Math.sqrt(attacker.squaredDistanceTo(hostile));
                    double vertical = attacker.getY() - hostile.getY();
                    state.recordIncoming(attacker, damageTaken, projectile, distance, vertical, attackerUsedRanged);
                }
            }
        }

        if (entity instanceof ServerPlayerEntity player) {
            LivingEntity attacker = resolveLivingAttacker(source);
            if (attacker != null) {
                TrackedMobState state = trackedMobs.get(attacker.getUuid());
                if (state != null) {
                    state.recordOutgoing(damageTaken, blocked || player.isBlocking());
                }
            }
        }
    }

    public void onAfterDeath(LivingEntity entity, DamageSource source) {
        if (brain == null) {
            return;
        }

        TrackedMobState state = trackedMobs.remove(entity.getUuid());
        if (state == null) {
            return;
        }

        int previousStage = brain.evolutionStage();
        brain.recordEncounter(state.toSample());
        repository.save(brain);

        if (config.announceStageUpgrades && brain.evolutionStage() > previousStage && server != null) {
            String signal = brain.dominantSignals().isEmpty() ? "baseline escalation" : brain.dominantSignals().get(0);
            Text message = Text.literal("[VortexMobs] ")
                    .formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("Stage " + brain.evolutionStage() + " unlocked. Counter focus: " + signal).formatted(Formatting.RED));
            server.getPlayerManager().broadcast(message, false);
        }
    }

    public void onAfterKilledOtherEntity(ServerWorld world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) {
        TrackedMobState state = trackedMobs.get(entity.getUuid());
        if (state != null && killedEntity instanceof ServerPlayerEntity) {
            state.recordPlayerKill();
        }
    }

    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("vortexmobs")
                .then(CommandManager.literal("brain")
                        .executes(context -> {
                            sendInfo(context.getSource(), "[VortexMobs] " + brain.shortSummary());
                            sendInfo(context.getSource(), "[VortexMobs] Dominant counters: " + (brain.dominantSignals().isEmpty() ? "still profiling your players" : String.join(", ", brain.dominantSignals())));
                            return 1;
                        }))
                .then(CommandManager.literal("spawnboss")
                        .executes(context -> spawnBoss(context.getSource())))
                .then(CommandManager.literal("resetbrain")
                        .then(CommandManager.literal("confirm")
                                .executes(context -> {
                                    brain.reset();
                                    repository.save(brain);
                                    sendInfo(context.getSource(), "[VortexMobs] The adaptive server brain has been reset.");
                                    return 1;
                                })))
                .then(CommandManager.literal("reload")
                        .executes(context -> {
                            config = FabricConfig.loadOrCreate(baseDir.resolve("fabric-runtime.json"), logger);
                            try {
                                config.save(baseDir.resolve("fabric-runtime.json"));
                            } catch (IOException exception) {
                                logger.warn("Failed to save reloaded VortexMobs Fabric config", exception);
                            }
                            sendInfo(context.getSource(), "[VortexMobs] Runtime config reloaded.");
                            return 1;
                        })));
    }

    private int spawnBoss(ServerCommandSource source) {
        if (server == null || brain == null) {
            source.sendError(Text.literal("VortexMobs is not ready yet."));
            return 0;
        }

        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Only a player can spawn the adaptive boss."));
            return 0;
        }

        Entity entity = EntityType.HUSK.create(player.getEntityWorld(), SpawnReason.COMMAND);
        if (!(entity instanceof MobEntity boss)) {
            source.sendError(Text.literal("Failed to create the adaptive boss."));
            return 0;
        }

        boss.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        boss.setCustomName(Text.literal("Neural Sovereign").formatted(Formatting.DARK_RED, Formatting.BOLD));
        boss.setCustomNameVisible(true);
        boss.setPersistent();
        boss.addCommandTag(BOSS_TAG);
        setAttribute(boss, EntityAttributes.MAX_HEALTH, config.bossBaseHealth);
        boss.setHealth((float) config.bossBaseHealth);
        boss.setTarget(player);
        player.getEntityWorld().spawnEntity(boss);
        trackEntity(boss, true);
        sendInfo(source, "[VortexMobs] The Neural Sovereign has entered the battlefield.");
        return 1;
    }

    private void scanNearbyHostiles(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }

            Box box = player.getBoundingBox().expand(config.scanRadius, config.verticalScanRadius, config.scanRadius);
            List<HostileEntity> hostiles = player.getEntityWorld().getEntitiesByClass(
                    HostileEntity.class,
                    box,
                    hostile -> hostile.isAlive() && !hostile.isRemoved()
            );
            for (HostileEntity hostile : hostiles) {
                trackEntity(hostile, hostile.getCommandTags().contains(BOSS_TAG));
            }
        }
    }

    private void tickTrackedMobs(MinecraftServer server) {
        Iterator<Map.Entry<UUID, TrackedMobState>> iterator = trackedMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedMobState> entry = iterator.next();
            LivingEntity entity = findLivingEntity(server, entry.getKey());
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            tickTrackedMob(entity, entry.getValue());
        }
    }

    private void tickTrackedMob(LivingEntity entity, TrackedMobState state) {
        ServerPlayerEntity target = resolveTarget(entity, state);
        CombatSnapshot snapshot = buildSnapshot(entity, target);
        AdaptationPlan plan = brain.planFor(state.archetype(), snapshot, state.boss());
        applyPlan(entity, state, plan);
        if (target == null) {
            return;
        }

        if (plan.targetSwapUrgency() > 0.5D) {
            ServerPlayerEntity weakerTarget = findLowerHealthPlayer(entity, target);
            if (weakerTarget != null && entity instanceof MobEntity mob) {
                mob.setTarget(weakerTarget);
                target = weakerTarget;
            }
        }

        if (plan.packFocusBonus() > 0.35D) {
            spreadTarget(entity, target);
        }

        if (plan.rangedPunishChance() > 0.25D && state.readyForLeap(tickCounter) && shouldLeap(snapshot)) {
            launchToward(entity, target, 0.42D + (plan.leapStrength() * 0.26D), 0.24D + (plan.jumpBoostAmplifier() * 0.06D));
            state.markLeap(tickCounter, LEAP_COOLDOWN_TICKS);
        }

        if (state.boss()) {
            handleBossAbilities(entity, target, state, plan);
        }
    }

    private CombatSnapshot buildSnapshot(LivingEntity entity, ServerPlayerEntity target) {
        if (target == null) {
            return new CombatSnapshot(0.0D, 0.0D, 0, 0, false, false, true, 1.0D, 0.0D);
        }

        double distance = Math.sqrt(entity.squaredDistanceTo(target));
        double verticalDifference = target.getY() - entity.getY();
        int nearbyAllies = countNearbyAllies(entity);
        int nearbyPlayers = countNearbyPlayers(entity);
        double maxHealth = Math.max(1.0D, target.getMaxHealth());
        return new CombatSnapshot(
                distance,
                verticalDifference,
                nearbyAllies,
                nearbyPlayers,
                isRangedWeapon(target.getMainHandStack()),
                target.isBlocking(),
                entity.canSee(target),
                target.getHealth() / (float) maxHealth,
                0.0D
        );
    }

    private void applyPlan(LivingEntity entity, TrackedMobState state, AdaptationPlan plan) {
        setAttribute(entity, EntityAttributes.MOVEMENT_SPEED, state.baseSpeed() * plan.speedMultiplier());
        setAttribute(entity, EntityAttributes.ATTACK_DAMAGE, state.baseAttackDamage() * plan.attackMultiplier());
        setAttribute(entity, EntityAttributes.FOLLOW_RANGE, state.baseFollowRange() + Math.min(18.0D, plan.followRangeBonus()));
        setAttribute(entity, EntityAttributes.KNOCKBACK_RESISTANCE, Math.min(1.0D, state.baseKnockbackResistance() + plan.knockbackResistance()));

        if (state.boss()) {
            setAttribute(entity, EntityAttributes.MAX_HEALTH, Math.max(state.baseMaxHealth(), config.bossBaseHealth * (1.0D + ((plan.threatLevel() - 1.0D) * 0.1D))));
            EntityAttributeInstance maxHealth = entity.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            if (maxHealth != null && entity.getHealth() > maxHealth.getValue()) {
                entity.setHealth((float) maxHealth.getValue());
            }
        }

        if (plan.jumpBoostAmplifier() > 0) {
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, config.updateIntervalTicks + 10, plan.jumpBoostAmplifier() - 1));
        }
        if (plan.speedMultiplier() > 1.18D) {
            int amplifier = Math.min(2, (int) Math.floor((plan.speedMultiplier() - 1.0D) * 2.5D));
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, config.updateIntervalTicks + 10, amplifier));
        }
    }

    private void handleBossAbilities(LivingEntity entity, ServerPlayerEntity target, TrackedMobState state, AdaptationPlan plan) {
        if (plan.bossAbilities().contains(BossAbility.PHASE_DASH) && state.readyForDash(tickCounter) && entity.squaredDistanceTo(target) > 49.0D) {
            launchToward(entity, target, 0.95D, 0.30D);
            state.markDash(tickCounter, DASH_COOLDOWN_TICKS);
            entity.playSound(SoundEvents.ENTITY_ENDERMAN_SCREAM, 1.0F, 0.7F);
        }

        if (plan.bossAbilities().contains(BossAbility.ROAR_DISRUPTION) && state.readyForRoar(tickCounter)) {
            List<ServerPlayerEntity> players = entity.getEntityWorld().getEntitiesByClass(
                    ServerPlayerEntity.class,
                    entity.getBoundingBox().expand(10.0D, 6.0D, 10.0D),
                    player -> !player.isSpectator() && player.isAlive()
            );
            for (ServerPlayerEntity nearby : players) {
                nearby.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
            }
            state.markRoar(tickCounter, ROAR_COOLDOWN_TICKS);
            entity.playSound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.0F, 0.8F);
        }

        if (plan.bossAbilities().contains(BossAbility.SHIELD_PUNISH) && target.isBlocking()) {
            target.getItemCooldownManager().set(new ItemStack(Items.SHIELD), 60);
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 50, 0));
        }

        if (plan.bossAbilities().contains(BossAbility.HUNTER_SURGE)) {
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, config.updateIntervalTicks + 20, 0));
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, config.updateIntervalTicks + 20, 0));
        }

        if (config.summonMinions && plan.bossAbilities().contains(BossAbility.SUMMON_INTERCEPTORS) && state.readyForSummon(tickCounter)) {
            summonInterceptors(entity, target);
            state.markSummon(tickCounter, SUMMON_COOLDOWN_TICKS);
        }
    }

    private void summonInterceptors(LivingEntity source, ServerPlayerEntity target) {
        spawnMinion(source, target, EntityType.SPIDER, 1.5D);
        spawnMinion(source, target, EntityType.ZOMBIE, -1.5D);
    }

    private void spawnMinion(LivingEntity source, ServerPlayerEntity target, EntityType<?> type, double xOffset) {
        Entity entity = type.create(source.getEntityWorld(), SpawnReason.MOB_SUMMONED);
        if (!(entity instanceof MobEntity minion)) {
            return;
        }

        minion.refreshPositionAndAngles(source.getX() + xOffset, source.getY(), source.getZ() + 0.5D, source.getYaw(), source.getPitch());
        minion.setPersistent();
        minion.setTarget(target);
        source.getEntityWorld().spawnEntity(minion);
        trackEntity(minion, false);
    }

    private TrackedMobState trackEntity(LivingEntity entity, boolean boss) {
        TrackedMobState existing = trackedMobs.get(entity.getUuid());
        if (existing != null) {
            return existing;
        }
        if (!boss && trackedMobs.size() >= config.maxTrackedMobs) {
            return null;
        }
        TrackedMobState state = new TrackedMobState(entity, boss);
        trackedMobs.put(entity.getUuid(), state);
        if (config.debugLogging) {
            logger.info("Tracking {} as {}", entity.getType(), state.archetype());
        }
        return state;
    }

    private LivingEntity findLivingEntity(MinecraftServer server, UUID uuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private ServerWorld getServerWorld(Entity entity) {
        return entity.getEntityWorld() instanceof ServerWorld world ? world : null;
    }

    private ServerPlayerEntity resolveTarget(LivingEntity entity, TrackedMobState state) {
        ServerWorld world = getServerWorld(entity);
        if (world == null) {
            return null;
        }

        if (entity instanceof MobEntity mob && mob.getTarget() instanceof ServerPlayerEntity player && isUsableTarget(player, world)) {
            return player;
        }

        if (state.lastAttackerId() != null && server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(state.lastAttackerId());
            if (isUsableTarget(player, world) && entity.squaredDistanceTo(player) <= (config.bossAcquireRange * config.bossAcquireRange)) {
                if (entity instanceof MobEntity mob) {
                    mob.setTarget(player);
                }
                return player;
            }
        }

        if (state.boss()) {
            ServerPlayerEntity closest = findClosestPlayer(world, new Vec3d(entity.getX(), entity.getY(), entity.getZ()), config.bossAcquireRange);
            if (closest != null && entity instanceof MobEntity mob) {
                mob.setTarget(closest);
                return closest;
            }
        }
        return null;
    }

    private boolean isUsableTarget(ServerPlayerEntity player, ServerWorld world) {
        return player != null && player.isAlive() && !player.isSpectator() && player.getEntityWorld() == world;
    }

    private ServerPlayerEntity findClosestPlayer(ServerWorld world, Vec3d origin, double radius) {
        ServerPlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        double maxSquared = radius * radius;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            double distance = player.squaredDistanceTo(origin);
            if (distance <= maxSquared && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private ServerPlayerEntity findLowerHealthPlayer(LivingEntity entity, ServerPlayerEntity currentTarget) {
        ServerWorld world = getServerWorld(entity);
        if (world == null) {
            return currentTarget;
        }

        ServerPlayerEntity candidate = currentTarget;
        float lowestHealth = currentTarget.getHealth();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || !player.isAlive() || entity.squaredDistanceTo(player) > (config.scanRadius * config.scanRadius)) {
                continue;
            }
            if (player.getHealth() < lowestHealth) {
                lowestHealth = player.getHealth();
                candidate = player;
            }
        }
        return candidate;
    }

    private int countNearbyAllies(LivingEntity entity) {
        ServerWorld world = getServerWorld(entity);
        if (world == null) {
            return 0;
        }

        return world.getEntitiesByClass(
                HostileEntity.class,
                entity.getBoundingBox().expand(config.scanRadius, config.verticalScanRadius, config.scanRadius),
                hostile -> hostile != entity && hostile.isAlive() && trackedMobs.containsKey(hostile.getUuid())
        ).size();
    }

    private int countNearbyPlayers(LivingEntity entity) {
        ServerWorld world = getServerWorld(entity);
        if (world == null) {
            return 1;
        }

        int count = 0;
        double maxSquared = config.scanRadius * config.scanRadius;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (entity.squaredDistanceTo(player) <= maxSquared) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private void spreadTarget(LivingEntity entity, ServerPlayerEntity target) {
        ServerWorld world = getServerWorld(entity);
        if (world == null) {
            return;
        }

        List<HostileEntity> allies = world.getEntitiesByClass(
                HostileEntity.class,
                entity.getBoundingBox().expand(config.scanRadius, config.verticalScanRadius, config.scanRadius),
                hostile -> hostile != entity && hostile.isAlive() && trackedMobs.containsKey(hostile.getUuid())
        );
        for (HostileEntity hostile : allies) {
            if (hostile instanceof MobEntity mob) {
                mob.setTarget(target);
            }
        }
    }

    private void launchToward(LivingEntity entity, ServerPlayerEntity target, double forward, double upward) {
        Vec3d direction = new Vec3d(target.getX(), target.getY(), target.getZ())
            .subtract(new Vec3d(entity.getX(), entity.getY(), entity.getZ()))
            .normalize()
            .multiply(forward);
        entity.setVelocity(direction.x, Math.max(upward, entity.getVelocity().y), direction.z);
        entity.velocityDirty = true;
    }

    private boolean shouldLeap(CombatSnapshot snapshot) {
        return snapshot.distanceToTarget() > 5.0D || snapshot.verticalDifference() > 1.5D || snapshot.targetUsingRanged();
    }

    private void setAttribute(LivingEntity entity, RegistryEntry<EntityAttribute> attribute, double value) {
        EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private ServerPlayerEntity resolvePlayerAttacker(DamageSource source) {
        Entity attacker = source.getAttacker();
        return attacker instanceof ServerPlayerEntity player ? player : null;
    }

    private LivingEntity resolveLivingAttacker(DamageSource source) {
        Entity attacker = source.getAttacker();
        return attacker instanceof LivingEntity living ? living : null;
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.TRIDENT);
    }

    private void sendInfo(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }
}