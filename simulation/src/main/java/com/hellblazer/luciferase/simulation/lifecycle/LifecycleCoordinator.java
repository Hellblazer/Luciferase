/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates lifecycle operations across multiple components with dependency ordering.
 * <p>
 * Uses Kahn's topological sort to compute dependency layers:
 * <ul>
 *   <li>Startup: Process layers 0→N sequentially, components within layer in parallel</li>
 *   <li>Shutdown: Process layers N→0 sequentially (reverse order)</li>
 *   <li>Circular dependencies: Detected and rejected with LifecycleException</li>
 * </ul>
 * <p>
 * Thread-safe for concurrent registration and lifecycle operations.
 * Start/stop operations are idempotent.
 *
 * @author hal.hildebrand
 */
public class LifecycleCoordinator {
    private static final Logger log = LoggerFactory.getLogger(LifecycleCoordinator.class);

    private final ConcurrentHashMap<String, LifecycleComponent> components;
    private final ConcurrentHashMap<String, LifecycleState> states;
    private final AtomicBoolean isStarted;
    private final ViewStabilityGate viewStabilityGate;  // Optional Fireflies integration

    /**
     * Create a new lifecycle coordinator without Fireflies integration.
     */
    public LifecycleCoordinator() {
        this(null);
    }

    /**
     * Create a new lifecycle coordinator with Fireflies view stability integration.
     * <p>
     * When a ViewStabilityGate is provided, shutdown will wait for view stability
     * before closing components, ensuring all messages sent within the current view
     * are delivered (Fireflies virtual synchrony guarantee).
     *
     * @param viewStabilityGate optional gate for view stability checking (null for no Fireflies integration)
     */
    public LifecycleCoordinator(ViewStabilityGate viewStabilityGate) {
        this.components = new ConcurrentHashMap<>();
        this.states = new ConcurrentHashMap<>();
        this.isStarted = new AtomicBoolean(false);
        this.viewStabilityGate = viewStabilityGate;

        if (viewStabilityGate != null) {
            log.debug("LifecycleCoordinator created with Fireflies view stability integration");
        }
    }

    /**
     * Register a component for lifecycle management.
     * <p>
     * Component must not be already registered.
     *
     * @param component the component to register
     * @throws LifecycleException if component already registered
     */
    public void register(LifecycleComponent component) {
        var name = component.name();
        if (components.putIfAbsent(name, component) != null) {
            throw new LifecycleException("Component already registered: " + name);
        }
        states.put(name, component.getState());
        log.debug("Registered component: {}", name);
    }

    /**
     * Unregister a component from lifecycle management.
     * <p>
     * Component must be in STOPPED or CREATED state.
     *
     * @param componentName the name of the component to unregister
     * @throws LifecycleException if component is not stopped
     */
    public void unregister(String componentName) {
        var component = components.get(componentName);
        if (component == null) {
            return;
        }

        var state = component.getState();
        if (state != LifecycleState.STOPPED && state != LifecycleState.CREATED) {
            throw new LifecycleException("Cannot unregister component in state: " + state);
        }

        components.remove(componentName);
        states.remove(componentName);
        log.debug("Unregistered component: {}", componentName);
    }

