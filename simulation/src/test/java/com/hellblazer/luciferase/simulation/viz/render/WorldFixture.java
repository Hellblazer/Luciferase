/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import javax.vecmath.Point3f;
import java.util.Random;

/**
 * Fluent builder for populating a SpatialIndexFacade with test entities.
 *
 * @author hal.hildebrand
 */
public final class WorldFixture {

    private final SpatialIndexFacade facade;

    private WorldFixture(SpatialIndexFacade facade) {
        this.facade = facade;
    }

    public static WorldFixture tetree(int minLevel, int maxLevel) {
        return new WorldFixture(new TetreeSpatialIndexFacade(minLevel, maxLevel));
    }

    public static WorldFixture octree(int minLevel, int maxLevel) {
        return new WorldFixture(new OctreeSpatialIndexFacade(minLevel, maxLevel));
    }

    /** Insert n entities at random positions using the given seed. */
    public WorldFixture withRandomEntities(int count, long seed) {
        var rng = new Random(seed);
        int max = 1 << 21;  // MAX_EXTENT
        for (int i = 0; i < count; i++) {
            facade.put(i, new Point3f(rng.nextInt(max), rng.nextInt(max), rng.nextInt(max)));
        }
        return this;
    }

    /** Insert a single entity at an exact position. */
    public WorldFixture withEntity(long id, float x, float y, float z) {
        facade.put(id, new Point3f(x, y, z));
        return this;
    }

    public SpatialIndexFacade build() {
        return facade;
    }
}
