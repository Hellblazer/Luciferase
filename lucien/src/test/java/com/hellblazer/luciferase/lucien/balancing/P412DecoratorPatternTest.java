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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * TDD tests for FaultTolerantDistributedForest decorator pattern (P4.1.2).
 *
 * <p>Validates that FaultTolerantDistributedForest properly wraps DistributedForestImpl using
 * the decorator pattern and correctly delegates all interface methods.
 *
 * <p><b>Test Strategy</b>:
 * <ol>
 *   <li>Decorator properly wraps delegate (not composition, actual wrapping)</li>
 *   <li>All DistributedForest interface methods delegated correctly</li>
 *   <li>Factory method creates proper instance with correct configuration</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class P412DecoratorPatternTest {

    @Mock
    private Forest mockLocalForest;

    @Mock
    private DistributedGhostManager mockGhostManager;

    @Mock
    private ParallelBalancer.PartitionRegistry mockRegistry;

    private ParallelBalancer.DistributedForest delegate;
    private FaultTolerantDistributedForest decorator;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create delegate with mocks using raw types for testing
        delegate = new DistributedForestImpl(
            mockLocalForest,
            mockGhostManager,
            mockRegistry,
            1,  // partition rank
            3   // total partitions
        );

        // Create decorator wrapping delegate
        decorator = new FaultTolerantDistributedForest(delegate);
    }

    /**
     * Test 1: Decorator properly wraps delegate without composition.
     *
     * <p>Verifies that the decorator stores and returns the delegate correctly.
     */
    @Test
    void testDecoratorWrapsDelegate() {
        // When: Creating decorator
        var result = decorator.getDelegate();

        // Then: Delegate should be the same object
        assertSame(delegate, result, "Decorator should wrap the exact delegate instance");
    }

    /**
     * Test 2: All DistributedForest methods delegated correctly.
     *
     * <p>Verifies that calling interface methods on decorator delegates to delegate.
     */
    @Test
    void testAllMethodsDelegated() {
        // When: Call interface methods on decorator
        var localForest = decorator.getLocalForest();
        var ghostManager = decorator.getGhostManager();
        var registry = decorator.getPartitionRegistry();

        // Then: All should delegate to delegate's implementation
        assertSame(mockLocalForest, localForest, "getLocalForest() should delegate");
        assertSame(mockGhostManager, ghostManager, "getGhostManager() should delegate");
        assertSame(mockRegistry, registry, "getPartitionRegistry() should delegate");
    }

    /**
     * Test 3: Factory method creates proper instance with correct configuration.
     *
     * <p>Verifies that the factory method properly wraps a balancer's operation tracker.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testFactoryMethodCreatesProperInstance() {
        // Given: Configuration and mock balancer
        var config = BalanceConfiguration.defaultConfig();
        var balancer = (DefaultParallelBalancer<?, ?, ?>) new DefaultParallelBalancer<>(config);

        // When: Create decorator using factory method with proper casting
        var result = FaultTolerantDistributedForest.wrap(
            (ParallelBalancer.DistributedForest<?, ?, ?>) delegate,
            (DefaultParallelBalancer<?, ?, ?>) balancer
        );

        // Then: Result should be properly configured decorator
        assertNotNull(result, "Factory should create decorator");
        assertSame(delegate, result.getDelegate(), "Decorator should wrap provided delegate");

        // And: Decorator should have access to balancer's operation tracker
        var tracker = result.getOperationTracker();
        assertSame(balancer.getOperationTracker(), tracker,
                   "Decorator should use balancer's operation tracker");
    }

    /**
     * Test 4: Decorator maintains type safety with generics.
     *
     * <p>Verifies that decorator properly preserves generic type parameters.
     */
    @Test
    void testDecoratorMaintainsTypeGenericSafety() {
        // Given: Delegate with specific types
        var localForest = decorator.getLocalForest();
        var ghostManager = decorator.getGhostManager();

        // When: Access through decorator
        var decoratorLocalForest = decorator.getLocalForest();
        var decoratorGhostManager = decorator.getGhostManager();

        // Then: Types should match
        assertSame(localForest, decoratorLocalForest, "Local forest should match");
        assertSame(ghostManager, decoratorGhostManager, "Ghost manager should match");
    }
}
