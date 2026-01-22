/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementRequest;
import com.hellblazer.luciferase.lucien.balancing.proto.RefinementResponse;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages refinement requests for cross-partition balancing.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Building RefinementRequest messages with boundary keys and level information</li>
 *   <li>Batching multiple requests for efficiency</li>
 *   <li>Tracking request/response round-trip times</li>
 *   <li>Collecting timing metrics for performance analysis</li>
 * </ul>
 *
 * <p>Thread-safe: Uses concurrent collections for request tracking.
 *
 * @author hal.hildebrand
 */
public class RefinementRequestManager {

    private static final Logger log = LoggerFactory.getLogger(RefinementRequestManager.class);

    // Request tracking
    private final Map<String, Long> requestTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> responseTimestamps = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalResponses = new AtomicLong(0);
    private final AtomicLong totalRoundTripTime = new AtomicLong(0);

    /**
     * Build a RefinementRequest for specific boundaries.
     *
     * <p>The request includes:
     * <ul>
     *   <li>Requester rank and tree ID</li>
     *   <li>Round number for coordination</li>
     *   <li>Tree level where refinement is needed</li>
     *   <li>Specific boundary keys to refine (optional)</li>
     *   <li>Timestamp for tracking</li>
     * </ul>
     *
     * @param requesterRank the rank of the requesting partition
     * @param roundNumber the current refinement round
     * @param boundaryKeys the spatial keys at partition boundaries
     * @param treeLevel the level in the tree requiring refinement
     * @return the refinement request
     */
    public RefinementRequest buildRequest(int requesterRank, int roundNumber,
                                         List<SpatialKey> boundaryKeys, int treeLevel) {
        // TODO: Implement request building
        // 1. Convert SpatialKey objects to protobuf SpatialKey
        // 2. Build RefinementRequest with all fields
        // 3. Track request timestamp

        totalRequests.incrementAndGet();

        log.debug("Building refinement request: rank={}, round={}, level={}, keys={}",
                 requesterRank, roundNumber, treeLevel, boundaryKeys.size());

        return RefinementRequest.newBuilder()
            .setRequesterRank(requesterRank)
            .setRequesterTreeId(0L)
            .setRoundNumber(roundNumber)
            .setTreeLevel(treeLevel)
            .setTimestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Batch multiple refinement requests for efficiency.
     *
     * <p>Groups requests by target partition and combines them into batches
     * of the specified size. This reduces network overhead by sending multiple
     * refinement requests in a single RPC call (if supported by the protocol).
     *
     * @param requests the individual refinement requests
     * @param batchSize the maximum batch size
     * @return batched requests (may be fewer than input if combined)
     */
    public List<RefinementRequest> batchRequests(List<RefinementRequest> requests, int batchSize) {
        // TODO: Implement request batching
        // 1. Group requests by target partition
        // 2. Combine boundary keys from same target
        // 3. Create batched requests

        log.debug("Batching {} requests with batch size {}", requests.size(), batchSize);

        return requests; // Stub: return unbatched for now
    }

    /**
     * Track a refinement request round-trip.
     *
     * @param request the request being sent
     * @param timestamp the send timestamp
     */
    public void trackRequest(RefinementRequest request, long timestamp) {
        // TODO: Implement request tracking
        // 1. Generate unique key for request (rank + round)
        // 2. Store timestamp in map

        var key = generateRequestKey(request);
        requestTimestamps.put(key, timestamp);

        log.trace("Tracking request: key={}, timestamp={}", key, timestamp);
    }

    /**
     * Track a refinement response and update metrics.
     *
     * @param response the response received
     */
    public void trackResponse(RefinementResponse response) {
        // TODO: Implement response tracking
        // 1. Generate matching key from response
        // 2. Calculate round-trip time
        // 3. Update statistics

        totalResponses.incrementAndGet();

        var key = generateResponseKey(response);
        var requestTime = requestTimestamps.get(key);
        if (requestTime != null) {
            var roundTripTime = System.currentTimeMillis() - requestTime;
            totalRoundTripTime.addAndGet(roundTripTime);
            responseTimestamps.put(key, System.currentTimeMillis());

            log.trace("Tracked response: key={}, round-trip={}ms", key, roundTripTime);
        }
    }

    /**
     * Get the average round-trip time for requests.
     *
     * @return average round-trip time in milliseconds, or 0 if no responses
     */
    public long getAverageRoundTripTime() {
        var responses = totalResponses.get();
        if (responses == 0) {
            return 0;
        }
        return totalRoundTripTime.get() / responses;
    }

    /**
     * Get the total number of requests tracked.
     *
     * @return the request count
     */
    public long getTotalRequests() {
        return totalRequests.get();
    }

    /**
     * Get the total number of responses tracked.
     *
     * @return the response count
     */
    public long getTotalResponses() {
        return totalResponses.get();
    }

    /**
     * Clear all tracking state (for testing or new balance cycle).
     */
    public void clear() {
        requestTimestamps.clear();
        responseTimestamps.clear();
        totalRequests.set(0);
        totalResponses.set(0);
        totalRoundTripTime.set(0);

        log.debug("Cleared request manager state");
    }

    /**
     * Generate a unique key for a refinement request.
     */
    private String generateRequestKey(RefinementRequest request) {
        return String.format("req-%d-%d", request.getRequesterRank(), request.getRoundNumber());
    }

    /**
     * Generate a matching key for a refinement response.
     */
    private String generateResponseKey(RefinementResponse response) {
        return String.format("req-%d-%d", response.getResponderRank(), response.getRoundNumber());
    }
}
