/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RDR-021 S3 (Luciferase-0frcy.135.4) — coordinator semantics for
 * {@link LifecycleComponent#dependenciesAreOrderingOnly() ordering-only} dependencies:
 * <ul>
 *   <li>{@code stopAndUnregister}'s dependents guard skips ordering-only dependents (a live
 *       bubble removal must not be blocked by the recovery adapter's dynamic dependency);</li>
 *   <li>{@code computeLayers} tolerates an ordering-only dependency that vanished between the
 *       {@code dependencies()} snapshot and the existence check (TOCTOU under concurrent
 *       {@code leave()} + {@code close()} — S3 critique Significant 1). Without tolerance, the
 *       whole coordinated shutdown aborts and the ordering invariant is bypassed;</li>
 *   <li>static (liveness) dependencies keep their strict behavior: guard blocks removal, unknown
 *       dependency still fails loud.</li>
 * </ul>
 */
class OrderingOnlyDependenciesTest {

    private LifecycleCoordinator coordinator;

    /** Minimal stub component; dependencies and ordering-only flag are configurable. */
    private static final class StubComponent implements LifecycleComponent {
        private final String name;
        private final AtomicReference<List<String>> deps;
        private final boolean orderingOnly;
        private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.CREATED);

        StubComponent(String name, List<String> deps, boolean orderingOnly) {
            this.name = name;
            this.deps = new AtomicReference<>(deps);
            this.orderingOnly = orderingOnly;
        }

        void setDependencies(List<String> newDeps) {
            deps.set(newDeps);
        }

        @Override
        public CompletableFuture<Void> start() {
            state.set(LifecycleState.RUNNING);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            state.set(LifecycleState.STOPPED);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public LifecycleState getState() {
            return state.get();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> dependencies() {
            return deps.get();
        }

        @Override
        public boolean dependenciesAreOrderingOnly() {
            return orderingOnly;
        }
    }

    @BeforeEach
    void setUp() {
        coordinator = new LifecycleCoordinator();
        coordinator.start();
    }

    @Test
    void orderingOnlyDependentDoesNotBlockStopAndUnregister() {
        var base = new StubComponent("base", List.of(), false);
        coordinator.registerAndStart(base);
        var watcher = new StubComponent("watcher", List.of("base"), true);
        coordinator.registerAndStart(watcher);

        assertThatCode(() -> coordinator.stopAndUnregister("base")).doesNotThrowAnyException();
        assertThat(coordinator.getState("base")).isNull();
        assertThat(coordinator.getState("watcher")).isEqualTo(LifecycleState.RUNNING);
    }

    @Test
    void livenessDependentStillBlocksStopAndUnregister() {
        var base = new StubComponent("base", List.of(), false);
        coordinator.registerAndStart(base);
        var dependent = new StubComponent("dependent", List.of("base"), false);
        coordinator.registerAndStart(dependent);

        assertThatThrownBy(() -> coordinator.stopAndUnregister("base"))
            .isInstanceOf(LifecycleException.class)
            .hasMessageContaining("depends on it");
    }

    @Test
    void computeLayersToleratesVanishedOrderingOnlyDependency() {
        // TOCTOU: an ordering-only dependency vanishing between the dependencies() snapshot and
        // computeLayers' existence check (concurrent stopAndUnregister during stop()) must be
        // skipped, not abort the whole coordinated shutdown. Mirrors the real adapter: empty
        // deps at registration, the dependency vanishes later.
        var watcher = new StubComponent("watcher", List.of(), true);
        coordinator.registerAndStart(watcher);
        watcher.setDependencies(List.of("vanished-component"));

        assertThatCode(() -> coordinator.computeLayers()).doesNotThrowAnyException();
        assertThatCode(() -> coordinator.stop(5_000)).doesNotThrowAnyException();
        assertThat(coordinator.getState("watcher")).isEqualTo(LifecycleState.STOPPED);
    }

    @Test
    void computeLayersStillFailsLoudForUnknownLivenessDependency() {
        var broken = new StubComponent("broken", List.of(), false);
        coordinator.registerAndStart(broken);
        broken.setDependencies(List.of("never-registered"));

        assertThatThrownBy(() -> coordinator.computeLayers())
            .isInstanceOf(LifecycleException.class)
            .hasMessageContaining("non-existent");
    }
}
