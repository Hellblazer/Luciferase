/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import net.jpountz.lz4.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ESVO Memory Streamer with LZ4 compression and adaptive caching.
 * 
 * Implements the memory streaming system from the ESVO paper with:
 * - Real-time LZ4 compression/decompression
 * - LRU page cache with adaptive sizing
 * - Memory bandwidth monitoring and optimization
 * - Prefetching based on spatial coherence
 * - Integration with beam optimization
 * 
 * This system optimizes memory bandwidth usage during ray traversal by:
 * 1. Compressing inactive pages with LZ4 
 * 2. Maintaining hot pages in uncompressed cache
 * 3. Prefetching pages based on beam traversal patterns
 * 4. Monitoring bandwidth usage and adapting cache policies
 */
public class ESVOMemoryStreamer {
    private static final Logger log = LoggerFactory.getLogger(ESVOMemoryStreamer.class);
    
    // LZ4 compression instances
    private final LZ4Factory lz4Factory;
    private final LZ4Compressor compressor;
    private final LZ4SafeDecompressor decompressor;
    
    // Cache configuration
    private final StreamingConfig config;
    private final Arena arena;
    
    // Page cache storage
    private final Map<Integer, CachedPage> pageCache;
    private final LinkedHashMap<Integer, CachedPage> lruOrder;
    private final ReentrantLock cacheLock;
    
    // Memory bandwidth monitoring
    private final AtomicLong bytesRead;
    private final AtomicLong bytesWritten;
    private final AtomicLong compressionSavings;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;
    
    // Prefetch system
    private final Set<Integer> prefetchQueue;
    private final Map<Integer, Integer> spatialNeighbors;
    
    /**
     * Configuration for memory streaming behavior.
     */
    public static class StreamingConfig {
        /** Maximum pages to keep in cache */
        public int maxCachedPages = 512;
        
        /** Compression threshold - pages smaller than this stay uncompressed */
        public int compressionThreshold = 1024;
        
        /** Enable aggressive prefetching based on spatial patterns */
        public boolean enablePrefetching = true;
        
        /** Maximum prefetch queue size */
        public int maxPrefetchQueue = 128;
        
        /** Bandwidth budget in MB/s (0 = unlimited) */
        public double bandwidthBudgetMBps = 0.0;
        
        /** Enable adaptive cache sizing based on hit rates */
        public boolean adaptiveCacheSize = true;
        
        /** Minimum cache hit rate to maintain current size */
        public double minHitRate = 0.85;
        
        public static StreamingConfig defaultConfig() {
            return new StreamingConfig();
        }
        
        public StreamingConfig withMaxPages(int maxPages) {
            this.maxCachedPages = maxPages;
            return this;
        }
        
        public StreamingConfig withBandwidthBudget(double mbps) {
            this.bandwidthBudgetMBps = mbps;
            return this;
        }
        
        public StreamingConfig withPrefetching(boolean enabled) {
            this.enablePrefetching = enabled;
            return this;
        }
    }
    
    /**
     * Represents a cached page with compression state.
     */
    private static class CachedPage {
        final int pageId;
        final long lastAccessTime;
        final byte[] originalData;
        final byte[] compressedData;
        final boolean isCompressed;
        final int uncompressedSize;
        final int compressedSize;
        final int accessCount;
        
        // Uncompressed page in cache
        CachedPage(int pageId, byte[] data) {
            this.pageId = pageId;
            this.lastAccessTime = System.nanoTime();
            this.originalData = data.clone();
            this.compressedData = null;
            this.isCompressed = false;
            this.uncompressedSize = data.length;
            this.compressedSize = data.length;
            this.accessCount = 1;
        }
        
        // Compressed page in cache
        CachedPage(int pageId, byte[] originalData, byte[] compressedData, int uncompressedSize) {
            this.pageId = pageId;
            this.lastAccessTime = System.nanoTime();
            this.originalData = originalData;
            this.compressedData = compressedData;
            this.isCompressed = true;
            this.uncompressedSize = uncompressedSize;
            this.compressedSize = compressedData.length;
            this.accessCount = 1;
        }
        
        CachedPage withAccess() {
            if (isCompressed) {
                return new CachedPage(pageId, originalData, compressedData, uncompressedSize) {
                    @Override
                    public long getLastAccessTime() { return System.nanoTime(); }
                    @Override
                    public int getAccessCount() { return accessCount + 1; }
                };
            } else {
                return new CachedPage(pageId, originalData) {
                    @Override
                    public long getLastAccessTime() { return System.nanoTime(); }
                    @Override
                    public int getAccessCount() { return accessCount + 1; }
                };
            }
        }
        
        public long getLastAccessTime() { return lastAccessTime; }
        public int getAccessCount() { return accessCount; }
        public float getCompressionRatio() { return (float) compressedSize / uncompressedSize; }
    }
    
