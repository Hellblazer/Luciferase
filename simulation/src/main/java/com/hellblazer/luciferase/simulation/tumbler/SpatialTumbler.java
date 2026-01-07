/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spatial Tumbler for load-balanced server selection.
 * <p>
 * Uses grid-based spatial partitioning to create regions.
 * Each region contains a set of servers. Server selection uses
 * the power-of-2 random choices algorithm for load balancing.
 * <p>
 * This is SYMBOLIC locality - regions indicate spatial affinity
 * but do NOT imply ownership. A bubble can be hosted on any
 * server in its region.
 * <p>
 * Per v4.0 architecture:
 * <pre>
 * TumblerRegion region = spatialTumbler.getRegion(bubble.position());
 * Set&lt;Server&gt; localServers = region.getServers();
 * Server s1 = randomChoice(localServers);
 * Server s2 = randomChoice(localServers);
 * return s1.utilization() < s2.utilization() ? s1 : s2;
 * </pre>
 */
public class SpatialTumbler {

    private static final Logger log = LoggerFactory.getLogger(SpatialTumbler.class);

    private final int regionGridSize;
    private final double targetFrameTimeMs;
    private final Map<Long, TumblerRegion> regions = new ConcurrentHashMap<>();
    private final Map<UUID, ServerMetrics> allServers = new ConcurrentHashMap<>();

    // Configuration
    private final double overloadThreshold;
    private final double underloadThreshold;

    /**
     * Create a SpatialTumbler with specified region granularity.
     *
     * @param regionLevel       Region level (determines grid cell size: 2^level)
     * @param targetFrameTimeMs Target frame time for utilization calculation
     */
    public SpatialTumbler(byte regionLevel, double targetFrameTimeMs) {
        this(regionLevel, targetFrameTimeMs, 1.5, 0.5);
    }

    /**
     * Create a SpatialTumbler with custom thresholds.
     *
     * @param regionLevel        Region level (determines grid cell size)
     * @param targetFrameTimeMs  Target frame time for utilization calculation
     * @param overloadThreshold  Utilization threshold for overload (e.g., 1.5 = 150%)
     * @param underloadThreshold Utilization threshold for underload (e.g., 0.5 = 50%)
     */
    public SpatialTumbler(byte regionLevel, double targetFrameTimeMs,
                          double overloadThreshold, double underloadThreshold) {
        this.regionGridSize = 1 << regionLevel;  // 2^level
        this.targetFrameTimeMs = targetFrameTimeMs;
        this.overloadThreshold = overloadThreshold;
        this.underloadThreshold = underloadThreshold;
        log.info("SpatialTumbler created: gridSize={}, targetFrameMs={}, overload={}, underload={}",
                 regionGridSize, targetFrameTimeMs, overloadThreshold, underloadThreshold);
    }

    /**
     * Register a server with the tumbler.
     */
    public ServerMetrics registerServer(UUID serverId) {
        var metrics = new ServerMetrics(serverId, targetFrameTimeMs);
        allServers.put(serverId, metrics);
        log.debug("Registered server: {}", serverId);
        return metrics;
    }

    /**
     * Unregister a server from the tumbler.
     */
    public void unregisterServer(UUID serverId) {
        allServers.remove(serverId);
        // Remove from all regions
        regions.values().forEach(r -> r.removeServer(serverId));
        log.debug("Unregistered server: {}", serverId);
    }

    /**
     * Assign a server to a region (server can be in multiple regions).
     */
    public void assignServerToRegion(UUID serverId, long regionId) {
        var metrics = allServers.get(serverId);
        if (metrics == null) {
            log.warn("Cannot assign unregistered server {} to region", serverId);
            return;
        }

        var region = regions.computeIfAbsent(regionId, id -> new TumblerRegion(id, regionGridSize));
        region.addServer(serverId, metrics);
        log.debug("Assigned server {} to region {}", serverId, regionId);
    }

    /**
     * Get the region for a spatial position.
     * Creates the region if it doesn't exist.
     */
    public TumblerRegion getRegion(Point3f position) {
        var regionId = computeRegionId(position);
        return regions.computeIfAbsent(regionId, id -> new TumblerRegion(id, regionGridSize));
    }

    /**
     * Get the region for bubble bounds.
     */
    public TumblerRegion getRegion(BubbleBounds bounds) {
        // Use the bubble's centroid to determine region
        var centroid = bounds.centroid();
        return getRegion(new Point3f((float) centroid.getX(), (float) centroid.getY(), (float) centroid.getZ()));
    }

