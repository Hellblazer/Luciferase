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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
public class FirefliesMembershipView implements MembershipView<Member>, AutoCloseable {

    private final View                               view;
    /**
     * Stable reference to the Delos view-change listener so {@link #close()} can deregister it.
     * Without this, the listener (which captures {@code this}) is a permanent GC root from the
     * long-lived Delos View, leaking every FirefliesMembershipView ever created (Luciferase-zwyf2).
     */
    private final Consumer<com.hellblazer.delos.context.ViewChange> delosListener = this::handleDelosViewChange;
    private final List<Consumer<ViewChange<Member>>> listeners = new CopyOnWriteArrayList<>();
    // Luciferase-0frcy.36: Digest→Member cache of previously-seen members. A leaving member is no
    // longer present in the NEW post-change DynamicContext, so it cannot be resolved via
    // context.getMember(digest). We resolve leaving Digests from this cache (the previous-view
    // snapshot) instead, otherwise ViewChange.left is always empty in production.
    private final Map<Digest, Member>                knownMembers = new ConcurrentHashMap<>();

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
        view.register(listenerKey, delosListener);
    }

    /**
     * Deregister the Delos view-change listener so this adapter can be garbage-collected and stops
     * receiving notifications. Idempotent. Callers own the lifecycle and must close when done
     * (Luciferase-zwyf2).
     */
    @Override
    public void close() {
        view.deregister(delosListener);
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
    @SuppressWarnings("unchecked")
    public Stream<Member> activeMembers() {
        // active() excludes evicted-but-not-yet-GC'd members that allMembers() still returns.
        // Authorization gates MUST use this set, not getMembers() — see MembershipView javadoc
        // (RDR-005 / Luciferase-ah3). Same Participant-extends-Member cast as getMembers().
        var context = view.getContext();
        return (Stream<Member>) (Stream<? extends Member>) context.active();
    }

    @Override
    public void addListener(Consumer<ViewChange<Member>> listener) {
        listeners.add(listener);
    }

    /**
     * Get current view ID (diadem) for proposal tagging.
     * <p>
     * Phase 7G Day 1: Committee Consensus integration.
     * This ID is used to prevent double-commit race conditions across view boundaries.
     *
     * @return Current view context ID
     */
    public Digest getCurrentViewId() {
        return view.getContext().getId();
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

        // Convert joining Digests to Members from the NEW context (joiners ARE present there).
        // Cache each joiner so a future departure can be resolved after it leaves the context.
        var joinedMembers = new ArrayList<Member>();
        for (var digest : delosChange.joining()) {
            var member = (Member) context.getMember(digest);
            if (member != null) {
                joinedMembers.add(member);
                // Cache by the leaving Digest key. Prefer the member's own id, but fall back to
                // the join Digest so departures still resolve even if getId() is unavailable.
                var key = member.getId() != null ? member.getId() : digest;
                knownMembers.put(key, member);
            }
        }

        // Luciferase-0frcy.36: resolve leaving Digests from the previous-view cache, NOT from the
        // new context (which no longer contains them — that was the bug that made `left` always
        // empty). Remove each resolved member from the cache after teardown.
        var leftMembers = new ArrayList<Member>();
        for (var digest : delosChange.leaving()) {
            var member = knownMembers.remove(digest);
            if (member == null) {
                // Fall back to the new context in case the member is somehow still resolvable.
                member = (Member) context.getMember(digest);
            }
            if (member != null) {
                leftMembers.add(member);
            }
        }

        // Notify all our listeners
        var change = new ViewChange<Member>(joinedMembers, leftMembers);
        listeners.forEach(listener -> listener.accept(change));
    }
}
