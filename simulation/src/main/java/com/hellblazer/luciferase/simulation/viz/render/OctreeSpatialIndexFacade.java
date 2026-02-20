/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.octree.MortonKey;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SpatialIndexFacade backed by MortonKey. Supports levels 0-21.
 *
 * <p>Internal coordinate space: 0..2^21-1 cast to float, same as Octree.insert().
 */
public final class OctreeSpatialIndexFacade implements SpatialIndexFacade {

    private final int minLevel;
    private final int maxDirtyLevel;

    private final ConcurrentHashMap<Long, Point3f>            entityPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SpatialKey<?>, Set<Long>> cellOccupants   = new ConcurrentHashMap<>();

    public OctreeSpatialIndexFacade(int minLevel, int maxDirtyLevel) {
        this.minLevel = minLevel;
        this.maxDirtyLevel = Math.min(maxDirtyLevel, Constants.getMaxRefinementLevel());
    }

    @Override
    public void put(long entityId, Point3f position) {
        entityPositions.put(entityId, new Point3f(position));
        keysContaining(position, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void move(long entityId, Point3f newPosition) {
        entityPositions.compute(entityId, (id, oldPos) -> {
            if (oldPos == null) return null;
            keysContaining(oldPos, minLevel, maxDirtyLevel)
                .forEach(k -> { var s = cellOccupants.get(k);
                    if (s != null) { s.remove(id); if (s.isEmpty()) cellOccupants.remove(k, s); }});
            var newPos = new Point3f(newPosition);
            keysContaining(newPos, minLevel, maxDirtyLevel)
                .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                           .add(id));
            return newPos;
        });
    }

    @Override
    public void remove(long entityId) {
        var pos = entityPositions.remove(entityId);
        if (pos == null) return;
        keysContaining(pos, minLevel, maxDirtyLevel)
            .forEach(k -> {
                var s = cellOccupants.get(k);
                if (s != null) {
                    s.remove(entityId);
                    if (s.isEmpty()) cellOccupants.remove(k, s);
                }
            });
    }

    @Override
    public Set<SpatialKey<?>> keysContaining(Point3f point, int minLvl, int maxLvl) {
        var result = new HashSet<SpatialKey<?>>();
        int cap = Math.min(maxLvl, Constants.getMaxRefinementLevel());
        for (int L = minLvl; L <= cap; L++) {
            result.add(MortonKey.fromCoordinates(
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.x)),
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.y)),
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.z)),
                (byte) L));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public List<Point3f> positionsAt(SpatialKey<?> key) {
        var occupants = cellOccupants.get(key);
        if (occupants == null || occupants.isEmpty()) return List.of();
        var result = new ArrayList<Point3f>();
        for (var id : occupants) {
            var p = entityPositions.get(id);
            if (p != null) result.add(new Point3f(p));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() != level) continue;
            var s = cellOccupants.get(key);
            if (s == null || s.isEmpty()) continue;
            if (frustumIntersects(key, frustum)) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<SpatialKey<?>> allOccupiedKeys(int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() != level) continue;
            var s = cellOccupants.get(key);
            if (s != null && !s.isEmpty()) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int entityCount() { return entityPositions.size(); }

    private static boolean frustumIntersects(SpatialKey<?> key, Frustum3D frustum) {
        if (!(key instanceof MortonKey mk)) return false;
        var c = MortonCurve.decode(mk.getMortonCode());
        var s = (float) Constants.lengthAtLevel(mk.getLevel());
        return frustum.intersectsAABB(c[0], c[1], c[2], c[0] + s, c[1] + s, c[2] + s);
    }
}
