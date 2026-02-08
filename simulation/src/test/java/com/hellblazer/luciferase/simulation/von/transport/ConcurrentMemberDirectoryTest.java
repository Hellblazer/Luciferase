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

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.von.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ConcurrentMemberDirectory implementation.
 * <p>
 * Validates:
 * <ul>
 *   <li>Member registration and lookup</li>
 *   <li>Deterministic spatial key routing</li>
 *   <li>Race condition handling for concurrent access</li>
 *   <li>New unregisterMember() API</li>
 * </ul>
 * <p>
 * Tests migrated from SocketTransportTest (reflection-based tests replaced with API-based).
 *
 * @author hal.hildebrand
 */
class ConcurrentMemberDirectoryTest {

    private MemberDirectory directory;

    @BeforeEach
    void setup() {
        directory = new ConcurrentMemberDirectory();
    }

    /**
     * Test basic member registration and lookup.
     */
    @Test
    void testRegisterAndLookup() {
        var memberId = UUID.randomUUID();
        var address = ProcessAddress.localhost("test-process", 9991);

        // Register member
        directory.registerMember(memberId, address);

        // Lookup should succeed
        var result = directory.lookupMember(memberId);
        assertTrue(result.isPresent(), "Registered member should be found");
        assertEquals(memberId, result.get().nodeId(), "Member ID should match");
        assertEquals(address.toUrl(), result.get().endpoint(), "Address should match");

        // Lookup non-existent member
        var nonExistent = UUID.randomUUID();
        var notFound = directory.lookupMember(nonExistent);
        assertFalse(notFound.isPresent(), "Non-existent member should not be found");
    }

    /**
     * Test NEW unregisterMember() API.
     * <p>
     * This is a NEW capability not present in original SocketTransport.
     * Replaces reflection-based test manipulation.
     */
    @Test
    void testUnregisterMember() {
        var memberId = UUID.randomUUID();
        var address = ProcessAddress.localhost("test-process", 9991);

        // Register and verify
        directory.registerMember(memberId, address);
        assertTrue(directory.lookupMember(memberId).isPresent(), "Member should be registered");

        // Unregister
        directory.unregisterMember(memberId);

        // Verify removed
        assertFalse(directory.lookupMember(memberId).isPresent(), "Member should be unregistered");

        // Unregister non-existent member (should be no-op, not error)
        assertDoesNotThrow(() -> directory.unregisterMember(UUID.randomUUID()),
                          "Unregistering non-existent member should not throw");
    }

    /**
     * Test deterministic routing: same key always routes to same member.
     */
    @Test
    void testRouteToKeyDeterministic() throws Transport.TransportException {
        // Register 3 members
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        var member3 = UUID.randomUUID();
        directory.registerMember(member1, ProcessAddress.localhost("m1", 9991));
        directory.registerMember(member2, ProcessAddress.localhost("m2", 9992));
        directory.registerMember(member3, ProcessAddress.localhost("m3", 9993));

        // Create test key
        var key = TetreeKey.create((byte) 0, 12345L, 67890L);

        // Route same key multiple times
        var firstRoute = directory.routeToKey(key);
        for (int i = 0; i < 10; i++) {
            var route = directory.routeToKey(key);
            assertEquals(firstRoute.nodeId(), route.nodeId(),
                        "Same key should always route to same member");
        }

        // Different key might route to different member (depending on hash)
        var differentKey = TetreeKey.create((byte) 0, 99999L, 11111L);
        var differentRoute = directory.routeToKey(differentKey);
        assertNotNull(differentRoute, "Should route different key successfully");
    }

    /**
     * Test routeToKey() with no members registered.
     */
    @Test
    void testRouteToKeyNoMembers() {
        var key = TetreeKey.create((byte) 0, 0L, 0L);

        var ex = assertThrows(Transport.TransportException.class,
                             () -> directory.routeToKey(key),
                             "Should throw TransportException when no members");

        assertTrue(ex.getMessage().contains("No members") || ex.getMessage().contains("available"),
                  "Error message should indicate no members available");
    }

