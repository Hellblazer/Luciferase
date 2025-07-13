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

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages ghost elements for a distributed spatial index.
 * 
 * This class maintains ghost elements (non-local elements that neighbor local elements)
 * and remote elements (local elements that are ghosts on other processes).
 * It provides methods for creating, accessing, and synchronizing ghost data.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class GhostLayer<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    // Ghost elements indexed by spatial key
    private final ConcurrentNavigableMap<Key, List<GhostElement<Key, ID, Content>>> ghostElements;
    
    // Remote elements (our local elements that are ghosts elsewhere)
    private final Map<Integer, Set<RemoteElement<Key, ID, Content>>> remoteElements;
    
    // Maps global tree ID to local ghost tree index
    private final Map<Long, Integer> globalTreeToGhostTree;
    
    // Process offsets for ghost elements
    private final Map<Integer, ProcessOffset> processOffsets;
    
    // Ghost type configuration
    private final GhostType ghostType;
    
    // Statistics
    private final AtomicLong numGhostElements;
    private final AtomicLong numRemoteElements;
    
    // Thread safety
    private final ReadWriteLock lock;
    
    /**
     * Creates a new ghost layer.
     * 
     * @param ghostType the type of ghost neighbors to include
     */
    public GhostLayer(GhostType ghostType) {
        this.ghostType = Objects.requireNonNull(ghostType, "Ghost type cannot be null");
        this.ghostElements = new ConcurrentSkipListMap<>();
        this.remoteElements = new ConcurrentHashMap<>();
        this.globalTreeToGhostTree = new ConcurrentHashMap<>();
        this.processOffsets = new ConcurrentHashMap<>();
        this.numGhostElements = new AtomicLong(0);
        this.numRemoteElements = new AtomicLong(0);
        this.lock = new ReentrantReadWriteLock();
    }
    
    /**
     * Adds a ghost element to the layer.
     * 
     * @param element the ghost element to add
     */
    public void addGhostElement(GhostElement<Key, ID, Content> element) {
        Objects.requireNonNull(element, "Ghost element cannot be null");
        
        ghostElements.compute(element.getSpatialKey(), (key, list) -> {
            if (list == null) {
                list = new ArrayList<>();
            }
            list.add(element);
            numGhostElements.incrementAndGet();
            return list;
        });
        
        // Update process offsets
        updateProcessOffset(element.getOwnerRank());
    }
    
    /**
     * Adds a remote element (local element that is a ghost elsewhere).
     * 
     * @param remoteRank the rank of the remote process
     * @param element the remote element
     */
    public void addRemoteElement(int remoteRank, RemoteElement<Key, ID, Content> element) {
        Objects.requireNonNull(element, "Remote element cannot be null");
        
        remoteElements.compute(remoteRank, (rank, set) -> {
            if (set == null) {
                set = new HashSet<>();
            }
            set.add(element);
            numRemoteElements.incrementAndGet();
            return set;
        });
    }
    
    /**
     * Gets all ghost elements at a specific spatial key.
     * 
     * @param key the spatial key
     * @return list of ghost elements at that key, or empty list if none
     */
    public List<GhostElement<Key, ID, Content>> getGhostElements(Key key) {
        lock.readLock().lock();
        try {
            List<GhostElement<Key, ID, Content>> elements = ghostElements.get(key);
            return elements != null ? new ArrayList<>(elements) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all ghost elements within a range of spatial keys.
     * 
     * @param fromKey the starting key (inclusive)
     * @param toKey the ending key (inclusive)
     * @return list of all ghost elements in the range
     */
    public List<GhostElement<Key, ID, Content>> getGhostElementsInRange(Key fromKey, Key toKey) {
        lock.readLock().lock();
        try {
            List<GhostElement<Key, ID, Content>> result = new ArrayList<>();
            ConcurrentNavigableMap<Key, List<GhostElement<Key, ID, Content>>> subMap = 
                ghostElements.subMap(fromKey, true, toKey, true);
            
            for (List<GhostElement<Key, ID, Content>> elements : subMap.values()) {
                result.addAll(elements);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all ghost elements in the layer.
     * 
     * @return list of all ghost elements
     */
    public List<GhostElement<Key, ID, Content>> getAllGhostElements() {
        lock.readLock().lock();
        try {
            List<GhostElement<Key, ID, Content>> result = new ArrayList<>();
            for (List<GhostElement<Key, ID, Content>> elements : ghostElements.values()) {
                result.addAll(elements);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all remote elements for a specific process rank.
     * 
     * @param remoteRank the rank of the remote process
     * @return set of remote elements, or empty set if none
     */
    public Set<RemoteElement<Key, ID, Content>> getRemoteElements(int remoteRank) {
        lock.readLock().lock();
        try {
            Set<RemoteElement<Key, ID, Content>> elements = remoteElements.get(remoteRank);
            return elements != null ? new HashSet<>(elements) : Collections.emptySet();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the set of remote process ranks.
     * 
     * @return set of ranks that have ghost relationships with this process
     */
    public Set<Integer> getRemoteRanks() {
        lock.readLock().lock();
        try {
            return new HashSet<>(remoteElements.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the total number of ghost elements.
     * 
     * @return the number of ghost elements
     */
    public long getNumGhostElements() {
        return numGhostElements.get();
    }
    
    /**
     * Gets the total number of remote elements.
     * 
     * @return the number of remote elements
     */
    public long getNumRemoteElements() {
        return numRemoteElements.get();
    }
    
    /**
     * Gets the ghost type configuration.
     * 
     * @return the ghost type
     */
    public GhostType getGhostType() {
        return ghostType;
    }
    
    /**
     * Clears all ghost and remote elements.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            ghostElements.clear();
            remoteElements.clear();
            globalTreeToGhostTree.clear();
            processOffsets.clear();
            numGhostElements.set(0);
            numRemoteElements.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Updates process offset information.
     * 
     * @param rank the process rank
     */
    private void updateProcessOffset(int rank) {
        processOffsets.compute(rank, (r, offset) -> {
            if (offset == null) {
                offset = new ProcessOffset(r);
            }
            offset.incrementGhostCount();
            return offset;
        });
    }
    
    /**
     * Represents a remote element (local element that is a ghost elsewhere).
     */
    public static class RemoteElement<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        private final Key spatialKey;
        private final ID entityId;
        private final Content content;
        private final Point3f position;
        private final long localTreeId;
        
        public RemoteElement(Key spatialKey, ID entityId, Content content,
                           Point3f position, long localTreeId) {
            this.spatialKey = Objects.requireNonNull(spatialKey);
            this.entityId = Objects.requireNonNull(entityId);
            this.content = content;
            this.position = Objects.requireNonNull(position);
            this.localTreeId = localTreeId;
        }
        
        public Key getSpatialKey() { return spatialKey; }
        public ID getEntityId() { return entityId; }
        public Content getContent() { return content; }
        public Point3f getPosition() { return new Point3f(position); }
        public long getLocalTreeId() { return localTreeId; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RemoteElement)) return false;
            RemoteElement<?, ?, ?> that = (RemoteElement<?, ?, ?>) o;
            return localTreeId == that.localTreeId &&
                   spatialKey.equals(that.spatialKey) &&
                   entityId.equals(that.entityId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(spatialKey, entityId, localTreeId);
        }
    }
    
    /**
     * Tracks process-specific ghost information.
     */
    private static class ProcessOffset {
        private final int rank;
        private long ghostCount;
        private int firstTreeIndex;
        
        ProcessOffset(int rank) {
            this.rank = rank;
            this.ghostCount = 0;
            this.firstTreeIndex = -1;
        }
        
        void incrementGhostCount() {
            ghostCount++;
        }
    }
}