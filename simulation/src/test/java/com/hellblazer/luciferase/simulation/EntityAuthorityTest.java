package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntityAuthority - epoch/version tracking for distributed entity updates.
 * <p>
 * Authority tracking prevents stale updates:
 * - Epoch: Increments when entity migrates to new bubble (ownership transfer)
 * - Version: Increments on each update within current epoch
 * <p>
 * Update acceptance rules:
 * - Higher epoch always wins (newer ownership)
 * - Within same epoch, higher version wins (newer update)
 * - Equal epoch+version = duplicate/concurrent update
 *
 * @author hal.hildebrand
 */
class EntityAuthorityTest {

    @Test
    void testInitialAuthority() {
        var auth = new EntityAuthority(0, 0);

        assertEquals(0, auth.epoch());
        assertEquals(0, auth.version());
    }

    @Test
    void testIncrementVersion() {
        var auth = new EntityAuthority(1, 5);

        var next = auth.incrementVersion();

        assertEquals(1, next.epoch(), "Epoch should remain unchanged");
        assertEquals(6, next.version(), "Version should increment");

        // Original unchanged (immutable)
        assertEquals(5, auth.version());
    }

    @Test
    void testIncrementEpoch() {
        var auth = new EntityAuthority(1, 5);

        var next = auth.incrementEpoch();

        assertEquals(2, next.epoch(), "Epoch should increment");
        assertEquals(0, next.version(), "Version should reset to 0 on epoch change");

        // Original unchanged (immutable)
        assertEquals(1, auth.epoch());
        assertEquals(5, auth.version());
    }

    @Test
    void testIsNewerThan_HigherEpoch() {
        var current = new EntityAuthority(1, 10);
        var incoming = new EntityAuthority(2, 0);

        assertTrue(incoming.isNewerThan(current),
                  "Higher epoch should be newer, even with lower version");
    }

    @Test
    void testIsNewerThan_SameEpochHigherVersion() {
        var current = new EntityAuthority(1, 5);
        var incoming = new EntityAuthority(1, 6);

        assertTrue(incoming.isNewerThan(current),
                  "Within same epoch, higher version should be newer");
    }

    @Test
    void testIsNewerThan_SameEpochLowerVersion() {
        var current = new EntityAuthority(1, 6);
        var incoming = new EntityAuthority(1, 5);

        assertFalse(incoming.isNewerThan(current),
                   "Within same epoch, lower version should be stale");
    }

    @Test
    void testIsNewerThan_LowerEpoch() {
        var current = new EntityAuthority(2, 0);
        var incoming = new EntityAuthority(1, 100);

        assertFalse(incoming.isNewerThan(current),
                   "Lower epoch should be stale, even with higher version");
    }

    @Test
    void testIsNewerThan_Equal() {
        var current = new EntityAuthority(1, 5);
        var incoming = new EntityAuthority(1, 5);

        assertFalse(incoming.isNewerThan(current),
                   "Equal epoch+version should not be considered newer");
    }

    @Test
    void testMigrationScenario() {
        // Entity starts in bubble A (epoch 0)
        var authA = new EntityAuthority(0, 0);

        // Several updates in bubble A
        authA = authA.incrementVersion();  // 0, 1
        authA = authA.incrementVersion();  // 0, 2
        authA = authA.incrementVersion();  // 0, 3

        assertEquals(0, authA.epoch());
        assertEquals(3, authA.version());

        // Entity migrates to bubble B (epoch increments, version resets)
        var authB = authA.incrementEpoch();  // 1, 0

        assertEquals(1, authB.epoch());
        assertEquals(0, authB.version());

        // Update in bubble B
        authB = authB.incrementVersion();  // 1, 1

        // Stale update from bubble A should be rejected
        var staleUpdate = authA.incrementVersion();  // 0, 4

        assertTrue(authB.isNewerThan(staleUpdate),
                  "New bubble authority should reject stale updates from old bubble");
    }

    @Test
    void testConcurrentUpdateDetection() {
        var auth1 = new EntityAuthority(1, 5);
        var auth2 = new EntityAuthority(1, 5);

        // Neither is newer than the other (concurrent/duplicate)
        assertFalse(auth1.isNewerThan(auth2));
        assertFalse(auth2.isNewerThan(auth1));
    }

    @Test
    void testMultipleMigrations() {
        var auth = new EntityAuthority(0, 0);

        // Migrate through multiple bubbles
        auth = auth.incrementEpoch();  // Epoch 1
        auth = auth.incrementVersion();
        auth = auth.incrementVersion();

        auth = auth.incrementEpoch();  // Epoch 2
        auth = auth.incrementVersion();

        auth = auth.incrementEpoch();  // Epoch 3
        auth = auth.incrementVersion();
        auth = auth.incrementVersion();
        auth = auth.incrementVersion();

        assertEquals(3, auth.epoch());
        assertEquals(3, auth.version());

        // Stale update from epoch 2
        var stale = new EntityAuthority(2, 100);

        assertTrue(auth.isNewerThan(stale),
                  "Current epoch should reject updates from previous epochs");
    }

    @Test
    void testVersionProgressionWithinEpoch() {
        var auth = new EntityAuthority(5, 0);

        // Monotonic version increase within epoch
        for (int i = 1; i <= 10; i++) {
            var next = auth.incrementVersion();
            assertEquals(5, next.epoch());
            assertEquals(i, next.version());
            auth = next;
        }
    }

    @Test
    void testCompareDifferentEpochsIgnoresVersion() {
        // Even with much higher version, lower epoch is stale
        var lowEpochHighVersion = new EntityAuthority(1, 1000);
        var highEpochLowVersion = new EntityAuthority(2, 0);

        assertTrue(highEpochLowVersion.isNewerThan(lowEpochHighVersion));
    }

    @Test
    void testEqualitySemantics() {
        var auth1 = new EntityAuthority(1, 5);
        var auth2 = new EntityAuthority(1, 5);
        var auth3 = new EntityAuthority(1, 6);

        assertEquals(auth1, auth2, "Equal epoch+version should be equal");
        assertNotEquals(auth1, auth3, "Different version should not be equal");
    }

    @Test
    void testHashCodeConsistency() {
        var auth1 = new EntityAuthority(1, 5);
        var auth2 = new EntityAuthority(1, 5);

        assertEquals(auth1.hashCode(), auth2.hashCode(),
                    "Equal objects should have equal hash codes");
    }
}
