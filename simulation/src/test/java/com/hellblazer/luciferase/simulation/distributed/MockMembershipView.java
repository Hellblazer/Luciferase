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
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.delos.MembershipView;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mock MembershipView for testing (Phase 4.1.4).
 * <p>
 * Provides controllable implementation of MembershipView for testing.
 * Allows setting members and automatically generates view change events.
 * Real Fireflies integration would use FirefliesMembershipView.
 *
 * @param <M> member type
 * @author hal.hildebrand
 */
public class MockMembershipView<M> implements MembershipView<M> {
    private final List<Consumer<ViewChange<M>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Set<M> currentMembers = new HashSet<>();

    @Override
    public Stream<M> getMembers() {
        return currentMembers.stream();
    }

    @Override
    public void addListener(Consumer<ViewChange<M>> listener) {
        listeners.add(listener);
    }

    /**
     * Set the members in the view and generate view change events.
     * <p>
     * Automatically calculates joined and left members and notifies listeners.
     *
     * @param newMembers the new set of members
     */
    public void setMembers(Set<M> newMembers) {
        var previous = currentMembers;
        currentMembers = new HashSet<>(newMembers);

        // Calculate joined and left
        var joined = new HashSet<>(newMembers);
        joined.removeAll(previous);

        var left = new HashSet<>(previous);
        left.removeAll(newMembers);

        // Notify listeners if there were changes
        if (!joined.isEmpty() || !left.isEmpty()) {
            var change = new ViewChange<>(List.copyOf(joined), List.copyOf(left));
            listeners.forEach(listener -> listener.accept(change));
        }
    }

    /**
     * Simulate a view change (for testing).
     *
     * @param change the simulated view change
     */
    public void simulateViewChange(ViewChange<M> change) {
        listeners.forEach(listener -> listener.accept(change));
    }
}
