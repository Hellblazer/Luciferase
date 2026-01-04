package com.hellblazer.luciferase.simulation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Stock neighbor list for partition recovery.
 * <p>
 * Implements VON "stock neighbors" pattern:
 * - Keep historical list of previously discovered neighbors
 * - Use for partition recovery when current discovery fails
 * - Prioritize recent contacts (more likely still reachable)
 * - Limit list size to prevent unbounded growth
 * <p>
 * Partition recovery workflow:
 * <pre>
 * // Normal operation - build stock list
 * stockList.addStockNeighbor(neighborId, currentBucket);
 *
 * // Partition detected (NC < 0.5)
 * if (health.isPartitionRisk(0.5f)) {
 *     // Contact top 3 stock neighbors for reintroduction
 *     var recoveryContacts = stockList.getTopNStockNeighbors(3);
 *     for (var contact : recoveryContacts) {
 *         contactNeighbor(contact.neighborId());
 *     }
 * }
 * </pre>
 * <p>
 * Recency-based eviction:
 * - When list reaches max capacity, oldest neighbors evicted
 * - Recent contacts more likely to be reachable
 * - Updates timestamp on duplicate add
 * <p>
 * Thread-safe: All operations use concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class StockNeighborList {

    /**
     * Stock neighbor entry with last seen timestamp.
     *
     * @param neighborId Neighbor UUID
     * @param lastSeen   Bucket when last seen/contacted
     */
    public record StockEntry(UUID neighborId, long lastSeen) {
    }

    private final int maxCapacity;
    private final Map<UUID, Long> stockNeighbors;  // neighborId -> lastSeen

    /**
     * Create stock neighbor list with maximum capacity.
     *
     * @param maxCapacity Maximum number of stock neighbors to retain
     */
    public StockNeighborList(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Max capacity must be positive");
        }
        this.maxCapacity = maxCapacity;
        this.stockNeighbors = new ConcurrentHashMap<>();
    }

    /**
     * Add or update stock neighbor.
     * <p>
     * If neighbor already exists, updates last seen timestamp.
     * If list is full, evicts oldest neighbor.
     *
     * @param neighborId Neighbor UUID
     * @param bucket     Bucket when seen
     */
    public void addStockNeighbor(UUID neighborId, long bucket) {
        stockNeighbors.put(neighborId, bucket);

        // Enforce capacity limit (synchronized only for eviction)
        if (stockNeighbors.size() > maxCapacity) {
            synchronized (this) {
                // Double-check after acquiring lock
                while (stockNeighbors.size() > maxCapacity) {
                    evictOldest();
                }
            }
        }
    }

    /**
     * Add multiple stock neighbors with same timestamp.
     *
     * @param neighbors Collection of neighbor UUIDs
     * @param bucket    Bucket when seen
     */
    public void addStockNeighbors(Collection<UUID> neighbors, long bucket) {
        for (var neighbor : neighbors) {
            addStockNeighbor(neighbor, bucket);
        }
    }

    /**
     * Remove stock neighbor.
     *
     * @param neighborId Neighbor to remove
     */
    public void removeStockNeighbor(UUID neighborId) {
        stockNeighbors.remove(neighborId);
    }

    /**
     * Remove stock neighbors older than threshold.
     *
     * @param bucket Remove neighbors last seen before this bucket
     * @return Number of neighbors removed
     */
    public int removeOlderThan(long bucket) {
        var toRemove = stockNeighbors.entrySet().stream()
            .filter(entry -> entry.getValue() < bucket)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        toRemove.forEach(stockNeighbors::remove);
        return toRemove.size();
    }

    /**
     * Check if neighbor is in stock list.
     *
     * @param neighborId Neighbor to check
     * @return true if in stock list
     */
    public boolean contains(UUID neighborId) {
        return stockNeighbors.containsKey(neighborId);
    }

    /**
     * Get all stock neighbors sorted by recency (most recent first).
     *
     * @return Unmodifiable list of stock entries
     */
    public List<StockEntry> getStockNeighbors() {
        return stockNeighbors.entrySet().stream()
            .map(entry -> new StockEntry(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingLong(StockEntry::lastSeen).reversed())
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Get top N most recent stock neighbors.
     * <p>
     * Use for partition recovery: contact most recent neighbors first.
     *
     * @param n Number of neighbors to return
     * @return List of top N stock entries (most recent first)
     */
    public List<StockEntry> getTopNStockNeighbors(int n) {
        return stockNeighbors.entrySet().stream()
            .map(entry -> new StockEntry(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingLong(StockEntry::lastSeen).reversed())
            .limit(n)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Get random stock neighbor.
     * <p>
     * Use for diversified partition recovery.
     *
     * @return Optional containing random neighbor ID, or empty if no neighbors
     */
    public Optional<UUID> getRandomStockNeighbor() {
        if (stockNeighbors.isEmpty()) {
            return Optional.empty();
        }

        var neighborList = new ArrayList<>(stockNeighbors.keySet());
        int randomIndex = ThreadLocalRandom.current().nextInt(neighborList.size());
        return Optional.of(neighborList.get(randomIndex));
    }

    /**
     * Get all neighbor IDs (unordered).
     *
     * @return Set of neighbor UUIDs
     */
    public Set<UUID> getNeighborIds() {
        return new HashSet<>(stockNeighbors.keySet());
    }

    /**
     * Get stock neighbor count.
     *
     * @return Number of stock neighbors
     */
    public int getStockNeighborCount() {
        return stockNeighbors.size();
    }

    /**
     * Clear all stock neighbors.
     */
    public void clear() {
        stockNeighbors.clear();
    }

    /**
     * Evict oldest neighbor when capacity exceeded.
     */
    private void evictOldest() {
        // Find oldest neighbor (minimum lastSeen)
        var oldest = stockNeighbors.entrySet().stream()
            .min(Comparator.comparingLong(Map.Entry::getValue));

        oldest.ifPresent(entry -> stockNeighbors.remove(entry.getKey()));
    }

    @Override
    public String toString() {
        return String.format("StockNeighborList{count=%d, capacity=%d}",
                            stockNeighbors.size(), maxCapacity);
    }
}
