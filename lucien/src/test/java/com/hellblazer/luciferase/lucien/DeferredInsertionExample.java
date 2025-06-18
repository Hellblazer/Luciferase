/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Example demonstrating deferred insertion functionality for bulk operations in Tetree.
 *
 * @author hal.hildebrand
 */
public class DeferredInsertionExample {

    public static void main(String[] args) {
        // Create a Tetree instance
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        System.out.println("=== Deferred Insertion Example ===\n");
        
        // Example 1: Basic deferred insertion
        basicDeferredInsertion(tetree);
        
        // Example 2: Bulk loading with convenience methods
        bulkLoadingExample();
        
        // Example 3: Custom configuration
        customConfigurationExample();
    }
    
    private static void basicDeferredInsertion(Tetree<LongEntityID, String> tetree) {
        System.out.println("1. Basic Deferred Insertion:");
        
        // Enable deferred insertion
        tetree.setDeferredInsertionEnabled(true);
        
        // Configure batch size
        tetree.getDeferredInsertionConfig().setMaxBatchSize(50);
        
        // Insert multiple entities - they'll be buffered
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Point3f position = new Point3f(10 + i, 20 + i, 30 + i);
            LongEntityID id = tetree.deferredInsert(position, (byte) 5, "Entity " + i);
            entityIds.add(id);
        }
        
        // Check pending count
        System.out.println("  Pending insertions: " + tetree.getPendingInsertionCount());
        System.out.println("  Current entity count: " + tetree.entityCount());
        
        // Flush remaining insertions
        int flushed = tetree.flushDeferredInsertions();
        System.out.println("  Flushed " + flushed + " insertions");
        System.out.println("  Final entity count: " + tetree.entityCount());
        
        // Disable deferred insertion
        tetree.setDeferredInsertionEnabled(false);
        System.out.println();
    }
    
    private static void bulkLoadingExample() {
        System.out.println("2. Bulk Loading with Convenience Methods:");
        
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Prepare data for bulk loading
        class EntityData {
            Point3f position;
            byte level;
            String content;
            
            EntityData(float x, float y, float z, byte level, String content) {
                this.position = new Point3f(x, y, z);
                this.level = level;
                this.content = content;
            }
        }
        
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(new EntityData(
                100 + i % 10,
                200 + i % 20,
                300 + i % 30,
                (byte) 5,
                "Bulk Entity " + i
            ));
        }
        
        // Use bulk loading convenience method
        long startTime = System.currentTimeMillis();
        tetree.enableBulkLoading(100);
        
        List<LongEntityID> ids = tetree.bulkInsert(
            entities,
            e -> e.position,
            e -> e.level,
            e -> e.content
        );
        
        long endTime = System.currentTimeMillis();
        
        System.out.println("  Bulk loaded " + ids.size() + " entities in " + (endTime - startTime) + " ms");
        System.out.println("  Entity count: " + tetree.entityCount());
        System.out.println();
    }
    
    private static void customConfigurationExample() {
        System.out.println("3. Custom Configuration Example:");
        
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Configure deferred insertion
        DeferredInsertionManager.DeferredInsertionConfig config = tetree.getDeferredInsertionConfig();
        config.setMaxBatchSize(200)
              .setMaxFlushDelayMillis(500)  // Auto-flush after 500ms
              .setAutoFlushOnQuery(true)     // Auto-flush before queries
              .setMinBatchSize(50);           // Optimize for at least 50 items
        
        tetree.setDeferredInsertionEnabled(true);
        
        // Insert some entities
        for (int i = 0; i < 75; i++) {
            Point3f position = new Point3f(50 + i, 60 + i, 70 + i);
            tetree.deferredInsert(position, (byte) 4, "Config Entity " + i);
        }
        
        System.out.println("  Pending after 75 insertions: " + tetree.getPendingInsertionCount());
        
        // Perform a query - should trigger auto-flush
        var neighbors = tetree.kNearestNeighbors(new Point3f(100, 100, 100), 5, 1000f);
        
        System.out.println("  Pending after query: " + tetree.getPendingInsertionCount());
        System.out.println("  Found " + neighbors.size() + " neighbors");
        
        // Get statistics
        var stats = tetree.getDeferredInsertionStats();
        System.out.println("\n  Deferred Insertion Statistics:");
        stats.forEach((key, value) -> System.out.println("    " + key + ": " + value));
        
        tetree.setDeferredInsertionEnabled(false);
    }
}