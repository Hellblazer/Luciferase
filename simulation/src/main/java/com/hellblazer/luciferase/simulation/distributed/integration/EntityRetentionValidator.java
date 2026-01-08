/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates entity retention across bubbles in a distributed simulation.
 * <p>
 * Periodically checks that the total entity count matches the expected value
 * and that no duplicates or orphans exist.
 * <p>
 * Phase 6B5.4: Cross-Process Migration Validation
 *
 * @author hal.hildebrand
 */
public class EntityRetentionValidator {

    private static final Logger log = LoggerFactory.getLogger(EntityRetentionValidator.class);

    private final EntityAccountant accountant;
    private final int expectedEntityCount;
    private final AtomicInteger violationCount = new AtomicInteger(0);
    private final AtomicLong checkCount = new AtomicLong(0);
    private ScheduledExecutorService scheduler;

    /**
     * Creates a retention validator.
     *
     * @param accountant          the entity accountant
     * @param expectedEntityCount expected total entity count
     */
    public EntityRetentionValidator(EntityAccountant accountant, int expectedEntityCount) {
        this.accountant = accountant;
        this.expectedEntityCount = expectedEntityCount;
    }

    /**
     * Starts periodic validation.
     *
     * @param intervalMs validation interval in milliseconds
     */
    public void startPeriodicValidation(long intervalMs) {
        if (scheduler != null && !scheduler.isShutdown()) {
            throw new IllegalStateException("Validator already running");
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "retention-validator");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::validateRetention, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops periodic validation.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Validates entity retention once.
     *
     * @return validation result
     */
    public RetentionValidationResult validateOnce() {
        checkCount.incrementAndGet();

        // Count total entities
        var distribution = accountant.getDistribution();
        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();

        // Check count matches expected
        if (totalEntities != expectedEntityCount) {
            violationCount.incrementAndGet();
            log.error("Entity retention violation: expected {}, found {}", expectedEntityCount, totalEntities);
            return new RetentionValidationResult(false, expectedEntityCount, totalEntities,
                "Entity count mismatch");
        }

        // Run full validation
        var validation = accountant.validate();
        if (!validation.success()) {
            violationCount.incrementAndGet();
            log.error("Entity validation failed: {}", validation.details());
            return new RetentionValidationResult(false, expectedEntityCount, totalEntities,
                String.join(", ", validation.details()));
        }

        return new RetentionValidationResult(true, expectedEntityCount, totalEntities, null);
    }

    /**
     * Gets the number of violations detected.
     *
     * @return violation count
     */
    public int getViolationCount() {
        return violationCount.get();
    }

    /**
     * Gets the number of checks performed.
     *
     * @return check count
     */
    public long getCheckCount() {
        return checkCount.get();
    }

    private void validateRetention() {
        try {
            validateOnce();
        } catch (Exception e) {
            log.error("Error during retention validation: {}", e.getMessage(), e);
            violationCount.incrementAndGet();
        }
    }
}

/**
 * Result of a retention validation check.
 */
record RetentionValidationResult(boolean valid, int expectedCount, int actualCount, String error) {
}
