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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test for FirefliesMembershipView - real Delos Fireflies integration.
 * <p>
 * These tests verify that the adapter properly wraps Fireflies View and provides:
 * 1. Access to current cluster members via getMembers()
 * 2. Notifications when members join/leave via listeners
 * 3. Support for multiple concurrent listeners
 *
 * @author hal.hildebrand
 */
class FirefliesMembershipViewTest {

    private View                       mockView;
    private DynamicContext<Member>     mockContext;
    private FirefliesMembershipView    adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockView = mock(View.class);
        mockContext = mock(DynamicContext.class);
        when(mockView.getContext()).thenReturn((DynamicContext) mockContext);
    }

    /**
     * Test 1: Verify getMembers() returns stream of current cluster members
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetMembers() {
        // Given: A mock Fireflies view with 3 members
        var member1 = mock(Member.class);
        var member2 = mock(Member.class);
        var member3 = mock(Member.class);

        when(mockContext.allMembers()).thenReturn(Stream.of(member1, member2, member3));

        adapter = new FirefliesMembershipView(mockView);

        // When: We query getMembers()
        var memberStream = adapter.getMembers();

        // Then: Should see all members from the Fireflies context
        var memberList = memberStream.collect(Collectors.toList());
        assertThat(memberList)
            .as("Should have all cluster members from Fireflies context")
            .hasSize(3)
            .containsExactly(member1, member2, member3);

        verify(mockContext).allMembers();
    }

    /**
     * Test 2: Verify listener receives notifications when members join/leave
     */
    @Test
    @SuppressWarnings("unchecked")
    void testViewChangeNotification() throws Exception {
        // Given: An adapter with a registered listener
        adapter = new FirefliesMembershipView(mockView);

        var changes = new ArrayList<MembershipView.ViewChange<Member>>();
        var latch = new CountDownLatch(1);

        adapter.addListener(change -> {
            changes.add(change);
            latch.countDown();
        });

        // When: Fireflies View triggers a view change event
        // Simulate the registration callback that Fireflies will invoke
        verify(mockView).register(anyString(), any());

        // Get the registered listener and trigger it
        var listenerCaptor = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(mockView).register(anyString(), listenerCaptor.capture());

        var joinedMember = mock(Member.class);
        var leftMember = mock(Member.class);
        var joinedDigest = mock(Digest.class);
        var leftDigest = mock(Digest.class);

        when(mockContext.getMember(joinedDigest)).thenReturn(joinedMember);
        when(mockContext.getMember(leftDigest)).thenReturn(leftMember);

        var delosViewChange = new ViewChange(
            mockContext,
            mock(Digest.class),
            List.of(joinedDigest),
            List.of(leftDigest)
        );

        listenerCaptor.getValue().accept(delosViewChange);

        // Then: Our adapter's listener should be notified
        var notified = latch.await(1, TimeUnit.SECONDS);
        assertThat(notified)
            .as("Listener should receive view change notification")
            .isTrue();

        assertThat(changes)
            .as("Should have received exactly one view change")
            .hasSize(1);

        var change = changes.get(0);
        assertThat(change.joined())
            .as("Should have one joined member")
            .hasSize(1)
            .containsExactly(joinedMember);
        assertThat(change.left())
            .as("Should have one left member")
            .hasSize(1)
            .containsExactly(leftMember);
    }

    /**
     * Test 3: Verify multiple listeners all receive notifications
     */
    @Test
    @SuppressWarnings("unchecked")
    void testMultipleListeners() throws Exception {
        // Given: An adapter with THREE registered listeners
        adapter = new FirefliesMembershipView(mockView);

        var changes1 = new ArrayList<MembershipView.ViewChange<Member>>();
        var changes2 = new ArrayList<MembershipView.ViewChange<Member>>();
        var changes3 = new ArrayList<MembershipView.ViewChange<Member>>();
        var latch = new CountDownLatch(3);

        adapter.addListener(change -> {
            changes1.add(change);
            latch.countDown();
        });
        adapter.addListener(change -> {
            changes2.add(change);
            latch.countDown();
        });
        adapter.addListener(change -> {
            changes3.add(change);
            latch.countDown();
        });

        // When: Fireflies View triggers a view change event
        verify(mockView).register(anyString(), any());

        var listenerCaptor = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(mockView).register(anyString(), listenerCaptor.capture());

        var joinedMember = mock(Member.class);
        var joinedDigest = mock(Digest.class);

        when(mockContext.getMember(joinedDigest)).thenReturn(joinedMember);

        var delosViewChange = new ViewChange(
            mockContext,
            mock(Digest.class),
            List.of(joinedDigest),
            List.of()
        );

        listenerCaptor.getValue().accept(delosViewChange);

        // Then: All three listeners should be notified
        var notified = latch.await(1, TimeUnit.SECONDS);
        assertThat(notified)
            .as("All listeners should receive notifications")
            .isTrue();

        assertThat(changes1).as("Listener 1 should receive changes").hasSize(1);
        assertThat(changes2).as("Listener 2 should receive changes").hasSize(1);
        assertThat(changes3).as("Listener 3 should receive changes").hasSize(1);
    }
}
