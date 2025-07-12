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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages connectivity relationships between trees in a forest structure.
 * This class maintains an adjacency graph of tree connections and provides
 * methods to analyze and query connectivity relationships.
 *
 * <p>The connectivity manager tracks different types of connections:
 * <ul>
 *   <li>FACE: Trees share a complete face boundary</li>
 *   <li>EDGE: Trees share an edge boundary</li>
 *   <li>VERTEX: Trees share only a vertex</li>
 *   <li>OVERLAP: Trees have overlapping regions</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe for concurrent access.
 * Read-write locks protect the connectivity graph while allowing
 * concurrent read operations.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class TreeConnectivityManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(TreeConnectivityManager.class);
    
    /**
     * Types of connectivity between trees.
     */
    public enum ConnectivityType {
        /** Trees share a complete face boundary */
        FACE,
        /** Trees share an edge boundary */
        EDGE,
        /** Trees share only a vertex */
        VERTEX,
        /** Trees have overlapping regions */
        OVERLAP,
        /** Trees are spatially disjoint but related */
        DISJOINT
    }
    
    /**
     * Represents a connection between two trees.
     */
    public static class TreeConnection {
        private final String tree1Id;
        private final String tree2Id;
        private final ConnectivityType type;
        private final EntityBounds sharedBoundary;
        private final double distance;
        private final Map<String, Object> metadata;
        
        public TreeConnection(String tree1Id, String tree2Id, ConnectivityType type,
                            EntityBounds sharedBoundary, double distance) {
            this.tree1Id = tree1Id;
            this.tree2Id = tree2Id;
            this.type = type;
            this.sharedBoundary = sharedBoundary;
            this.distance = distance;
            this.metadata = new HashMap<>();
        }
        
        public String getTree1Id() { return tree1Id; }
        public String getTree2Id() { return tree2Id; }
        public ConnectivityType getType() { return type; }
        public EntityBounds getSharedBoundary() { return sharedBoundary; }
        public double getDistance() { return distance; }
        public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
        
        public void setMetadata(String key, Object value) {
            metadata.put(key, value);
        }
        
        public boolean involves(String treeId) {
            return tree1Id.equals(treeId) || tree2Id.equals(treeId);
        }
        
        public String getOtherId(String treeId) {
            if (tree1Id.equals(treeId)) return tree2Id;
            if (tree2Id.equals(treeId)) return tree1Id;
            return null;
        }
        
        @Override
        public String toString() {
            return String.format("TreeConnection[%s <-%s-> %s, distance=%.2f]",
                               tree1Id, type, tree2Id, distance);
        }
    }
    
    /** Adjacency list representation of the connectivity graph */
    private final Map<String, Set<TreeConnection>> adjacencyList;
    
    /** Quick lookup of connections by tree pair */
    private final Map<String, TreeConnection> connectionMap;
    
    /** Read-write lock for thread safety */
    private final ReadWriteLock lock;
    
    /** Connection metadata */
    private final Map<String, Object> managerMetadata;
    
    /**
     * Create a new TreeConnectivityManager.
     */
    public TreeConnectivityManager() {
        this.adjacencyList = new ConcurrentHashMap<>();
        this.connectionMap = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.managerMetadata = new ConcurrentHashMap<>();
        
        log.debug("Created TreeConnectivityManager");
    }
    
    /**
     * Add a connection between two trees.
     *
     * @param tree1Id the first tree ID
     * @param tree2Id the second tree ID
     * @param type the connectivity type
     * @param sharedBoundary the shared boundary (optional)
     * @return true if the connection was added, false if it already exists
     */
    public boolean addConnection(String tree1Id, String tree2Id, ConnectivityType type,
                               EntityBounds sharedBoundary) {
        lock.writeLock().lock();
        try {
            // Ensure consistent ordering
            var key = createConnectionKey(tree1Id, tree2Id);
            if (connectionMap.containsKey(key)) {
                return false;
            }
            
            // Calculate distance between tree centers if bounds provided
            var distance = sharedBoundary != null ? 0.0 : Double.MAX_VALUE;
            
            var connection = new TreeConnection(tree1Id, tree2Id, type, sharedBoundary, distance);
            connectionMap.put(key, connection);
            
            // Update adjacency lists
            adjacencyList.computeIfAbsent(tree1Id, k -> ConcurrentHashMap.newKeySet()).add(connection);
            adjacencyList.computeIfAbsent(tree2Id, k -> ConcurrentHashMap.newKeySet()).add(connection);
            
            log.debug("Added connection: {}", connection);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove a connection between two trees.
     *
     * @param tree1Id the first tree ID
     * @param tree2Id the second tree ID
     * @return true if the connection was removed
     */
    public boolean removeConnection(String tree1Id, String tree2Id) {
        lock.writeLock().lock();
        try {
            var key = createConnectionKey(tree1Id, tree2Id);
            var connection = connectionMap.remove(key);
            
            if (connection != null) {
                // Remove from adjacency lists
                var tree1Connections = adjacencyList.get(tree1Id);
                if (tree1Connections != null) {
                    tree1Connections.remove(connection);
                    if (tree1Connections.isEmpty()) {
                        adjacencyList.remove(tree1Id);
                    }
                }
                
                var tree2Connections = adjacencyList.get(tree2Id);
                if (tree2Connections != null) {
                    tree2Connections.remove(connection);
                    if (tree2Connections.isEmpty()) {
                        adjacencyList.remove(tree2Id);
                    }
                }
                
                log.debug("Removed connection between {} and {}", tree1Id, tree2Id);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove all connections for a tree.
     *
     * @param treeId the tree ID
     * @return the number of connections removed
     */
    public int removeAllConnections(String treeId) {
        lock.writeLock().lock();
        try {
            var connections = adjacencyList.remove(treeId);
            if (connections == null) {
                return 0;
            }
            
            var count = connections.size();
            for (var connection : connections) {
                var otherId = connection.getOtherId(treeId);
                var key = createConnectionKey(treeId, otherId);
                connectionMap.remove(key);
                
                // Remove from other tree's adjacency list
                var otherConnections = adjacencyList.get(otherId);
                if (otherConnections != null) {
                    otherConnections.remove(connection);
                    if (otherConnections.isEmpty()) {
                        adjacencyList.remove(otherId);
                    }
                }
            }
            
            log.debug("Removed {} connections for tree {}", count, treeId);
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get all connections for a tree.
     *
     * @param treeId the tree ID
     * @return list of connections, empty if none
     */
    public List<TreeConnection> getConnections(String treeId) {
        lock.readLock().lock();
        try {
            var connections = adjacencyList.get(treeId);
            if (connections == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(connections);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get connections of a specific type for a tree.
     *
     * @param treeId the tree ID
     * @param type the connectivity type
     * @return list of connections of the specified type
     */
    public List<TreeConnection> getConnectionsByType(String treeId, ConnectivityType type) {
        lock.readLock().lock();
        try {
            var connections = adjacencyList.get(treeId);
            if (connections == null) {
                return Collections.emptyList();
            }
            return connections.stream()
                .filter(c -> c.getType() == type)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get a specific connection between two trees.
     *
     * @param tree1Id the first tree ID
     * @param tree2Id the second tree ID
     * @return the connection, or null if not found
     */
    public TreeConnection getConnection(String tree1Id, String tree2Id) {
        lock.readLock().lock();
        try {
            var key = createConnectionKey(tree1Id, tree2Id);
            return connectionMap.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if two trees are neighbors (have any connection).
     *
     * @param tree1Id the first tree ID
     * @param tree2Id the second tree ID
     * @return true if the trees are connected
     */
    public boolean areNeighbors(String tree1Id, String tree2Id) {
        lock.readLock().lock();
        try {
            var key = createConnectionKey(tree1Id, tree2Id);
            return connectionMap.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Find all trees within a certain distance of a given tree.
     *
     * @param treeId the tree ID
     * @param maxDistance the maximum distance
     * @return list of tree IDs within the distance
     */
    public List<String> findTreesWithinDistance(String treeId, double maxDistance) {
        lock.readLock().lock();
        try {
            var connections = adjacencyList.get(treeId);
            if (connections == null) {
                return Collections.emptyList();
            }
            
            return connections.stream()
                .filter(c -> c.getDistance() <= maxDistance)
                .map(c -> c.getOtherId(treeId))
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Find shared boundaries between two trees.
     *
     * @param tree1 the first tree node
     * @param tree2 the second tree node
     * @return the shared boundary, or null if trees don't touch
     */
    public EntityBounds findSharedBoundary(TreeNode<Key, ID, Content> tree1, 
                                         TreeNode<Key, ID, Content> tree2) {
        var bounds1 = tree1.getGlobalBounds();
        var bounds2 = tree2.getGlobalBounds();
        
        // Check if bounds overlap
        if (!boundsOverlap(bounds1, bounds2)) {
            return null;
        }
        
        // Calculate intersection
        var min = new Point3f(
            Math.max(bounds1.getMinX(), bounds2.getMinX()),
            Math.max(bounds1.getMinY(), bounds2.getMinY()),
            Math.max(bounds1.getMinZ(), bounds2.getMinZ())
        );
        
        var max = new Point3f(
            Math.min(bounds1.getMaxX(), bounds2.getMaxX()),
            Math.min(bounds1.getMaxY(), bounds2.getMaxY()),
            Math.min(bounds1.getMaxZ(), bounds2.getMaxZ())
        );
        
        // Validate intersection
        if (min.x <= max.x && min.y <= max.y && min.z <= max.z) {
            return new EntityBounds(min, max);
        }
        
        return null;
    }
    
    /**
     * Determine the connectivity type between two trees based on their bounds.
     *
     * @param tree1 the first tree node
     * @param tree2 the second tree node
     * @return the connectivity type
     */
    public ConnectivityType determineConnectivityType(TreeNode<Key, ID, Content> tree1,
                                                    TreeNode<Key, ID, Content> tree2) {
        var sharedBoundary = findSharedBoundary(tree1, tree2);
        if (sharedBoundary == null) {
            return ConnectivityType.DISJOINT;
        }
        
        var min = sharedBoundary.getMin();
        var max = sharedBoundary.getMax();
        
        // Count the number of dimensions where bounds match
        var matchingDimensions = 0;
        var epsilon = 1e-6f;
        
        if (Math.abs(max.x - min.x) < epsilon) matchingDimensions++;
        if (Math.abs(max.y - min.y) < epsilon) matchingDimensions++;
        if (Math.abs(max.z - min.z) < epsilon) matchingDimensions++;
        
        // Determine type based on matching dimensions
        switch (matchingDimensions) {
            case 0:
                return ConnectivityType.OVERLAP;
            case 1:
                return ConnectivityType.FACE;
            case 2:
                return ConnectivityType.EDGE;
            case 3:
                return ConnectivityType.VERTEX;
            default:
                return ConnectivityType.DISJOINT;
        }
    }
    
    /**
     * Find all connected components in the connectivity graph.
     *
     * @return list of connected components (each component is a set of tree IDs)
     */
    public List<Set<String>> findConnectedComponents() {
        lock.readLock().lock();
        try {
            var visited = new HashSet<String>();
            var components = new ArrayList<Set<String>>();
            
            for (var treeId : adjacencyList.keySet()) {
                if (!visited.contains(treeId)) {
                    var component = new HashSet<String>();
                    dfs(treeId, visited, component);
                    components.add(component);
                }
            }
            
            return components;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Find the shortest path between two trees.
     *
     * @param startId the starting tree ID
     * @param endId the ending tree ID
     * @return list of tree IDs forming the path, or empty if no path exists
     */
    public List<String> findShortestPath(String startId, String endId) {
        lock.readLock().lock();
        try {
            if (!adjacencyList.containsKey(startId) || !adjacencyList.containsKey(endId)) {
                return Collections.emptyList();
            }
            
            // BFS to find shortest path
            var queue = new LinkedList<String>();
            var visited = new HashSet<String>();
            var parent = new HashMap<String, String>();
            
            queue.offer(startId);
            visited.add(startId);
            
            while (!queue.isEmpty()) {
                var current = queue.poll();
                
                if (current.equals(endId)) {
                    // Reconstruct path
                    var path = new ArrayList<String>();
                    var node = endId;
                    while (node != null) {
                        path.add(0, node);
                        node = parent.get(node);
                    }
                    return path;
                }
                
                var connections = adjacencyList.get(current);
                if (connections != null) {
                    for (var connection : connections) {
                        var neighbor = connection.getOtherId(current);
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            parent.put(neighbor, current);
                            queue.offer(neighbor);
                        }
                    }
                }
            }
            
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get statistics about the connectivity graph.
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getStatistics() {
        lock.readLock().lock();
        try {
            var stats = new HashMap<String, Object>();
            stats.put("totalTrees", adjacencyList.size());
            stats.put("totalConnections", connectionMap.size());
            
            // Count connections by type
            var typeCounts = new HashMap<ConnectivityType, Integer>();
            for (var connection : connectionMap.values()) {
                typeCounts.merge(connection.getType(), 1, Integer::sum);
            }
            stats.put("connectionsByType", typeCounts);
            
            // Calculate average degree
            var totalDegree = adjacencyList.values().stream()
                .mapToInt(Set::size)
                .sum();
            var avgDegree = adjacencyList.isEmpty() ? 0.0 : 
                (double) totalDegree / adjacencyList.size();
            stats.put("averageDegree", avgDegree);
            
            // Find connected components
            var components = findConnectedComponents();
            stats.put("connectedComponents", components.size());
            stats.put("largestComponentSize", components.stream()
                .mapToInt(Set::size)
                .max()
                .orElse(0));
            
            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clear all connections.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            adjacencyList.clear();
            connectionMap.clear();
            managerMetadata.clear();
            log.debug("Cleared all connections");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Helper methods
    
    private String createConnectionKey(String tree1Id, String tree2Id) {
        // Ensure consistent ordering for bidirectional connections
        if (tree1Id.compareTo(tree2Id) < 0) {
            return tree1Id + ":" + tree2Id;
        } else {
            return tree2Id + ":" + tree1Id;
        }
    }
    
    private boolean boundsOverlap(EntityBounds bounds1, EntityBounds bounds2) {
        return bounds1.getMinX() <= bounds2.getMaxX() && bounds1.getMaxX() >= bounds2.getMinX() &&
               bounds1.getMinY() <= bounds2.getMaxY() && bounds1.getMaxY() >= bounds2.getMinY() &&
               bounds1.getMinZ() <= bounds2.getMaxZ() && bounds1.getMaxZ() >= bounds2.getMinZ();
    }
    
    private void dfs(String treeId, Set<String> visited, Set<String> component) {
        visited.add(treeId);
        component.add(treeId);
        
        var connections = adjacencyList.get(treeId);
        if (connections != null) {
            for (var connection : connections) {
                var neighbor = connection.getOtherId(treeId);
                if (!visited.contains(neighbor)) {
                    dfs(neighbor, visited, component);
                }
            }
        }
    }
    
    @Override
    public String toString() {
        return String.format("TreeConnectivityManager[trees=%d, connections=%d]",
                           adjacencyList.size(), connectionMap.size());
    }
}