package com.scenttracker.managers;

import com.scenttracker.ScentTrackerPlugin;
import com.scenttracker.models.CompassDirection;
import com.scenttracker.models.ParticleStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lê e guarda em cache os valores do config.yml. Mensagens são lidas sob demanda
 * (e interpretadas com MiniMessage), então um reload as atualiza na hora.
 */
public final class ConfigManager {

    public enum Behavior { NORMAL, SHORT, SKIP }

    public enum InteractMode { ITEM, SNEAK_EMPTY_HAND }

    public record StateRule(Behavior behavior, long shortLifetimeMillis) { }

    private final ScentTrackerPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // trail
    private long recordIntervalTicks;
    private int maxPoints;
    private long maxAgeMillis;
    private double minMoveDistance;
    private StateRule sneakingRule;
    private StateRule waterRule;
    private boolean mobsEnabled;
    private Set<EntityType> mobTypes = Set.of();
    private int mobMaxPoints;
    private int mobMaxTracked;
    private double mobScanRadius;

    // sniff
    private double sniffRadius;
    private long cooldownMillis;
    private int visualDurationTicks;
    private long freshSeconds;
    private long recentSeconds;
    private String soundKey;
    private float soundVolume;
    private float soundPitch;
    private boolean soundBroadcast;
    private ParticleStyle trailStyle;
    private double particleSpacing;
    private int particleIntervalTicks;
    private double renderDistance;
    private int maxParticlesPerTick;
    private double maxSegmentGap;

    // proximity
    private boolean proximityEnabled;
    private double proximityRadius;
    private boolean proximityRequireLineOfSight;
    private long proximityDelayTicks;
    private ParticleStyle proximityStyle;
    private int proximityParticleCount;

    // interact
    private boolean interactEnabled;
    private InteractMode interactMode;
    private Material interactItem;

