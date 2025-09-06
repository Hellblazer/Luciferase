package com.hellblazer.luciferase.esvo.builder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-local batch cache with LRU eviction.
 */
public class ThreadLocalBatchCache {
    private final int maxBatchesPerThread;
    private final Map<Long, LinkedHashMap<Integer, VoxelBatch>> threadBatches;
    
    public ThreadLocalBatchCache(int maxBatchesPerThread) {
        this.maxBatchesPerThread = maxBatchesPerThread;
        this.threadBatches = new ConcurrentHashMap<>();
    }
    
    /**
     * Add a batch to the current thread's cache.
     */
    public void addBatch(VoxelBatch batch) {
        long threadId = Thread.currentThread().getId();
        
        LinkedHashMap<Integer, VoxelBatch> batches = threadBatches.computeIfAbsent(
            threadId, 
            k -> new LinkedHashMap<Integer, VoxelBatch>(maxBatchesPerThread + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, VoxelBatch> eldest) {
                    return size() > maxBatchesPerThread;
                }
            }
        );
        
        synchronized (batches) {
            batches.put(batch.getId(), batch);
        }
    }
    
    /**
     * Get the number of batches for a specific thread.
     */
    public int getBatchCount(long threadId) {
        LinkedHashMap<Integer, VoxelBatch> batches = threadBatches.get(threadId);
        if (batches == null) return 0;
        
        synchronized (batches) {
            return batches.size();
        }
    }
    
    /**
     * Check if a thread has a specific batch.
     */
    public boolean hasBatch(long threadId, int batchId) {
        LinkedHashMap<Integer, VoxelBatch> batches = threadBatches.get(threadId);
        if (batches == null) return false;
        
        synchronized (batches) {
            return batches.containsKey(batchId);
        }
    }
    
    /**
     * Get all thread IDs with batches (for testing).
     */
    public Set<Long> getThreadIds() {
        return new HashSet<>(threadBatches.keySet());
    }
    
    /**
     * Get a batch from the current thread's cache.
     */
    public VoxelBatch getBatch(int batchId) {
        long threadId = Thread.currentThread().getId();
        LinkedHashMap<Integer, VoxelBatch> batches = threadBatches.get(threadId);
        if (batches == null) return null;
        
        synchronized (batches) {
            return batches.get(batchId);
        }
    }
    
    /**
     * Clear all caches.
     */
    public void clear() {
        threadBatches.clear();
    }
}