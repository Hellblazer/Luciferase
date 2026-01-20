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
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.dag.config.MemoryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe memory budget tracker with configurable enforcement policies.
 *
 * <p>Tracks allocated memory and enforces budget constraints based on the
 * configured {@link MemoryPolicy}:
 *
 * <ul>
 * <li>STRICT: Throws exception if allocation would exceed budget</li>
 * <li>WARN: Logs warning but allows allocation</li>
 * <li>ADAPTIVE: Allows allocation and adjusts automatically</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All operations are thread-safe using {@link AtomicLong} for tracking.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var tracker = new MemoryBudgetTracker(512 * 1024 * 1024, MemoryPolicy.STRICT);
 *
 * if (tracker.canAllocate(bytes)) {
 *     tracker.allocate(bytes);
 *     try {
 *         // ... use memory
 *     } finally {
 *         tracker.release(bytes);
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class MemoryBudgetTracker {
    private static final Logger log = LoggerFactory.getLogger(MemoryBudgetTracker.class);

    private final long budgetBytes;
    private final MemoryPolicy policy;
    private final AtomicLong allocatedBytes;

    /**
     * Create a memory budget tracker.
     *
     * @param budgetBytes total budget in bytes (must be >= 0)
     * @param policy enforcement policy (must not be null)
     * @throws IllegalArgumentException if budgetBytes < 0
     * @throws NullPointerException if policy is null
     */
    public MemoryBudgetTracker(long budgetBytes, MemoryPolicy policy) {
        if (budgetBytes < 0) {
            throw new IllegalArgumentException("budgetBytes cannot be negative: " + budgetBytes);
        }
        Objects.requireNonNull(policy, "policy cannot be null");

        this.budgetBytes = budgetBytes;
        this.policy = policy;
        this.allocatedBytes = new AtomicLong(0);
    }

    /**
     * Check if the given number of bytes can be allocated within budget.
     *
     * <p>For WARN and ADAPTIVE policies, this always returns true.
     * For STRICT policy, returns true only if allocation fits within budget.
     *
     * @param bytes bytes to allocate
     * @return true if allocation is permitted by policy
     */
    public boolean canAllocate(long bytes) {
        return switch (policy) {
            case STRICT -> allocatedBytes.get() + bytes <= budgetBytes;
            case WARN, ADAPTIVE -> true; // Always allow, handle in allocate()
        };
    }

    /**
     * Allocate the given number of bytes.
     *
     * <p>Behavior depends on policy:
     * <ul>
     * <li>STRICT: Throws if would exceed budget</li>
     * <li>WARN: Logs warning if exceeds budget but proceeds</li>
     * <li>ADAPTIVE: Allows allocation regardless of budget</li>
     * </ul>
     *
     * @param bytes bytes to allocate (must be >= 0)
     * @throws IllegalArgumentException if bytes < 0
     * @throws IllegalStateException if STRICT policy and allocation would exceed budget
     */
    public void allocate(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes cannot be negative: " + bytes);
        }

        var newAllocated = allocatedBytes.addAndGet(bytes);

        if (newAllocated > budgetBytes) {
            switch (policy) {
                case STRICT -> {
                    allocatedBytes.addAndGet(-bytes); // Rollback
                    throw new IllegalStateException(
                        "Memory budget exceeded: budget=" + budgetBytes + " bytes, " +
                        "would allocate=" + newAllocated + " bytes"
                    );
                }
                case WARN -> {
                    log.warn("Memory budget exceeded: budget={} bytes, allocated={} bytes",
                             budgetBytes, newAllocated);
                }
                case ADAPTIVE -> {
                    // Allow allocation, no warning
                }
            }
        }
    }

    /**
     * Release the given number of bytes.
     *
     * @param bytes bytes to release (must be >= 0)
     * @throws IllegalArgumentException if bytes < 0
     * @throws IllegalStateException if would release more than allocated
     */
    public void release(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes cannot be negative: " + bytes);
        }

        var newAllocated = allocatedBytes.addAndGet(-bytes);

        if (newAllocated < 0) {
            allocatedBytes.addAndGet(bytes); // Rollback
            throw new IllegalStateException(
                "Cannot release more than allocated: allocated=" + (newAllocated + bytes) +
                " bytes, attempted to release=" + bytes + " bytes"
            );
        }
    }

    /**
     * Get remaining budget in bytes.
     *
     * @return bytes remaining in budget (can be negative if budget exceeded)
     */
    public long getRemainingBytes() {
        return budgetBytes - allocatedBytes.get();
    }

    /**
     * Get current memory utilization as a percentage.
     *
     * @return utilization percentage [0.0, 100.0+] (can exceed 100% if budget exceeded)
     */
    public float getUtilizationPercent() {
        if (budgetBytes == 0) {
            return allocatedBytes.get() > 0 ? Float.POSITIVE_INFINITY : 0.0f;
        }
        return (float) allocatedBytes.get() / budgetBytes * 100.0f;
    }

    /**
     * Get the enforcement policy.
     *
     * @return memory policy
     */
    public MemoryPolicy getPolicy() {
        return policy;
    }

    /**
     * Get currently allocated bytes.
     *
     * @return allocated bytes
     */
    public long getAllocatedBytes() {
        return allocatedBytes.get();
    }
}
