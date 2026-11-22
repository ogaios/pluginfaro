package com.scenttracker.models;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Estilo de partícula. Para DUST, a cor é um degradê entre {@code oldColor} e {@code freshColor}.
 * Sempre envia apenas para o jogador informado (visão exclusiva, client-side).
 */
public record ParticleStyle(Particle particle, Color freshColor, Color oldColor, float size) {

    public boolean usesDust() {
        return particle.getDataType() == Particle.DustOptions.class;
    }

    /**
     * @param freshness 0.0 = ponto mais antigo, 1.0 = ponto mais recente
     */
    public void spawn(Player player, double x, double y, double z, int count, double offset, double freshness) {
        if (usesDust()) {
            Color color = blend(oldColor, freshColor, freshness);
            player.spawnParticle(particle, x, y, z, count, offset, offset, offset, 0.0,
                    new Particle.DustOptions(color, size));
        } else {
            player.spawnParticle(particle, x, y, z, count, offset, offset, offset, 0.0);
        }
    }

    private static Color blend(Color from, Color to, double t) {
        double k = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * k);
        int g = (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * k);
        int b = (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * k);
        return Color.fromRGB(r, g, b);
    }
}
