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

package com.hellblazer.luciferase.simulation.delos.mock;

import com.hellblazer.luciferase.simulation.delos.MembershipView;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mock implementation of MembershipView for testing.
 * <p>
 * Provides programmatic control over cluster membership simulation.
 * Allows tests to add/remove members and verify listener notifications
 * without requiring real Fireflies infrastructure.
 *
 * @param <M> the member type
 * @author hal.hildebrand
 */
public class MockFirefliesView<M> implements MembershipView<M> {

    private final Set<M>                          members   = ConcurrentHashMap.newKeySet();
    private final List<Consumer<ViewChange<M>>>  listeners = new CopyOnWriteArrayList<>();

    /**
     * Add a member to the view and notify listeners.
     *
     * @param member the member to add
     */
    public void addMember(M member) {
        if (members.add(member)) {
            notifyListeners(new ViewChange<>(List.of(member), List.of()));
        }
    }

    /**
     * Remove a member from the view and notify listeners.
     *
     * @param member the member to remove
     */
    public void removeMember(M member) {
        if (members.remove(member)) {
            notifyListeners(new ViewChange<>(List.of(), List.of(member)));
        }
    }

    @Override
    public Stream<M> getMembers() {
        return members.stream();
    }

    @Override
    public void addListener(Consumer<ViewChange<M>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners(ViewChange<M> change) {
        listeners.forEach(listener -> listener.accept(change));
    }
}
