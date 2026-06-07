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
 * Note: PersistenceManager auto-starts background tasks (batch flush, checkpoints) on construction.
 * The start() method is effectively a no-op but maintains lifecycle state consistency.
 * <p>
 * Extends {@link AbstractLifecycleAdapter} for common state management logic.
 * <p>
 * <b>Composition status (RDR-017).</b> P0 (Production Node Bootstrap) fixes the lifecycle dependency
 * ordering: {@code dependencies()} is now {@code List.of()} so this adapter sits at Layer 0, and
 * {@link com.hellblazer.luciferase.simulation.von.NodeBootstrap} registers it alongside
 * {@code SocketConnectionManagerAdapter}. {@link #doStart()} remains a deliberate no-op: making it call
 * {@code recover()} fail-loud and relocating the batch-flush/checkpoint schedulers out of the
 * {@link PersistenceManager} constructor into {@code doStart()} (so a checkpoint cannot overwrite an
 * unrecovered log) is <b>RDR-017 P1</b> (Luciferase-pf1iu).
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
    protected void doStart() {
        // No-op: PersistenceManager auto-starts background tasks on construction
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
