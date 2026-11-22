package com.scenttracker.models;

import java.util.UUID;

/**
 * Um ponto do rastro. Guarda apenas o UUID do mundo (nunca o objeto World),
 * então não segura mundos descarregados na memória.
 */
public record ScentPoint(UUID worldId, double x, double y, double z, long createdAt, long expiresAt) {

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
}
