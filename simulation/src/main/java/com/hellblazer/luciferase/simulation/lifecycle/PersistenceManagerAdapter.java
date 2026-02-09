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
 * Dependency Layer: 1 (depends on SocketConnectionManager)
 * <p>
 * Note: PersistenceManager auto-starts background tasks (batch flush, checkpoints) on construction.
 * The start() method is effectively a no-op but maintains lifecycle state consistency.
 * <p>
 * Extends {@link AbstractLifecycleAdapter} for common state management logic.
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
        return List.of("SocketConnectionManager"); // Layer 1 - depends on SocketConnectionManager
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
