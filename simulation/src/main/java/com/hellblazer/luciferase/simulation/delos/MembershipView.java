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
     * Get a stream of current cluster members.
     *
     * @return stream of active members
     */
    Stream<M> getMembers();

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