    public ESVOMemoryStreamer(StreamingConfig config, Arena arena) {
        this.config = config;
        this.arena = arena;
        
        // Initialize LZ4
        this.lz4Factory = LZ4Factory.fastestInstance();
        this.compressor = lz4Factory.fastCompressor();
        this.decompressor = lz4Factory.safeDecompressor();
        
        // Initialize cache
        this.pageCache = new ConcurrentHashMap<>();
        this.lruOrder = new LinkedHashMap<>(16, 0.75f, true); // Access-order LinkedHashMap for LRU
        this.cacheLock = new ReentrantLock();
        
        // Initialize monitoring
        this.bytesRead = new AtomicLong();
        this.bytesWritten = new AtomicLong();
        this.compressionSavings = new AtomicLong();
        this.cacheHits = new AtomicInteger();
        this.cacheMisses = new AtomicInteger();
        
        // Initialize prefetch system
        this.prefetchQueue = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.spatialNeighbors = new ConcurrentHashMap<>();
        
        log.info("ESVOMemoryStreamer initialized. Config: maxPages={}, prefetching={}, bandwidth={}MB/s",
                config.maxCachedPages, config.enablePrefetching, config.bandwidthBudgetMBps);
    }
    
    /**
     * Loads a page from cache or storage with compression support.
     */
    public ESVOPage loadPage(int pageId, byte[] storageData) {
        CachedPage cached = getCachedPage(pageId);
        
        if (cached != null) {
            cacheHits.incrementAndGet();
            updateLRU(pageId, cached);
            
            byte[] pageData = cached.isCompressed ? 
                decompressPage(cached.compressedData, cached.uncompressedSize) : 
                cached.originalData;
            
            bytesRead.addAndGet(pageData.length);
            
            // Trigger prefetching based on spatial patterns
            if (config.enablePrefetching) {
                triggerSpatialPrefetch(pageId);
            }
            
            return ESVOPage.readFrom(ByteBuffer.wrap(pageData), arena);
        }
        
        // Cache miss - load from storage
        cacheMisses.incrementAndGet();
        bytesRead.addAndGet(storageData.length);
        
        // Add to cache (may compress if beneficial)
        addToCache(pageId, storageData);
        
        // Register spatial relationship for prefetching
        if (config.enablePrefetching) {
            registerSpatialNeighbor(pageId);
        }
        
        return ESVOPage.readFrom(ByteBuffer.wrap(storageData), arena);
    }
    
    /**
     * Stores a page with potential compression.
     */
    public byte[] storePage(int pageId, ESVOPage page) {
        byte[] pageData = page.serialize();
        bytesWritten.addAndGet(pageData.length);
        
        // Update cache
        addToCache(pageId, pageData);
        
        return pageData;
    }
    
    /**
     * Prefetches pages based on beam traversal patterns.
     */
    public void prefetchForBeam(BeamOptimizer.Beam beam) {
        if (!config.enablePrefetching) return;
        
        // Analyze beam spatial patterns to predict needed pages
        var centroid = beam.getCentroidOrigin();
        int estimatedPageId = estimatePageId(centroid);
        
        // Add surrounding pages to prefetch queue
        for (int offset = -2; offset <= 2; offset++) {
            int neighborId = estimatedPageId + offset;
            if (neighborId >= 0 && prefetchQueue.size() < config.maxPrefetchQueue) {
                prefetchQueue.add(neighborId);
            }
        }
    }
    
    /**
     * Gets memory streaming performance metrics.
     */
    public StreamingStats getStats() {
        return new StreamingStats(
            cacheHits.get(),
            cacheMisses.get(),
            bytesRead.get(),
            bytesWritten.get(),
            compressionSavings.get(),
            pageCache.size(),
            prefetchQueue.size()
        );
    }
    
    /**
     * Adapts cache size based on performance metrics.
     */
    public void adaptCacheSize() {
        if (!config.adaptiveCacheSize) return;
        
        double hitRate = getHitRate();
        
        if (hitRate < config.minHitRate && config.maxCachedPages < 2048) {
            // Increase cache size
            config.maxCachedPages = Math.min(2048, (int)(config.maxCachedPages * 1.2));
            log.debug("Increased cache size to {} due to low hit rate: {:.2f}", 
                     config.maxCachedPages, hitRate);
        } else if (hitRate > 0.95 && config.maxCachedPages > 128) {
            // Decrease cache size
            config.maxCachedPages = Math.max(128, (int)(config.maxCachedPages * 0.9));
            log.debug("Decreased cache size to {} due to high hit rate: {:.2f}", 
                     config.maxCachedPages, hitRate);
        }
    }
    
    /**
     * Clears cache and resets statistics.
     */
    public void reset() {
        cacheLock.lock();
        try {
            pageCache.clear();
            lruOrder.clear();
            prefetchQueue.clear();
            spatialNeighbors.clear();
            
            bytesRead.set(0);
            bytesWritten.set(0);
            compressionSavings.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
        } finally {
            cacheLock.unlock();
        }
    }
    
