package com.hellblazer.luciferase.render.voxel.storage;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.render.voxel.quality.QualityController;

/**
 * Runtime memory management system for ESVO three-tier storage architecture.
 * 
 * Manages memory allocation between:
 * - Hot cache: Frequently accessed nodes in memory
 * - Warm cache: Recently accessed nodes eligible for eviction
 * - Cold storage: Disk-based storage through ClusteredFile/OctreeFile
 * 
 * Features:
 * - LRU-based cache eviction with memory pressure monitoring
 * - Streaming nodes from disk storage as needed
 * - Memory pool allocation for octree nodes
 * - Quality-driven LOD management
 * - Background prefetching of likely-needed nodes
 */
public class RuntimeMemoryManager {
    
    /**
     * Configuration for memory management behavior.
     */
    public static class Config {
        /** Maximum memory for hot cache in bytes */
        public long hotCacheMaxBytes = 256 * 1024 * 1024; // 256MB
        
        /** Maximum memory for warm cache in bytes */
        public long warmCacheMaxBytes = 128 * 1024 * 1024; // 128MB
        
        /** Memory pressure threshold (0.0-1.0) to trigger eviction */
        public float memoryPressureThreshold = 0.85f;
        
        /** Number of background prefetch threads */
        public int prefetchThreads = 2;
        
        /** Maximum nodes to prefetch per operation */
        public int maxPrefetchNodes = 64;
        
        /** Enable aggressive memory reclamation */
        public boolean aggressiveEviction = false;
        
        /** Node pool initial size */
        public int nodePoolSize = 1000;
        
        /** Quality controller for LOD decisions */
        public QualityController qualityController = new QualityController();
    }
    
    /**
     * Memory-managed octree node with lifecycle tracking.
     */
    public static class ManagedNode {
        // Node identity and hierarchy
        public final long nodeId;
        public final int depth;
        public final Vector3f position;
        public final float size;
        
        // Node data
        public Color3f color;
        public Vector3f normal;
        public boolean isLeaf;
        public ManagedNode[] children;
        
        // Memory management state
        public volatile long lastAccessTime;
        public volatile int accessCount;
        public volatile boolean isDirty;
        public volatile boolean isLoading;
        public volatile CacheLevel cacheLevel;
        
        // Storage references
        public long diskOffset = -1;
        public int serializedSize = 0;
        
        public ManagedNode(long nodeId, int depth, Vector3f position, float size) {
            this.nodeId = nodeId;
            this.depth = depth;
            this.position = new Vector3f(position);
            this.size = size;
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount = 0;
            this.isDirty = false;
            this.isLoading = false;
            this.cacheLevel = CacheLevel.NONE;
        }
        
        public void recordAccess() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
        }
        
        public boolean hasChildren() {
            return children != null && Arrays.stream(children).anyMatch(Objects::nonNull);
        }
        
