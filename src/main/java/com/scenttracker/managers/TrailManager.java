package com.scenttracker.managers;

import com.scenttracker.ScentTrackerPlugin;
import com.scenttracker.managers.ConfigManager.Behavior;
import com.scenttracker.managers.ConfigManager.StateRule;
import com.scenttracker.models.ScentPoint;
import com.scenttracker.models.ScentTrail;
import com.scenttracker.models.TrailHit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;

/**
 * Grava e expira os rastros. Tudo roda na thread principal (a API do Bukkit não é thread-safe
 * e o trabalho por tick é pequeno: no máximo alguns pontos por jogador a cada 2s).
 */
public final class TrailManager {

    private final ScentTrackerPlugin plugin;
    private final ConfigManager config;
    private final Map<UUID, ScentTrail> trails = new HashMap<>();
    private BukkitTask task;

    public TrailManager(ScentTrackerPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** (Re)inicia a task de gravação, usando o intervalo atual do config. */
    public void start() {
        stop();
        long interval = config.recordIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void clear() {
        trails.clear();
    }

    /** Chamado no PlayerQuitEvent: descarta o rastro na hora. */
    public void remove(UUID id) {
        trails.remove(id);
    }

    // ------------------------------------------------------------------
    //  Gravação
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isTrackable(player)) {
                trails.remove(player.getUniqueId());
                continue;
            }
            track(player, ScentTrail.Kind.PLAYER, config.maxPoints(), now);
        }

        if (config.mobsEnabled()) {
            trackMobs(now);
        }

        purge(now);
    }

    private boolean isTrackable(Player player) {
        return !player.hasPermission(ScentTrackerPlugin.PERM_BYPASS)
                && player.getGameMode() != GameMode.SPECTATOR
                && !player.isDead();
    }

    private void trackMobs(long now) {
        int tracked = 0;
        for (ScentTrail trail : trails.values()) {
            if (trail.kind() == ScentTrail.Kind.MOB) {
                tracked++;
            }
        }

        double radius = config.mobScanRadius();
        Set<UUID> seen = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (!(entity instanceof LivingEntity) || entity instanceof Player) {
                    continue;
                }
                if (!config.mobTypes().contains(entity.getType()) || !entity.isValid()) {
                    continue;
                }
                if (!seen.add(entity.getUniqueId())) {
                    continue;
                }
                boolean isNew = !trails.containsKey(entity.getUniqueId());
                if (isNew && tracked >= config.mobMaxTracked()) {
                    continue;
                }
                if (track(entity, ScentTrail.Kind.MOB, config.mobMaxPoints(), now) && isNew) {
                    tracked++;
                }
            }
        }
    }

    /** @return true se um ponto foi gravado. */
    private boolean track(Entity entity, ScentTrail.Kind kind, int maxPoints, long now) {
        boolean sneaking = entity instanceof Player p && p.isSneaking();
        long lifetime = lifetimeFor(sneaking, entity.isInWater());
        if (lifetime < 0) {
            return false; // regra SKIP: não deixa rastro agora
        }

        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }

        ScentTrail trail = trails.get(entity.getUniqueId());
        if (trail != null) {
            ScentPoint last = trail.last();
            double min = config.minMoveDistance();
            if (last != null && last.worldId().equals(world.getUID())
                    && last.distanceSquared(loc.getX(), loc.getY(), loc.getZ()) < min * min) {
                return false; // quase não se mexeu
            }
        } else {
            trail = new ScentTrail(entity.getUniqueId(), kind, displayNameFor(entity, kind));
            trails.put(entity.getUniqueId(), trail);
        }

        trail.add(new ScentPoint(world.getUID(), loc.getX(), loc.getY(), loc.getZ(), now, now + lifetime), maxPoints);
        return true;
    }

    /** @return tempo de vida do ponto em ms, ou -1 se não deve gravar. */
    private long lifetimeFor(boolean sneaking, boolean inWater) {
        long lifetime = config.maxAgeMillis();
        if (sneaking) {
            StateRule rule = config.sneakingRule();
            if (rule.behavior() == Behavior.SKIP) {
                return -1;
            }
            if (rule.behavior() == Behavior.SHORT) {
                lifetime = Math.min(lifetime, rule.shortLifetimeMillis());
            }
        }
        if (inWater) {
            StateRule rule = config.waterRule();
            if (rule.behavior() == Behavior.SKIP) {
                return -1;
            }
            if (rule.behavior() == Behavior.SHORT) {
                lifetime = Math.min(lifetime, rule.shortLifetimeMillis());
            }
        }
        return lifetime;
    }

    private Component displayNameFor(Entity entity, ScentTrail.Kind kind) {
        if (kind == ScentTrail.Kind.PLAYER) {
            return Component.text(entity.getName());
        }
        return Component.translatable(entity.getType().translationKey());
    }

    /** Remove pontos expirados e descarta rastros vazios (inclusive de criaturas que sumiram). */
    private void purge(long now) {
        Iterator<Map.Entry<UUID, ScentTrail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            ScentTrail trail = it.next().getValue();
            trail.purgeExpired(now);
            if (trail.isEmpty()) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------------

    /** Rastro de um alvo específico, se algum ponto estiver dentro do raio. */
    public Optional<TrailHit> findFor(UUID ownerId, Location from, double radius) {
        ScentTrail trail = trails.get(ownerId);
        if (trail == null || from.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(evaluate(trail, from, radius * radius, System.currentTimeMillis()));
    }

    /** Rastro mais próximo (qualquer dono, exceto {@code exclude}). */
    public Optional<TrailHit> findNearest(Location from, double radius, UUID exclude, boolean includeMobs) {
        if (from.getWorld() == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        double radiusSq = radius * radius;
        TrailHit best = null;
        for (ScentTrail trail : trails.values()) {
            if (trail.ownerId().equals(exclude)) {
                continue;
            }
            if (trail.kind() == ScentTrail.Kind.MOB && !includeMobs) {
                continue;
            }
            TrailHit hit = evaluate(trail, from, radiusSq, now);
            if (hit != null && (best == null || hit.distance() < best.distance())) {
                best = hit;
            }
        }
        return Optional.ofNullable(best);
    }

    private TrailHit evaluate(ScentTrail trail, Location from, double radiusSq, long now) {
        UUID worldId = from.getWorld().getUID();
        List<ScentPoint> valid = new ArrayList<>();
        for (ScentPoint point : trail.snapshot()) {
            if (!point.isExpired(now) && point.worldId().equals(worldId)) {
                valid.add(point);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }

        int bestIndex = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < valid.size(); i++) {
            double d = valid.get(i).distanceSquared(from.getX(), from.getY(), from.getZ());
            if (d < bestSq) {
                bestSq = d;
                bestIndex = i;
            }
        }
        if (bestSq > radiusSq) {
            return null;
        }
        return new TrailHit(trail, List.copyOf(valid), bestIndex, Math.sqrt(bestSq));
    }
}
