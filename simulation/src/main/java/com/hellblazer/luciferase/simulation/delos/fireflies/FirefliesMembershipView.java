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

package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.MembershipView;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Real Fireflies-based implementation of MembershipView.
 * <p>
 * Wraps a Delos Fireflies View to provide cluster membership information
 * and notifications. Adapts Delos ViewChange events (using Digests) to
 * our MembershipView.ViewChange format (using Members).
 *
 * @author hal.hildebrand
 */
public class FirefliesMembershipView implements MembershipView<Member> {

    private final View                               view;
    private final List<Consumer<ViewChange<Member>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Create a new FirefliesMembershipView wrapping a Delos Fireflies View.
     *
     * @param view the Delos Fireflies View to wrap
     */
    public FirefliesMembershipView(View view) {
        this.view = view;

        // Register with Delos View to receive ViewChange notifications
        // Use a unique key based on this instance
        var listenerKey = "FirefliesMembershipView-" + UUID.randomUUID();
        view.register(listenerKey, this::handleDelosViewChange);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<Member> getMembers() {
        // Get the DynamicContext from the View
        // The context contains Participants which extend Member
        // We cast the stream directly rather than individual elements
        var context = view.getContext();
        return (Stream<Member>) (Stream<? extends Member>) context.allMembers();
    }

    @Override
    public void addListener(Consumer<ViewChange<Member>> listener) {
        listeners.add(listener);
    }

    /**
     * Handle ViewChange notifications from Delos Fireflies.
     * <p>
     * Converts Delos ViewChange (using Digests) to our ViewChange format (using Members).
     *
     * @param delosChange the ViewChange from Delos
     */
    @SuppressWarnings("unchecked")
    private void handleDelosViewChange(com.hellblazer.delos.context.ViewChange delosChange) {
        // Context is DynamicContext<Participant> where Participant implements Member
        var context = delosChange.context();

        // Convert joining Digests to Members
        // Participants extend Member, so we cast the list
        var joinedMembers = (List<Member>) (List<? extends Member>) delosChange.joining()
                                       .stream()
                                       .map(context::getMember)
                                       .filter(m -> m != null)
                                       .toList();

        // Convert leaving Digests to Members
        var leftMembers = (List<Member>) (List<? extends Member>) delosChange.leaving()
                                     .stream()
                                     .map(context::getMember)
                                     .filter(m -> m != null)
                                     .toList();

        // Notify all our listeners
        var change = new ViewChange<>(joinedMembers, leftMembers);
        listeners.forEach(listener -> listener.accept(change));
    }
}
