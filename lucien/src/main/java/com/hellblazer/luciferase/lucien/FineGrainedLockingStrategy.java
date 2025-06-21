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

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fine-grained locking strategy for spatial indices to maximize concurrency.
 * Implements node-level locking with lock-free read operations where possible.
 * 
 * Key features:
 * - Node-level read/write locks for maximum parallelism
 * - Optimistic read locks using StampedLock for traversal operations
 * - Lock ordering to prevent deadlocks during multi-node operations
 * - Lock-free snapshot reads for query operations
 * - Configurable lock timeout and retry strategies
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @param <NodeType> The type of spatial node used by the implementation
 * 
 * @author hal.hildebrand
 */
public class FineGrainedLockingStrategy<ID extends EntityID, Content, NodeType extends SpatialNodeStorage<ID>> {
    
    /**
     * Locking configuration and policies
     */
    public static class LockingConfig {
        private long lockTimeoutMs = 5000;
        private int maxRetries = 3;
        private boolean useOptimisticReads = true;
        private boolean enableDeadlockDetection = true;
        private LockingMode mode = LockingMode.ADAPTIVE;
        
        public enum LockingMode {
            CONSERVATIVE,   // Always use write locks, maximum safety
            ADAPTIVE,       // Use read locks when possible, write when necessary
            OPTIMISTIC     // Use optimistic reads, fallback to locks only when needed
        }
        
        public LockingConfig withLockTimeout(long timeoutMs) {
            this.lockTimeoutMs = timeoutMs;
            return this;
        }
        
        public LockingConfig withMaxRetries(int retries) {
            this.maxRetries = Math.max(1, retries);
            return this;
        }
        
        public LockingConfig withOptimisticReads(boolean enable) {
            this.useOptimisticReads = enable;
            return this;
        }
        
        public LockingConfig withDeadlockDetection(boolean enable) {
            this.enableDeadlockDetection = enable;
            return this;
        }
        
        public LockingConfig withMode(LockingMode mode) {
            this.mode = mode;
            return this;
        }
        
        // Getters
        public long getLockTimeoutMs() { return lockTimeoutMs; }
        public int getMaxRetries() { return maxRetries; }
        public boolean isUseOptimisticReads() { return useOptimisticReads; }
        public boolean isEnableDeadlockDetection() { return enableDeadlockDetection; }
        public LockingMode getMode() { return mode; }
    }
    
    /**
     * Node-specific lock wrapper
     */
    private static class NodeLock {
        private final StampedLock stampedLock = new StampedLock();
        private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        private volatile long lastAccessTime = System.currentTimeMillis();
        private volatile int accessCount = 0;
        
        // Lock acquisition methods
        
        public long tryOptimisticRead() {
            updateAccess();
            return stampedLock.tryOptimisticRead();
        }
        
        public boolean validate(long stamp) {
            return stampedLock.validate(stamp);
        }
        
        public long readLock() {
            updateAccess();
            return stampedLock.readLock();
        }
        
        public long tryReadLock(long timeout, TimeUnit unit) throws InterruptedException {
            updateAccess();
            return stampedLock.tryReadLock(timeout, unit);
        }
        
        public void unlockRead(long stamp) {
            stampedLock.unlockRead(stamp);
        }
        
        public long writeLock() {
            updateAccess();
            return stampedLock.writeLock();
        }
        
        public long tryWriteLock(long timeout, TimeUnit unit) throws InterruptedException {
            updateAccess();
            return stampedLock.tryWriteLock(timeout, unit);
        }
        
        public void unlockWrite(long stamp) {
            stampedLock.unlockWrite(stamp);
        }
        
        // Traditional read/write locks for compatibility
        
        public void readLockTraditional() {
            updateAccess();
            readWriteLock.readLock().lock();
        }
        
        public boolean tryReadLockTraditional(long timeout, TimeUnit unit) throws InterruptedException {
            updateAccess();
            return readWriteLock.readLock().tryLock(timeout, unit);
        }
        
