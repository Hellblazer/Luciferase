/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree.performance;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.benchmark.CIEnvironmentCheck;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Continuous profiling test for Tetree that runs indefinitely. This test maintains a steady state of operations to help
 * identify: - Memory leaks - Performance degradation over time - GC pressure - Cache behavior
 *
 * Run with: -Xmx4g -XX:+UseG1GC -XX:+PrintGCDetails Attach profiler while running to analyze behavior
 *
 * @author hal.hildebrand
 */
public class TetreeContinuousProfilingTest {

    private static final int STEADY_STATE_ENTITIES = 50_000;
    private static final int OPERATIONS_PER_CYCLE  = 1000;
    private static final int REPORT_INTERVAL_MS    = 10_000; // Report every 10 seconds

    private Tetree<LongEntityID, SimulationEntity> tetree;
    private SequentialLongIDGenerator              idGenerator;
    private List<EntityState>                      activeEntities;
    private Random                                 random;
    private OperationStats                         stats;

    @Test
    void continuousProfilingTest() throws InterruptedException {
        System.out.println("=== Continuous Tetree Profiling Test ===");
        System.out.println("Target entities: " + STEADY_STATE_ENTITIES);
        System.out.println("Operations per cycle: " + OPERATIONS_PER_CYCLE);
        System.out.println("Report interval: " + (REPORT_INTERVAL_MS / 1000) + " seconds");
        System.out.println("\nPress Ctrl+C to stop...\n");

        // Initial population
        populateToSteadyState();

        // Continuous operation loop
        long startTime = System.currentTimeMillis();
        long lastReportTime = startTime;
        long cycleCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            // Run one cycle of mixed operations
            runOperationCycle();
            cycleCount++;

            // Report statistics periodically
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastReportTime >= REPORT_INTERVAL_MS) {
                reportStatistics(cycleCount, currentTime - startTime);
                lastReportTime = currentTime;

                // Reset cycle count to prevent overflow
                if (cycleCount > 1_000_000) {
                    cycleCount = 0;
                    stats.reset();
                }
            }

