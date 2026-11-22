package com.scenttracker.managers;

import com.scenttracker.ScentTrackerPlugin;
import com.scenttracker.managers.ConfigManager.Behavior;
import com.scenttracker.models.CompassDirection;
import com.scenttracker.models.ParticleStyle;
import com.scenttracker.models.ScentPoint;
import com.scenttracker.models.ScentTrail;
import com.scenttracker.models.TrailHit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executa a ação de farejar: cooldown, som, partículas exclusivas, Actionbar e bônus de proximidade.
 */
public final class SniffManager {

    private final ScentTrackerPlugin plugin;
    private final ConfigManager config;
    private final TrailManager trailManager;

    /** UUID -> instante (ms) em que o cooldown termina. Entradas vencidas são limpas periodicamente. */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Visualizações ativas (no máximo uma por jogador). */
    private final Map<UUID, TrailVisual> visuals = new HashMap<>();
    private BukkitTask housekeeping;

    public SniffManager(ScentTrackerPlugin plugin, ConfigManager config, TrailManager trailManager) {
        this.plugin = plugin;
        this.config = config;
        this.trailManager = trailManager;
    }

    public void start() {
        // a cada 5 minutos remove cooldowns já vencidos (quem saiu do servidor não fica na memória)
        housekeeping = Bukkit.getScheduler().runTaskTimer(plugin, this::purgeCooldowns, 6000L, 6000L);
    }

    public void shutdown() {
        if (housekeeping != null) {
            housekeeping.cancel();
            housekeeping = null;
        }
        for (TrailVisual visual : List.copyOf(visuals.values())) {
            visual.cancel();
        }
        visuals.clear();
        cooldowns.clear();
    }

    /** Chamado no PlayerQuitEvent. */
    public void cleanup(UUID playerId) {
        TrailVisual visual = visuals.remove(playerId);
        if (visual != null) {
            visual.cancel();
        }
    }

    private void purgeCooldowns() {
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(until -> until <= now);
    }

    // ------------------------------------------------------------------
    //  Farejar
    // ------------------------------------------------------------------

    /**
     * @param explicitTarget alvo escolhido pelo jogador, ou {@code null} para o rastro mais próximo
     */
    public void sniff(Player player, Player explicitTarget) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();

        if (!player.hasPermission(ScentTrackerPlugin.PERM_COOLDOWN_BYPASS)) {
            Long until = cooldowns.get(id);
            if (until != null && until > now) {
                long seconds = (long) Math.ceil((until - now) / 1000.0);
                player.sendActionBar(config.message("cooldown",
                        Placeholder.unparsed("seconds", String.valueOf(seconds))));
                return;
            }
            cooldowns.put(id, now + config.cooldownMillis());
        }

        playSound(player);
        cleanup(id); // cancela a visualização anterior

        Location origin = player.getLocation();
        Optional<TrailHit> result = explicitTarget != null
                ? trailManager.findFor(explicitTarget.getUniqueId(), origin, config.sniffRadius())
                : trailManager.findNearest(origin, config.sniffRadius(), id, config.mobsEnabled());

        if (result.isEmpty()) {
            if (explicitTarget != null) {
                player.sendActionBar(config.message("no-trail-player",
                        Placeholder.unparsed("target", explicitTarget.getName())));
            } else {
                player.sendActionBar(config.message("no-trail-found"));
            }
            return;
        }

