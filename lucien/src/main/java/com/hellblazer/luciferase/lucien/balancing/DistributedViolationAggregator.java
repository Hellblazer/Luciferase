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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Distributed violation aggregator using a {@link ViolationExchange} port for cross-partition exchange.
 *
 * <p>Wraps {@link ButterflyViolationAggregator} with network communication via the domain exchange port.
 * Provides timeout handling, retry logic, and metrics for distributed operation.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Timeout: 5 seconds per exchange
 *   <li>Retries: 1 retry on transient failures
 *   <li>Failure handling: Graceful degradation - continues with partial results
 * </ul>
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @author hal.hildebrand
 */
public class DistributedViolationAggregator<Key extends SpatialKey<Key>> {

    private static final Logger log = LoggerFactory.getLogger(DistributedViolationAggregator.class);
    private static final long DEFAULT_TIMEOUT_MILLIS = 5000;
    private static final int MAX_RETRIES = 1;

    private final ButterflyViolationAggregator<Key> aggregator;
    private final ViolationExchange<Key> exchange;
    private final int myRank;
    private final long timeoutMillis;

    // Metrics
    private final AtomicLong successfulExchanges;
    private final AtomicLong failedExchanges;
    private final AtomicLong retries;
    private final AtomicLong timeouts;
    private final Map<Integer, Long> exchangeLatencies;

    /**
     * Creates a new distributed violation aggregator with default timeout.
     *
     * @param myRank this partition's rank
     * @param totalPartitions total number of partitions
     * @param exchange the violation-exchange port for network communication
     */
    public DistributedViolationAggregator(int myRank, int totalPartitions, ViolationExchange<Key> exchange) {
        this(myRank, totalPartitions, exchange, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a new distributed violation aggregator with custom timeout.
     *
     * @param myRank this partition's rank
     * @param totalPartitions total number of partitions
     * @param exchange the violation-exchange port for network communication
     * @param timeoutMillis timeout for each exchange in milliseconds
     */
    public DistributedViolationAggregator(int myRank, int totalPartitions,
                                         ViolationExchange<Key> exchange, long timeoutMillis) {
        Objects.requireNonNull(exchange, "exchange cannot be null");

        this.myRank = myRank;
        this.exchange = exchange;
        this.timeoutMillis = timeoutMillis;
        this.successfulExchanges = new AtomicLong(0);
        this.failedExchanges = new AtomicLong(0);
        this.retries = new AtomicLong(0);
        this.timeouts = new AtomicLong(0);
        this.exchangeLatencies = new ConcurrentHashMap<>();

        // Create exchanger function backed by the exchange port
        var exchanger = this.createExchanger();

        // Create butterfly aggregator with the exchanger
        this.aggregator = new ButterflyViolationAggregator<>(myRank, totalPartitions, exchanger);

        log.debug("DistributedViolationAggregator initialized for rank {} of {} partitions (timeout: {}ms)",
                 myRank, totalPartitions, timeoutMillis);
    }

    /**
     * Aggregates violations across all partitions using distributed butterfly pattern.
     *
     * @param localViolations this partition's local violations
     * @return set of all violations from all partitions (deduplicated)
     * @throws NullPointerException if localViolations is null
     */
    public Set<TwoOneBalanceChecker.BalanceViolation<Key>> aggregateDistributed(
            List<TwoOneBalanceChecker.BalanceViolation<Key>> localViolations) {
        Objects.requireNonNull(localViolations, "localViolations cannot be null");

        log.debug("Starting distributed violation aggregation with {} local violations", localViolations.size());

        var startTime = System.currentTimeMillis();
        var result = aggregator.aggregateViolations(localViolations);
        var duration = System.currentTimeMillis() - startTime;

        log.info("Distributed aggregation complete: {} total violations in {}ms (success: {}, failures: {}, retries: {})",
                result.size(), duration, successfulExchanges.get(), failedExchanges.get(), retries.get());

        return result;
    }

    /**
     * Creates an exchanger function with timeout and retry handling, backed by the exchange port.
     */
    private BiFunction<Integer, ViolationBatch<Key>, ViolationBatch<Key>> createExchanger() {
        return (partner, batch) -> {
            var startTime = System.currentTimeMillis();

            // Try exchange with retry on transient failures
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (attempt > 0) {
                        retries.incrementAndGet();
                        log.debug("Retry attempt {} for exchange with partner {}", attempt, partner);
                    }

                    var responseBatch = exchange.exchangeViolations(batch);

                    // A null response signals a skipped exchange (no connection); fall through to the
                    // empty-batch fallback without counting it as a success (graceful degradation).
                    if (responseBatch != null) {
                        successfulExchanges.incrementAndGet();
                        var latency = System.currentTimeMillis() - startTime;
                        exchangeLatencies.put(partner, latency);

                        log.debug("Successfully exchanged {} violations with partner {} ({}ms, {} received)",
                                 batch.violations().size(), partner, latency,
                                 responseBatch.violations().size());

                        // Return partner's violations for merging
                        return responseBatch;
                    }

                } catch (BalanceExchangeException e) {
                    if (e.isTimeout()) {
                        timeouts.incrementAndGet();
                        log.warn("Exchange with partner {} timed out after {}ms",
                                partner, System.currentTimeMillis() - startTime);
                        failedExchanges.incrementAndGet();
                        break; // Don't retry timeouts
                    }

                    if (!e.isTransient() || attempt == MAX_RETRIES) {
                        log.error("Exchange with partner {} failed: {} (attempt {}/{})",
                                 partner, e.getMessage(), attempt + 1, MAX_RETRIES + 1);
                        failedExchanges.incrementAndGet();
                        break;
                    }

                    log.debug("Transient failure on exchange with partner {}: {}, will retry",
                             partner, e.getMessage());

                } catch (Exception e) {
                    log.error("Unexpected error exchanging with partner {}: {}",
                             partner, e.getMessage(), e);
                    failedExchanges.incrementAndGet();
                    break;
                }
            }

            // Return empty batch on failure - aggregation continues with partial results
            return new ViolationBatch<>(partner, myRank, batch.roundNumber(), List.of(), System.currentTimeMillis());
        };
    }

    /**
     * Gets aggregation metrics for monitoring.
     *
     * @return map of metrics
     */
    public Map<String, Object> getMetrics() {
        return Map.of(
            "rank", myRank,
            "successfulExchanges", successfulExchanges.get(),
            "failedExchanges", failedExchanges.get(),
            "retries", retries.get(),
            "timeouts", timeouts.get(),
            "avgLatencyMs", exchangeLatencies.isEmpty() ? 0.0 :
                exchangeLatencies.values().stream().mapToLong(Long::longValue).average().orElse(0.0)
        );
    }

    /**
     * Resets metrics counters.
     */
    public void resetMetrics() {
        successfulExchanges.set(0);
        failedExchanges.set(0);
        retries.set(0);
        timeouts.set(0);
        exchangeLatencies.clear();
    }

    /**
     * Shuts down the aggregator and releases resources.
     */
    public void shutdown() {
        log.info("Shutting down DistributedViolationAggregator for rank {} " +
                "(exchanges: {} successful, {} failed, {} retries, {} timeouts)",
                myRank, successfulExchanges.get(), failedExchanges.get(),
                retries.get(), timeouts.get());
    }
}
