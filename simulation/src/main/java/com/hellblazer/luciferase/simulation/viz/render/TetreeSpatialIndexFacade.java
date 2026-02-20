/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SpatialIndexFacade backed by Tet.locatePointBeyRefinementFromRoot.
 *
 * <p>Internal coordinate space: 0..2^21-1 cast to float, same as Tetree.insert().
 * Level cap: 0-10 (CompactTetreeKey range).
 */
public final class TetreeSpatialIndexFacade implements SpatialIndexFacade {

    private final int minLevel;
    private final int maxDirtyLevel;

    private final ConcurrentHashMap<Long, Point3f>            entityPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SpatialKey<?>, Set<Long>> cellOccupants   = new ConcurrentHashMap<>();

    public TetreeSpatialIndexFacade(int minLevel, int maxDirtyLevel) {
        this.minLevel = minLevel;
        this.maxDirtyLevel = Math.min(maxDirtyLevel, 10);
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
        var oldPos = entityPositions.get(entityId);
        if (oldPos == null) return;
        keysContaining(oldPos, minLevel, maxDirtyLevel)
            .forEach(k -> {
                var occupants = cellOccupants.get(k);
                if (occupants != null) {
                    occupants.remove(entityId);
                    if (occupants.isEmpty()) cellOccupants.remove(k, occupants);
                }
            });
        entityPositions.put(entityId, new Point3f(newPosition));
        keysContaining(newPosition, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void remove(long entityId) {
        var pos = entityPositions.remove(entityId);
        if (pos == null) return;
        keysContaining(pos, minLevel, maxDirtyLevel)
            .forEach(k -> {
                var occupants = cellOccupants.get(k);
                if (occupants != null) {
                    occupants.remove(entityId);
                    if (occupants.isEmpty()) cellOccupants.remove(k, occupants);
                }
            });
    }

    @Override
    public Set<SpatialKey<?>> keysContaining(Point3f point, int minLvl, int maxLvl) {
        var result = new HashSet<SpatialKey<?>>();
        int cap = Math.min(maxLvl, 10);
        for (int L = minLvl; L <= cap; L++) {
            var tet = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, (byte) L);
            result.add(tet.tmIndex());
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public List<Point3f> positionsAt(SpatialKey<?> key) {
        var occupants = cellOccupants.get(key);
        if (occupants == null || occupants.isEmpty()) return List.of();
        var result = new ArrayList<Point3f>();
        for (var entityId : occupants) {
            var pos = entityPositions.get(entityId);
            if (pos != null) result.add(new Point3f(pos));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() != level) continue;
            if (frustumIntersects(key, frustum)) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<SpatialKey<?>> allOccupiedKeys(int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() == level && !cellOccupants.get(key).isEmpty()) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int entityCount() { return entityPositions.size(); }

    private static boolean frustumIntersects(SpatialKey<?> key, Frustum3D frustum) {
        if (!(key instanceof TetreeKey<?> tk)) return false;
        var verts = Tet.tetrahedron(tk).coordinates(); // Point3i[4]
        float minX = verts[0].x, maxX = verts[0].x;
        float minY = verts[0].y, maxY = verts[0].y;
        float minZ = verts[0].z, maxZ = verts[0].z;
        for (var v : verts) {
            minX = Math.min(minX, v.x);
            maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }
        return frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
