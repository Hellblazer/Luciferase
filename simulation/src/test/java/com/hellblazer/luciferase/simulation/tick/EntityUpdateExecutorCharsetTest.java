/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.tick;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble.EntityRecord;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Luciferase-0frcy.108: EntityUpdateExecutor must derive the entity UUID using an explicit UTF-8
 * charset so the key is deterministic across platforms/nodes regardless of the JVM default charset.
 * A non-ASCII entity id discriminates a UTF-8 encoding from other charsets.
 */
class EntityUpdateExecutorCharsetTest {

    private EntityUpdateExecutor executor;
    private EntityBehavior behavior;
    private Map<UUID, Vector3f> velocities;
    private EnhancedBubble bubble;

    @BeforeEach
    void setUp() {
        behavior = mock(EntityBehavior.class);
        velocities = new ConcurrentHashMap<>();
        var gridConfig = new GridConfiguration(2, 2, 100f, 100f, 0f, 0f);
        executor = EntityUpdateExecutor.create(behavior, velocities, gridConfig);
        bubble = mock(EnhancedBubble.class);
    }

    @Test
    void velocityKeyIsUtf8DerivedUuid() {
        // Non-ASCII id: under UTF-8 it encodes to multibyte sequences; the key must match the
        // UTF-8-derived UUID, not a platform-default-charset-derived one.
        var entityId = "entité-naïve-Ω";
        var entity = new EntityRecord(entityId, new Point3f(10f, 10f, 10f), "content", 0L);
        var velocity = new Vector3f(1f, 0f, 0f);

        when(bubble.getAllEntityRecords()).thenReturn(List.of(entity));
        when(behavior.computeVelocity(eq(entityId), any(Point3f.class), any(Vector3f.class), eq(bubble), anyFloat()))
            .thenReturn(velocity);

        executor.updateEntities(bubble, 1.0f);

        var expectedKey = UUID.nameUUIDFromBytes(entityId.getBytes(StandardCharsets.UTF_8));
        assertTrue(velocities.containsKey(expectedKey),
            "velocity map must be keyed by the UTF-8-derived UUID for deterministic cross-node keys");
        assertEquals(velocity, velocities.get(expectedKey));
    }
}
