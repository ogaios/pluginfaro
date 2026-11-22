package com.scenttracker.models;

import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rastro de uma entidade: fila limitada de pontos, do mais antigo ao mais recente.
 * Não é thread-safe: use apenas na thread principal do servidor.
 */
public final class ScentTrail {

    public enum Kind { PLAYER, MOB }

    private final UUID ownerId;
    private final Kind kind;
    private final Component displayName;
    private final ArrayDeque<ScentPoint> points = new ArrayDeque<>();

    public ScentTrail(UUID ownerId, Kind kind, Component displayName) {
        this.ownerId = ownerId;
        this.kind = kind;
        this.displayName = displayName;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public Kind kind() {
        return kind;
    }

    public Component displayName() {
        return displayName;
    }

    public void add(ScentPoint point, int maxPoints) {
        points.addLast(point);
        while (points.size() > maxPoints) {
            points.pollFirst();
        }
    }

    /** Remove pontos expirados (a validade varia: agachado/água expiram antes). */
    public void purgeExpired(long now) {
        points.removeIf(p -> p.isExpired(now));
    }

    public ScentPoint last() {
        return points.peekLast();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Cópia ordenada do mais antigo ao mais recente. */
    public List<ScentPoint> snapshot() {
        return new ArrayList<>(points);
    }
}
