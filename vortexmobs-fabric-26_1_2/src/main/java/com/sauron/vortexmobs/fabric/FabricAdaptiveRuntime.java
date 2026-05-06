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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

        if (entity instanceof Monster hostile) {
            ServerPlayer attacker = resolvePlayerAttacker(source);
            if (attacker != null) {
                TrackedMobState state = trackEntity(hostile, hostile.entityTags().contains(BOSS_TAG));
                if (state != null) {
                    boolean projectile = source.getDirectEntity() != null && source.getDirectEntity() != attacker;
                    boolean attackerUsedRanged = projectile || isRangedWeapon(attacker.getMainHandItem());
                    double squaredDistance = attacker.distanceToSqr(hostile);
                    double distance = squaredDistance <= 0.0D ? 0.0D : Math.sqrt(squaredDistance);
                    double vertical = attacker.getY() - hostile.getY();
                    state.recordIncoming(attacker, damageTaken, projectile, distance, vertical, attackerUsedRanged);
                }
            }
        }

        if (entity instanceof ServerPlayer player) {
            LivingEntity attacker = resolveLivingAttacker(source);
            if (attacker != null) {
                TrackedMobState state = trackedMobs.get(attacker.getUUID());
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

        TrackedMobState state = trackedMobs.remove(entity.getUUID());
        if (state == null) {
            return;
        }

        int previousStage = brain.evolutionStage();
        brain.recordEncounter(state.toSample());
        repository.save(brain);

        if (config.announceStageUpgrades && brain.evolutionStage() > previousStage && server != null) {
            String signal = brain.dominantSignals().isEmpty() ? "baseline escalation" : brain.dominantSignals().get(0);
            Component message = Component.literal("[VortexMobs] ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("Stage " + brain.evolutionStage() + " unlocked. Counter focus: " + signal).withStyle(ChatFormatting.RED));
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    public void onAfterKilledOtherEntity(ServerLevel world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) {
        TrackedMobState state = trackedMobs.get(entity.getUUID());
        if (state != null && killedEntity instanceof ServerPlayer) {
            state.recordPlayerKill();
        }
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vortexmobs")
                .then(Commands.literal("brain")
                        .executes(context -> {
                            sendInfo(context.getSource(), "[VortexMobs] " + brain.shortSummary());
                            sendInfo(context.getSource(), "[VortexMobs] Dominant counters: " + (brain.dominantSignals().isEmpty() ? "still profiling your players" : String.join(", ", brain.dominantSignals())));
                            return 1;
                        }))
                .then(Commands.literal("spawnboss")
                        .executes(context -> spawnBoss(context.getSource())))
                .then(Commands.literal("resetbrain")
                        .then(Commands.literal("confirm")
                                .executes(context -> {
                                    brain.reset();
                                    repository.save(brain);
                                    sendInfo(context.getSource(), "[VortexMobs] The adaptive server brain has been reset.");
                                    return 1;
                                })))
                .then(Commands.literal("reload")
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

    private int spawnBoss(CommandSourceStack source) {
        if (server == null || brain == null) {
            source.sendFailure(Component.literal("VortexMobs is not ready yet."));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Only a player can spawn the adaptive boss."));
            return 0;
        }

        Entity entity = EntityType.HUSK.create(player.level(), EntitySpawnReason.COMMAND);
        if (!(entity instanceof Mob boss)) {
            source.sendFailure(Component.literal("Failed to create the adaptive boss."));
            return 0;
        }

        boss.setPos(player.getX(), player.getY(), player.getZ());
        boss.setYRot(player.getYRot());
        boss.setXRot(player.getXRot());
        boss.setCustomName(Component.literal("Neural Sovereign").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        boss.setCustomNameVisible(true);
        boss.setPersistenceRequired();
        boss.addTag(BOSS_TAG);
        setAttribute(boss, Attributes.MAX_HEALTH, config.bossBaseHealth);
        boss.setHealth((float) config.bossBaseHealth);
        boss.setTarget(player);
        player.level().addFreshEntity(boss);
        trackEntity(boss, true);
        sendInfo(source, "[VortexMobs] The Neural Sovereign has entered the battlefield.");
        return 1;
    }

    private void scanNearbyHostiles(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }

            AABB box = player.getBoundingBox().inflate(config.scanRadius, config.verticalScanRadius, config.scanRadius);
            List<Monster> hostiles = player.level().getEntitiesOfClass(
                    Monster.class,
                    box,
                    hostile -> hostile.isAlive() && !hostile.isRemoved()
            );
            for (Monster hostile : hostiles) {
                trackEntity(hostile, hostile.entityTags().contains(BOSS_TAG));
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
        ServerPlayer target = resolveTarget(entity, state);
        CombatSnapshot snapshot = buildSnapshot(entity, target);
        AdaptationPlan plan = brain.planFor(state.archetype(), snapshot, state.boss());
        applyPlan(entity, state, plan);
        if (target == null) {
            return;
        }

        if (plan.targetSwapUrgency() > 0.5D) {
            ServerPlayer weakerTarget = findLowerHealthPlayer(entity, target);
            if (weakerTarget != null && entity instanceof Mob mob) {
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

    private CombatSnapshot buildSnapshot(LivingEntity entity, ServerPlayer target) {
        if (target == null) {
            return new CombatSnapshot(0.0D, 0.0D, 0, 0, false, false, true, 1.0D, 0.0D);
        }

        double distance = Math.sqrt(entity.distanceToSqr(target));
        double verticalDifference = target.getY() - entity.getY();
        int nearbyAllies = countNearbyAllies(entity);
        int nearbyPlayers = countNearbyPlayers(entity);
        double maxHealth = Math.max(1.0D, target.getMaxHealth());
        return new CombatSnapshot(
                distance,
                verticalDifference,
                nearbyAllies,
                nearbyPlayers,
                isRangedWeapon(target.getMainHandItem()),
                target.isBlocking(),
                entity.hasLineOfSight(target),
                target.getHealth() / maxHealth,
                0.0D
        );
    }

    private void applyPlan(LivingEntity entity, TrackedMobState state, AdaptationPlan plan) {
        setAttribute(entity, Attributes.MOVEMENT_SPEED, state.baseSpeed() * plan.speedMultiplier());
        setAttribute(entity, Attributes.ATTACK_DAMAGE, state.baseAttackDamage() * plan.attackMultiplier());
        setAttribute(entity, Attributes.FOLLOW_RANGE, state.baseFollowRange() + Math.min(18.0D, plan.followRangeBonus()));
        setAttribute(entity, Attributes.KNOCKBACK_RESISTANCE, Math.min(1.0D, state.baseKnockbackResistance() + plan.knockbackResistance()));

        if (state.boss()) {
            setAttribute(entity, Attributes.MAX_HEALTH, Math.max(state.baseMaxHealth(), config.bossBaseHealth * (1.0D + ((plan.threatLevel() - 1.0D) * 0.1D))));
            AttributeInstance maxHealth = entity.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null && entity.getHealth() > maxHealth.getValue()) {
                entity.setHealth((float) maxHealth.getValue());
            }
        }

        if (plan.jumpBoostAmplifier() > 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, config.updateIntervalTicks + 10, plan.jumpBoostAmplifier() - 1));
        }
        if (plan.speedMultiplier() > 1.18D) {
            int amplifier = Math.min(2, (int) Math.floor((plan.speedMultiplier() - 1.0D) * 2.5D));
            entity.addEffect(new MobEffectInstance(MobEffects.SPEED, config.updateIntervalTicks + 10, amplifier));
        }
    }

    private void handleBossAbilities(LivingEntity entity, ServerPlayer target, TrackedMobState state, AdaptationPlan plan) {
        if (plan.bossAbilities().contains(BossAbility.PHASE_DASH) && state.readyForDash(tickCounter) && entity.distanceToSqr(target) > 49.0D) {
            launchToward(entity, target, 0.95D, 0.30D);
            state.markDash(tickCounter, DASH_COOLDOWN_TICKS);
            entity.playSound(SoundEvents.ENDERMAN_SCREAM, 1.0F, 0.7F);
        }

        if (plan.bossAbilities().contains(BossAbility.ROAR_DISRUPTION) && state.readyForRoar(tickCounter)) {
            List<ServerPlayer> players = serverLevel(entity).getEntitiesOfClass(
                    ServerPlayer.class,
                    entity.getBoundingBox().inflate(10.0D, 6.0D, 10.0D),
                    player -> !player.isSpectator() && player.isAlive()
            );
            for (ServerPlayer nearby : players) {
                nearby.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
            }
            state.markRoar(tickCounter, ROAR_COOLDOWN_TICKS);
            entity.playSound(SoundEvents.RAVAGER_ROAR, 1.0F, 0.8F);
        }

        if (plan.bossAbilities().contains(BossAbility.SHIELD_PUNISH) && target.isBlocking()) {
            target.getCooldowns().addCooldown(new ItemStack(Items.SHIELD), 60);
            target.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 50, 0));
        }

        if (plan.bossAbilities().contains(BossAbility.HUNTER_SURGE)) {
            entity.addEffect(new MobEffectInstance(MobEffects.STRENGTH, config.updateIntervalTicks + 20, 0));
            entity.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, config.updateIntervalTicks + 20, 0));
        }

        if (config.summonMinions && plan.bossAbilities().contains(BossAbility.SUMMON_INTERCEPTORS) && state.readyForSummon(tickCounter)) {
            summonInterceptors(entity, target);
            state.markSummon(tickCounter, SUMMON_COOLDOWN_TICKS);
        }
    }

    private void summonInterceptors(LivingEntity source, ServerPlayer target) {
        spawnMinion(source, target, EntityType.SPIDER, 1.5D);
        spawnMinion(source, target, EntityType.ZOMBIE, -1.5D);
    }

    private void spawnMinion(LivingEntity source, ServerPlayer target, EntityType<?> type, double xOffset) {
        Entity entity = type.create(source.level(), EntitySpawnReason.MOB_SUMMONED);
        if (!(entity instanceof Mob minion)) {
            return;
        }

        minion.setPos(source.getX() + xOffset, source.getY(), source.getZ() + 0.5D);
        minion.setYRot(source.getYRot());
        minion.setXRot(source.getXRot());
        minion.setPersistenceRequired();
        minion.setTarget(target);
        serverLevel(source).addFreshEntity(minion);
        trackEntity(minion, false);
    }

    private TrackedMobState trackEntity(LivingEntity entity, boolean boss) {
        TrackedMobState existing = trackedMobs.get(entity.getUUID());
        if (existing != null) {
            return existing;
        }
        if (!boss && trackedMobs.size() >= config.maxTrackedMobs) {
            return null;
        }
        TrackedMobState state = new TrackedMobState(entity, boss);
        trackedMobs.put(entity.getUUID(), state);
        if (config.debugLogging) {
            logger.info("Tracking {} as {}", entity.getType(), state.archetype());
        }
        return state;
    }

    private LivingEntity findLivingEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel world : server.getAllLevels()) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private ServerPlayer resolveTarget(LivingEntity entity, TrackedMobState state) {
        ServerLevel level = serverLevel(entity);

        if (entity instanceof Mob mob && mob.getTarget() instanceof ServerPlayer player && isUsableTarget(player, level)) {
            return player;
        }

        if (state.lastAttackerId() != null && server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.lastAttackerId());
            if (isUsableTarget(player, level) && entity.distanceToSqr(player) <= (config.bossAcquireRange * config.bossAcquireRange)) {
                if (entity instanceof Mob mob) {
                    mob.setTarget(player);
                }
                return player;
            }
        }

        if (state.boss()) {
            ServerPlayer closest = findClosestPlayer(level, entity.position(), config.bossAcquireRange);
            if (closest != null && entity instanceof Mob mob) {
                mob.setTarget(closest);
                return closest;
            }
        }
        return null;
    }

    private boolean isUsableTarget(ServerPlayer player, ServerLevel world) {
        return player != null && player.isAlive() && !player.isSpectator() && player.level() == world;
    }

    private ServerPlayer findClosestPlayer(ServerLevel world, Vec3 origin, double radius) {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        double maxSquared = radius * radius;
        for (ServerPlayer player : world.getPlayers(candidate -> !candidate.isSpectator() && candidate.isAlive())) {
            double distance = player.distanceToSqr(origin);
            if (distance <= maxSquared && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private ServerPlayer findLowerHealthPlayer(LivingEntity entity, ServerPlayer currentTarget) {
        ServerPlayer candidate = currentTarget;
        float lowestHealth = currentTarget.getHealth();
        for (ServerPlayer player : serverLevel(entity).getPlayers(candidatePlayer -> !candidatePlayer.isSpectator() && candidatePlayer.isAlive())) {
            if (entity.distanceToSqr(player) > (config.scanRadius * config.scanRadius)) {
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
        return serverLevel(entity).getEntitiesOfClass(
                Monster.class,
                entity.getBoundingBox().inflate(config.scanRadius, config.verticalScanRadius, config.scanRadius),
                hostile -> hostile != entity && hostile.isAlive() && trackedMobs.containsKey(hostile.getUUID())
        ).size();
    }

    private int countNearbyPlayers(LivingEntity entity) {
        int count = 0;
        double maxSquared = config.scanRadius * config.scanRadius;
        for (ServerPlayer player : serverLevel(entity).getPlayers(candidate -> !candidate.isSpectator() && candidate.isAlive())) {
            if (entity.distanceToSqr(player) <= maxSquared) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private void spreadTarget(LivingEntity entity, ServerPlayer target) {
        List<Monster> allies = serverLevel(entity).getEntitiesOfClass(
                Monster.class,
                entity.getBoundingBox().inflate(config.scanRadius, config.verticalScanRadius, config.scanRadius),
                hostile -> hostile != entity && hostile.isAlive() && trackedMobs.containsKey(hostile.getUUID())
        );
        for (Monster hostile : allies) {
            if (hostile instanceof Mob mob) {
                mob.setTarget(target);
            }
        }
    }

    private void launchToward(LivingEntity entity, ServerPlayer target, double forward, double upward) {
        Vec3 direction = target.position().subtract(entity.position()).normalize().scale(forward);
        entity.setDeltaMovement(direction.x, Math.max(upward, entity.getDeltaMovement().y), direction.z);
        entity.hurtMarked = true;
    }

    private boolean shouldLeap(CombatSnapshot snapshot) {
        return snapshot.distanceToTarget() > 5.0D || snapshot.verticalDifference() > 1.5D || snapshot.targetUsingRanged();
    }

    private void setAttribute(LivingEntity entity, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private ServerPlayer resolvePlayerAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        return attacker instanceof ServerPlayer player ? player : null;
    }

    private LivingEntity resolveLivingAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        return attacker instanceof LivingEntity living ? living : null;
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() == Items.BOW || stack.getItem() == Items.CROSSBOW || stack.getItem() == Items.TRIDENT;
    }

    private void sendInfo(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private static ServerLevel serverLevel(LivingEntity entity) {
        return (ServerLevel) entity.level();
    }
}