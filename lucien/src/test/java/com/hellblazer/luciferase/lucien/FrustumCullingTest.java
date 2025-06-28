/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for frustum culling functionality
 *
 * @author hal.hildebrand
 */
class FrustumCullingTest {

    @Test
    void testFrustumCullVisibleEmpty() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        // Create any frustum
        Frustum3D frustum = Frustum3D.createOrthographic(new Point3f(500, 500, 100), new Point3f(500, 500, 1000),
                                                         new Vector3f(0, 1, 0), 0, 1000, 0, 1000, 50, 2000);

        // Test with empty index
        List<LongEntityID> visible = octree.frustumCullVisible(frustum);

        assertNotNull(visible);
        assertTrue(visible.isEmpty(), "Empty index should return empty list");
    }

    @Test
    void testFrustumCullVisibleOctree() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        // Insert some test entities
        LongEntityID id1 = octree.insert(new Point3f(500, 500, 500), (byte) 10, "Entity1");
        LongEntityID id2 = octree.insert(new Point3f(100, 100, 100), (byte) 10, "Entity2");
        LongEntityID id3 = octree.insert(new Point3f(900, 900, 900), (byte) 10, "Entity3");
        LongEntityID id4 = octree.insert(new Point3f(50, 50, 50), (byte) 10, "Entity4");

        // Create a frustum that should see entities near (500,500,500)
        Frustum3D frustum = Frustum3D.createOrthographic(new Point3f(500, 500, 100),  // camera position
                                                         new Point3f(500, 500, 1000), // look at
                                                         new Vector3f(0, 1, 0),       // up
                                                         400,                         // left
                                                         600,                         // right
                                                         400,                         // bottom
                                                         600,                         // top
                                                         50,                          // near
                                                         2000                         // far
                                                        );

        // Test frustum culling
        List<LongEntityID> visible = octree.frustumCullVisible(frustum);

        assertNotNull(visible);
        assertEquals(1, visible.size(), "Should see 1 entity in the frustum");
        assertEquals(id1, visible.get(0), "Should see Entity1");
    }

    @Test
    void testFrustumCullVisiblePerspective() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        // Insert entities in a line
        for (int i = 0; i < 10; i++) {
            float z = 200 + i * 100;
            octree.insert(new Point3f(500, 500, z), (byte) 10, "Entity" + i);
        }

        // Create a perspective frustum
        Frustum3D frustum = Frustum3D.createPerspective(new Point3f(500, 500, 100),  // camera position
                                                        new Point3f(500, 500, 1000), // look at
                                                        new Vector3f(0, 1, 0),       // up
                                                        (float) Math.toRadians(60),  // 60 degree FOV
                                                        1.0f,                        // aspect ratio
                                                        50,                          // near
                                                        500
                                                        // far (should only see first few entities)
                                                       );

        // Test frustum culling
        List<LongEntityID> visible = octree.frustumCullVisible(frustum);

        assertNotNull(visible);
        assertTrue(visible.size() > 0, "Should see some entities");
        assertTrue(visible.size() < 10, "Should not see all entities (some are beyond far plane)");
    }

    @Test
    void testFrustumCullVisibleTetree() {
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());

        // Insert some test entities
        LongEntityID id1 = tetree.insert(new Point3f(500, 500, 500), (byte) 10, "Entity1");
        LongEntityID id2 = tetree.insert(new Point3f(100, 100, 100), (byte) 10, "Entity2");
        LongEntityID id3 = tetree.insert(new Point3f(900, 900, 900), (byte) 10, "Entity3");
        LongEntityID id4 = tetree.insert(new Point3f(50, 50, 50), (byte) 10, "Entity4");

        // Create a frustum that should see entities near (500,500,500)
        Frustum3D frustum = Frustum3D.createOrthographic(new Point3f(500, 500, 100),  // camera position
                                                         new Point3f(500, 500, 1000), // look at
                                                         new Vector3f(0, 1, 0),       // up
                                                         400,                         // left
                                                         600,                         // right
                                                         400,                         // bottom
                                                         600,                         // top
                                                         50,                          // near
                                                         2000                         // far
                                                        );

        // Test frustum culling
        List<LongEntityID> visible = tetree.frustumCullVisible(frustum);

        assertNotNull(visible);
        assertEquals(1, visible.size(), "Should see 1 entity in the frustum");
        assertEquals(id1, visible.get(0), "Should see Entity1");
    }
}
