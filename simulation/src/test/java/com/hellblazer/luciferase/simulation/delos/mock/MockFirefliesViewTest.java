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

package com.hellblazer.luciferase.simulation.delos.mock;

import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-driven development for MockFirefliesView.
 * <p>
 * Tests verify that the mock provides programmatic control over membership
 * for testing cluster coordination logic without real Fireflies infrastructure.
 *
 * @author hal.hildebrand
 */
class MockFirefliesViewTest {

    private MockFirefliesView<UUID> view;

    @BeforeEach
    void setUp() {
        view = new MockFirefliesView<>();
    }

    @Test
    void testInitiallyEmpty() {
        var members = view.getMembers().collect(Collectors.toList());
        assertThat(members).isEmpty();
    }

    @Test
    void testAddMembers() {
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();

        view.addMember(member1);
        view.addMember(member2);

        var members = view.getMembers().collect(Collectors.toList());
        assertThat(members).containsExactlyInAnyOrder(member1, member2);
    }

    @Test
    void testRemoveMembers() {
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        var member3 = UUID.randomUUID();

        view.addMember(member1);
        view.addMember(member2);
        view.addMember(member3);

        view.removeMember(member2);

        var members = view.getMembers().collect(Collectors.toList());
        assertThat(members).containsExactlyInAnyOrder(member1, member3);
        assertThat(members).doesNotContain(member2);
    }

    @Test
    void testListenerNotifications() throws InterruptedException {
        var latch = new CountDownLatch(2);
        var changes = new ArrayList<MembershipView.ViewChange<UUID>>();

        view.addListener(change -> {
            changes.add(change);
            latch.countDown();
        });

        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();

        // Adding member should trigger notification
        view.addMember(member1);

        // Removing member should trigger notification
        view.removeMember(member1);
        view.addMember(member2);

        // Wait for notifications
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        // Verify we got at least 2 change notifications
        assertThat(changes).hasSizeGreaterThanOrEqualTo(2);

        // Verify first change shows member1 joined
        assertThat(changes.get(0).joined()).contains(member1);
        assertThat(changes.get(0).left()).isEmpty();

        // Verify second change shows member1 left or member2 joined
        var secondChange = changes.get(1);
        var hasLeftNotification = secondChange.left().contains(member1);
        var hasJoinNotification = secondChange.joined().contains(member2);
        assertThat(hasLeftNotification || hasJoinNotification).isTrue();
    }
}
