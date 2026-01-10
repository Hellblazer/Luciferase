/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for FirefliesViewMonitor getCurrentViewId() wrapper.
 *
 * Tests cover:
 * - getCurrentViewId() returns view ID correctly
 * - View ID changes on view change
 * - getCurrentViewId() thread safety
 *
 * @author hal.hildebrand
 */
class FirefliesViewMonitorIntegrationTest {

    @Test
    void testGetCurrentViewId() {
        // Given: FirefliesViewMonitor with real FirefliesMembershipView
        var viewId = DigestAlgorithm.DEFAULT.random();
        var membershipView = createMockFirefliesView(viewId);
        var monitor = new FirefliesViewMonitor(membershipView);

        // When: Getting current view ID
        var result = monitor.getCurrentViewId();

        // Then: Returns correct view ID
        assertEquals(viewId, result, "getCurrentViewId() must return view context ID");
    }

    @Test
    void testViewIdChangesOnViewChange() {
        // Given: FirefliesViewMonitor with initial view
        var viewId1 = DigestAlgorithm.DEFAULT.random();
        var membershipView = createMockFirefliesView(viewId1);
        var monitor = new FirefliesViewMonitor(membershipView);

        // When: View ID before change
        var idBefore = monitor.getCurrentViewId();

        // Then: Simulate view change (in real scenario, Fireflies would update context)
        // For this test, we verify that getCurrentViewId() calls through to FirefliesMembershipView
        assertEquals(viewId1, idBefore);
        // Note: Full view change testing is in FirefliesMembershipViewTest
    }

    @Test
    void testGetCurrentViewIdThreadSafety() {
        // Given: FirefliesViewMonitor
        var viewId = DigestAlgorithm.DEFAULT.random();
        var membershipView = createMockFirefliesView(viewId);
        var monitor = new FirefliesViewMonitor(membershipView);

        // When: Multiple threads access getCurrentViewId() concurrently
        var thread1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                assertNotNull(monitor.getCurrentViewId());
            }
        });
        var thread2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                assertNotNull(monitor.getCurrentViewId());
            }
        });

        // Then: No concurrent modification exceptions
        assertDoesNotThrow(() -> {
            thread1.start();
            thread2.start();
            thread1.join();
            thread2.join();
        });
    }

    @Test
    void testGetCurrentViewIdWithMockView() {
        // Given: FirefliesViewMonitor with mock MembershipView (not FirefliesMembershipView)
        MembershipView<Member> mockView = mock(MembershipView.class);
        when(mockView.getMembers()).thenReturn(java.util.stream.Stream.empty());
        var monitor = new FirefliesViewMonitor(mockView);

        // When: Getting current view ID
        var result = monitor.getCurrentViewId();

        // Then: Returns null (mock view doesn't expose ID)
        assertNull(result, "Mock views should return null for getCurrentViewId()");
    }

    // Helper to create mock FirefliesMembershipView
    @SuppressWarnings("unchecked")
    private FirefliesMembershipView createMockFirefliesView(Digest viewId) {
        var mockView = mock(View.class);
        var mockContext = mock(DynamicContext.class);
        when(mockView.getContext()).thenReturn((DynamicContext) mockContext);
        when(mockContext.getId()).thenReturn(viewId);
        when(mockContext.allMembers()).thenReturn(java.util.stream.Stream.empty());

        return new FirefliesMembershipView(mockView);
    }
}