            // Small pause to prevent CPU saturation
            Thread.sleep(1);
        }
    }

    @BeforeEach
    void setup() {
        // Skip this test in CI environments
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), "Continuous profiling test is disabled in CI environments");
        // Skip if not flagged
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")));

        idGenerator = new SequentialLongIDGenerator();
        EntitySpanningPolicy spanningPolicy = new EntitySpanningPolicy(
        EntitySpanningPolicy.SpanningStrategy.SPAN_TO_OVERLAPPING, true, 0.1f);
        tetree = new Tetree<>(idGenerator, 30, (byte) 10, spanningPolicy);
        activeEntities = new ArrayList<>();
        random = new Random();
        stats = new OperationStats();

        // Enable performance monitoring
        // Performance monitoring is automatic
    }

    private EntityState createRandomEntity(int id) {
        Point3f position = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);

        float size = 0.5f + random.nextFloat() * 4.5f;
        SimulationEntity entity = new SimulationEntity("entity_" + id, position, size);

        // Random velocity for movement
        Point3f velocity = new Point3f((random.nextFloat() - 0.5f) * 2, (random.nextFloat() - 0.5f) * 2,
                                       (random.nextFloat() - 0.5f) * 2);

        return new EntityState(entity, velocity);
    }

    private void performFrustumCull() {
        long start = System.nanoTime();

        Point3f cameraPos = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                        random.nextFloat() * 1000);
        Point3f lookAt = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);

        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, new Vector3f(0, 1, 0),
                                                        (float) Math.toRadians(60.0), 1.77f, 1.0f, 300.0f);

        var visible = tetree.frustumCullVisible(frustum, cameraPos);

        stats.frustumTime += System.nanoTime() - start;
        stats.frustumCulls++;
        stats.frustumVisible += visible.size();
    }

    private void performInsert() {
        long start = System.nanoTime();

        EntityState state = createRandomEntity(activeEntities.size());
        LongEntityID id = tetree.insert(state.entity.getPosition(), (byte) 8, state.entity);
        state.id = id;
        activeEntities.add(state);

        stats.insertTime += System.nanoTime() - start;
        stats.inserts++;
    }

    private void performKNNSearch() {
        long start = System.nanoTime();

        Point3f query = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);

        int k = 5 + random.nextInt(15);
        var results = tetree.kNearestNeighbors(query, k, Float.MAX_VALUE);

        stats.knnTime += System.nanoTime() - start;
        stats.knnSearches++;
        stats.knnFound += results.size();
    }

    private void performPointLookup() {
        long start = System.nanoTime();

        Point3f query = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);

        var results = tetree.lookup(query, (byte) 8);

        stats.lookupTime += System.nanoTime() - start;
        stats.lookups++;
        if (!results.isEmpty()) {
            stats.lookupHits++;
        }
    }

    private void performRangeQuery() {
        long start = System.nanoTime();

        Point3f center = new Point3f(random.nextFloat() * 900, random.nextFloat() * 900, random.nextFloat() * 900);
        float radius = 20 + random.nextFloat() * 80;

        // Use cube approximation for range query
        var results = tetree.entitiesInRegion(
        new Spatial.Cube(center.x - radius, center.y - radius, center.z - radius, radius * 2));

        stats.rangeTime += System.nanoTime() - start;
        stats.rangeQueries++;
        stats.rangeFound += results.size();
    }

    private void performRemove() {
        if (activeEntities.size() <= STEADY_STATE_ENTITIES * 0.9) {
            return; // Keep minimum population
        }

        long start = System.nanoTime();

        int index = random.nextInt(activeEntities.size());
        EntityState state = activeEntities.remove(index);
        tetree.removeEntity(state.id);

        stats.removeTime += System.nanoTime() - start;
        stats.removes++;
    }

    private void performUpdate() {
        if (activeEntities.isEmpty()) {
            return;
        }

        long start = System.nanoTime();

        int index = random.nextInt(activeEntities.size());
        EntityState state = activeEntities.get(index);

        // Move entity
        Point3f oldPos = state.entity.getPosition();
        Point3f velocity = state.velocity;
        Point3f newPos = new Point3f(oldPos.x + velocity.x, oldPos.y + velocity.y, oldPos.z + velocity.z);

        // Bounce off boundaries
        if (newPos.x < 0 || newPos.x > 1000) {
            velocity.x = -velocity.x;
            newPos.x = Math.max(0, Math.min(1000, newPos.x));
        }
        if (newPos.y < 0 || newPos.y > 1000) {
            velocity.y = -velocity.y;
            newPos.y = Math.max(0, Math.min(1000, newPos.y));
        }
        if (newPos.z < 0 || newPos.z > 1000) {
            velocity.z = -velocity.z;
            newPos.z = Math.max(0, Math.min(1000, newPos.z));
        }

        state.entity.setPosition(newPos);
        tetree.updateEntity(state.id, newPos, (byte) 8);

        stats.updateTime += System.nanoTime() - start;
        stats.updates++;
    }

    private void populateToSteadyState() {
        System.out.println("Populating to steady state...");
        long start = System.nanoTime();

        for (int i = 0; i < STEADY_STATE_ENTITIES; i++) {
            EntityState state = createRandomEntity(i);
            LongEntityID id = tetree.insert(state.entity.getPosition(), (byte) 8, state.entity);
            state.id = id;
            activeEntities.add(state);

            if (i % 10000 == 0) {
                System.out.printf("  Populated %d entities%n", i);
            }
        }

        long elapsed = System.nanoTime() - start;
        System.out.printf("Population complete in %.2f seconds%n%n", elapsed / 1_000_000_000.0);
    }

    private void reportStatistics(long cycles, long totalTimeMs) {
        System.out.println("\n=== Performance Report ===");
        System.out.printf("Uptime: %.1f minutes%n", totalTimeMs / 60000.0);
        System.out.printf("Cycles: %d (%.1f/sec)%n", cycles, cycles * 1000.0 / totalTimeMs);

        // Operation counts and rates
        System.out.println("\nOperation Counts:");
        System.out.printf("  Inserts: %d (%.0f/sec)%n", stats.inserts, stats.inserts * 1000.0 / totalTimeMs);
        System.out.printf("  Removes: %d (%.0f/sec)%n", stats.removes, stats.removes * 1000.0 / totalTimeMs);
        System.out.printf("  Updates: %d (%.0f/sec)%n", stats.updates, stats.updates * 1000.0 / totalTimeMs);
        System.out.printf("  Lookups: %d (%.0f/sec, %.1f%% hits)%n", stats.lookups,
                          stats.lookups * 1000.0 / totalTimeMs,
                          stats.lookups > 0 ? (stats.lookupHits * 100.0 / stats.lookups) : 0);
        System.out.printf("  k-NN: %d (%.0f/sec, avg %.1f found)%n", stats.knnSearches,
                          stats.knnSearches * 1000.0 / totalTimeMs,
                          stats.knnSearches > 0 ? (double) stats.knnFound / stats.knnSearches : 0);
        System.out.printf("  Range: %d (%.0f/sec, avg %.1f found)%n", stats.rangeQueries,
                          stats.rangeQueries * 1000.0 / totalTimeMs,
                          stats.rangeQueries > 0 ? (double) stats.rangeFound / stats.rangeQueries : 0);
        System.out.printf("  Frustum: %d (%.0f/sec, avg %.1f visible)%n", stats.frustumCulls,
                          stats.frustumCulls * 1000.0 / totalTimeMs,
                          stats.frustumCulls > 0 ? (double) stats.frustumVisible / stats.frustumCulls : 0);

        // Average operation times
        System.out.println("\nAverage Operation Times:");
        System.out.printf("  Insert: %.1f μs%n", stats.inserts > 0 ? stats.insertTime / stats.inserts / 1000.0 : 0);
        System.out.printf("  Remove: %.1f μs%n", stats.removes > 0 ? stats.removeTime / stats.removes / 1000.0 : 0);
        System.out.printf("  Update: %.1f μs%n", stats.updates > 0 ? stats.updateTime / stats.updates / 1000.0 : 0);
        System.out.printf("  Lookup: %.1f μs%n", stats.lookups > 0 ? stats.lookupTime / stats.lookups / 1000.0 : 0);
        System.out.printf("  k-NN: %.1f μs%n", stats.knnSearches > 0 ? stats.knnTime / stats.knnSearches / 1000.0 : 0);
        System.out.printf("  Range: %.1f μs%n",
                          stats.rangeQueries > 0 ? stats.rangeTime / stats.rangeQueries / 1000.0 : 0);
        System.out.printf("  Frustum: %.1f μs%n",
                          stats.frustumCulls > 0 ? stats.frustumTime / stats.frustumCulls / 1000.0 : 0);

        // Tree statistics
        var treeStats = tetree.getTreeStatistics();
        System.out.println("\nTree Statistics:");
        System.out.printf("  Entities: %d%n", activeEntities.size());
        System.out.printf("  Nodes: %d%n", tetree.nodeCount());
        System.out.printf("  Balance factor: %.2f%n", treeStats.getBalanceFactor());

        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1_048_576; // MB
        long freeMemory = runtime.freeMemory() / 1_048_576; // MB
        long usedMemory = totalMemory - freeMemory;
        System.out.println("\nMemory Usage:");
        System.out.printf("  Used: %d MB%n", usedMemory);
        System.out.printf("  Total: %d MB%n", totalMemory);
        System.out.printf("  Max: %d MB%n", runtime.maxMemory() / 1_048_576);

        // Tetree performance metrics
        var metrics = tetree.getMetrics();
        System.out.println("\nTetree Metrics:");
        System.out.printf("  Cache hit rate: %.1f%%%n", metrics.cacheHitRate() * 100);
        System.out.printf("  Neighbor queries: %d%n", metrics.neighborQueryCount());
        System.out.printf("  Traversals: %d%n", metrics.traversalCount());
    }

    private void runOperationCycle() {
        long cycleStart = System.nanoTime();

        for (int i = 0; i < OPERATIONS_PER_CYCLE; i++) {
            // Choose operation based on realistic workload distribution
            double rand = random.nextDouble();

            if (rand < 0.05) {
                // 5% - Insert new entity
                performInsert();
            } else if (rand < 0.10) {
                // 5% - Remove entity
                performRemove();
            } else if (rand < 0.30) {
                // 20% - Update (movement)
                performUpdate();
            } else if (rand < 0.60) {
                // 30% - Point lookup
                performPointLookup();
            } else if (rand < 0.80) {
                // 20% - k-NN search
                performKNNSearch();
            } else if (rand < 0.95) {
                // 15% - Range query
                performRangeQuery();
            } else {
                // 5% - Frustum culling
                performFrustumCull();
            }
        }

        stats.cycleTime += System.nanoTime() - cycleStart;
        stats.cycles++;
    }

    // Entity state for tracking
    static class EntityState {
        SimulationEntity entity;
        Point3f          velocity;
        LongEntityID     id;

        EntityState(SimulationEntity entity, Point3f velocity) {
            this.entity = entity;
            this.velocity = velocity;
        }
    }

    // Simulation entity
    static class SimulationEntity {
        private final String  name;
        private final float   size;
        private       Point3f position;

        SimulationEntity(String name, Point3f position, float size) {
            this.name = name;
            this.position = new Point3f(position);
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public Point3f getPosition() {
            return new Point3f(position);
        }

        public float getSize() {
            return size;
        }

        public void setPosition(Point3f pos) {
            this.position = new Point3f(pos);
        }
    }

    // Statistics tracking
    static class OperationStats {
        long cycles;
        long cycleTime;

        long inserts;
        long insertTime;

        long removes;
        long removeTime;

        long updates;
        long updateTime;

        long lookups;
        long lookupTime;
        long lookupHits;

        long knnSearches;
        long knnTime;
        long knnFound;

        long rangeQueries;
        long rangeTime;
        long rangeFound;

        long frustumCulls;
        long frustumTime;
        long frustumVisible;

        void reset() {
            cycles = 0;
            cycleTime = 0;
            inserts = 0;
            insertTime = 0;
            removes = 0;
            removeTime = 0;
            updates = 0;
            updateTime = 0;
            lookups = 0;
            lookupTime = 0;
            lookupHits = 0;
            knnSearches = 0;
            knnTime = 0;
            knnFound = 0;
            rangeQueries = 0;
            rangeTime = 0;
            rangeFound = 0;
            frustumCulls = 0;
            frustumTime = 0;
            frustumVisible = 0;
        }
    }
}
