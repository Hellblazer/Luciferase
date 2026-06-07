/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;

import java.util.List;
import java.util.Objects;

/**
 * Lifecycle adapter for PersistenceManager.
 * <p>
 * Wraps PersistenceManager to provide LifecycleComponent interface without modifying
 * the original class. Uses composition pattern for clean separation.
 * <p>
 * Dependency Layer: 0 (no dependencies — RDR-017 gate C2; persistence has zero network surface)
 * <p>
 * Extends {@link AbstractLifecycleAdapter} for common state management logic.
 * <p>
 * <b>Composition status (RDR-017).</b> P0 fixed the lifecycle dependency ordering: {@code dependencies()}
 * is {@code List.of()} so this adapter sits at Layer 0, and
 * {@link com.hellblazer.luciferase.simulation.von.NodeBootstrap} registers it alongside
 * {@code SocketConnectionManagerAdapter}. P1 (Luciferase-pf1iu) makes {@link #doStart()} call
 * {@link PersistenceManager#recover()} fail-loud (a corrupt WAL aborts startup) and then
 * {@link PersistenceManager#startSchedulers()} — the batch-flush and checkpoint schedulers are no longer
 * started in the {@link PersistenceManager} constructor, so neither can run before recovery completes.
 *
 * @author hal.hildebrand
 */
public class PersistenceManagerAdapter extends AbstractLifecycleAdapter {

    private final PersistenceManager persistenceManager;

    /**
     * Create an adapter for PersistenceManager.
     *
     * @param persistenceManager The PersistenceManager instance to wrap
     */
    public PersistenceManagerAdapter(PersistenceManager persistenceManager) {
        this.persistenceManager = Objects.requireNonNull(persistenceManager, "persistenceManager must not be null");
    }

    @Override
    protected String getComponentName() {
        return "PersistenceManager";
    }

    @Override
    protected void doStart() throws Exception {
        // Idempotent across restart-from-FAILED: if a prior doStart() already recovered and started the
        // schedulers, do not re-run recover() (which would replay WAL events into the FSM a second time).
        if (persistenceManager.isSchedulersStarted()) {
            return;
        }
        // RDR-017 P1 (Luciferase-pf1iu): recover the WAL BEFORE starting the background schedulers.
        // recover() rethrows IOException on a corrupt WAL — letting it propagate aborts startup
        // (fail-loud, RDR-004-class silent-data-loss closure) rather than degrading into a node that
        // runs on partially-recovered state. Schedulers start only after a clean recover(), so a
        // checkpoint/batch-flush can never overwrite an unrecovered log.
        try {
            persistenceManager.recover();
        } catch (Exception e) {
            // Startup is refused. Release the resources this PM owns (scheduler executor + WAL file
            // handles) — the FAILED adapter is never RUNNING, so the coordinator's stop() will not call
            // doStop()/close() for us, and leaving them open leaks a thread + descriptors per restart.
            try {
                persistenceManager.close();
            } catch (Exception closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
        persistenceManager.startSchedulers();
    }

    @Override
    protected void doStop() throws Exception {
        persistenceManager.close();
    }

    @Override
    public String name() {
        return getComponentName();
    }

    @Override
    public List<String> dependencies() {
        // Layer 0 - no dependencies (RDR-017 gate C2). PersistenceManager imports only
        // java.io/java.nio.file/java.util.concurrent/Clock — zero network surface — and runs
        // flush/checkpoint/recover entirely from local WAL files. The former
        // "SocketConnectionManager" dependency was spurious and crashed computeLayers() when SCM
        // was not co-registered. Bubbles declare their own network dependency on the bubble adapter.
        return List.of();
    }

    /**
     * Get the wrapped PersistenceManager instance.
     *
     * @return The underlying manager
     */
    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }
}
