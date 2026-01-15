/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.UUID;

/**
 * Factory for creating test bubbles with entities.
 *
 * @author hal.hildebrand
 */
public class TestBubbleFactory {

    /**
     * Create a test bubble with specified number of randomly placed entities.
     *
     * @param entityCount Number of entities to create
     * @param random      Random generator for entity placement
     * @return EnhancedBubble with entities
     */
    public static EnhancedBubble createTestBubble(int entityCount, Random random) throws Exception {
        var bubbleId = UUID.randomUUID();
        var bubble = new EnhancedBubble(bubbleId, (byte) 12, 16L);  // spatialLevel=12, targetFrameMs=16

        // Add entities at random positions within [0, 200] bounds
        for (int i = 0; i < entityCount; i++) {
            var position = new Point3f(
                random.nextFloat() * 200.0f,
                random.nextFloat() * 200.0f,
                random.nextFloat() * 200.0f
            );
            bubble.addEntity("entity-" + i, position, null);
        }

        return bubble;
    }
}
