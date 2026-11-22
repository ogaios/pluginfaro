package com.scenttracker.models;

import java.util.List;
import java.util.Optional;

/**
 * Resultado de uma consulta de rastro: pontos válidos (mesmo mundo do farejador),
 * índice do ponto mais próximo e a distância até ele.
 */
public record TrailHit(ScentTrail trail, List<ScentPoint> points, int nearestIndex, double distance) {

    public ScentPoint nearestPoint() {
        return points.get(nearestIndex);
    }

    /** Direção para onde o alvo seguiu, a partir do ponto mais próximo. */
    public Optional<CompassDirection> travelDirection() {
        int to = points.size() - 1;
        int from = nearestIndex;
        if (from == to) {
            from = to - 1; // está sobre o ponto mais novo: usa o último trecho
        }
        if (from < 0) {
            return Optional.empty();
        }
        ScentPoint a = points.get(from);
        ScentPoint b = points.get(to);
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        if (dx * dx + dz * dz < 0.25) {
            return Optional.empty();
        }
        return Optional.of(CompassDirection.fromVector(dx, dz));
    }
}