        public void unlockReadTraditional() {
            readWriteLock.readLock().unlock();
        }
        
        public void writeLockTraditional() {
            updateAccess();
            readWriteLock.writeLock().lock();
        }
        
        public boolean tryWriteLockTraditional(long timeout, TimeUnit unit) throws InterruptedException {
            updateAccess();
            return readWriteLock.writeLock().tryLock(timeout, unit);
        }
        
        public void unlockWriteTraditional() {
            readWriteLock.writeLock().unlock();
        }
        
        private void updateAccess() {
            lastAccessTime = System.currentTimeMillis();
            accessCount++;
        }
        
        // Statistics
        public long getLastAccessTime() { return lastAccessTime; }
        public int getAccessCount() { return accessCount; }
    }
    
    /**
     * Lock acquisition result
     */
    public static class LockResult {
        private final boolean success;
        private final long stamp;
        private final boolean isOptimistic;
        private final Exception error;
        
        private LockResult(boolean success, long stamp, boolean isOptimistic, Exception error) {
            this.success = success;
            this.stamp = stamp;
            this.isOptimistic = isOptimistic;
            this.error = error;
        }
        
        public static LockResult success(long stamp, boolean isOptimistic) {
            return new LockResult(true, stamp, isOptimistic, null);
        }
        
        public static LockResult failure(Exception error) {
            return new LockResult(false, 0, false, error);
        }
        
        public boolean isSuccess() { return success; }
        public long getStamp() { return stamp; }
        public boolean isOptimistic() { return isOptimistic; }
        public Exception getError() { return error; }
    }
    
    // Lock storage: index -> NodeLock
    private final ConcurrentHashMap<Long, NodeLock> nodeLocks = new ConcurrentHashMap<>();
    private final LockingConfig config;
    private final AbstractSpatialIndex<ID, Content, NodeType> spatialIndex;
    
    // Lock ordering for deadlock prevention
    private final ThreadLocal<java.util.Set<Long>> heldLocks = ThreadLocal.withInitial(java.util.HashSet::new);
    
    public FineGrainedLockingStrategy(AbstractSpatialIndex<ID, Content, NodeType> spatialIndex, 
                                     LockingConfig config) {
        this.spatialIndex = spatialIndex;
        this.config = config;
    }
    
    /**
     * Execute a read operation with appropriate locking
     */
    public <T> T executeRead(long nodeIndex, Supplier<T> operation) {
        return executeRead(nodeIndex, operation, config.getMaxRetries());
    }
    
    private <T> T executeRead(long nodeIndex, Supplier<T> operation, int retriesLeft) {
        NodeLock nodeLock = getOrCreateNodeLock(nodeIndex);
        
        if (config.isUseOptimisticReads() && config.getMode() != LockingConfig.LockingMode.CONSERVATIVE) {
            // Try optimistic read first
            long stamp = nodeLock.tryOptimisticRead();
            if (stamp != 0) {
                T result = operation.get();
                if (nodeLock.validate(stamp)) {
                    return result;
                }
                // Optimistic read failed, fall back to read lock
            }
        }
        
        // Use read lock
        long stamp = 0;
        try {
            stamp = nodeLock.tryReadLock(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);
            if (stamp == 0) {
                throw new RuntimeException("Failed to acquire read lock for node " + nodeIndex);
            }
            
            trackLockAcquisition(nodeIndex);
            return operation.get();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring read lock", e);
        } catch (Exception e) {
            if (retriesLeft > 0) {
                return executeRead(nodeIndex, operation, retriesLeft - 1);
            }
            throw new RuntimeException("Read operation failed after retries", e);
        } finally {
            if (stamp != 0) {
                nodeLock.unlockRead(stamp);
                releaseLockTracking(nodeIndex);
            }
        }
    }
    
    /**
     * Execute a write operation with write locking
     */
    public <T> T executeWrite(long nodeIndex, Supplier<T> operation) {
        return executeWrite(nodeIndex, operation, config.getMaxRetries());
    }
    