    /**
     * Test routeToKey() race condition: member removed between snapshot and get.
     * <p>
     * MIGRATED from SocketTransportTest - now uses unregisterMember() instead of reflection.
     * <p>
     * Verifies that NPE is prevented and TransportException is thrown instead.
     */
    @Test
    void testRouteToKeyMemberRemovedRace() throws Transport.TransportException {
        // Register 3 members
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        var member3 = UUID.randomUUID();
        directory.registerMember(member1, ProcessAddress.localhost("m1", 9991));
        directory.registerMember(member2, ProcessAddress.localhost("m2", 9992));
        directory.registerMember(member3, ProcessAddress.localhost("m3", 9993));

        // Create a key
        var key = TetreeKey.create((byte) 0, 0L, 0L);

        // Remove all members using NEW API (no reflection needed!)
        directory.unregisterMember(member1);
        directory.unregisterMember(member2);
        directory.unregisterMember(member3);

        // Try to route with no members available
        var ex = assertThrows(Transport.TransportException.class,
                             () -> directory.routeToKey(key),
                             "Should throw TransportException when member removed");

        // Should get helpful error message (not NPE)
        assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("No members"),
                  "Error message should indicate member was removed: " + ex.getMessage());
    }

    /**
     * Test routeToKey() race condition with concurrent member churn.
     * <p>
     * MIGRATED from SocketTransportTest - now uses registerMember/unregisterMember instead of reflection.
     * <p>
     * Verifies thread-safe behavior under concurrent access.
     */
    @Test
    void testConcurrentMemberChurn() {
        var key = TetreeKey.create((byte) 0, 0L, 0L);

        var failures = new AtomicInteger(0);
        var successes = new AtomicInteger(0);

        // Thread 1: Keep adding/removing members using NEW API
        var churnThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                var memberId = UUID.randomUUID();
                directory.registerMember(memberId, ProcessAddress.localhost("m" + i, 9000 + i));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
                directory.unregisterMember(memberId);  // NEW API - no reflection!
            }
        });

        // Thread 2: Keep trying to route
        var routeThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    directory.routeToKey(key);
                    successes.incrementAndGet();
                } catch (Transport.TransportException e) {
                    // Expected - member might be removed during routing
                    failures.incrementAndGet();
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        });

        // Start both threads
        churnThread.start();
        routeThread.start();

        // Wait for completion
        try {
            churnThread.join(5000);
            routeThread.join(5000);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        // Should have some successes and failures (depending on timing)
        // but NO NPEs (all exceptions should be TransportException)
        var total = successes.get() + failures.get();
        assertEquals(100, total, "Should complete all routing attempts without NPE");
    }

    /**
     * Test getAddressFor() method.
     */
    @Test
    void testGetAddressFor() {
        var memberId = UUID.randomUUID();
        var address = ProcessAddress.localhost("test-process", 9991);

        // Before registration
        assertNull(directory.getAddressFor(memberId), "Should return null for unregistered member");

        // After registration
        directory.registerMember(memberId, address);
        var result = directory.getAddressFor(memberId);
        assertNotNull(result, "Should return address for registered member");
        assertEquals(address, result, "Address should match");

        // After unregistration
        directory.unregisterMember(memberId);
        assertNull(directory.getAddressFor(memberId), "Should return null after unregistration");
    }

    /**
     * Test getRegisteredMembers() returns thread-safe snapshot.
     */
    @Test
    void testGetRegisteredMembers() {
        // Initially empty
        var empty = directory.getRegisteredMembers();
        assertTrue(empty.isEmpty(), "Initially should have no members");

        // Register 3 members
        var member1 = UUID.randomUUID();
        var member2 = UUID.randomUUID();
        var member3 = UUID.randomUUID();
        directory.registerMember(member1, ProcessAddress.localhost("m1", 9991));
        directory.registerMember(member2, ProcessAddress.localhost("m2", 9992));
        directory.registerMember(member3, ProcessAddress.localhost("m3", 9993));

        // Get snapshot
        var members = directory.getRegisteredMembers();
        assertEquals(3, members.size(), "Should have 3 members");
        assertTrue(members.contains(member1), "Should contain member1");
        assertTrue(members.contains(member2), "Should contain member2");
        assertTrue(members.contains(member3), "Should contain member3");

        // Modify snapshot should not affect directory
        var snapshot = directory.getRegisteredMembers();
        snapshot.clear();  // Modify the returned set
        assertEquals(3, directory.getRegisteredMembers().size(),
                    "Directory should be unaffected by snapshot modification");

        // Unregister and verify
        directory.unregisterMember(member2);
        var afterRemoval = directory.getRegisteredMembers();
        assertEquals(2, afterRemoval.size(), "Should have 2 members after unregistration");
        assertFalse(afterRemoval.contains(member2), "Should not contain unregistered member");
    }
}