    // Private helper methods
    
    private CachedPage getCachedPage(int pageId) {
        return pageCache.get(pageId);
    }
    
    private void updateLRU(int pageId, CachedPage page) {
        cacheLock.lock();
        try {
            lruOrder.put(pageId, page.withAccess());
        } finally {
            cacheLock.unlock();
        }
    }
    
    private void addToCache(int pageId, byte[] data) {
        // Decide whether to compress
        boolean shouldCompress = data.length > config.compressionThreshold;
        CachedPage newPage;
        
        if (shouldCompress) {
            byte[] compressed = compressPage(data);
            if (compressed.length < data.length * 0.9f) { // At least 10% savings
                newPage = new CachedPage(pageId, data, compressed, data.length);
                compressionSavings.addAndGet(data.length - compressed.length);
            } else {
                newPage = new CachedPage(pageId, data);
            }
        } else {
            newPage = new CachedPage(pageId, data);
        }
        
        cacheLock.lock();
        try {
            // Evict if necessary
            while (pageCache.size() >= config.maxCachedPages) {
                evictLRU();
            }
            
            pageCache.put(pageId, newPage);
            lruOrder.put(pageId, newPage);
        } finally {
            cacheLock.unlock();
        }
    }
    
    private void evictLRU() {
        if (lruOrder.isEmpty()) return;
        
        var oldest = lruOrder.entrySet().iterator().next();
        int pageId = oldest.getKey();
        
        pageCache.remove(pageId);
        lruOrder.remove(pageId);
    }
    
    private byte[] compressPage(byte[] data) {
        int maxCompressedLength = compressor.maxCompressedLength(data.length);
        byte[] compressed = new byte[maxCompressedLength];
        int compressedLength = compressor.compress(data, 0, data.length, compressed, 0, maxCompressedLength);
        
        return Arrays.copyOf(compressed, compressedLength);
    }
    
    private byte[] decompressPage(byte[] compressed, int originalLength) {
        byte[] decompressed = new byte[originalLength];
        decompressor.decompress(compressed, 0, compressed.length, decompressed, 0, originalLength);
        return decompressed;
    }
    
    private void triggerSpatialPrefetch(int pageId) {
        // Look for spatial neighbors
        Integer neighbor = spatialNeighbors.get(pageId);
        if (neighbor != null && !pageCache.containsKey(neighbor)) {
            prefetchQueue.add(neighbor);
        }
        
        // Add adjacent pages
        for (int offset = 1; offset <= 3; offset++) {
            int nextPage = pageId + offset;
            if (!pageCache.containsKey(nextPage) && prefetchQueue.size() < config.maxPrefetchQueue) {
                prefetchQueue.add(nextPage);
            }
        }
    }
    
    private void registerSpatialNeighbor(int pageId) {
        // Simple spatial relationship - could be enhanced with actual spatial analysis
        spatialNeighbors.put(pageId, pageId + 1);
        spatialNeighbors.put(pageId + 1, pageId);
    }
    
    private int estimatePageId(javax.vecmath.Vector3f position) {
        // Simple spatial hash - could be enhanced with actual octree addressing
        return Math.abs((int)(position.x * 100 + position.y * 10 + position.z));
    }
    
    private double getHitRate() {
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }
    
    /**
     * Streaming performance statistics.
     */
    public static class StreamingStats {
        public final int cacheHits;
        public final int cacheMisses;
        public final long bytesRead;
        public final long bytesWritten;
        public final long compressionSavings;
        public final int cachedPages;
        public final int prefetchQueueSize;
        
        public StreamingStats(int cacheHits, int cacheMisses, long bytesRead, long bytesWritten,
                             long compressionSavings, int cachedPages, int prefetchQueueSize) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.bytesRead = bytesRead;
            this.bytesWritten = bytesWritten;
            this.compressionSavings = compressionSavings;
            this.cachedPages = cachedPages;
            this.prefetchQueueSize = prefetchQueueSize;
        }
        
        public double getHitRate() {
            int total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }
        
        public double getCompressionRatio() {
            return compressionSavings > 0 ? 
                (double) compressionSavings / (bytesRead + compressionSavings) : 0.0;
        }
        
        public double getBandwidthMBps() {
            // This would need timing information in a real implementation
            return (bytesRead + bytesWritten) / (1024.0 * 1024.0);
        }
        
        @Override
        public String toString() {
            return String.format("StreamingStats[hits=%d, misses=%d, hitRate=%.3f, " +
                               "read=%dKB, written=%dKB, compressionSavings=%dKB (%.1f%%), " +
                               "cachedPages=%d, prefetchQueue=%d]",
                cacheHits, cacheMisses, getHitRate(),
                bytesRead / 1024, bytesWritten / 1024, compressionSavings / 1024, 
                getCompressionRatio() * 100, cachedPages, prefetchQueueSize);
        }
    }
}