    private <T> T executeWrite(long nodeIndex, Supplier<T> operation, int retriesLeft) {
        NodeLock nodeLock = getOrCreateNodeLock(nodeIndex);
        long stamp = 0;
        
        try {
            stamp = nodeLock.tryWriteLock(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);
            if (stamp == 0) {
                throw new RuntimeException("Failed to acquire write lock for node " + nodeIndex);
            }
            
            trackLockAcquisition(nodeIndex);
            return operation.get();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring write lock", e);
        } catch (Exception e) {
            if (retriesLeft > 0) {
                return executeWrite(nodeIndex, operation, retriesLeft - 1);
            }
            throw new RuntimeException("Write operation failed after retries", e);
        } finally {
            if (stamp != 0) {
                nodeLock.unlockWrite(stamp);
                releaseLockTracking(nodeIndex);
            }
        }
    }
    
    /**
     * Execute operation on multiple nodes with ordered locking to prevent deadlocks
     */
    public <T> T executeMultiNode(java.util.Set<Long> nodeIndices, Supplier<T> operation) {
        if (nodeIndices.isEmpty()) {
            return operation.get();
        }
        
        if (nodeIndices.size() == 1) {
            return executeWrite(nodeIndices.iterator().next(), operation);
        }
        
        // Sort indices to ensure consistent lock ordering
        java.util.List<Long> sortedIndices = new java.util.ArrayList<>(nodeIndices);
        sortedIndices.sort(Long::compareTo);
        
        // Check for potential deadlock
        if (config.isEnableDeadlockDetection()) {
            checkForDeadlock(sortedIndices);
        }
        
        return executeOrderedLocking(sortedIndices, operation);
    }
    
    /**
     * Acquire read lock and return lock result for manual management
     */
    public LockResult acquireReadLock(long nodeIndex) {
        NodeLock nodeLock = getOrCreateNodeLock(nodeIndex);
        
        if (config.isUseOptimisticReads() && config.getMode() == LockingConfig.LockingMode.OPTIMISTIC) {
            long stamp = nodeLock.tryOptimisticRead();
            if (stamp != 0) {
                trackLockAcquisition(nodeIndex);
                return LockResult.success(stamp, true);
            }
        }
        
        try {
            long stamp = nodeLock.tryReadLock(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);
            if (stamp == 0) {
                return LockResult.failure(new RuntimeException("Failed to acquire read lock"));
            }
            
            trackLockAcquisition(nodeIndex);
            return LockResult.success(stamp, false);
            
        } catch (Exception e) {
            return LockResult.failure(e);
        }
    }
    
    /**
     * Acquire write lock and return lock result for manual management
     */
    public LockResult acquireWriteLock(long nodeIndex) {
        NodeLock nodeLock = getOrCreateNodeLock(nodeIndex);
        
        try {
            long stamp = nodeLock.tryWriteLock(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);
            if (stamp == 0) {
                return LockResult.failure(new RuntimeException("Failed to acquire write lock"));
            }
            
            trackLockAcquisition(nodeIndex);
            return LockResult.success(stamp, false);
            
        } catch (Exception e) {
            return LockResult.failure(e);
        }
    }
    
    /**
     * Release a manually acquired lock
     */
    public void releaseLock(long nodeIndex, long stamp, boolean isOptimistic, boolean isWrite) {
        NodeLock nodeLock = nodeLocks.get(nodeIndex);
        if (nodeLock == null) {
            return;
        }
        
        try {
            if (isOptimistic) {
                // Optimistic locks are automatically released, just validate
                nodeLock.validate(stamp);
            } else if (isWrite) {
                nodeLock.unlockWrite(stamp);
            } else {
                nodeLock.unlockRead(stamp);
            }
        } finally {
            releaseLockTracking(nodeIndex);
        }
    }
    
    /**
     * Validate an optimistic read lock
     */
    public boolean validateOptimisticLock(long nodeIndex, long stamp) {
        NodeLock nodeLock = nodeLocks.get(nodeIndex);
        return nodeLock != null && nodeLock.validate(stamp);
    }
    