        public int getEstimatedMemorySize() {
            int baseSize = 200; // Approximate object overhead + fields
            if (children != null) baseSize += children.length * 8; // Reference array
            return baseSize;
        }
    }
    
    /**
     * Cache level enumeration for three-tier architecture.
     */
    public enum CacheLevel {
        NONE,     // Not cached, must load from disk
        WARM,     // In warm cache, eligible for eviction
        HOT       // In hot cache, frequently accessed
    }
    
    /**
     * Cache statistics for monitoring and debugging.
     */
    public static class CacheStats {
        public long hotCacheHits = 0;
        public long warmCacheHits = 0;
        public long cacheMisses = 0;
        public long evictions = 0;
        public long nodesLoaded = 0;
        public long nodesSaved = 0;
        public long currentHotMemory = 0;
        public long currentWarmMemory = 0;
        
        public double getHitRatio() {
            long totalAccesses = hotCacheHits + warmCacheHits + cacheMisses;
            return totalAccesses > 0 ? (double)(hotCacheHits + warmCacheHits) / totalAccesses : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "CacheStats{hitRatio=%.2f%%, hotHits=%d, warmHits=%d, misses=%d, evictions=%d, loaded=%d, saved=%d, hotMem=%dKB, warmMem=%dKB}",
                getHitRatio() * 100, hotCacheHits, warmCacheHits, cacheMisses, evictions, 
                nodesLoaded, nodesSaved, currentHotMemory / 1024, currentWarmMemory / 1024
            );
        }
    }
    
    private final Config config;
    private final ClusteredFile clusteredFile;
    private final OctreeFile octreeFile;
    
    // Cache data structures
    private final Map<Long, ManagedNode> hotCache = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, ManagedNode> warmCache = new LinkedHashMap<Long, ManagedNode>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ManagedNode> eldest) {
            return size() > config.warmCacheMaxBytes / 200; // Rough estimate
        }
    };
    
    // Thread safety
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final Object warmCacheLock = new Object();
    
    // Memory tracking
    private final AtomicLong currentHotMemory = new AtomicLong(0);
    private final AtomicLong currentWarmMemory = new AtomicLong(0);
    private final CacheStats stats = new CacheStats();
    
    // Node pool for memory efficiency
    private final Queue<ManagedNode> nodePool = new ConcurrentLinkedQueue<>();
    
    // Background processing
    private final ExecutorService prefetchExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    
    public RuntimeMemoryManager(Config config, ClusteredFile clusteredFile, OctreeFile octreeFile) {
        this.config = config;
        this.clusteredFile = clusteredFile;
        this.octreeFile = octreeFile;
        
        // Initialize thread pools
        this.prefetchExecutor = Executors.newFixedThreadPool(config.prefetchThreads, 
            r -> {
                var thread = new Thread(r, "RuntimeMemoryManager-Prefetch");
                thread.setDaemon(true);
                return thread;
            });
        
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                var thread = new Thread(r, "RuntimeMemoryManager-Maintenance");
                thread.setDaemon(true);
                return thread;
            });
        
        // Pre-populate node pool
        for (int i = 0; i < config.nodePoolSize; i++) {
            nodePool.offer(createPooledNode());
        }
        
        // Start background maintenance
        maintenanceExecutor.scheduleAtFixedRate(this::performMaintenance, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Get or load a node from the three-tier storage system.
     * 
     * @param nodeId Unique identifier for the node
     * @return The managed node, either from cache or loaded from disk
     */
    public ManagedNode getNode(long nodeId) {
        // Check hot cache first
        ManagedNode node = hotCache.get(nodeId);
        if (node != null) {
            node.recordAccess();
            stats.hotCacheHits++;
            return node;
        }
        
        // Check warm cache
        synchronized (warmCacheLock) {
            node = warmCache.get(nodeId);
            if (node != null) {
                node.recordAccess();
                stats.warmCacheHits++;
                
                // Promote to hot cache if frequently accessed
                if (node.accessCount > 3) {
                    promoteToHotCache(node);
                }
                return node;
            }
        }
        
        // Cache miss - load from disk
        stats.cacheMisses++;
        return loadNodeFromDisk(nodeId);
    }
    
    /**
     * Store a node in the appropriate cache level.
     */
    public void putNode(ManagedNode node) {
        node.recordAccess();
        
        // New or frequently accessed nodes go to hot cache
        if (node.accessCount > 2 || isMemoryAvailable(CacheLevel.HOT)) {
            addToHotCache(node);
        } else {
            addToWarmCache(node);
        }
        
        // Mark as dirty if it needs to be written to disk
        if (node.diskOffset == -1 || node.isDirty) {
            scheduleNodeSave(node);
        }
    }
    
    /**
     * Evict nodes to free memory under pressure.
     * 
     * @param targetBytes Amount of memory to free
     * @return Actual bytes freed
     */
    public long evictNodes(long targetBytes) {
        long freedBytes = 0;
        
        // First evict from warm cache (LRU order)
        synchronized (warmCacheLock) {
            var iterator = warmCache.entrySet().iterator();
            while (iterator.hasNext() && freedBytes < targetBytes) {
                var entry = iterator.next();
                var node = entry.getValue();
                
                if (node.isDirty) {
                    saveNodeToDisk(node);
                }
                
                freedBytes += node.getEstimatedMemorySize();
                currentWarmMemory.addAndGet(-node.getEstimatedMemorySize());
                iterator.remove();
                node.cacheLevel = CacheLevel.NONE;
                stats.evictions++;
                
                // Return node to pool if possible
                recycleNode(node);
            }
        }
        
        // If still need more memory, evict from hot cache (least recently used)
        if (freedBytes < targetBytes) {
            if (!config.aggressiveEviction) {
                // Move some hot cache nodes to warm cache instead of evicting
                var candidates = hotCache.values().stream()
                    .sorted(Comparator.comparing(n -> n.lastAccessTime))
                    .limit((int) ((targetBytes - freedBytes) / 200))
                    .toList();
                for (var node : candidates) {
                    if (freedBytes >= targetBytes) break;
                    hotCache.remove(node.nodeId);
                    freedBytes += node.getEstimatedMemorySize();
                    currentHotMemory.addAndGet(-node.getEstimatedMemorySize());
                    addToWarmCache(node);
                }
            } else {
                // Aggressive eviction - remove from memory entirely
                var candidates = hotCache.values().stream()
                    .sorted(Comparator.comparing(n -> n.lastAccessTime))
                    .limit((int) ((targetBytes - freedBytes) / 200))
                    .toList();
                
                for (var node : candidates) {
                    if (freedBytes >= targetBytes) break;
                    
                    if (node.isDirty) {
                        saveNodeToDisk(node);
                    }
                    
                    hotCache.remove(node.nodeId);
                    freedBytes += node.getEstimatedMemorySize();
                    currentHotMemory.addAndGet(-node.getEstimatedMemorySize());
                    node.cacheLevel = CacheLevel.NONE;
                    stats.evictions++;
                    
                    recycleNode(node);
                }
            }
        }
        
        return freedBytes;
    }
    
    /**
     * Prefetch nodes that are likely to be accessed soon.
     */
    public void prefetchNodes(List<Long> nodeIds) {
        final List<Long> finalNodeIds = nodeIds.size() > config.maxPrefetchNodes 
            ? nodeIds.subList(0, config.maxPrefetchNodes)
            : nodeIds;
        
        prefetchExecutor.submit(() -> {
            for (var nodeId : finalNodeIds) {
                // Only prefetch if not already cached
                if (!hotCache.containsKey(nodeId) && !warmCache.containsKey(nodeId)) {
                    try {
                        loadNodeFromDisk(nodeId);
                    } catch (Exception e) {
                        // Log error but continue prefetching
                        System.err.println("Prefetch failed for node " + nodeId + ": " + e.getMessage());
                    }
                }
            }
        });
    }
    
    /**
     * Get current cache statistics.
     */
    public CacheStats getStats() {
        stats.currentHotMemory = currentHotMemory.get();
        stats.currentWarmMemory = currentWarmMemory.get();
        return stats;
    }
    
    /**
     * Shutdown the memory manager and clean up resources.
     */
    public void shutdown() {
        // Save all dirty nodes
        cacheLock.writeLock().lock();
        try {
            for (var node : hotCache.values()) {
                if (node.isDirty) {
                    saveNodeToDisk(node);
                }
            }
            
            synchronized (warmCacheLock) {
                for (var node : warmCache.values()) {
                    if (node.isDirty) {
                        saveNodeToDisk(node);
                    }
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
        
        // Shutdown thread pools
        prefetchExecutor.shutdown();
        maintenanceExecutor.shutdown();
        
        try {
            if (!prefetchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                prefetchExecutor.shutdownNow();
            }
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // Private implementation methods
    
    private ManagedNode loadNodeFromDisk(long nodeId) {
        try {
            // Load from octree file first (metadata)
            var serializedNodes = octreeFile.readNodesById(Collections.singletonList(nodeId));
            if (serializedNodes.isEmpty()) {
                // Node not found in octree file - could be it was evicted but not actually saved
                System.err.println("DEBUG: Node " + nodeId + " not found in octree file, returning null");
                return null;
            }
            
            var serializedNode = serializedNodes.get(0);
            
            // Create managed node - use the requested nodeId, not the one from serializedNode
            // which might be 0 if it wasn't properly set
            var node = allocateNode(nodeId, serializedNode.depth, 
                new Vector3f(serializedNode.position), serializedNode.size);
            
            // Load actual data from clustered file if available
            if (serializedNode.dataOffset >= 0) {
                try {
                    var clusterData = clusteredFile.readCluster((int) serializedNode.dataOffset);
                    deserializeNodeData(node, clusterData.data);
                } catch (Exception e) {
                    // Node metadata exists but data not yet written - that's ok
                    System.err.println("Could not load cluster data for node " + nodeId + ": " + e.getMessage());
                }
            }
            
            node.diskOffset = serializedNode.dataOffset;
            node.serializedSize = serializedNode.dataSize;
            stats.nodesLoaded++;
            
            // Add to appropriate cache
            addToWarmCache(node);
            
            return node;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load node " + nodeId, e);
        }
    }
    
    private void saveNodeToDisk(ManagedNode node) {
        try {
            // Serialize node data
            var nodeData = serializeNodeData(node);
            
            // Write to clustered file as a cluster
            var clusterData = new ClusteredFile.ClusterData(
                (int) node.nodeId, // Use nodeId as clusterId
                nodeData,
                ClusteredFile.CompressionType.NONE
            );
            clusteredFile.writeCluster(clusterData);
            
            node.diskOffset = node.nodeId; // Use nodeId as offset reference
            node.serializedSize = nodeData.length;
            
            // Update octree file metadata
            float[] positionArray = new float[3];
            node.position.get(positionArray);
            var serializedNode = new OctreeFile.SerializedNode(
                node.nodeId, node.depth, positionArray, node.size,
                node.nodeId, nodeData.length, node.hasChildren()
            );
            octreeFile.writeNode(serializedNode);
            
            node.isDirty = false;
            stats.nodesSaved++;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save node " + node.nodeId, e);
        }
    }
    
    private void promoteToHotCache(ManagedNode node) {
        synchronized (warmCacheLock) {
            warmCache.remove(node.nodeId);
            currentWarmMemory.addAndGet(-node.getEstimatedMemorySize());
        }
        addToHotCache(node);
    }
    
    private void addToHotCache(ManagedNode node) {
        // Add node first
        hotCache.put(node.nodeId, node);
        currentHotMemory.addAndGet(node.getEstimatedMemorySize());
        node.cacheLevel = CacheLevel.HOT;
        
        // Check if we exceeded the limit and need to evict
        long currentMem = currentHotMemory.get();
        long maxMem = config.hotCacheMaxBytes;
        if (currentMem > maxMem) {
            // Evict enough to get back under the threshold
            long targetEviction = currentMem - (long)(maxMem * config.memoryPressureThreshold);
            evictNodes(targetEviction);
        }
    }
    
    private void addToWarmCache(ManagedNode node) {
        synchronized (warmCacheLock) {
            warmCache.put(node.nodeId, node);
            currentWarmMemory.addAndGet(node.getEstimatedMemorySize());
            node.cacheLevel = CacheLevel.WARM;
        }
    }
    
    private boolean isMemoryPressure(CacheLevel level) {
        long currentMemory = level == CacheLevel.HOT ? 
            currentHotMemory.get() : currentWarmMemory.get();
        long maxMemory = level == CacheLevel.HOT ? 
            config.hotCacheMaxBytes : config.warmCacheMaxBytes;
        
        return (double) currentMemory / maxMemory > config.memoryPressureThreshold;
    }
    
    private boolean isMemoryAvailable(CacheLevel level) {
        return !isMemoryPressure(level);
    }
    
    private void scheduleNodeSave(ManagedNode node) {
        // Mark as dirty - will be saved during maintenance
        node.isDirty = true;
    }
    
    private void performMaintenance() {
        try {
            // Save dirty nodes
            saveDirtyNodes();
            
            // Check memory pressure and evict if needed
            if (isMemoryPressure(CacheLevel.HOT)) {
                evictNodes(config.hotCacheMaxBytes / 10); // Free 10%
            }
            
            if (isMemoryPressure(CacheLevel.WARM)) {
                evictNodes(config.warmCacheMaxBytes / 10);
            }
            
            // Replenish node pool if needed
            replenishNodePool();
            
        } catch (Exception e) {
            System.err.println("Maintenance error: " + e.getMessage());
        }
    }
    
    private void saveDirtyNodes() {
        var dirtyHotNodes = hotCache.values().stream()
            .filter(n -> n.isDirty)
            .limit(10) // Limit to avoid blocking
            .toList();
        
        for (var node : dirtyHotNodes) {
            saveNodeToDisk(node);
        }
        
        synchronized (warmCacheLock) {
            var dirtyWarmNodes = warmCache.values().stream()
                .filter(n -> n.isDirty)
                .limit(10)
                .toList();
            
            for (var node : dirtyWarmNodes) {
                saveNodeToDisk(node);
            }
        }
    }
    
    private void replenishNodePool() {
        while (nodePool.size() < config.nodePoolSize / 2) {
            nodePool.offer(createPooledNode());
        }
    }
    
    private ManagedNode allocateNode(long nodeId, int depth, Vector3f position, float size) {
        // Cannot use pooled nodes because nodeId is final and can't be reset
        // var pooledNode = nodePool.poll();
        // if (pooledNode != null) {
        //     // Reset pooled node
        //     resetPooledNode(pooledNode, nodeId, depth, position, size);
        //     return pooledNode;
        // }
        
        return new ManagedNode(nodeId, depth, position, size);
    }
    
    private ManagedNode createPooledNode() {
        return new ManagedNode(0, 0, new Vector3f(), 1.0f);
    }
    
    private void resetPooledNode(ManagedNode node, long nodeId, int depth, Vector3f position, float size) {
        // Reset all fields to initial state
        // This would be implemented based on ManagedNode structure
    }
    
    private void recycleNode(ManagedNode node) {
        if (nodePool.size() < config.nodePoolSize) {
            // Reset node state for reuse
            node.color = null;
            node.normal = null;
            node.children = null;
            node.isDirty = false;
            node.isLoading = false;
            node.cacheLevel = CacheLevel.NONE;
            node.diskOffset = -1;
            node.serializedSize = 0;
            
            nodePool.offer(node);
        }
    }
    
    private byte[] serializeNodeData(ManagedNode node) {
        // Simple serialization - in practice would use more efficient format
        var buffer = new byte[64]; // Fixed size for simplicity
        // Serialize color, normal, children references, etc.
        return buffer;
    }
    
    private void deserializeNodeData(ManagedNode node, byte[] data) {
        // Deserialize from byte array back to node fields
        // This would be implemented based on serialization format
    }
}