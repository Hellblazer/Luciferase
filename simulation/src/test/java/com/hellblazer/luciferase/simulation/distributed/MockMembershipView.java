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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mock MembershipView for testing (Phase 4.1.4).
 * <p>
 * Provides stub implementation of MembershipView that does nothing.
 * Real Fireflies integration would use FirefliesMembershipView.
 *
 * @param <M> member type
 * @author hal.hildebrand
 */
public class MockMembershipView<M> implements MembershipView<M> {
    private final List<Consumer<ViewChange<M>>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public Stream<M> getMembers() {
        return Stream.empty();  // No members in mock view
    }

    @Override
    public void addListener(Consumer<ViewChange<M>> listener) {
        listeners.add(listener);
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