    // Private helper methods
    
    private NodeLock getOrCreateNodeLock(long nodeIndex) {
        return nodeLocks.computeIfAbsent(nodeIndex, k -> new NodeLock());
    }
    
    private <T> T executeOrderedLocking(java.util.List<Long> sortedIndices, Supplier<T> operation) {
        java.util.List<LockResult> acquiredLocks = new java.util.ArrayList<>();
        
        try {
            // Acquire locks in order
            for (Long index : sortedIndices) {
                LockResult result = acquireWriteLock(index);
                if (!result.isSuccess()) {
                    throw new RuntimeException("Failed to acquire lock for node " + index, result.getError());
                }
                acquiredLocks.add(result);
            }
            
            // Execute operation with all locks held
            return operation.get();
            
        } finally {
            // Release locks in reverse order
            for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
                LockResult result = acquiredLocks.get(i);
                Long index = sortedIndices.get(i);
                releaseLock(index, result.getStamp(), result.isOptimistic(), true);
            }
        }
    }
    
    private void trackLockAcquisition(long nodeIndex) {
        if (config.isEnableDeadlockDetection()) {
            heldLocks.get().add(nodeIndex);
        }
    }
    
    private void releaseLockTracking(long nodeIndex) {
        if (config.isEnableDeadlockDetection()) {
            heldLocks.get().remove(nodeIndex);
        }
    }
    
    private void checkForDeadlock(java.util.List<Long> requestedIndices) {
        java.util.Set<Long> currentlyHeld = heldLocks.get();
        
        for (Long requested : requestedIndices) {
            if (currentlyHeld.contains(requested)) {
                continue; // Already held by this thread
            }
            
            // Check if any held lock has higher index than requested
            for (Long held : currentlyHeld) {
                if (held > requested) {
                    throw new RuntimeException("Potential deadlock detected: holding " + held + 
                                             " while requesting " + requested);
                }
            }
        }
    }
    
    /**
     * Clean up unused node locks to prevent memory leaks
     */
    public void cleanupUnusedLocks(long maxAgeMs) {
        long cutoffTime = System.currentTimeMillis() - maxAgeMs;
        
        nodeLocks.entrySet().removeIf(entry -> {
            NodeLock lock = entry.getValue();
            return lock.getLastAccessTime() < cutoffTime && lock.getAccessCount() == 0;
        });
    }
    
    /**
     * Get locking statistics
     */
    public java.util.Map<String, Object> getLockingStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalNodeLocks", nodeLocks.size());
        stats.put("currentlyHeldLocks", heldLocks.get().size());
        stats.put("lockingMode", config.getMode().toString());
        stats.put("optimisticReadsEnabled", config.isUseOptimisticReads());
        stats.put("deadlockDetectionEnabled", config.isEnableDeadlockDetection());
        
        // Aggregate access statistics
        int totalAccess = nodeLocks.values().stream()
            .mapToInt(NodeLock::getAccessCount)
            .sum();
        stats.put("totalLockAccesses", totalAccess);
        
        return stats;
    }
    
    /**
     * Create default locking configuration
     */
    public static LockingConfig defaultConfig() {
        return new LockingConfig();
    }
    
    /**
     * Create high-concurrency locking configuration
     */
    public static LockingConfig highConcurrencyConfig() {
        return new LockingConfig()
            .withMode(LockingConfig.LockingMode.OPTIMISTIC)
            .withOptimisticReads(true)
            .withLockTimeout(1000)
            .withMaxRetries(5);
    }
    
    /**
     * Create conservative locking configuration for maximum safety
     */
    public static LockingConfig conservativeConfig() {
        return new LockingConfig()
            .withMode(LockingConfig.LockingMode.CONSERVATIVE)
            .withOptimisticReads(false)
            .withLockTimeout(10000)
            .withMaxRetries(1);
    }
}