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
    private final Set<M>                          inactive  = ConcurrentHashMap.newKeySet();
    private final List<Consumer<ViewChange<M>>>  listeners = new CopyOnWriteArrayList<>();

    /**
     * Add a member to the view and notify listeners.
     *
     * @param member the member to add
     */
    public void addMember(M member) {
        inactive.remove(member);
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
        inactive.remove(member);
        if (members.remove(member)) {
            notifyListeners(new ViewChange<>(List.of(), List.of(member)));
        }
    }

    /**
     * Mark a member as inactive without removing it from the known-membership set, modelling the
     * "evicted-but-not-yet-GC'd" state: the member is still returned by {@link #getMembers()} but
     * excluded from {@link #activeMembers()}. Lets tests exercise the active-vs-all authorization
     * distinction (RDR-005 / Luciferase-ah3). No listener notification — eviction signalling is the
     * job of {@link #removeMember}.
     *
     * @param member the member to mark inactive (must already be known via {@link #addMember})
     */
    public void markInactive(M member) {
        if (!members.contains(member)) {
            throw new IllegalArgumentException(
                "Cannot mark unknown member inactive (call addMember first): " + member);
        }
        inactive.add(member);
    }

    @Override
    public Stream<M> getMembers() {
        return members.stream();
    }

    @Override
    public Stream<M> activeMembers() {
        return members.stream().filter(m -> !inactive.contains(m));
    }

    @Override
    public void addListener(Consumer<ViewChange<M>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners(ViewChange<M> change) {
        listeners.forEach(listener -> listener.accept(change));
    }
}