    /**
     * Start all registered components in dependency order.
     * <p>
     * Uses Kahn's algorithm to compute layers, starts each layer in parallel.
     * If any component fails, stops the startup process and throws exception.
     * <p>
     * Idempotent: If already started, returns immediately.
     *
     * @throws LifecycleException if circular dependency detected or component fails to start
     */
    public void start() {
        // Idempotent: already started
        if (!isStarted.compareAndSet(false, true)) {
            log.debug("start() called but already started - idempotent no-op");
            return;
        }

        log.info("Starting lifecycle coordinator with {} components", components.size());

        try {
            // Compute dependency layers using Kahn's algorithm
            var layers = computeLayers();

            // Start each layer sequentially (0→N)
            for (int i = 0; i < layers.size(); i++) {
                var layer = layers.get(i);
                log.debug("Starting layer {} with {} components: {}", i, layer.size(), layer);

                // Start all components in this layer in parallel
                var futures = layer.stream()
                                   .map(name -> {
                                       var component = components.get(name);
                                       try {
                                           return component.start()
                                                           .whenComplete((v, ex) -> {
                                                               if (ex != null) {
                                                                   log.error("Component {} failed to start", name, ex);
                                                                   states.put(name, LifecycleState.FAILED);
                                                               } else {
                                                                   states.put(name, component.getState());
                                                               }
                                                           });
                                       } catch (Exception e) {
                                           log.error("Exception starting component {}", name, e);
                                           states.put(name, LifecycleState.FAILED);
                                           return CompletableFuture.<Void>failedFuture(e);
                                       }
                                   })
                                   .toList();

                // Wait for all components in this layer to complete (with timeout)
                try {
                    // 5 seconds per component to prevent indefinite blocking
                    var layerTimeout = layer.size() * 5000L;
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(layerTimeout, TimeUnit.MILLISECONDS)
                        .join();
                } catch (Exception e) {
                    isStarted.set(false); // Reset on failure
                    log.error("Startup failed at layer {}, rolling back {} previously started layers", i, i);

                    // Rollback: Stop already-started layers in reverse order
                    for (int rollback = i - 1; rollback >= 0; rollback--) {
                        var rollbackLayer = layers.get(rollback);
                        log.info("Rolling back layer {}: {}", rollback, rollbackLayer);
                        try {
                            stopLayer(rollbackLayer, 5000); // 5s timeout per layer rollback

                            // Force states map update after layer rollback to ensure test visibility
                            for (String compName : rollbackLayer) {
                                var comp = components.get(compName);
                                if (comp != null) {
                                    states.put(compName, comp.getState());
                                }
                            }
                        } catch (Exception rollbackError) {
                            log.error("Rollback failed for layer {}", rollback, rollbackError);
                            // Continue with remaining rollback despite error
                        }
                    }

                    throw new LifecycleException("Failed to start layer " + i + ": " + layer, e);
                }
            }

            log.info("All components started successfully");

        } catch (LifecycleException e) {
            isStarted.set(false);
            throw e;
        } catch (Exception e) {
            isStarted.set(false);
            throw new LifecycleException("Unexpected error during startup", e);
        }
    }

