/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.ghost;

import com.hellblazer.luciferase.lucien.benchmark.CIEnvironmentCheck;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.*;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.*;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Comprehensive performance benchmark for ghost operations.
 * 
 * Tests ghost creation overhead, data exchange performance, memory usage,
 * and distributed communication throughput to validate performance targets
 * from the GHOST implementation plan.
 * 
 * Performance Targets:
 * - Ghost creation: < 10% overhead vs local operations
 * - Data exchange: > 80% network utilization
 * - Memory usage: < 2x local element storage
 * 
 * @author Hal Hildebrand
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class GhostPerformanceBenchmark {
    
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final int[] GHOST_COUNTS = { 100, 1000, 5000 };
    private static final int[] PROCESS_COUNTS = { 2, 4, 8 };
    private static final byte TEST_LEVEL = 10;
    private static final int BASE_PORT = 9100;
    private static final String BIND_ADDRESS = "localhost";
    
    // Test data
    private SequentialLongIDGenerator idGenerator;
    private Runtime runtime;
    
    // Multi-process simulation components
    private List<GhostCommunicationManager<MortonKey, LongEntityID, String>> managers;
    private SimpleServiceDiscovery serviceDiscovery;
    
    @BeforeEach
    void setUp() throws Exception {
        // Skip if running in CI environment
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), CIEnvironmentCheck.getSkipMessage());
        
        idGenerator = new SequentialLongIDGenerator();
        runtime = Runtime.getRuntime();
        
        System.out.println("=== GHOST OPERATIONS PERFORMANCE BENCHMARK ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + runtime.availableProcessors());
        System.out.println("Memory: " + (runtime.maxMemory() / 1024 / 1024) + " MB");
        System.out.println();
        
        // Force GC before benchmarks
        System.gc();
        Thread.sleep(100);
    }
    
    @AfterEach
    void tearDown() {
        if (managers != null) {
            managers.forEach(GhostCommunicationManager::shutdown);
            managers.clear();
        }
    }
    
    @Test
    void benchmarkGhostCreationOverhead() {
        System.out.println("\n=== GHOST CREATION OVERHEAD BENCHMARK ===");
        System.out.println("Target: < 10% overhead vs local operations\n");
        
        for (int ghostCount : GHOST_COUNTS) {
            System.out.printf("Testing with %d ghosts:%n", ghostCount);
            
            // Generate test data
            var ghosts = generateTestGhosts(ghostCount);
            
            // Benchmark local spatial index insertion
            var localIndex = new Octree<LongEntityID, String>(idGenerator);
            var localTime = benchmarkLocalInsertion(localIndex, ghosts);
            
            // Benchmark ghost layer creation
            var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
            var ghostTime = benchmarkGhostCreation(ghostLayer, ghosts);
            
            // Calculate overhead
            var overhead = ((double) (ghostTime - localTime) / localTime) * 100.0;
            var passed = overhead < 10.0;
            
            System.out.printf("  Local insertion:  %,10.2f μs (%,8.0f ops/sec)%n", 
                            localTime / 1000.0, (ghostCount * 1_000_000_000.0) / localTime);
            System.out.printf("  Ghost creation:   %,10.2f μs (%,8.0f ops/sec)%n", 
                            ghostTime / 1000.0, (ghostCount * 1_000_000_000.0) / ghostTime);
            System.out.printf("  Overhead:         %,10.2f%% %s%n", 
                            overhead, passed ? "✓ PASS" : "✗ FAIL");
            System.out.println();
        }
    }
    
    @Test
    void benchmarkDataExchangePerformance() throws Exception {
        System.out.println("\n=== DATA EXCHANGE PERFORMANCE BENCHMARK ===");
        System.out.println("Target: > 80% network utilization\n");
        
        for (int processCount : PROCESS_COUNTS) {
            System.out.printf("Testing with %d processes:%n", processCount);
            
            // Setup multi-process simulation
            setupMultiProcessEnvironment(processCount);
            
            // Create ghost layers with varying sizes
            for (int ghostCount : GHOST_COUNTS) {
                var ghosts = generateTestGhosts(ghostCount);
                
                // Setup ghost data on rank 0
                var treeId = 1000L + ghostCount;
                var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
                for (var ghost : ghosts) {
                    ghostLayer.addGhostElement(ghost);
                }
                managers.get(0).addGhostLayer(treeId, ghostLayer);
                
                // Benchmark data exchange from rank 0 to all others
                var exchangeTime = benchmarkDataExchange(treeId, ghostCount);
                
                // Calculate throughput
                var payloadSize = estimatePayloadSize(ghosts);
                var throughputMBps = (payloadSize * (processCount - 1) * 1000.0) / (exchangeTime / 1_000_000.0) / (1024 * 1024);
                var utilizationPercent = Math.min(100.0, (throughputMBps / 125.0) * 100.0); // Assume 1Gbps = 125 MB/s
                var passed = utilizationPercent > 80.0;
                
                System.out.printf("  %,5d ghosts: %,8.2f ms, %,8.2f MB/s (%,5.1f%% util) %s%n",
                                ghostCount, exchangeTime / 1_000_000.0, throughputMBps, 
                                utilizationPercent, passed ? "✓" : "✗");
            }
            
            tearDownMultiProcessEnvironment();
            System.out.println();
        }
    }
    
    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== MEMORY USAGE BENCHMARK ===");
        System.out.println("Target: < 2x local element storage\n");
        
        for (int ghostCount : GHOST_COUNTS) {
            System.out.printf("Testing with %d elements:%n", ghostCount);
            
            // Generate test data
            var entities = generateTestEntities(ghostCount);
            var ghosts = generateTestGhosts(ghostCount);
            
            // Measure local storage memory
            System.gc();
            var memBefore = getUsedMemory();
            
            var localIndex = new Octree<LongEntityID, String>(idGenerator);
            for (var entity : entities) {
                localIndex.insert(entity.id, entity.position, TEST_LEVEL, entity.data);
            }
            
            var memAfterLocal = getUsedMemory();
            var localMemory = memAfterLocal - memBefore;
            
            // Measure ghost storage memory
            System.gc();
            memBefore = getUsedMemory();
            
            var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
            for (var ghost : ghosts) {
                ghostLayer.addGhostElement(ghost);
            }
            
            var memAfterGhost = getUsedMemory();
            var ghostMemory = memAfterGhost - memBefore;
            
            // Calculate memory ratio
            var memoryRatio = (double) ghostMemory / localMemory;
            var passed = memoryRatio < 2.0;
            
            System.out.printf("  Local storage:    %,10.2f KB (%,6.1f bytes/element)%n", 
                            localMemory / 1024.0, (double) localMemory / ghostCount);
            System.out.printf("  Ghost storage:    %,10.2f KB (%,6.1f bytes/element)%n", 
                            ghostMemory / 1024.0, (double) ghostMemory / ghostCount);
            System.out.printf("  Memory ratio:     %,10.2fx %s%n", 
                            memoryRatio, passed ? "✓ PASS" : "✗ FAIL");
            System.out.println();
        }
    }
    
    @Test
    void benchmarkProtobufSerialization() {
        System.out.println("\n=== PROTOBUF SERIALIZATION BENCHMARK ===");
        System.out.println("Performance impact of serialization overhead\n");
        
        for (int ghostCount : GHOST_COUNTS) {
            System.out.printf("Testing with %d ghosts:%n", ghostCount);
            
            var ghosts = generateTestGhosts(ghostCount);
            var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
            for (var ghost : ghosts) {
                ghostLayer.addGhostElement(ghost);
            }
            
            try {
                // Benchmark serialization
                var serializeTime = benchmarkSerialization(ghostLayer, ghostCount);
                
                // Benchmark deserialization
                var batch = ghostLayer.toProtobufBatch(0, 1000L, ContentSerializer.STRING_SERIALIZER);
                var deserializeTime = benchmarkDeserialization(batch, ghostCount);
                
                // Calculate throughput
                var serializeThroughput = (ghostCount * 1_000_000_000.0) / serializeTime;
                var deserializeThroughput = (ghostCount * 1_000_000_000.0) / deserializeTime;
                
                System.out.printf("  Serialize:        %,10.2f μs (%,8.0f ops/sec)%n", 
                                serializeTime / 1000.0, serializeThroughput);
                System.out.printf("  Deserialize:      %,10.2f μs (%,8.0f ops/sec)%n", 
                                deserializeTime / 1000.0, deserializeThroughput);
                System.out.printf("  Roundtrip:        %,10.2f μs (%,8.0f ops/sec)%n", 
                                (serializeTime + deserializeTime) / 1000.0, 
                                (ghostCount * 1_000_000_000.0) / (serializeTime + deserializeTime));
                
            } catch (ContentSerializer.SerializationException e) {
                System.err.printf("  Serialization failed for %d ghosts: %s%n", ghostCount, e.getMessage());
            }
            System.out.println();
        }
    }
    
    @Test
    void benchmarkConcurrentOperations() throws Exception {
        System.out.println("\n=== CONCURRENT OPERATIONS BENCHMARK ===");
        System.out.println("Multi-threaded ghost operations performance\n");
        
        var processCount = 4;
        setupMultiProcessEnvironment(processCount);
        
        for (int ghostCount : GHOST_COUNTS) {
            System.out.printf("Testing with %d ghosts:%n", ghostCount);
            
            // Setup ghost layers on all processes
            var treeId = 2000L + ghostCount;
            for (int rank = 0; rank < processCount; rank++) {
                var ghosts = generateTestGhostsForRank(ghostCount / processCount, rank);
                var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
                for (var ghost : ghosts) {
                    ghostLayer.addGhostElement(ghost);
                }
                managers.get(rank).addGhostLayer(treeId, ghostLayer);
            }
            
            // Benchmark concurrent synchronization
            var concurrentTime = benchmarkConcurrentSync(treeId, processCount);
            
            // Benchmark sequential synchronization for comparison
            var sequentialTime = benchmarkSequentialSync(treeId, processCount);
            
            // Calculate speedup
            var speedup = (double) sequentialTime / concurrentTime;
            var efficiency = speedup / processCount * 100.0;
            
            System.out.printf("  Sequential sync:  %,10.2f ms%n", sequentialTime / 1_000_000.0);
            System.out.printf("  Concurrent sync:  %,10.2f ms%n", concurrentTime / 1_000_000.0);
            System.out.printf("  Speedup:          %,10.2fx (%,5.1f%% efficiency)%n", speedup, efficiency);
            System.out.println();
        }
        
        tearDownMultiProcessEnvironment();
    }
    
    // === Helper Methods ===
    
    private List<GhostElement<MortonKey, LongEntityID, String>> generateTestGhosts(int count) {
        var ghosts = new ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            var position = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            var key = new MortonKey(random.nextLong() & 0x7FFFFFFFFFFFFFFFL);
            var id = idGenerator.generateID();
            var content = "test-ghost-content-" + i;
            
            ghosts.add(new GhostElement<>(key, id, content, position, 
                                        random.nextInt(4), 1000L));
        }
        
        return ghosts;
    }
    
    private List<GhostElement<MortonKey, LongEntityID, String>> generateTestGhostsForRank(int count, int rank) {
        var ghosts = new ArrayList<GhostElement<MortonKey, LongEntityID, String>>();
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            var position = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            var key = new MortonKey(random.nextLong() & 0x7FFFFFFFFFFFFFFFL);
            var id = idGenerator.generateID();
            var content = "test-ghost-content-rank" + rank + "-" + i;
            
            ghosts.add(new GhostElement<>(key, id, content, position, 
                                        rank, 1000L));
        }
        
        return ghosts;
    }
    
    private List<TestEntity> generateTestEntities(int count) {
        var entities = new ArrayList<TestEntity>();
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            var position = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            var id = idGenerator.generateID();
            var data = "test-entity-data-" + i;
            
            entities.add(new TestEntity(id, position, data));
        }
        
        return entities;
    }
    
    private long benchmarkLocalInsertion(Octree<LongEntityID, String> index, 
                                        List<GhostElement<MortonKey, LongEntityID, String>> ghosts) {
        // Warmup
        for (int i = 0; i < Math.min(WARMUP_ITERATIONS, ghosts.size()); i++) {
            var ghost = ghosts.get(i);
            index.insert(ghost.getEntityId(), ghost.getPosition(), TEST_LEVEL, ghost.getContent());
        }
        // Clear by removing all entities
        for (var ghost : ghosts.subList(0, Math.min(WARMUP_ITERATIONS, ghosts.size()))) {
            index.removeEntity(ghost.getEntityId());
        }
        
        // Benchmark
        var start = System.nanoTime();
        for (var ghost : ghosts) {
            index.insert(ghost.getEntityId(), ghost.getPosition(), TEST_LEVEL, ghost.getContent());
        }
        return System.nanoTime() - start;
    }
    
    private long benchmarkGhostCreation(GhostLayer<MortonKey, LongEntityID, String> layer,
                                       List<GhostElement<MortonKey, LongEntityID, String>> ghosts) {
        // Warmup
        for (int i = 0; i < Math.min(WARMUP_ITERATIONS, ghosts.size()); i++) {
            layer.addGhostElement(ghosts.get(i));
        }
        // Clear layer by creating a new one
        layer = new GhostLayer<>(GhostType.FACES);
        
        // Benchmark
        var start = System.nanoTime();
        for (var ghost : ghosts) {
            layer.addGhostElement(ghost);
        }
        return System.nanoTime() - start;
    }
    
    private void setupMultiProcessEnvironment(int processCount) throws Exception {
        serviceDiscovery = SimpleServiceDiscovery.forLocalTesting(processCount, BASE_PORT);
        managers = new ArrayList<>();
        
        for (int rank = 0; rank < processCount; rank++) {
            var manager = new GhostCommunicationManager<MortonKey, LongEntityID, String>(
                rank, BIND_ADDRESS, BASE_PORT + rank,
                ContentSerializer.STRING_SERIALIZER, LongEntityID.class, serviceDiscovery
            );
            managers.add(manager);
            manager.start();
        }
        
        // Give servers time to start
        Thread.sleep(200);
    }
    
    private void tearDownMultiProcessEnvironment() {
        if (managers != null) {
            managers.forEach(GhostCommunicationManager::shutdown);
            managers.clear();
        }
    }
    
    private long benchmarkDataExchange(long treeId, int ghostCount) {
        var start = System.nanoTime();
        
        // All other processes request from rank 0
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for (int rank = 1; rank < managers.size(); rank++) {
            var manager = managers.get(rank);
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                manager.requestGhosts(0, treeId, GhostType.FACES, null)
            );
            futures.add(future);
        }
        
        // Wait for all exchanges to complete
        futures.forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                // Ignore for benchmark
            }
        });
        
        return System.nanoTime() - start;
    }
    
    private long benchmarkConcurrentSync(long treeId, int processCount) {
        var start = System.nanoTime();
        
        // All processes sync with all others concurrently
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for (int sourceRank = 0; sourceRank < processCount; sourceRank++) {
            for (int targetRank = 0; targetRank < processCount; targetRank++) {
                if (sourceRank != targetRank) {
                    var source = managers.get(sourceRank);
                    var target = targetRank;
                    var future = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        source.syncGhosts(target, List.of(treeId), GhostType.FACES)
                    );
                    futures.add(future);
                }
            }
        }
        
        // Wait for all syncs to complete
        futures.forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                // Ignore for benchmark
            }
        });
        
        return System.nanoTime() - start;
    }
    
    private long benchmarkSequentialSync(long treeId, int processCount) {
        var start = System.nanoTime();
        
        // All processes sync with all others sequentially
        for (int sourceRank = 0; sourceRank < processCount; sourceRank++) {
            for (int targetRank = 0; targetRank < processCount; targetRank++) {
                if (sourceRank != targetRank) {
                    managers.get(sourceRank).syncGhosts(targetRank, List.of(treeId), GhostType.FACES);
                }
            }
        }
        
        return System.nanoTime() - start;
    }
    
    private long benchmarkSerialization(GhostLayer<MortonKey, LongEntityID, String> layer, int count) 
            throws ContentSerializer.SerializationException {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            layer.toProtobufBatch(0, 1000L, ContentSerializer.STRING_SERIALIZER);
        }
        
        // Benchmark
        var start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            layer.toProtobufBatch(0, 1000L, ContentSerializer.STRING_SERIALIZER);
        }
        return (System.nanoTime() - start) / BENCHMARK_ITERATIONS;
    }
    
    private long benchmarkDeserialization(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostBatch batch, int count) 
            throws ContentSerializer.SerializationException {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var element : batch.getElementsList()) {
                GhostElement.<MortonKey, LongEntityID, String>fromProtobuf(
                    element, ContentSerializer.STRING_SERIALIZER, LongEntityID.class);
            }
        }
        
        // Benchmark
        var start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            for (var element : batch.getElementsList()) {
                GhostElement.<MortonKey, LongEntityID, String>fromProtobuf(
                    element, ContentSerializer.STRING_SERIALIZER, LongEntityID.class);
            }
        }
        return (System.nanoTime() - start) / BENCHMARK_ITERATIONS;
    }
    
    private long estimatePayloadSize(List<GhostElement<MortonKey, LongEntityID, String>> ghosts) {
        // Rough estimate: 64 bytes per ghost (key + id + position + content)
        return ghosts.size() * 64L;
    }
    
    private long getUsedMemory() {
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    /**
     * Test entity for local storage benchmarks.
     */
    private static class TestEntity {
        final LongEntityID id;
        final Point3f position;
        final String data;
        
        TestEntity(LongEntityID id, Point3f position, String data) {
            this.id = id;
            this.position = position;
            this.data = data;
        }
    }
}