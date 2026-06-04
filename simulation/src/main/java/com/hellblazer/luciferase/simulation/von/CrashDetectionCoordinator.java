/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wires Fireflies view-change notifications to {@link LeaveProtocol#handleCrash(UUID, UUID)}
 * (Luciferase-0frcy.125).
 * <p>
 * Before this coordinator existed, {@code LeaveProtocol.handleCrash()} was a dead API: nothing in the
 * production path called it, so a crashed neighbor stayed permanently in the VON neighbor set and every
 * subsequent MOVE broadcast attempted delivery to a dead node. This coordinator registers a view-change
 * listener on a {@link FirefliesViewMonitor}; when Fireflies reports members departing the stable view
 * (the {@code left} set of a {@link MembershipView.ViewChange}), each departed member is mapped to its
 * VON node id and reported to {@code handleCrash} as a forced leave detected by the local node.
 * <p>
 * The member-to-UUID mapping is supplied by the caller because the membership member type is
 * application-specific (a Delos {@code Member}, a test stand-in, etc.). Crash dispatch is idempotent at
 * the {@link LeaveProtocol} layer: {@code handleCrash} no-ops when the node is no longer in the index.
 *
 * @param <M> membership member type carried by the {@link MembershipView}
 * @author hal.hildebrand
 */
public class CrashDetectionCoordinator<M> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrashDetectionCoordinator.class);

    private final FirefliesViewMonitor viewMonitor;
    private final LeaveProtocol leaveProtocol;
    private final UUID localNodeId;
    private final Function<M, UUID> memberToNodeId;
    private final Consumer<MembershipView.ViewChange<?>> listener;

    /**
     * Create and register a crash-detection coordinator.
     *
     * @param viewMonitor    monitor whose view-change notifications signal member departures
     * @param leaveProtocol  protocol whose {@link LeaveProtocol#handleCrash} is invoked per departure
     * @param localNodeId    this node's id, reported as the crash detector
     * @param memberToNodeId maps a departed membership member to its VON node id
     */
    public CrashDetectionCoordinator(FirefliesViewMonitor viewMonitor,
                                     LeaveProtocol leaveProtocol,
                                     UUID localNodeId,
                                     Function<M, UUID> memberToNodeId) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor cannot be null");
        this.leaveProtocol = Objects.requireNonNull(leaveProtocol, "leaveProtocol cannot be null");
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId cannot be null");
        this.memberToNodeId = Objects.requireNonNull(memberToNodeId, "memberToNodeId cannot be null");
        this.listener = this::onViewChange;
        viewMonitor.addViewChangeListener(listener);
        log.debug("CrashDetectionCoordinator registered for node {}", localNodeId);
    }

    @SuppressWarnings("unchecked")
    private void onViewChange(MembershipView.ViewChange<?> change) {
        for (var member : change.left()) {
            UUID crashedId;
            try {
                crashedId = memberToNodeId.apply((M) member);
            } catch (RuntimeException e) {
                log.warn("Could not map departed member {} to a node id; skipping crash dispatch", member, e);
                continue;
            }
            if (crashedId == null || crashedId.equals(localNodeId)) {
                continue;
            }
            log.debug("Fireflies view-change: member {} departed; reporting crash to LeaveProtocol", crashedId);
            leaveProtocol.handleCrash(crashedId, localNodeId);
        }
    }

    /**
     * Deregister the view-change listener.
     */
    @Override
    public void close() {
        viewMonitor.removeViewChangeListener(listener);
    }
}
