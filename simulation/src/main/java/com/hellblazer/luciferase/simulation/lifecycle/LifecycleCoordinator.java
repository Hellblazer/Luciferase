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
 *
 * <p>Uses Kahn's topological sort to compute dependency layers:
 * <ul>
 *   <li>Startup: Process layers 0→N sequentially, components within layer in parallel</li>
 *   <li>Shutdown: Process layers N→0 sequentially (reverse order)</li>
 *   <li>Circular dependencies: Detected and rejected with {@link LifecycleException}</li>
 * </ul>
 *
 * <h2>Thread-Safety Guarantees and Constraints</h2>
 *
 * <h3>Coordinator Operations (What the Coordinator Provides)</h3>
 * <ul>
 *   <li>All public methods are thread-safe for concurrent access from multiple threads</li>
 *   <li>{@link ConcurrentHashMap} provides atomic map operations for registration/unregistration</li>
 *   <li>{@link #start()} and {@link #stop(long)} are idempotent via {@link java.util.concurrent.atomic.AtomicBoolean} guards</li>
 *   <li>{@link #unregister(String)} synchronizes on components to prevent state-change races</li>
 *   <li>{@link #stopAndUnregister(String)} synchronizes on component during state check,
 *       preventing concurrent state changes during stop operation</li>
 *   <li>{@link #register(LifecycleComponent)} and {@link #registerAndStart(LifecycleComponent)} use
 *       atomic {@code computeIfAbsent} to prevent dual-map inconsistencies</li>
 * </ul>
 *
 * <h3>Component Implementation Requirements (What Components Must Provide)</h3>
 * <ul>
 *   <li><b>Memory Visibility</b>: State storage MUST use {@code volatile} fields or
 *       {@link java.util.concurrent.atomic} classes to ensure cross-thread visibility.
 *       See {@link LifecycleComponent} javadoc for correct patterns.</li>
 *   <li><b>No Re-entrant Calls</b>: {@link LifecycleComponent#getState()} MUST NOT call back into
 *       the coordinator. The coordinator holds internal locks while querying component state.
 *       Re-entrant calls create lock cycles causing deadlock.</li>
 *   <li><b>State Transition Visibility</b>: State changes in {@link LifecycleComponent#start()} and
 *       {@link LifecycleComponent#stop()} MUST be visible to concurrent {@code getState()} calls
 *       without additional synchronization.</li>
 * </ul>
 *
 * <h3>Caller Responsibilities (How to Use the Coordinator Safely)</h3>
 * <ul>
 *   <li><b>Avoid Concurrent Lifecycle Changes</b>: Do NOT call {@link #unregister(String)} and
 *       {@link #registerAndStart(LifecycleComponent)} concurrently for the same component.
 *       While registerAndStart marks state as STARTING to reduce risk, external synchronization
 *       is recommended if these operations may race.</li>
 *   <li><b>Coordinate Start Operations</b>: Do NOT call {@link #unregister(String)} while another
 *       thread may call {@link LifecycleComponent#start()} directly on the same component.
 *       Use the coordinator's start methods or provide external synchronization.</li>
 *   <li><b>Choose Registration Pattern</b>: Use {@link #register(LifecycleComponent)} followed by
 *       {@link #start()} OR use {@link #registerAndStart(LifecycleComponent)}, but not both
 *       concurrently for the same component.</li>
 * </ul>
 *
 * <h3>Known Limitations</h3>
 * <ul>
 *   <li><b>State Check Atomicity</b>: While {@link #unregister(String)} synchronizes on the component
 *       to check state, there remains a small window where external state changes (outside coordinator
 *       control) could cause unexpected behavior. Components should primarily use coordinator methods
 *       for state transitions.</li>
 *   <li><b>Deadlock Detection</b>: No automatic detection of component callback deadlocks. Component
 *       implementations must follow the no-callback contract documented in {@link LifecycleComponent}.</li>
 *   <li><b>Dependency Resolution</b>: Dependencies are resolved at {@link #start()} time only.
 *       Components added via {@link #registerAndStart(LifecycleComponent)} after coordinator has
 *       started will have dependencies checked at registration but not re-validated against the
 *       existing dependency graph.</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>
 * // Create coordinator
 * var coordinator = new LifecycleCoordinator();
 *
 * // Register components with dependencies
 * var database = new DatabaseComponent();
 * var cache = new CacheComponent();
 * cache.addDependency(database.name());  // Cache depends on Database
 *
 * coordinator.register(database);
 * coordinator.register(cache);
 *
 * // Start all (database starts first due to dependency)
 * coordinator.start();
 *
 * // Later: graceful shutdown (cache stops first)
 * coordinator.stop(5000);  // 5 second timeout
 * </pre>
 *
 * @author hal.hildebrand
 * @see LifecycleComponent
 * @see LifecycleState
 */
public class LifecycleCoordinator {
    private static final Logger log = LoggerFactory.getLogger(LifecycleCoordinator.class);
    private static final ThreadLocal<Boolean> inCoordinatorCall = ThreadLocal.withInitial(() -> false);

    final ConcurrentHashMap<String, LifecycleComponent> components;  // Package-private for Test 27 dual-map race detection
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
     * Check for re-entrant calls from component callbacks.
     * <p>
     * Re-entrant calls occur when a component's getState() callback calls back into
     * the coordinator while the coordinator holds internal locks. This creates
     * lock cycles leading to deadlock.
     * <p>
     * Components must follow the no-callback contract documented in {@link LifecycleComponent}.
     *
     * @throws IllegalStateException if re-entrant call detected
     */
    private void checkReentrancy() {
        if (inCoordinatorCall.get()) {
            throw new IllegalStateException(
                "Re-entrant coordinator call detected - component called back into " +
                "coordinator from getState(). This violates the thread-safety contract " +
                "and will cause deadlock. See LifecycleComponent javadoc.");
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
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            var name = component.name();
            // FIX Issue #3 (Luciferase-qxy6): Use atomic pattern to prevent dual-map race
            // Same pattern as registerAndStart() to ensure components and states updated atomically
            var result = components.computeIfAbsent(name, k -> {
                // Use actual component state (typically CREATED) rather than STARTING sentinel.
                // Unlike registerAndStart(), this component won't start immediately,
                // so no need to block concurrent unregister() with STARTING marker.
                states.put(name, component.getState());
                log.debug("Registered component: {}", name);
                return component;
            });
            if (result != component) {
                throw new LifecycleException("Component already registered: " + name);
            }
        } finally {
            inCoordinatorCall.set(false);
        }
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
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            // FIX Issue #1 (Luciferase-7q6q): Use computeIfPresent for atomic check-and-remove
            // FIX Issue #1 (Luciferase-bo26): Synchronize on component to prevent state-change race
            // Previous: check-then-act race between get() and remove()
            components.computeIfPresent(componentName, (name, comp) -> {
                synchronized (comp) {  // Prevent concurrent state changes during check
                    var state = comp.getState();
                    // FIX Luciferase-vt8d: Defensive null check for contract violation
                    if (state == null) {
                        throw new LifecycleException(
                            "Component " + name + " violated contract: getState() returned null");
                    }
                    if (state != LifecycleState.STOPPED && state != LifecycleState.CREATED) {
                        throw new LifecycleException("Cannot unregister component in state: " + state);
                    }
                    states.remove(name);
                    log.debug("Unregistered component: {}", name);
                    return null; // Remove from map
                }
            });
        } finally {
            inCoordinatorCall.set(false);
        }
    }

    /**
     * Register and optionally start a component.
     * <p>
     * If the coordinator is already started, the component is started immediately.
     * If not started, the component is only registered and will start when coordinator.start() is called.
     * <p>
     * Dependencies must already be registered and satisfied.
     * <p>
     * FIX Luciferase-1k4s: Component state set to STARTING during registration (not actual component state)
     * to prevent unregister() race. This marks intent to start and prevents removal during startup.
     *
     * @param component the component to register and start
     * @throws LifecycleException if component already registered or dependencies not satisfied
     */
    public void registerAndStart(LifecycleComponent component) {
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            var name = component.name();

            // FIX Issue #2 (Luciferase-7q6q): Use computeIfAbsent for atomic registration
            // FIX Luciferase-1k4s: Set state to STARTING in atomic block to prevent unregister race
            // FIX Luciferase-3nio: Move dependency validation inside atomic block to prevent TOCTOU race
            // Previous: validation happened outside atomic block, allowing dependency removal between check and registration
            var result = components.computeIfAbsent(name, k -> {
                // Validate dependencies exist NOW, inside atomic block
                for (var depName : component.dependencies()) {
                    if (!components.containsKey(depName)) {
                        throw new LifecycleException(
                            "Component " + name + " depends on non-existent component: " + depName);
                    }
                }
                // Mark as STARTING immediately to block unregister() during start operation
                // This is intentional deviation from component's actual state (which is CREATED)
                states.put(name, LifecycleState.STARTING);
                log.debug("Registered component with dependencies: {}", name);
                return component;
            });
            if (result != component) {
                throw new LifecycleException("Component already registered: " + name);
            }

            // If coordinator is started, start this component immediately
            if (isStarted.get()) {
                try {
                    component.start()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((v, ex) -> {
                            if (ex != null) {
                                log.error("Component {} failed to start", name, ex);
                                states.put(name, LifecycleState.FAILED);
                            } else {
                                states.put(name, component.getState());
                            }
                        })
                        .join(); // Wait for completion
                    log.debug("Started component immediately: {}", name);
                } catch (Exception e) {
                    states.put(name, LifecycleState.FAILED);
                    throw new LifecycleException("Failed to start component: " + name, e);
                }
            } else {
                // Coordinator not started - update state to actual component state
                states.put(name, component.getState());
            }
        } finally {
            inCoordinatorCall.set(false);
        }
    }

    /**
     * Stop and unregister a component.
     * <p>
     * If the component is RUNNING, it is stopped first.
     * Then the component is unregistered from lifecycle management.
     * <p>
     * This method checks if any other components depend on this one and throws if so.
     * This prevents removing components that are dependencies of others.
     *
     * @param componentName the name of the component to stop and remove
     * @throws LifecycleException if other components depend on this one
     */
    public void stopAndUnregister(String componentName) {
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            var component = components.get(componentName);
            if (component == null) {
                log.debug("Component {} not found - no-op", componentName);
                return;
            }

            // CRITICAL: Check if any component depends on this one
            for (var comp : components.values()) {
                if (comp.dependencies().contains(componentName)) {
                    throw new LifecycleException(
                        "Cannot remove " + componentName + " - " + comp.name() + " depends on it");
                }
            }

            // Stop component if it's running
            // FIX Luciferase-3f3o: Synchronize on component to prevent state-change race
            // Same pattern as unregister() to ensure atomic state check
            synchronized (component) {  // Prevent concurrent state changes during check
                var state = component.getState();
                // FIX Luciferase-vt8d: Defensive null check for contract violation
                if (state == null) {
                    throw new LifecycleException(
                        "Component " + componentName + " violated contract: getState() returned null");
                }

                // STARTING state: registerAndStart() uses .join() which blocks until
                // component reaches RUNNING or FAILED, so STARTING is transient and
                // rarely observed here. If encountered, treat as CREATED/STOPPED.
                if (state == LifecycleState.RUNNING) {
                    // FIX Issue #3 (Luciferase-7q6q): Only unregister on successful stop
                    // Previous: component removed even if stop() failed
                    try {
                        component.stop()
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();
                        states.put(componentName, component.getState());
                        log.debug("Stopped component: {}", componentName);
                } catch (Exception e) {
                    // Distinguish timeout from other errors for better diagnostics
                    if (e instanceof java.util.concurrent.TimeoutException ||
                        (e.getCause() instanceof java.util.concurrent.TimeoutException)) {
                        log.error("Component {} stop timed out after 5s, keeping in coordinator",
                                  componentName, e);
                    } else {
                        log.error("Component {} stop failed with exception, keeping in coordinator",
                                  componentName, e);
                    }
                    states.put(componentName, LifecycleState.FAILED);
                    throw new LifecycleException("Cannot unregister component that failed to stop", e);
                }
            }
            // STOPPED/CREATED/FAILED: Just remove without stop
            }  // End synchronized block (Luciferase-3f3o fix)

            // Unregister the component (only reached if stop succeeded or component wasn't running)
            components.remove(componentName);
            states.remove(componentName);
            log.debug("Unregistered component: {}", componentName);
        } finally {
            inCoordinatorCall.set(false);
        }
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
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            // Idempotent: already started
            if (!isStarted.compareAndSet(false, true)) {
                log.debug("start() called but already started - idempotent no-op");
                return;
            }

            log.info("Starting lifecycle coordinator with {} components", components.size());
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

                    // Track rollback failures for complete error reporting
                    // Fix for Luciferase-b8al: report which components failed rollback
                    java.util.List<String> rollbackFailures = new java.util.ArrayList<>();

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
                            String failure = String.format("Layer %d (%s): %s",
                                rollback,
                                String.join(", ", rollbackLayer),
                                rollbackError.getMessage()
                            );
                            rollbackFailures.add(failure);
                            log.error("Rollback failed for layer {}", rollback, rollbackError);
                            // Continue with remaining rollback despite error
                        }
                    }

                    // Include rollback failures in exception for complete error context
                    String errorMsg = "Failed to start layer " + i + ": " + layer;
                    if (!rollbackFailures.isEmpty()) {
                        errorMsg += "; Rollback also failed: " + String.join("; ", rollbackFailures);
                    }
                    throw new LifecycleException(errorMsg, e);
                }
            }

            log.info("All components started successfully");

        } catch (LifecycleException e) {
            isStarted.set(false);
            throw e;
        } catch (Exception e) {
            isStarted.set(false);
            throw new LifecycleException("Unexpected error during startup", e);
        } finally {
            inCoordinatorCall.set(false);
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
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            // Idempotent: already stopped
            if (!isStarted.compareAndSet(true, false)) {
                log.debug("stop() called but already stopped - idempotent no-op");
                return;
            }

            log.info("Stopping lifecycle coordinator with {} components, timeout: {}ms", components.size(), timeoutMs);
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
        } finally {
            inCoordinatorCall.set(false);
        }
    }

    /**
     * Get the current lifecycle state of a component.
     *
     * @param componentName the component name
     * @return current state, or null if component not registered
     */
    public LifecycleState getState(String componentName) {
        checkReentrancy();
        inCoordinatorCall.set(true);
        try {
            return states.get(componentName);
        } finally {
            inCoordinatorCall.set(false);
        }
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
            // Update states immediately - components use volatile/atomic per contract
            for (var comp : componentsToStop) {
                states.put(comp.name(), comp.getState());
            }
        } catch (Exception e) {
            log.warn("stopLayer encountered errors (continuing)", e);
        }
    }
}
