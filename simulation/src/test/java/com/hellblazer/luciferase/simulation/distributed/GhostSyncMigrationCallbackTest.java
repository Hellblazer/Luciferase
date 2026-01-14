/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for GhostSyncMigrationCallback integration with migration lifecycle.
 * <p>
 * Validates that ghost layer coordinates properly with entity migrations:
 * <ul>
 *   <li>Ghost removed from target before migration (prevents conflict)</li>
 *   <li>Ghost sync after successful migration</li>
 *   <li>Ghost restoration on rollback</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class GhostSyncMigrationCallbackTest {

    private LocalServerTransport.Registry transportRegistry;
    private VonBubble bubble1;
    private VonBubble bubble2;
    private GhostSyncCoordinator ghostSyncCoordinator;
    private GhostSyncMigrationCallback callback;

    @BeforeEach
    void setUp() {
        // Create transport registry for in-process P2P
        transportRegistry = LocalServerTransport.Registry.create();

        // Create bubbles
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var transport1 = transportRegistry.register(uuid1);
        var transport2 = transportRegistry.register(uuid2);

        bubble1 = new VonBubble(uuid1, (byte) 10, 16L, transport1);
        bubble2 = new VonBubble(uuid2, (byte) 10, 16L, transport2);

        // Create ghost sync coordinator with simple strategy
        var velocities1 = new ConcurrentHashMap<String, Vector3f>();
        var velocities2 = new ConcurrentHashMap<String, Vector3f>();
        var ghostStrategy = new TwoBubbleSyncStrategy(
            bubble1, bubble2, 100.0f,
            30.0f, 10,
            velocities1, velocities2
        );
        ghostSyncCoordinator = new GhostSyncCoordinator(ghostStrategy);

        // Create callback
        callback = new GhostSyncMigrationCallback(ghostSyncCoordinator, bubble1, bubble2);
    }

    @AfterEach
    void tearDown() {
        if (bubble1 != null) {
            bubble1.close();
        }
        if (bubble2 != null) {
            bubble2.close();
        }
        if (transportRegistry != null) {
            transportRegistry.close();
        }
    }

    // ========== Ghost Removal Tests ==========

    @Test
    void testOnMigrationPrepare_RemovesGhostFromTarget() throws Exception {
        // Setup: Create a ghost in bubble2 for entity that will migrate from bubble1
        String entityId = "entity-1";
        var ghostPosition = new Point3f(110.0f, 50.0f, 50.0f);
        var ghostVelocity = new Vector3f(1.0f, 0.0f, 0.0f);

        // Manually add ghost to bubble2's ghost map
        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        ghostsInBubble2.put(entityId, new GhostSyncCoordinator.GhostEntry(
            entityId, ghostPosition, ghostVelocity, 1000L
        ));

        assertThat(ghostsInBubble2).containsKey(entityId);

        // Act: Call onMigrationPrepare (simulate migration from bubble1 to bubble2)
        callback.onMigrationPrepare(entityId, bubble1.id(), bubble2.id());

        // Assert: Ghost should be removed from bubble2
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);
    }

    @Test
    void testOnMigrationPrepare_NoGhostInTarget_NoError() throws Exception {
        // Setup: No ghost in bubble2
        String entityId = "entity-1";
        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);

        // Act: Call onMigrationPrepare (no ghost to remove)
        callback.onMigrationPrepare(entityId, bubble1.id(), bubble2.id());

        // Assert: No error, no ghost in target
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);
    }

    @Test
    void testOnMigrationPrepare_RemovesGhostFromBubble1() throws Exception {
        // Setup: Create a ghost in bubble1 for entity that will migrate from bubble2
        String entityId = "entity-2";
        var ghostPosition = new Point3f(90.0f, 50.0f, 50.0f);
        var ghostVelocity = new Vector3f(-1.0f, 0.0f, 0.0f);

        // Manually add ghost to bubble1's ghost map
        var ghostsInBubble1 = ghostSyncCoordinator.getGhostsInBubble1();
        ghostsInBubble1.put(entityId, new GhostSyncCoordinator.GhostEntry(
            entityId, ghostPosition, ghostVelocity, 1000L
        ));

        assertThat(ghostsInBubble1).containsKey(entityId);

        // Act: Call onMigrationPrepare (simulate migration from bubble2 to bubble1)
        callback.onMigrationPrepare(entityId, bubble2.id(), bubble1.id());

        // Assert: Ghost should be removed from bubble1
        assertThat(ghostsInBubble1).doesNotContainKey(entityId);
    }

    // ========== Ghost Sync Tests ==========

    @Test
    void testOnMigrationCommit_DoesNotThrow() {
        // Setup: Entity migrated successfully
        String entityId = "entity-1";

        // Act: Call onMigrationCommit
        assertThatCode(() ->
            callback.onMigrationCommit(entityId, bubble1.id(), bubble2.id())
        ).doesNotThrowAnyException();

        // Note: onMigrationCommit relies on periodic ghost sync in TwoBubbleSimulation.tick()
        // This test just verifies it doesn't throw exceptions
    }

    @Test
    void testOnMigrationCommit_ReverseDirection() {
        // Test migration from bubble2 to bubble1
        String entityId = "entity-2";

        // Act: Call onMigrationCommit (reverse direction)
        assertThatCode(() ->
            callback.onMigrationCommit(entityId, bubble2.id(), bubble1.id())
        ).doesNotThrowAnyException();
    }

    // ========== Rollback Tests ==========

    @Test
    void testOnMigrationRollback_DoesNotThrow() {
        // Setup: Migration failed, need to rollback
        String entityId = "entity-1";

        // Act: Call onMigrationRollback
        assertThatCode(() ->
            callback.onMigrationRollback(entityId, bubble1.id(), bubble2.id())
        ).doesNotThrowAnyException();

        // Note: onMigrationRollback relies on next ghost sync to restore ghost if needed
        // This test just verifies it doesn't throw exceptions
    }

    @Test
    void testOnMigrationRollback_ReverseDirection() {
        // Test rollback from bubble2 to bubble1
        String entityId = "entity-2";

        // Act: Call onMigrationRollback (reverse direction)
        assertThatCode(() ->
            callback.onMigrationRollback(entityId, bubble2.id(), bubble1.id())
        ).doesNotThrowAnyException();
    }

    // ========== Integration Tests ==========

    @Test
    void testFullMigrationLifecycle_SuccessPath() throws Exception {
        // Setup: Entity migrates from bubble1 to bubble2
        String entityId = "entity-1";
        var ghostPosition = new Point3f(110.0f, 50.0f, 50.0f);
        var ghostVelocity = new Vector3f(1.0f, 0.0f, 0.0f);

        // Add ghost to bubble2 (simulate ghost from previous sync)
        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        ghostsInBubble2.put(entityId, new GhostSyncCoordinator.GhostEntry(
            entityId, ghostPosition, ghostVelocity, 1000L
        ));

        // Act: Execute full migration lifecycle
        // 1. PREPARE: Remove ghost from target
        callback.onMigrationPrepare(entityId, bubble1.id(), bubble2.id());
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);

        // 2. COMMIT: (Migration successful, ghost sync will occur on next tick)
        callback.onMigrationCommit(entityId, bubble1.id(), bubble2.id());

        // Assert: Ghost should still be removed (sync happens later)
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);
    }

    @Test
    void testFullMigrationLifecycle_RollbackPath() throws Exception {
        // Setup: Entity migration fails, need to rollback
        String entityId = "entity-1";
        var ghostPosition = new Point3f(110.0f, 50.0f, 50.0f);
        var ghostVelocity = new Vector3f(1.0f, 0.0f, 0.0f);

        // Add ghost to bubble2 (simulate ghost from previous sync)
        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        ghostsInBubble2.put(entityId, new GhostSyncCoordinator.GhostEntry(
            entityId, ghostPosition, ghostVelocity, 1000L
        ));

        // Act: Execute migration lifecycle with rollback
        // 1. PREPARE: Remove ghost from target
        callback.onMigrationPrepare(entityId, bubble1.id(), bubble2.id());
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);

        // 2. ROLLBACK: (Migration failed, ghost will be restored by next sync)
        callback.onMigrationRollback(entityId, bubble1.id(), bubble2.id());

        // Assert: Ghost remains removed (sync will restore if entity still near boundary)
        assertThat(ghostsInBubble2).doesNotContainKey(entityId);
    }

    // ========== Edge Cases ==========

    @Test
    void testOnMigrationPrepare_ConcurrentAccess() throws Exception {
        // Test thread safety of ghost removal during concurrent migrations
        String entityId1 = "entity-1";
        String entityId2 = "entity-2";

        // Add ghosts
        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        ghostsInBubble2.put(entityId1, new GhostSyncCoordinator.GhostEntry(
            entityId1, new Point3f(110.0f, 50.0f, 50.0f), new Vector3f(1.0f, 0.0f, 0.0f), 1000L
        ));
        ghostsInBubble2.put(entityId2, new GhostSyncCoordinator.GhostEntry(
            entityId2, new Point3f(120.0f, 50.0f, 50.0f), new Vector3f(1.0f, 0.0f, 0.0f), 1000L
        ));

        // Act: Concurrent calls to onMigrationPrepare
        var thread1 = new Thread(() -> {
            try {
                callback.onMigrationPrepare(entityId1, bubble1.id(), bubble2.id());
            } catch (Exception e) {
                fail("Thread 1 threw exception: " + e.getMessage());
            }
        });

        var thread2 = new Thread(() -> {
            try {
                callback.onMigrationPrepare(entityId2, bubble1.id(), bubble2.id());
            } catch (Exception e) {
                fail("Thread 2 threw exception: " + e.getMessage());
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        // Assert: Both ghosts should be removed
        assertThat(ghostsInBubble2).doesNotContainKeys(entityId1, entityId2);
    }

    @Test
    void testOnMigrationPrepare_NullEntityId_Throws() {
        // Act & Assert: Null entity ID should throw exception
        assertThatThrownBy(() ->
            callback.onMigrationPrepare(null, bubble1.id(), bubble2.id())
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testOnMigrationPrepare_MultipleGhostsInTarget() throws Exception {
        // Setup: Multiple ghosts in bubble2, only one should be removed
        String entityToMigrate = "entity-1";
        String otherEntity = "entity-2";

        var ghostsInBubble2 = ghostSyncCoordinator.getGhostsInBubble2();
        ghostsInBubble2.put(entityToMigrate, new GhostSyncCoordinator.GhostEntry(
            entityToMigrate, new Point3f(110.0f, 50.0f, 50.0f), new Vector3f(1.0f, 0.0f, 0.0f), 1000L
        ));
        ghostsInBubble2.put(otherEntity, new GhostSyncCoordinator.GhostEntry(
            otherEntity, new Point3f(120.0f, 50.0f, 50.0f), new Vector3f(1.0f, 0.0f, 0.0f), 1000L
        ));

        // Act: Remove only the migrating entity's ghost
        callback.onMigrationPrepare(entityToMigrate, bubble1.id(), bubble2.id());

        // Assert: Only migrating entity's ghost removed, other ghost remains
        assertThat(ghostsInBubble2).doesNotContainKey(entityToMigrate);
        assertThat(ghostsInBubble2).containsKey(otherEntity);
    }
}