    /**
     * Compute region ID from position using grid-based hashing.
     */
    private long computeRegionId(Point3f position) {
        int gx = (int) Math.floor(position.x / regionGridSize);
        int gy = (int) Math.floor(position.y / regionGridSize);
        int gz = (int) Math.floor(position.z / regionGridSize);
        // Combine into a single long using bit interleaving
        return ((long) gx & 0xFFFFF) | (((long) gy & 0xFFFFF) << 20) | (((long) gz & 0xFFFFF) << 40);
    }

    /**
     * Select a server for a new bubble using power-of-2 random choices.
     * <p>
     * Algorithm:
     * 1. Find the region for the bubble's position
     * 2. Pick 2 random servers from the region
     * 3. Return the one with lower utilization
     */
    public Optional<UUID> selectServer(Point3f position) {
        var region = getRegion(position);
        if (region.serverCount() == 0) {
            // Fallback: select from all servers
            return selectFromAllServers();
        }
        return region.selectLeastLoadedServer();
    }

    /**
     * Select a server for a bubble using power-of-2 random choices.
     */
    public Optional<UUID> selectServer(BubbleBounds bounds) {
        var region = getRegion(bounds);
        if (region.serverCount() == 0) {
            return selectFromAllServers();
        }
        return region.selectLeastLoadedServer();
    }

    /**
     * Fallback: select from all registered servers using power-of-2.
     */
    private Optional<UUID> selectFromAllServers() {
        if (allServers.isEmpty()) {
            return Optional.empty();
        }

        var serverList = new ArrayList<>(allServers.entrySet());
        if (serverList.size() == 1) {
            return Optional.of(serverList.get(0).getKey());
        }

        var random = java.util.concurrent.ThreadLocalRandom.current();
        int idx1 = random.nextInt(serverList.size());
        int idx2 = random.nextInt(serverList.size());
        while (idx2 == idx1) {
            idx2 = random.nextInt(serverList.size());
        }

        var entry1 = serverList.get(idx1);
        var entry2 = serverList.get(idx2);

        return Optional.of(
            entry1.getValue().utilization() <= entry2.getValue().utilization()
                ? entry1.getKey()
                : entry2.getKey()
        );
    }

    /**
     * Find migration candidates: bubbles that should move from overloaded
     * to underloaded servers.
     *
     * @return List of (sourceServer, targetServer) pairs for migration
     */
    public List<MigrationCandidate> findMigrationCandidates() {
        var candidates = new ArrayList<MigrationCandidate>();

        for (var region : regions.values()) {
            if (!region.hasOverloadedServer(overloadThreshold)) {
                continue;
            }
            if (!region.hasUnderloadedServer(underloadThreshold)) {
                continue;
            }

            var source = region.findMostLoadedServer();
            var target = region.findLeastLoadedServer();

            if (source.isPresent() && target.isPresent() && !source.equals(target)) {
                var sourceMetrics = region.getMetrics(source.get());
                var targetMetrics = region.getMetrics(target.get());

                if (sourceMetrics.utilization() > overloadThreshold &&
                    targetMetrics.utilization() < underloadThreshold) {
                    candidates.add(new MigrationCandidate(
                        region.regionId(),
                        source.get(),
                        target.get(),
                        sourceMetrics.utilization(),
                        targetMetrics.utilization()
                    ));
                }
            }
        }

        return candidates;
    }

    /**
     * Calculate overall load imbalance across all regions.
     */
    public double calculateOverallImbalance() {
        if (allServers.size() < 2) {
            return 0.0;
        }

        var stats = allServers.values()
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
     * Get metrics for a specific server.
     */
    public ServerMetrics getServerMetrics(UUID serverId) {
        return allServers.get(serverId);
    }

    /**
     * Get all registered servers.
     */
    public Set<UUID> getAllServers() {
        return Collections.unmodifiableSet(allServers.keySet());
    }

    /**
     * Get all regions.
     */
    public Collection<TumblerRegion> getAllRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public int regionGridSize() {
        return regionGridSize;
    }

    public double overloadThreshold() {
        return overloadThreshold;
    }

    public double underloadThreshold() {
        return underloadThreshold;
    }

    /**
     * Migration candidate record.
     */
    public record MigrationCandidate(
        long regionId,
        UUID sourceServer,
        UUID targetServer,
        double sourceUtilization,
        double targetUtilization
    ) {
        public double loadDelta() {
            return sourceUtilization - targetUtilization;
        }
    }
}
