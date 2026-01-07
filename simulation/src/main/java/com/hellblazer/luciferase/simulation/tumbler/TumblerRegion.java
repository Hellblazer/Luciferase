/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A spatial region in the Tumbler partitioning scheme.
 * Contains a set of servers that can host bubbles in this region.
 * <p>
 * The region is defined by a grid-based ID derived from spatial position.
 * <p>
 * Note: This is SYMBOLIC locality, not ownership. A bubble in this
 * region can be hosted on ANY server in the region's server set.
 */
public class TumblerRegion {

    private final long regionId;
    private final int gridSize;
    private final Map<UUID, ServerMetrics> servers = new ConcurrentHashMap<>();

    public TumblerRegion(long regionId, int gridSize) {
        this.regionId = regionId;
        this.gridSize = gridSize;
    }

    public long regionId() {
        return regionId;
    }

    public int gridSize() {
        return gridSize;
    }

    /**
     * Returns all servers in this region.
     */
    public Set<UUID> getServers() {
        return Collections.unmodifiableSet(servers.keySet());
    }

    /**
     * Returns metrics for all servers in this region.
     */
    public Collection<ServerMetrics> getServerMetrics() {
        return Collections.unmodifiableCollection(servers.values());
    }

    /**
     * Get metrics for a specific server.
     */
    public ServerMetrics getMetrics(UUID serverId) {
        return servers.get(serverId);
    }

    /**
     * Add a server to this region.
     */
    public void addServer(UUID serverId, ServerMetrics metrics) {
        servers.put(serverId, metrics);
    }

    /**
     * Remove a server from this region.
     */
    public void removeServer(UUID serverId) {
        servers.remove(serverId);
    }

    /**
     * Power-of-2 random choices: pick 2 random servers and return
     * the one with lower utilization.
     * <p>
     * If only 1 server exists, return it.
     * If no servers exist, return empty.
     */
    public Optional<UUID> selectLeastLoadedServer() {
        if (servers.isEmpty()) {
            return Optional.empty();
        }

        var serverList = new ArrayList<>(servers.entrySet());
        if (serverList.size() == 1) {
            return Optional.of(serverList.get(0).getKey());
        }

        var random = ThreadLocalRandom.current();

        // Pick 2 random servers
        int idx1 = random.nextInt(serverList.size());
        int idx2 = random.nextInt(serverList.size());
        while (idx2 == idx1 && serverList.size() > 1) {
            idx2 = random.nextInt(serverList.size());
        }

        var entry1 = serverList.get(idx1);
        var entry2 = serverList.get(idx2);

        // Return the one with lower utilization
        if (entry1.getValue().utilization() <= entry2.getValue().utilization()) {
            return Optional.of(entry1.getKey());
        } else {
            return Optional.of(entry2.getKey());
        }
    }

    /**
     * Find the server with lowest utilization (for migration target selection).
     */
    public Optional<UUID> findLeastLoadedServer() {
        return servers.entrySet()
                      .stream()
                      .min(Comparator.comparingDouble(e -> e.getValue().utilization()))
                      .map(Map.Entry::getKey);
    }

    /**
     * Find the server with highest utilization (for migration source selection).
     */
    public Optional<UUID> findMostLoadedServer() {
        return servers.entrySet()
                      .stream()
                      .max(Comparator.comparingDouble(e -> e.getValue().utilization()))
                      .map(Map.Entry::getKey);
    }

    /**
     * Calculate load imbalance as (max - min) / average.
     * Returns 0.0 if no servers or all servers have same load.
     */
    public double calculateImbalance() {
        if (servers.size() < 2) {
            return 0.0;
        }

        var stats = servers.values()
                           .stream()
                           .mapToDouble(ServerMetrics::utilization)
                           .summaryStatistics();

        double avg = stats.getAverage();
        if (avg == 0.0) {
            return 0.0;
        }

        return (stats.getMax() - stats.getMin()) / avg;
    }

    /**
     * Returns true if any server in this region is overloaded.
     */
    public boolean hasOverloadedServer(double threshold) {
        return servers.values()
                      .stream()
                      .anyMatch(m -> m.utilization() > threshold);
    }

    /**
     * Returns true if any server in this region is underloaded.
     */
    public boolean hasUnderloadedServer(double threshold) {
        return servers.values()
                      .stream()
                      .anyMatch(m -> m.utilization() < threshold);
    }

    public int serverCount() {
        return servers.size();
    }

    @Override
    public String toString() {
        return String.format("TumblerRegion[id=%d, gridSize=%d, servers=%d, imbalance=%.1f%%]",
                             regionId, gridSize, servers.size(), calculateImbalance() * 100);
    }
}
