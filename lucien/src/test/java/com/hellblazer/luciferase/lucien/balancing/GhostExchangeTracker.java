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

import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks ghost element exchanges between partitions during distributed balancing.
 *
 * <p>Records:
 * <ul>
 *   <li>Boundary elements extracted from each partition</li>
 *   <li>Ghost exchanges sent between neighbors</li>
 *   <li>Received ghosts at partition boundaries</li>
 *   <li>Per-round exchange statistics</li>
 * </ul>
 *
 * <p>Thread-safe for concurrent partition updates.
 *
 * @author hal.hildebrand
 */
public class GhostExchangeTracker {

    private static final Logger log = LoggerFactory.getLogger(GhostExchangeTracker.class);

    private final int partitionCount;
    private final Map<Integer, List<GhostElement<MortonKey, LongEntityID, String>>> extractedGhosts =
        new ConcurrentHashMap<>();
    private final Map<String, ExchangeRecord> exchanges = new ConcurrentHashMap<>();
    private final AtomicLong totalGhostsExtracted = new AtomicLong(0);
    private final AtomicLong totalGhostsExchanged = new AtomicLong(0);

    public GhostExchangeTracker(int partitionCount) {
        this.partitionCount = partitionCount;
        log.info("Created ghost exchange tracker for {} partitions", partitionCount);
    }

    /**
     * Record boundary ghosts extracted from a partition.
     *
     * @param partitionRank the partition rank
     * @param ghosts the extracted boundary ghost elements
     */
    public void recordExtractedGhosts(int partitionRank,
                                     List<GhostElement<MortonKey, LongEntityID, String>> ghosts) {
        extractedGhosts.put(partitionRank, new ArrayList<>(ghosts));
        totalGhostsExtracted.addAndGet(ghosts.size());
        log.debug("Recorded {} ghosts extracted from partition {}", ghosts.size(), partitionRank);
    }

    /**
     * Record a ghost exchange between two partitions.
     *
     * @param sourceRank the source partition
     * @param targetRank the target partition
     * @param ghostCount the number of ghosts exchanged
     * @param round the refinement round number
     */
    public void recordExchange(int sourceRank, int targetRank, int ghostCount, int round) {
        var key = String.format("%d->%d:r%d", sourceRank, targetRank, round);
        var record = new ExchangeRecord(sourceRank, targetRank, ghostCount, round);
        exchanges.put(key, record);
        totalGhostsExchanged.addAndGet(ghostCount);
        log.trace("Recorded exchange: {} ghosts from partition {} to {}", ghostCount, sourceRank, targetRank);
    }

    /**
     * Get ghosts extracted from a specific partition.
     *
     * @param partitionRank the partition rank
     * @return list of extracted ghosts (empty if none)
     */
    public List<GhostElement<MortonKey, LongEntityID, String>> getExtractedGhosts(int partitionRank) {
        return extractedGhosts.getOrDefault(partitionRank, Collections.emptyList());
    }

    /**
     * Get all recorded exchanges.
     *
     * @return unmodifiable view of all exchange records
     */
    public Collection<ExchangeRecord> getAllExchanges() {
        return Collections.unmodifiableCollection(exchanges.values());
    }

    /**
     * Get exchanges for a specific round.
     *
     * @param round the refinement round number
     * @return list of exchanges in that round
     */
    public List<ExchangeRecord> getExchangesForRound(int round) {
        return exchanges.values().stream()
            .filter(r -> r.round == round)
            .toList();
    }

    /**
     * Get total ghosts extracted across all partitions.
     */
    public long getTotalGhostsExtracted() {
        return totalGhostsExtracted.get();
    }

    /**
     * Get total ghosts exchanged across all partitions.
     */
    public long getTotalGhostsExchanged() {
        return totalGhostsExchanged.get();
    }

    /**
     * Clear all tracked data (for new balance cycle).
     */
    public void clear() {
        extractedGhosts.clear();
        exchanges.clear();
        totalGhostsExtracted.set(0);
        totalGhostsExchanged.set(0);
        log.debug("Cleared ghost exchange tracking data");
    }

    /**
     * Record of a single ghost exchange operation.
     */
    public record ExchangeRecord(
        int sourceRank,
        int targetRank,
        int ghostCount,
        int round
    ) {
    }
}
