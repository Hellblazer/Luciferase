/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

/**
 * MigrationStateListener - Observer for EntityMigrationStateMachine State Transitions (Phase 7D Day 1)
 *
 * Allows external components (e.g., MigrationCoordinator for 2PC integration) to observe
 * entity state changes in the FSM. This interface creates the foundation for bridging
 * the FSM with the CrossProcessMigration 2PC protocol.
 *
 * SYNCHRONOUS EXECUTION:
 * - Listener callbacks are invoked SYNCHRONOUSLY during transition() and onViewChange()
 * - Listeners MUST be fast (< 1ms per call) to avoid blocking FSM operations
 * - Listeners should queue work for background processing rather than performing heavy operations
 *
 * THREAD SAFETY:
 * - Callbacks may be invoked from multiple threads if FSM operations are concurrent
 * - Listeners MUST be thread-safe
 * - Listeners SHOULD NOT modify entity states directly (risk of deadlock/infinite recursion)
 *
 * USAGE:
 * <pre>
 *   class MigrationCoordinator implements MigrationStateListener {
 *       {@literal @}Override
 *       public void onEntityStateTransition(Object entityId,
 *                                          EntityMigrationState fromState,
 *                                          EntityMigrationState toState,
 *                                          TransitionResult result) {
 *           if (result.success() && toState == MIGRATING_OUT) {
 *               // Queue 2PC PREPARE for entity migration
 *               executor.submit(() -> sendPrepare(entityId));
 *           }
 *       }
 *
 *       {@literal @}Override
 *       public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
 *           log.warn("View change rolled back {} migrations, {} ghosts", rolledBackCount, ghostCount);
 *       }
 *   }
 *
 *   fsm.addListener(new MigrationCoordinator());
 * </pre>
 *
 * DESIGN RATIONALE:
 * - Observer pattern enables loose coupling between FSM and 2PC coordinator
 * - MigrationCoordinator (Day 2) will implement this to bridge FSM↔2PC
 * - Synchronous notifications ensure FSM state is consistent when listener observes it
 * - Aggregated rollback notifications reduce event chatter (one per view change, not per entity)
 *
 * @author hal.hildebrand
 * @see EntityMigrationStateMachine
 * @see EntityMigrationState
 */
public interface MigrationStateListener {

    /**
     * Called when an entity transitions to a new state.
     *
     * Invoked SYNCHRONOUSLY during transition() - called for both successful
     * and failed transitions to provide complete visibility into FSM behavior.
     *
     * PERFORMANCE: This method MUST execute quickly (< 1ms).
     * For heavy operations, queue work for background processing:
     * <pre>
     *   executor.submit(() -> processTransition(entityId, toState));
     * </pre>
     *
     * THREAD SAFETY: May be called concurrently if multiple threads invoke transition().
     * Implementation must be thread-safe.
     *
     * @param entityId Entity changing state (never null)
     * @param fromState Previous state (null if entity not found in FSM)
     * @param toState New state (never null)
     * @param result Transition result (success/blocked/invalid) with reason (never null)
     */
    void onEntityStateTransition(Object entityId,
                                 EntityMigrationState fromState,
                                 EntityMigrationState toState,
                                 EntityMigrationStateMachine.TransitionResult result);

    /**
     * Called when a view change triggers rollbacks.
     *
     * Invoked SYNCHRONOUSLY during onViewChange() after all entity state updates complete.
     * Provides aggregate counts to reduce notification overhead (one call per view change).
     *
     * ROLLBACK SEMANTICS:
     * - rolledBackCount: Entities transitioned MIGRATING_OUT → ROLLBACK_OWNED
     * - ghostCount: Entities transitioned MIGRATING_IN → GHOST
     *
     * PERFORMANCE: This method MUST execute quickly (< 1ms).
     *
     * THREAD SAFETY: View change operations are typically single-threaded,
     * but implementations should still be thread-safe.
     *
     * @param rolledBackCount Number of entities rolled back to ROLLBACK_OWNED (≥0)
     * @param ghostCount Number of entities converted to GHOST (≥0)
     */
    void onViewChangeRollback(int rolledBackCount, int ghostCount);
}
