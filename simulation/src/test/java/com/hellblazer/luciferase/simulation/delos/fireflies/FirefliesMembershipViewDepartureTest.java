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
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Production-departure regression for {@link FirefliesMembershipView} (Luciferase-0frcy.36).
 *
 * <p>Pre-fix, {@code handleDelosViewChange} resolved leaving Digests via
 * {@code delosChange.context().getMember(digest)} — but {@code context()} is the NEW post-change
 * context, which no longer contains the departed member, so {@code getMember} returns {@code null}
 * and the {@code filter(m -> m != null)} silently dropped every departure. {@code ViewChange.left}
 * was therefore always empty in production. The existing happy-path test masked this by stubbing
 * {@code getMember(leftDigest)} to return a member (as if the leaver were still present).
 *
 * <p>This test reproduces production faithfully: the leaving member is NOT resolvable from the new
 * context ({@code getMember} returns {@code null}); it was only ever seen in a prior join. The fix
 * resolves departures from the previous-view cache, so {@code left} is correctly populated.
 *
 * @author hal.hildebrand
 */
class FirefliesMembershipViewDepartureTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Consumer<ViewChange> captureDelosListener(View mockView) {
        var captor = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(mockView).register(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void departedMemberIsReportedInLeftEvenWhenAbsentFromNewContext() {
        var mockView = mock(View.class);
        var mockContext = mock(DynamicContext.class);
        when(mockView.getContext()).thenReturn(mockContext);
        when(mockContext.allMembers()).thenReturn(Stream.empty());

        var adapter = new FirefliesMembershipView(mockView);
        var delosListener = captureDelosListener(mockView);

        var changes = new ArrayList<MembershipView.ViewChange<Member>>();
        adapter.addListener(changes::add);

        var member = mock(Member.class);
        var digest = mock(Digest.class);
        when(member.getId()).thenReturn(digest);

        // --- View change 1: member JOINS (present in the new context). ---
        when(mockContext.getMember(digest)).thenReturn(member);
        delosListener.accept(new ViewChange(mockContext, mock(Digest.class),
                                            List.of(digest), List.of()));

        // --- View change 2: member LEAVES. Production reality: it is NO LONGER in the new
        // context, so getMember(digest) now returns null. ---
        when(mockContext.getMember(digest)).thenReturn(null);
        delosListener.accept(new ViewChange(mockContext, mock(Digest.class),
                                            List.of(), List.of(digest)));

        assertThat(changes).hasSize(2);

        var join = changes.get(0);
        assertThat(join.joined()).containsExactly(member);
        assertThat(join.left()).isEmpty();

        var leave = changes.get(1);
        assertThat(leave.left())
            .as("A departed member MUST appear in ViewChange.left even though it is absent from "
                + "the new post-change context (resolved from the previous-view cache)")
            .containsExactly(member);
        assertThat(leave.joined()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void departureResolvedFromContextFallbackWhenStillResolvable() {
        // Defensive fallback: if the leaving member happens to still be resolvable from the new
        // context (and was never cached via a join), it is still reported in left.
        var mockView = mock(View.class);
        var mockContext = mock(DynamicContext.class);
        when(mockView.getContext()).thenReturn(mockContext);

        var adapter = new FirefliesMembershipView(mockView);
        var delosListener = captureDelosListener(mockView);

        var changes = new ArrayList<MembershipView.ViewChange<Member>>();
        adapter.addListener(changes::add);

        var member = mock(Member.class);
        var digest = mock(Digest.class);
        when(mockContext.getMember(digest)).thenReturn(member);

        delosListener.accept(new ViewChange(mockContext, mock(Digest.class),
                                            List.of(), List.of(digest)));

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).left()).containsExactly(member);
    }
}
