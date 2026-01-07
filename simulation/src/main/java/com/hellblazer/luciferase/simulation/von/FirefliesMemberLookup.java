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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Member discovery using Fireflies View.
 * <p>
 * In v4.0 architecture, Fireflies is used ONLY for initial member discovery
 * during JOIN. After JOIN completes, all communication is P2P via VonTransport.
 * <p>
 * This class wraps a Fireflies View and provides:
 * <ul>
 *   <li>Member lookup by Digest ID</li>
 *   <li>Member lookup by UUID (derived from Digest)</li>
 *   <li>List of all active members for initial contact</li>
 *   <li>Random member selection for JOIN contact point</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class FirefliesMemberLookup {

    private static final Logger log = LoggerFactory.getLogger(FirefliesMemberLookup.class);

    private final View view;
    private final Random random;

    /**
     * Create a FirefliesMemberLookup.
     *
     * @param view Fireflies View for member discovery
     */
    public FirefliesMemberLookup(View view) {
        this(view, new Random());
    }

    /**
     * Create a FirefliesMemberLookup with custom random source.
     *
     * @param view   Fireflies View for member discovery
     * @param random Random source for member selection
     */
    public FirefliesMemberLookup(View view, Random random) {
        this.view = view;
        this.random = random;
    }

    /**
     * Get a member by Digest ID.
     *
     * @param id Digest ID of the member
     * @return Member if found, empty if not in active context
     */
    public Optional<Member> getMember(Digest id) {
        var context = view.getContext();
        return Optional.ofNullable(context.getMember(id));
    }

    /**
     * Get a member by UUID.
     * <p>
     * Converts UUID to Digest and looks up in context.
     *
     * @param uuid UUID of the member
     * @return Member if found, empty if not in active context
     */
    public Optional<Member> getMemberByUuid(UUID uuid) {
        return getActiveMembers().stream()
                                 .filter(m -> digestToUuid(m.getId()).equals(uuid))
                                 .findFirst();
    }

    /**
     * Get all active members in the cluster.
     *
     * @return List of all active members
     */
    public List<Member> getActiveMembers() {
        var context = view.getContext();
        return StreamSupport.stream(context.allMembers().spliterator(), false)
                           .collect(Collectors.toList());
    }

    /**
     * Get a random active member for initial JOIN contact.
     * <p>
     * Excludes the local node (self).
     *
     * @return Random member if any available, empty if cluster is empty or only self
     */
    public Optional<Member> getRandomMember() {
        var members = getActiveMembers();
        var localId = view.getNode().getId();

        var others = members.stream()
                           .filter(m -> !m.getId().equals(localId))
                           .toList();

        if (others.isEmpty()) {
            return Optional.empty();
        }

        var index = random.nextInt(others.size());
        return Optional.of(others.get(index));
    }

    /**
     * Get the local node's member.
     *
     * @return Local member
     */
    public Member getLocalMember() {
        return view.getNode();
    }

    /**
     * Get the number of active members.
     *
     * @return Active member count
     */
    public int getActiveCount() {
        return view.getContext().activeCount();
    }

    /**
     * Check if the view has converged (all expected members active).
     *
     * @param expectedCount Expected number of members
     * @return true if active count matches expected
     */
    public boolean isConverged(int expectedCount) {
        return getActiveCount() == expectedCount;
    }

    /**
     * Convert a Delos Digest to a UUID.
     * <p>
     * Uses the first 16 bytes of the Digest to create a UUID.
     *
     * @param digest the Delos Digest
     * @return a UUID derived from the Digest
     */
    public static UUID digestToUuid(Digest digest) {
        var bytes = digest.getBytes();
        long msb = 0;
        long lsb = 0;

        for (int i = 0; i < 8 && i < bytes.length; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }

        for (int i = 8; i < 16 && i < bytes.length; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    /**
     * Get all member UUIDs.
     *
     * @return Set of UUIDs for all active members
     */
    public Set<UUID> getAllMemberUuids() {
        return getActiveMembers().stream()
                                 .map(m -> digestToUuid(m.getId()))
                                 .collect(Collectors.toSet());
    }
}
