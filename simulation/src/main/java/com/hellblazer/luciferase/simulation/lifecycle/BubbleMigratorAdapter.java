/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.tumbler.BubbleMigrator;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle adapter for {@link BubbleMigrator} (RDR-017, Luciferase-n6jrh.2).
 * <p>
 * Dependency Layer: 1 — depends on {@code PersistenceManager}, so reverse-order shutdown
 * drains the migrator BEFORE the coordinator stops the persistence layer and closes the WAL.
 * Without this ordering, an in-flight migration can call {@code logMigrationCommit()} against
 * a closed WAL: the commit fails, leaving a durable {@code ENTITY_DEPARTURE} with no
 * {@code MIGRATION_COMMIT} — the RDR-016 R2 split-brain precondition.
 * <p>
 * {@link #doStop()} drains via {@link BubbleMigrator#shutdownGracefully(Duration)}: in-flight
 * migrations run to completion (bounded by the migrator's per-migration timeout, the default
 * drain grace) and new ones are rejected with a clean failed result. The migrator's executor
 * cannot be revived after drain — a restart attempt fails loud rather than producing a zombie
 * adapter whose migrations are silently rejected.
 * <p>
 * <b>Over-budget drains.</b> The coordinator divides {@code Manager.close()}'s total stop
 * timeout across all components and does NOT cancel a drain that exceeds its share — it
 * proceeds to the persistence layer with the drain still running. That residual case is made
 * safe by the {@code PersistenceManagerAdapter} clean-shutdown gate (wired by the 4-arg
 * {@code NodeBootstrap.assemble} to {@link BubbleMigrator#isTerminated()}): the WAL is
 * checkpoint-truncated only when the migration executor has fully terminated; otherwise it is
 * closed crash-safe and retained, so a half-bracket recovers as MIGRATING_OUT instead of being
 * truncated away.
 *
 * @author hal.hildebrand
 */
public class BubbleMigratorAdapter extends AbstractLifecycleAdapter {

    public static final String NAME = "BubbleMigrator";

    private final BubbleMigrator migrator;
    private final Duration drainGrace;

    /**
     * Create an adapter draining with the migrator's own per-migration timeout as grace —
     * in-flight work is bounded by that timeout, so it is the natural drain budget.
     *
     * @param migrator the migrator to lifecycle-manage
     */
    public BubbleMigratorAdapter(BubbleMigrator migrator) {
        this(migrator, Objects.requireNonNull(migrator, "migrator must not be null").migrationTimeout());
    }

    /**
     * Create an adapter with an explicit drain grace.
     * <p>
     * Note the coordinator budgets {@code Manager.close()}'s total shutdown timeout across all
     * components; a grace exceeding the per-component share is cut short by the coordinator's
     * stop timeout (logged, shutdown continues).
     *
     * @param migrator   the migrator to lifecycle-manage
     * @param drainGrace how long {@link #doStop()} waits for in-flight migrations
     */
    public BubbleMigratorAdapter(BubbleMigrator migrator, Duration drainGrace) {
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.drainGrace = Objects.requireNonNull(drainGrace, "drainGrace must not be null");
    }

    @Override
    protected String getComponentName() {
        return NAME;
    }

    @Override
    protected void doStart() throws Exception {
        // The migrator's executor is live from construction — nothing to start. But it cannot
        // be revived after a drain: fail loud instead of running as a zombie whose migrations
        // are all silently rejected.
        if (migrator.isShutdown()) {
            throw new IllegalStateException(
                "BubbleMigrator cannot restart after drain — construct a new migrator");
        }
    }

    @Override
    protected void doStop() throws Exception {
        // shutdownGracefully logs a warning itself when the grace elapses and falls back to
        // interrupting; an interrupted migration aborts pre-commit (recoverable MIGRATING_OUT
        // half-bracket), never half-commits.
        migrator.shutdownGracefully(drainGrace);
        // shutdownGracefully re-asserts the interrupt flag if awaitTermination was interrupted;
        // doStop runs on a shared pool worker (ForkJoinPool does not clear the flag between
        // tasks), so swallow it here — the adapter's contract is "drain complete or timed out,
        // either way stop is done".
        Thread.interrupted();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> dependencies() {
        // Layer 1: started after, and stopped before, the persistence layer — the entire point
        // of this adapter (see class javadoc).
        return List.of("PersistenceManager");
    }

    /**
     * @return the wrapped migrator
     */
    public BubbleMigrator getMigrator() {
        return migrator;
    }
}