        TrailHit hit = result.get();
        startVisual(player, hit);
        sendTrailFeedback(player, hit, now);
        scheduleProximity(player, hit.trail().ownerId());
    }

    private void playSound(Player player) {
        String key = config.soundKey();
        if (key == null || key.isBlank()) {
            return;
        }
        Location loc = player.getLocation();
        if (config.soundBroadcast()) {
            player.getWorld().playSound(loc, key, SoundCategory.PLAYERS, config.soundVolume(), config.soundPitch());
        } else {
            player.playSound(loc, key, SoundCategory.PLAYERS, config.soundVolume(), config.soundPitch());
        }
    }

    // ------------------------------------------------------------------
    //  Actionbar
    // ------------------------------------------------------------------

    private void sendTrailFeedback(Player player, TrailHit hit, long now) {
        ScentTrail trail = hit.trail();
        var targetResolver = Placeholder.component("target", trail.displayName());

        // Alvo está na água agora e a água apaga o cheiro: o rastro termina ali.
        Entity target = Bukkit.getEntity(trail.ownerId());
        if (target != null && target.isValid() && target.isInWater()
                && config.waterRule().behavior() == Behavior.SKIP) {
            player.sendActionBar(config.message("scent-water-lost", targetResolver));
            return;
        }

        Optional<CompassDirection> direction = hit.travelDirection();
        if (direction.isEmpty()) {
            player.sendActionBar(config.message("scent-no-direction", targetResolver));
            return;
        }

        long ageSeconds = Math.max(0L, (now - hit.nearestPoint().createdAt()) / 1000L);
        String key;
        if (ageSeconds <= config.freshSeconds()) {
            key = "scent-fresh";
        } else if (ageSeconds <= config.recentSeconds()) {
            key = "scent-recent";
        } else {
            key = "scent-old";
        }
        player.sendActionBar(config.message(key, targetResolver,
                Placeholder.unparsed("direction", config.directionName(direction.get()))));
    }

    // ------------------------------------------------------------------
    //  Partículas do rastro (visíveis só para quem farejou)
    // ------------------------------------------------------------------

    private void startVisual(Player player, TrailHit hit) {
        TrailVisual visual = new TrailVisual(player, hit.points());
        visuals.put(player.getUniqueId(), visual);
        visual.runTaskTimer(plugin, 0L, config.particleIntervalTicks());
    }

    private final class TrailVisual extends BukkitRunnable {

        private final Player player;
        private final List<ScentPoint> points;
        private final UUID worldId;
        private final int period;
        private int ticksLeft;
        private int step;

        private TrailVisual(Player player, List<ScentPoint> points) {
            this.player = player;
            this.points = points;
            this.worldId = player.getWorld().getUID();
            this.period = config.particleIntervalTicks();
            this.ticksLeft = config.visualDurationTicks();
        }

        @Override
        public void run() {
            if (!player.isOnline() || ticksLeft <= 0 || !player.getWorld().getUID().equals(worldId)) {
                stop();
                return;
            }
            ticksLeft -= period;
            render();
            step++;
        }

        private void stop() {
            cancel();
            visuals.remove(player.getUniqueId(), this);
        }

        private void render() {
            Location loc = player.getLocation();
            double px = loc.getX();
            double py = loc.getY();
            double pz = loc.getZ();
            double maxSq = config.renderDistance() * config.renderDistance();
            double spacing = config.particleSpacing();
            double maxGap = config.maxSegmentGap();
            ParticleStyle style = config.trailStyle();
            int budget = config.maxParticlesPerTick();
            int n = points.size();

            if (n == 1) {
                ScentPoint only = points.get(0);
                style.spawn(player, only.x(), only.y() + 0.1, only.z(), 1, 0.0, 1.0);
                return;
            }

            // a "fase" desloca as partículas a cada passo, dando a impressão de fluxo rumo ao ponto mais novo
            double phase = (step % 4) / 4.0 * spacing;

            for (int i = 0; i < n - 1 && budget > 0; i++) {
                ScentPoint a = points.get(i);
                ScentPoint b = points.get(i + 1);
                double dx = b.x() - a.x();
                double dy = b.y() - a.y();
                double dz = b.z() - a.z();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.01 || len > maxGap) {
                    continue; // parado ou salto grande (teleporte): não liga os pontos
                }
                double freshA = (double) i / (n - 1);
                double freshB = (double) (i + 1) / (n - 1);

                for (double d = phase; d < len && budget > 0; d += spacing) {
                    double t = d / len;
                    double x = a.x() + dx * t;
                    double y = a.y() + dy * t + 0.1;
                    double z = a.z() + dz * t;
                    double ox = x - px;
                    double oy = y - py;
                    double oz = z - pz;
                    if (ox * ox + oy * oy + oz * oz > maxSq) {
                        continue;
                    }
                    style.spawn(player, x, y, z, 1, 0.0, freshA + (freshB - freshA) * t);
                    budget--;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Bônus de proximidade
    // ------------------------------------------------------------------

    private void scheduleProximity(Player player, UUID targetId) {
        if (!config.proximityEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkProximity(player, targetId), config.proximityDelayTicks());
    }

    private void checkProximity(Player player, UUID targetId) {
        if (!player.isOnline()) {
            return;
        }
        Entity target = Bukkit.getEntity(targetId);
        if (target == null || !target.isValid() || !target.getWorld().equals(player.getWorld())) {
            return;
        }
        if (target instanceof Player targetPlayer) {
            // não revela jogadores invisíveis para o farejador, em spectator ou com bypass
            if (!player.canSee(targetPlayer)
                    || targetPlayer.getGameMode() == GameMode.SPECTATOR
                    || targetPlayer.hasPermission(ScentTrackerPlugin.PERM_BYPASS)) {
                return;
            }
        }

        Location eye = player.getEyeLocation();
        Location targetPoint = target instanceof LivingEntity living
                ? living.getEyeLocation()
                : target.getLocation().add(0, target.getHeight() / 2.0, 0);

        Vector toTarget = targetPoint.toVector().subtract(eye.toVector());
        double distance = toTarget.length();
        if (distance > config.proximityRadius()) {
            return;
        }
        if (config.proximityRequireLineOfSight() && distance > 0.5) {
            RayTraceResult blocked = eye.getWorld().rayTraceBlocks(eye, toTarget.clone().normalize(), distance,
                    FluidCollisionMode.NEVER, true);
            if (blocked != null) {
                return;
            }
        }

        double horizontalSq = toTarget.getX() * toTarget.getX() + toTarget.getZ() * toTarget.getZ();
        if (horizontalSq < 1.0) {
            player.sendActionBar(config.message("proximity-here"));
        } else {
            CompassDirection direction = CompassDirection.fromVector(toTarget.getX(), toTarget.getZ());
            player.sendActionBar(config.message("proximity",
                    Placeholder.unparsed("direction", config.directionName(direction))));
        }

        // pulso de partículas ~2 blocos à frente, na direção do alvo (só o farejador vê)
        Vector pulseDir = distance > 0.01 ? toTarget.clone().normalize() : new Vector(0, 0, 0);
        Location pulse = eye.clone().add(pulseDir.multiply(Math.min(2.0, distance)));
        config.proximityStyle().spawn(player, pulse.getX(), pulse.getY(), pulse.getZ(),
                config.proximityParticleCount(), 0.15, 1.0);
    }
}
