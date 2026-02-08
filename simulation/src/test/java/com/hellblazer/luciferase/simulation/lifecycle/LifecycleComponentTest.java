/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LifecycleComponent interface contract.
 * Uses a simple mock implementation to verify the interface behavior.
 *
 * @author hal.hildebrand
 */
class LifecycleComponentTest {

    /**
     * Simple mock implementation for testing the interface contract.
     */
    static class MockLifecycleComponent implements LifecycleComponent {
        private LifecycleState state = LifecycleState.CREATED;
        private final String componentName;
        private final List<String> componentDependencies;

        MockLifecycleComponent(String name, List<String> dependencies) {
            this.componentName = name;
            this.componentDependencies = new ArrayList<>(dependencies);
        }

        @Override
        public CompletableFuture<Void> start() {
            if (state != LifecycleState.CREATED && state != LifecycleState.STOPPED) {
                return CompletableFuture.failedFuture(
                    new LifecycleException("Cannot start from state: " + state)
                );
            }
            state = LifecycleState.STARTING;
            return CompletableFuture.runAsync(() -> {
                // Simulate async startup
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                state = LifecycleState.RUNNING;
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            if (state != LifecycleState.RUNNING) {
                return CompletableFuture.failedFuture(
                    new LifecycleException("Cannot stop from state: " + state)
                );
            }
            state = LifecycleState.STOPPING;
            return CompletableFuture.runAsync(() -> {
                // Simulate async shutdown
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                state = LifecycleState.STOPPED;
            });
        }

        @Override
        public LifecycleState getState() {
            return state;
        }

        @Override
        public String name() {
            return componentName;
        }

        @Override
        public List<String> dependencies() {
            return new ArrayList<>(componentDependencies);
        }
    }

    @Test
    void testInterfaceContract() {
        var component = new MockLifecycleComponent("test-component", List.of());

        // Verify all required methods are callable
        assertNotNull(component.name(), "name() should return non-null");
        assertNotNull(component.dependencies(), "dependencies() should return non-null");
        assertNotNull(component.getState(), "getState() should return non-null");
        assertNotNull(component.start(), "start() should return CompletableFuture");
    }

    @Test
    void testStartReturnsFuture() throws Exception {
        var component = new MockLifecycleComponent("test-component", List.of());

        var startFuture = component.start();
        assertNotNull(startFuture, "start() should return non-null CompletableFuture");

        // Verify future completes
        startFuture.get(1, TimeUnit.SECONDS);
        assertEquals(LifecycleState.RUNNING, component.getState(),
            "Component should be RUNNING after start completes");
    }

    @Test
    void testStopReturnsFuture() throws Exception {
        var component = new MockLifecycleComponent("test-component", List.of());

        // Start component first
        component.start().get(1, TimeUnit.SECONDS);
        assertEquals(LifecycleState.RUNNING, component.getState());

        // Now stop it
        var stopFuture = component.stop();
        assertNotNull(stopFuture, "stop() should return non-null CompletableFuture");

        // Verify future completes
        stopFuture.get(1, TimeUnit.SECONDS);
        assertEquals(LifecycleState.STOPPED, component.getState(),
            "Component should be STOPPED after stop completes");
    }

    @Test
    void testDependenciesReturnsModifiableList() {
        var dependencies = new ArrayList<String>();
        dependencies.add("dep1");
        dependencies.add("dep2");

        var component = new MockLifecycleComponent("test-component", dependencies);

        var deps = component.dependencies();
        assertNotNull(deps, "dependencies() should return non-null");
        assertEquals(2, deps.size(), "Should have 2 dependencies");
        assertTrue(deps.contains("dep1"), "Should contain dep1");
        assertTrue(deps.contains("dep2"), "Should contain dep2");

        // Verify we can modify the returned list without affecting component
        deps.add("dep3");
        assertEquals(3, deps.size(), "Modified list should have 3 elements");
        assertEquals(2, component.dependencies().size(),
            "Original component dependencies should be unchanged");
    }

    @Test
    void testNameReturnsCorrectValue() {
        var component = new MockLifecycleComponent("my-component", List.of());
        assertEquals("my-component", component.name(),
            "name() should return the component name");
    }

    @Test
    void testGetStateReturnsCurrentState() {
        var component = new MockLifecycleComponent("test-component", List.of());
        assertEquals(LifecycleState.CREATED, component.getState(),
            "New component should be in CREATED state");
    }

    @Test
    void testAsyncStartStopLifecycle() throws Exception {
        var component = new MockLifecycleComponent("test-component", List.of());

        // Initial state
        assertEquals(LifecycleState.CREATED, component.getState());

        // Start component
        var startFuture = component.start();
        // State should transition through STARTING
        assertTrue(component.getState() == LifecycleState.STARTING ||
                   component.getState() == LifecycleState.RUNNING,
            "State should be STARTING or RUNNING during start");
        startFuture.get(1, TimeUnit.SECONDS);
        assertEquals(LifecycleState.RUNNING, component.getState());

        // Stop component
        var stopFuture = component.stop();
        // State should transition through STOPPING
        assertTrue(component.getState() == LifecycleState.STOPPING ||
                   component.getState() == LifecycleState.STOPPED,
            "State should be STOPPING or STOPPED during stop");
        stopFuture.get(1, TimeUnit.SECONDS);
        assertEquals(LifecycleState.STOPPED, component.getState());
    }
}
