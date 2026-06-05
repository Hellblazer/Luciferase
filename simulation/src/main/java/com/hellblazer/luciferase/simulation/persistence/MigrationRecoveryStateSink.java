/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.persistence;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Concrete {@link RecoveryStateSink} that reconstructs {@link EntityMigrationStateMachine} state
 * from replayed WAL events during crash recovery.
 *
 * <p>Event-to-state mapping (mirrors the normal forward-path transitions):
 * <ul>
 *   <li>{@code ENTITY_DEPARTURE} — entity left this node; force state to
 *       {@link EntityMigrationState#MIGRATING_OUT}.  If a subsequent
 *       {@code MIGRATION_COMMIT} is also replayed, the entity will be moved to
 *       {@link EntityMigrationState#DEPARTED} (see below).</li>
 *   <li>{@code MIGRATION_COMMIT} — migration completed; force state to
 *       {@link EntityMigrationState#DEPARTED}.</li>
 *   <li>{@code DEFERRED_UPDATE} — positional update replayed; no FSM state change (the
 *       entity store is updated when the live consumer is wired in).</li>
 *   <li>{@code VIEW_SYNC_ACK} — ack replayed; no FSM state change (view membership is
 *       re-established on node startup, not during WAL replay).</li>
 * </ul>
 *
 * <p>Both {@code ENTITY_DEPARTURE} and {@code MIGRATION_COMMIT} use
 * {@link EntityMigrationStateMachine#recoverEntityState} which bypasses the normal
 * view-stability guard — appropriate because recovery happens before the view is formed.
 *
 * <p>The seam is wired in the adapter/factory that constructs {@link PersistenceManager}:
 * <pre>
 *   var fsm = new EntityMigrationStateMachine(viewMonitor);
 *   var sink = new MigrationRecoveryStateSink(fsm);
 *   var mgr = new PersistenceManager(nodeId, logDir, sink);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class MigrationRecoveryStateSink implements RecoveryStateSink {

    private static final Logger log = LoggerFactory.getLogger(MigrationRecoveryStateSink.class);

    private final EntityMigrationStateMachine fsm;

    /**
     * Create a sink that reconstructs state into the given FSM.
     *
     * @param fsm migration state machine to mutate during recovery (must not be null)
     */
    public MigrationRecoveryStateSink(EntityMigrationStateMachine fsm) {
        this.fsm = Objects.requireNonNull(fsm, "fsm must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forces the entity to {@link EntityMigrationState#MIGRATING_OUT}, indicating that when
     * the node crashed, the entity had started its outbound migration but the commit had not yet
     * been confirmed.  If a {@code MIGRATION_COMMIT} for the same entity is also present in the
     * WAL, it will be replayed next and will overwrite this state with
     * {@link EntityMigrationState#DEPARTED}.
     */
    @Override
    public void onEntityDeparture(String entityId, String sourceBubble, String targetBubble) {
        log.debug("Recovery: ENTITY_DEPARTURE for {}, source={}, target={}", entityId, sourceBubble, targetBubble);
        fsm.recoverEntityState(entityId, EntityMigrationState.MIGRATING_OUT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forces the entity to {@link EntityMigrationState#DEPARTED}.  This supersedes any prior
     * {@code MIGRATING_OUT} state set by a previously replayed {@code ENTITY_DEPARTURE} event.
     */
    @Override
    public void onMigrationCommit(String entityId) {
        log.debug("Recovery: MIGRATION_COMMIT for {}", entityId);
        fsm.recoverEntityState(entityId, EntityMigrationState.DEPARTED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Positional deferred-updates are handled by the entity store consumer, which is wired
     * at node-startup time (not yet available during WAL replay).  This implementation logs the
     * event so that the replayed position/velocity values are visible in the recovery log.
     * When the live entity-store consumer is plumbed in, this method should be extended to
     * forward the update.
     */
    @Override
    public void onDeferredUpdate(String entityId, float[] position, float[] velocity) {
        // FSM carries no per-entity position; the entity store consumer handles this.
        // Logged so that missing positions are visible in the recovery trace.
        if (log.isDebugEnabled()) {
            log.debug("Recovery: DEFERRED_UPDATE for {} pos={} vel={}",
                    entityId,
                    position != null ? java.util.Arrays.toString(position) : "null",
                    velocity != null ? java.util.Arrays.toString(velocity) : "null");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>View-synchrony acks represent membership acknowledgements for a prior view epoch.
     * The membership view is re-established fresh on node startup; replaying this event does not
     * alter the current view state.
     */
    @Override
    public void onViewSynchronyAck(String entityId, boolean success) {
        log.debug("Recovery: VIEW_SYNC_ACK for {} success={}", entityId, success);
    }

    /**
     * Test-only entry point: calls {@link EntityMigrationStateMachine#recoverEntityState} via a
     * lambda to exercise the filter-based caller guard from this class's declaring context.
     *
     * <p>This proves that {@code enforceRecoveryCaller} is robust to synthetic lambda frames: the
     * JVM pushes a bridge frame for the lambda, but the filter skips all
     * {@code EntityMigrationStateMachine} frames and stops at the first external frame, whose
     * {@code getDeclaringClass()} returns {@code MigrationRecoveryStateSink} — the permitted
     * caller — so no {@link IllegalCallerException} is thrown.
     *
     * <p>Package-private. Called only from {@code MigrationRecoveryStateSinkTest}.
     */
    void recoverViaLambdaForTest(Object entityId, EntityMigrationState state) {
        Runnable r = () -> fsm.recoverEntityState(entityId, state);
        r.run();
    }
}
