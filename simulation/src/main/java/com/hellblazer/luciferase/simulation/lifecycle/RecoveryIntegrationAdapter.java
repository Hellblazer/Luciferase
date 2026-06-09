/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * Lifecycle adapter for the partition fault subsystem (RDR-021 S3, Luciferase-0frcy.135.4).
 * <p>
 * Makes {@code RecoveryIntegration} an explicit lifecycle participant ordered to stop <b>ahead of
 * the bubble adapters</b>, so {@code Manager.close()} deterministically unsubscribes the recovery
 * integration (VON + fault-event listeners) and stops the fault handler <b>before</b> any bubble's
 * {@code broadcastLeave()} can dispatch a VON {@code Leave} into a still-subscribed handler.
 * Without this ordering, a node would report sync failures against its own partitions during
 * normal shutdown — in-process neighbor bubbles synchronously re-dispatch the departing bubble's
 * LEAVE as a VON event. Mirrors the RDR-017 migrator-before-WAL shutdown contract.
 * <p>
 * <b>Dynamic dependencies.</b> Bubble adapters are created and removed at runtime, so the
 * dependency set cannot be fixed at registration. {@link #dependencies()} returns the
 * currently-registered {@code EnhancedBubble-*} component names via the package-private
 * {@code LifecycleCoordinator.componentNames()} view (a plain map read with no
 * {@code checkReentrancy()} guard, so no reentrancy violation when the coordinator invokes this
 * during {@code computeLayers()}). Kahn's sort then places this adapter in the layer above every
 * live bubble; shutdown processes layers in reverse, stopping this adapter first. At registration
 * time (before any bubble exists) the set is empty, so registration always succeeds. These
 * dependencies are {@linkplain #dependenciesAreOrderingOnly() ordering-only}: a live bubble
 * removal is never blocked by them, and {@code computeLayers} skips one that vanishes
 * concurrently (TOCTOU under concurrent {@code leave()} + {@code close()}).
 * <p>
 * {@code doStart()} is a no-op: the recovery integration subscribes in its constructor and the
 * fault handler is started by {@code NodeBootstrap.assembleFaultTolerance} before this adapter is
 * registered. {@code doStop()} closes the subsystem (idempotent — a caller that already invoked
 * {@code FaultSubsystem.close()} manually per the S1 contract is absorbed safely).
 *
 * @author hal.hildebrand
 */
public final class RecoveryIntegrationAdapter extends AbstractLifecycleAdapter {

    /** Component name in the lifecycle coordinator. */
    public static final String NAME = "RecoveryIntegration";

    private final AutoCloseable faultSubsystem;
    private final LifecycleCoordinator coordinator;

    /**
     * @param faultSubsystem the assembled fault subsystem ({@code NodeBootstrap.FaultSubsystem});
     *                       its {@code close()} unsubscribes the recovery integration, then stops
     *                       the fault handler
     * @param coordinator    the coordinator this adapter will be registered with, whose component
     *                       map supplies the dynamic bubble dependencies
     */
    public RecoveryIntegrationAdapter(AutoCloseable faultSubsystem, LifecycleCoordinator coordinator) {
        this.faultSubsystem = Objects.requireNonNull(faultSubsystem, "faultSubsystem cannot be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
    }

    @Override
    protected String getComponentName() {
        return NAME;
    }

    @Override
    public String name() {
        return getComponentName();
    }

    @Override
    public List<String> dependencies() {
        // Live snapshot of registered bubble adapters. Names come from the coordinator's own
        // component map, so they always pass computeLayers' existence validation — no dangling
        // dependency is possible even if a bubble bypassed adapter registration.
        // ConcurrentHashMap.keySet() iteration is safe under concurrent modification: a bubble
        // registered concurrently with computeLayers() may or may not appear in this snapshot —
        // acceptable, the coordinator already tolerates late registerAndStart racing stop() at
        // the layer level.
        return coordinator.componentNames().stream()
                          .filter(name -> name.startsWith(EnhancedBubbleAdapter.NAME_PREFIX))
                          .toList();
    }

    @Override
    public boolean dependenciesAreOrderingOnly() {
        // The bubble dependencies exist solely to stop this adapter ahead of the bubbles at full
        // shutdown. A live bubble removal (Manager.leave via stopAndUnregister) must NOT be
        // blocked by them — the dependency set self-heals on the next dependencies() call.
        // Without this, stopAndUnregister's dependents guard rejects every bubble removal and
        // Manager.leave silently falls back to a direct close, leaking the bubble adapter in the
        // coordinator (S3 review Critical).
        return true;
    }

    @Override
    protected void doStart() {
        // No-op: RecoveryIntegration subscribes in its constructor; the fault handler is
        // started by NodeBootstrap.assembleFaultTolerance before this adapter is registered.
    }

    @Override
    protected void doStop() throws Exception {
        faultSubsystem.close();
    }
}
