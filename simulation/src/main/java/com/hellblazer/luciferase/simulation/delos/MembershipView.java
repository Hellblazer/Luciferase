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

package com.hellblazer.luciferase.simulation.delos;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Abstraction for cluster membership view.
 * <p>
 * Provides access to current cluster members and notifications of membership changes.
 * Implementations may be backed by mock data for testing or real Fireflies views for production.
 *
 * @param <M> the member type
 * @author hal.hildebrand
 */
public interface MembershipView<M> {

    /**
     * Get a stream of all known cluster members.
     * <p>
     * Backed by the view's full membership (e.g. {@code DynamicContext.allMembers()}), which may
     * include members that have been evicted from the active view but not yet garbage-collected.
     *
     * @return stream of all known members
     */
    Stream<M> getMembers();

    /**
     * Get a stream of the <em>active</em> cluster members only.
     * <p>
     * Distinct from {@link #getMembers()}: this excludes members that are present in the view's
     * full membership set but no longer active (e.g. evicted-but-not-yet-GC'd). Backed by
     * {@code DynamicContext.active()} in the Fireflies implementation.
     * <p>
     * <b>Security-critical (RDR-005 / Luciferase-ah3).</b> Peer-identity authorization — e.g. the
     * {@code inCurrentView} predicate that gates gRPC mTLS peer verification — MUST be built from
     * this active-only set, never from {@link #getMembers()}. An evicted-but-not-GC'd node still
     * holds a cryptographically valid, KERL-committed certificate; admitting it via the full
     * membership set would let a node the view has already removed continue to authenticate.
     * <p>
     * <b>Production implementations backed by a real view MUST NOT delegate this to
     * {@link #getMembers()}</b> — they must consult the view's active-only set. Test doubles that
     * do not exercise the active/all distinction may return the same set as {@link #getMembers()},
     * but MUST document that the equivalence is intentional.
     *
     * @return stream of active members
     */
    Stream<M> activeMembers();

    /**
     * Register a listener for membership change events.
     *
     * @param listener consumer that receives ViewChange notifications
     */
    void addListener(Consumer<ViewChange<M>> listener);

    /**
     * Represents a change in cluster membership.
     *
     * @param joined  members that joined the cluster
     * @param left    members that left the cluster
     * @param <M>     the member type
     */
    record ViewChange<M>(java.util.List<M> joined, java.util.List<M> left) {
    }
}