    public ConfigManager(ScentTrackerPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        // ----- trail -----
        recordIntervalTicks = Math.max(5L, Math.round(c.getDouble("trail.record-interval-seconds", 2.0) * 20.0));
        maxPoints = clamp(c.getInt("trail.max-points", 30), 2, 500);
        maxAgeMillis = Math.max(1000L, Math.round(c.getDouble("trail.max-age-seconds", 120.0) * 1000.0));
        minMoveDistance = Math.max(0.0, c.getDouble("trail.min-move-distance", 0.75));
        sneakingRule = parseRule(c, "trail.sneaking", Behavior.SHORT, 20.0);
        waterRule = parseRule(c, "trail.water", Behavior.SKIP, 15.0);

        mobsEnabled = c.getBoolean("trail.mobs.enabled", false);
        Set<EntityType> types = new HashSet<>();
        for (String name : c.getStringList("trail.mobs.types")) {
            try {
                types.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("trail.mobs.types: tipo de entidade inválido '" + name + "' (ignorado).");
            }
        }
        mobTypes = Set.copyOf(types);
        mobMaxPoints = clamp(c.getInt("trail.mobs.max-points", 15), 2, 200);
        mobMaxTracked = clamp(c.getInt("trail.mobs.max-tracked", 150), 1, 5000);
        mobScanRadius = Math.max(8.0, c.getDouble("trail.mobs.scan-radius", 64.0));

        // ----- sniff -----
        sniffRadius = Math.max(1.0, c.getDouble("sniff.max-radius", 40.0));
        cooldownMillis = Math.max(0L, Math.round(c.getDouble("sniff.cooldown-seconds", 15.0) * 1000.0));
        visualDurationTicks = (int) Math.max(20L, Math.round(c.getDouble("sniff.duration-seconds", 10.0) * 20.0));
        freshSeconds = Math.max(1L, c.getLong("sniff.freshness.fresh-seconds", 20L));
        recentSeconds = Math.max(freshSeconds, c.getLong("sniff.freshness.recent-seconds", 60L));

        soundKey = c.getString("sniff.sound.key", "entity.warden.sniff");
        soundVolume = (float) c.getDouble("sniff.sound.volume", 1.0);
        soundPitch = (float) c.getDouble("sniff.sound.pitch", 1.0);
        soundBroadcast = c.getBoolean("sniff.sound.broadcast", true);

        trailStyle = parseStyle(c, "sniff.particles.type", c.getString("sniff.particles.fresh-color", "#6EE7A8"),
                c.getString("sniff.particles.old-color", "#6B7280"), c.getDouble("sniff.particles.size", 1.0));
        particleSpacing = Math.max(0.2, c.getDouble("sniff.particles.spacing", 0.6));
        particleIntervalTicks = clamp(c.getInt("sniff.particles.interval-ticks", 5), 1, 40);
        renderDistance = Math.max(4.0, c.getDouble("sniff.particles.render-distance", 32.0));
        maxParticlesPerTick = clamp(c.getInt("sniff.particles.max-particles-per-tick", 250), 10, 2000);
        maxSegmentGap = Math.max(2.0, c.getDouble("sniff.particles.max-segment-gap", 24.0));

        // ----- proximity -----
        proximityEnabled = c.getBoolean("proximity.enabled", true);
        proximityRadius = Math.max(1.0, c.getDouble("proximity.radius", 15.0));
        proximityRequireLineOfSight = c.getBoolean("proximity.require-line-of-sight", true);
        proximityDelayTicks = Math.max(1L, c.getLong("proximity.delay-ticks", 40L));
        String proximityColor = c.getString("proximity.particle.color", "#FF5555");
        proximityStyle = parseStyle(c, "proximity.particle.type", proximityColor, proximityColor,
                c.getDouble("proximity.particle.size", 1.4));
        proximityParticleCount = clamp(c.getInt("proximity.particle.count", 12), 1, 100);

        // ----- interact -----
        interactEnabled = c.getBoolean("interact.enabled", false);
        try {
            interactMode = InteractMode.valueOf(c.getString("interact.mode", "ITEM").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("interact.mode inválido; usando ITEM.");
            interactMode = InteractMode.ITEM;
        }
        Material item = Material.matchMaterial(c.getString("interact.item", "BONE"));
        if (item == null) {
            plugin.getLogger().warning("interact.item inválido; usando BONE.");
            item = Material.BONE;
        }
        interactItem = item;
    }

    private StateRule parseRule(FileConfiguration c, String path, Behavior def, double defSeconds) {
        Behavior behavior;
        try {
            behavior = Behavior.valueOf(c.getString(path + ".behavior", def.name()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(path + ".behavior inválido; usando " + def + ".");
            behavior = def;
        }
        long millis = Math.round(Math.max(1.0, c.getDouble(path + ".short-lifetime-seconds", defSeconds)) * 1000.0);
        return new StateRule(behavior, millis);
    }

    private ParticleStyle parseStyle(FileConfiguration c, String typePath, String freshHex, String oldHex, double size) {
        String typeName = c.getString(typePath, "DUST");
        Particle particle;
        try {
            particle = Particle.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(typePath + ": partícula inválida '" + typeName + "'; usando DUST.");
            particle = Particle.DUST;
        }
        Class<?> dataType = particle.getDataType();
        if (dataType != Void.class && dataType != Particle.DustOptions.class) {
            plugin.getLogger().warning(typePath + ": a partícula '" + particle.name()
                    + "' exige dados extras e não é suportada; usando DUST.");
            particle = Particle.DUST;
        }
        return new ParticleStyle(particle,
                parseColor(freshHex, Color.fromRGB(0x6EE7A8)),
                parseColor(oldHex, Color.fromRGB(0x6B7280)),
                (float) Math.max(0.1, size));
    }

    private Color parseColor(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        try {
            return Color.fromRGB(Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("Cor inválida '" + hex + "' (use #RRGGBB).");
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ----- mensagens -----

    public Component message(String key, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null) {
            raw = "<red>[mensagem ausente: " + key + "]";
        }
        return miniMessage.deserialize(raw, resolvers);
    }

    public Component prefixed(String key, TagResolver... resolvers) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        return miniMessage.deserialize(prefix).append(message(key, resolvers));
    }

    public String directionName(CompassDirection direction) {
        return plugin.getConfig().getString("directions." + direction.name(), direction.name());
    }

    // ----- getters: trail -----

    public long recordIntervalTicks() { return recordIntervalTicks; }
    public int maxPoints() { return maxPoints; }
    public long maxAgeMillis() { return maxAgeMillis; }
    public double minMoveDistance() { return minMoveDistance; }
    public StateRule sneakingRule() { return sneakingRule; }
    public StateRule waterRule() { return waterRule; }
    public boolean mobsEnabled() { return mobsEnabled; }
    public Set<EntityType> mobTypes() { return mobTypes; }
    public int mobMaxPoints() { return mobMaxPoints; }
    public int mobMaxTracked() { return mobMaxTracked; }
    public double mobScanRadius() { return mobScanRadius; }

    // ----- getters: sniff -----

    public double sniffRadius() { return sniffRadius; }
    public long cooldownMillis() { return cooldownMillis; }
    public int visualDurationTicks() { return visualDurationTicks; }
    public long freshSeconds() { return freshSeconds; }
    public long recentSeconds() { return recentSeconds; }
    public String soundKey() { return soundKey; }
    public float soundVolume() { return soundVolume; }
    public float soundPitch() { return soundPitch; }
    public boolean soundBroadcast() { return soundBroadcast; }
    public ParticleStyle trailStyle() { return trailStyle; }
    public double particleSpacing() { return particleSpacing; }
    public int particleIntervalTicks() { return particleIntervalTicks; }
    public double renderDistance() { return renderDistance; }
    public int maxParticlesPerTick() { return maxParticlesPerTick; }
    public double maxSegmentGap() { return maxSegmentGap; }

    // ----- getters: proximity -----

    public boolean proximityEnabled() { return proximityEnabled; }
    public double proximityRadius() { return proximityRadius; }
    public boolean proximityRequireLineOfSight() { return proximityRequireLineOfSight; }
    public long proximityDelayTicks() { return proximityDelayTicks; }
    public ParticleStyle proximityStyle() { return proximityStyle; }
    public int proximityParticleCount() { return proximityParticleCount; }

    // ----- getters: interact -----

    public boolean interactEnabled() { return interactEnabled; }
    public InteractMode interactMode() { return interactMode; }
    public Material interactItem() { return interactItem; }
}
