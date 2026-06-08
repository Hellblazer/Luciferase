/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P1 (Luciferase-pf1iu) — scheduler-relocation regression.
 * <p>
 * Proves the batch-flush scheduler is NOT started in the {@link PersistenceManager} constructor; it is
 * started only by {@link PersistenceManager#startSchedulers()} (which {@code
 * PersistenceManagerAdapter.doStart()} invokes after {@code recover()}). The assertion is non-vacuous:
 * re-introducing the scheduler into the constructor would make {@code scheduledTaskCount()} non-zero
 * immediately after construction.
 * <p>
 * RDR-019 (gate O1): there is no longer a periodic checkpoint scheduler — {@code startSchedulers()}
 * queues exactly ONE task (batch-flush). Re-adding a periodic checkpoint would make the post-start
 * count 2 and trip this regression.
 */
class PersistenceManagerSchedulerRelocationTest {

    @Test
    void schedulerIsNotQueuedInConstructor(@TempDir Path walDir) throws IOException {
        try (var pm = new PersistenceManager(UUID.randomUUID(), walDir)) {
            assertFalse(pm.isSchedulersStarted(), "schedulers must not be started by the constructor");
            assertEquals(0, pm.scheduledTaskCount(),
                         "no scheduler task may be queued before startSchedulers() — re-adding the "
                         + "batch-flush (or a periodic checkpoint) scheduler to the ctor would make this non-zero");

            pm.startSchedulers();
            assertTrue(pm.isSchedulersStarted(), "startSchedulers() must mark schedulers started");
            assertEquals(1, pm.scheduledTaskCount(),
                         "startSchedulers() must start exactly the batch-flush scheduler — RDR-019 gate O1 "
                         + "removed the periodic checkpoint scheduler (a count of 2 means it was re-added)");
        }
    }

    @Test
    void startSchedulersIsIdempotent(@TempDir Path walDir) throws IOException {
        try (var pm = new PersistenceManager(UUID.randomUUID(), walDir)) {
            pm.startSchedulers();
            pm.startSchedulers();
            assertEquals(1, pm.scheduledTaskCount(), "repeated startSchedulers() must not double-schedule");
        }
    }
}