    /**
     * Stop all registered components in reverse dependency order.
     * <p>
     * Processes layers N→0 sequentially, components within layer in parallel.
     * Gracefully handles timeouts - continues with remaining components.
     * <p>
     * Idempotent: If already stopped, returns immediately.
     *
     * @param timeoutMs total timeout in milliseconds for entire shutdown
     * @throws LifecycleException if shutdown fails
     */
    public void stop(long timeoutMs) {
        // Idempotent: already stopped
        if (!isStarted.compareAndSet(true, false)) {
            log.debug("stop() called but already stopped - idempotent no-op");
            return;
        }

        log.info("Stopping lifecycle coordinator with {} components, timeout: {}ms", components.size(), timeoutMs);

        try {
            // Phase 4: Wait for Fireflies view stability (if configured)
            if (viewStabilityGate != null) {
                log.debug("Waiting for Fireflies view stability before shutdown");
                try {
                    viewStabilityGate.awaitStability()
                        .get(5, TimeUnit.SECONDS);  // 5s timeout for view stability
                    log.info("View stable, proceeding with shutdown");
                } catch (java.util.concurrent.TimeoutException e) {
                    log.warn("View stability timeout after 5s, proceeding with shutdown anyway (graceful degradation)");
                } catch (Exception e) {
                    log.warn("View stability check failed, proceeding with shutdown anyway", e);
                }
            }

            // Compute dependency layers
            var layers = computeLayers();

            // Calculate per-component timeout
            var totalComponents = layers.stream().mapToLong(List::size).sum();
            var perComponentTimeout = totalComponents > 0 ? timeoutMs / totalComponents : timeoutMs;

            // Stop each layer sequentially in reverse order (N→0)
            for (int i = layers.size() - 1; i >= 0; i--) {
                var layer = layers.get(i);
                log.debug("Stopping layer {} with {} components: {}", i, layer.size(), layer);

                // Stop all components in this layer in parallel
                var futures = layer.stream()
                                   .filter(name -> {
                                       var state = states.get(name);
                                       return state == LifecycleState.RUNNING;
                                   })
                                   .map(name -> {
                                       var component = components.get(name);
                                       try {
                                           return component.stop()
                                                           .orTimeout(perComponentTimeout, TimeUnit.MILLISECONDS)
                                                           .whenComplete((v, ex) -> {
                                                               if (ex != null) {
                                                                   log.warn("Component {} stop timeout or error", name,
                                                                            ex);
                                                                   // Don't set FAILED on timeout - allow graceful continuation
                                                               } else {
                                                                   states.put(name, component.getState());
                                                               }
                                                           })
                                                           .exceptionally(ex -> {
                                                               // Graceful degradation on timeout
                                                               return null;
                                                           });
                                       } catch (Exception e) {
                                           log.error("Exception stopping component {}", name, e);
                                           return CompletableFuture.completedFuture(null);
                                       }
                                   })
                                   .toList();

                // Wait for all components in this layer (with timeout per component)
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    log.warn("Layer {} stop encountered errors (continuing with remaining layers)", i, e);
                    // Continue with next layer even if this layer had issues
                }
            }

            log.info("All components stopped");

        } catch (Exception e) {
            throw new LifecycleException("Unexpected error during shutdown", e);
        }
    }

    /**
     * Get the current lifecycle state of a component.
     *
     * @param componentName the component name
     * @return current state, or null if component not registered
     */
    public LifecycleState getState(String componentName) {
        return states.get(componentName);
    }

    /**
     * Compute dependency layers using Kahn's topological sort algorithm.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Count in-degrees (number of dependencies per component)</li>
     *   <li>Queue components with 0 in-degree (Layer 0 - no dependencies)</li>
     *   <li>Process each layer:
     *     <ul>
     *       <li>Dequeue all components at current layer</li>
     *       <li>Add to result layer</li>
     *       <li>Decrement in-degree of their dependents</li>
     *       <li>Enqueue dependents with 0 in-degree (next layer)</li>
     *     </ul>
     *   </li>
     *   <li>If components remain with non-zero in-degree → circular dependency</li>
     * </ol>
     *
     * @return list of layers, each layer is a list of component names
     * @throws LifecycleException if circular dependency detected
     */
    private List<List<String>> computeLayers() {
        var layers = new ArrayList<List<String>>();

        // Build in-degree map (count of dependencies for each component)
        var inDegree = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>(); // reverse mapping: who depends on me

        for (var component : components.values()) {
            var name = component.name();
            inDegree.putIfAbsent(name, 0);

            for (var depName : component.dependencies()) {
                // Validate dependency exists
                if (!components.containsKey(depName)) {
                    throw new LifecycleException(
                    "Component " + name + " depends on non-existent component: " + depName);
                }

                // Increment in-degree for this component
                inDegree.put(name, inDegree.getOrDefault(name, 0) + 1);

                // Track reverse dependency
                dependents.computeIfAbsent(depName, k -> new ArrayList<>()).add(name);
            }
        }

        // Layer 0: Components with no dependencies (in-degree = 0)
        var currentLayer = new ArrayList<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                currentLayer.add(entry.getKey());
            }
        }

        // Process layers using Kahn's algorithm
        var processed = new HashSet<String>();

        while (!currentLayer.isEmpty()) {
            layers.add(new ArrayList<>(currentLayer));
            processed.addAll(currentLayer);

            var nextLayer = new ArrayList<String>();

            // For each component in current layer
            for (var name : currentLayer) {
                // Decrement in-degree of dependents
                var deps = dependents.getOrDefault(name, List.of());
                for (var dependent : deps) {
                    var newInDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newInDegree);

                    // If in-degree becomes 0, add to next layer
                    if (newInDegree == 0) {
                        nextLayer.add(dependent);
                    }
                }
            }

            currentLayer = nextLayer;
        }

        // Check for circular dependencies
        if (processed.size() != components.size()) {
            var unprocessed = new ArrayList<String>();
            for (var name : components.keySet()) {
                if (!processed.contains(name)) {
                    unprocessed.add(name);
                }
            }
            throw new LifecycleException("Circular dependency detected among components: " + unprocessed);
        }

        log.debug("Computed {} layers: {}", layers.size(), layers);
        return layers;
    }

    /**
     * Stop all components in a single layer.
     * <p>
     * Helper method for rollback during startup failures.
     * Gracefully handles errors in individual component stops - logs and continues.
     *
     * @param layer list of component names to stop
     * @param timeoutMs timeout for the entire layer
     */
    private void stopLayer(List<String> layer, long timeoutMs) {
        var perComponentTimeout = layer.isEmpty() ? timeoutMs : timeoutMs / layer.size();

        var componentsToStop = layer.stream()
            .map(name -> components.get(name))
            .filter(Objects::nonNull)
            .filter(comp -> {
                var state = comp.getState();
                // During rollback, stop components that are STARTING or RUNNING
                return state == LifecycleState.STARTING || state == LifecycleState.RUNNING;
            })
            .toList();

        var futures = componentsToStop.stream()
            .map(comp -> comp.stop()
                .orTimeout(perComponentTimeout, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("Component {} stop failed during rollback: {}", comp.name(), ex.getMessage());
                    return null;
                }))
            .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // Small delay to ensure all async state transitions complete
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            // Update states map after all stop futures complete
            for (var comp : componentsToStop) {
                states.put(comp.name(), comp.getState());
            }
        } catch (Exception e) {
            log.warn("stopLayer encountered errors (continuing)", e);
        }
    }
}
