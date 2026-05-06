package com.sauron.vortexmobs.bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class SchedulerBridge {

    private final JavaPlugin plugin;
    private final boolean folia;

    public SchedulerBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    public boolean isFolia() {
        return folia;
    }

    public ScheduledHandle runRepeatingGlobal(Runnable runnable, long delayTicks, long periodTicks) {
        if (!folia) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
            return task::cancel;
        }

        try {
            Method getter = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object scheduler = getter.invoke(null);
            Method method = scheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            Object handle = method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> runnable.run(), delayTicks, periodTicks);
            return new ReflectiveHandle(handle);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Failed to schedule Folia global task", exception);
        }
    }

    public ScheduledHandle runRepeatingEntity(LivingEntity entity, Consumer<LivingEntity> runnable, long delayTicks, long periodTicks) {
        if (!folia) {
            BukkitRunnable task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!entity.isValid() || entity.isDead()) {
                        cancel();
                        return;
                    }
                    runnable.accept(entity);
                }
            };
            task.runTaskTimer(plugin, delayTicks, periodTicks);
            return task::cancel;
        }

        try {
            Method getter = entity.getClass().getMethod("getScheduler");
            Object scheduler = getter.invoke(entity);
            Method method = scheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
            Object handle = method.invoke(
                    scheduler,
                    plugin,
                    (Consumer<Object>) ignored -> {
                        if (entity.isValid() && !entity.isDead()) {
                            runnable.accept(entity);
                        }
                    },
                    (Runnable) () -> {
                    },
                    delayTicks,
                    periodTicks
            );
            return new ReflectiveHandle(handle);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Failed to schedule Folia entity task", exception);
        }
    }

    private boolean detectFolia() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public interface ScheduledHandle {
        void cancel();
    }

    private static final class ReflectiveHandle implements ScheduledHandle {

        private final Object handle;

        private ReflectiveHandle(Object handle) {
            this.handle = handle;
        }

        @Override
        public void cancel() {
            try {
                Method cancel = handle.getClass().getMethod("cancel");
                cancel.invoke(handle);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            }
        }
    }
}