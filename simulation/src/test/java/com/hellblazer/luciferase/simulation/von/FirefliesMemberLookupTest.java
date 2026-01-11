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

import com.hellblazer.luciferase.simulation.integration.TestClusterBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for FirefliesMemberLookup with real Fireflies cluster.
 *
 * Disabled in CI: This integration test uses TestClusterBuilder which is expensive
 * and times out on CI hardware. Developers can run locally with:
 * mvn test -Dtest=FirefliesMemberLookupTest
 *
 * @author hal.hildebrand
 */
@Tag("integration")
public class FirefliesMemberLookupTest {

    private TestClusterBuilder.TestCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    @Test
    void testGetActiveMembers_returnsAllMembers() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // When: Get active members
        var members = lookup.getActiveMembers();

        // Then: Should have all 4 members
        assertThat(members).hasSize(4);
    }

    @Test
    void testGetActiveCount_matchesClusterSize() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // When/Then: Active count matches cluster size
        assertThat(lookup.getActiveCount()).isEqualTo(4);
    }

    @Test
    void testIsConverged_trueWhenAllMembersActive() throws Exception {
        // Given: 4-node cluster that has converged
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // When/Then: Should be converged
        assertThat(lookup.isConverged(4)).isTrue();
        assertThat(lookup.isConverged(5)).isFalse();
    }

    @Test
    void testGetRandomMember_excludesSelf() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view, new Random(42));

        // When: Get random member multiple times
        var localId = view.getNode().getId();
        for (int i = 0; i < 10; i++) {
            var member = lookup.getRandomMember();
            // Then: Should not be self
            assertThat(member).isPresent();
            assertThat(member.get().getId()).isNotEqualTo(localId);
        }
    }

    @Test
    void testGetLocalMember_returnsSelf() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // When: Get local member
        var local = lookup.getLocalMember();

        // Then: Should match view's node
        assertThat(local.getId()).isEqualTo(view.getNode().getId());
    }

    @Test
    void testDigestToUuid_deterministicConversion() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var member = cluster.getMember(0);
        var digest = member.getId();

        // When: Convert to UUID twice
        var uuid1 = FirefliesMemberLookup.digestToUuid(digest);
        var uuid2 = FirefliesMemberLookup.digestToUuid(digest);

        // Then: Should be deterministic
        assertThat(uuid1).isEqualTo(uuid2);
    }

    @Test
    void testGetAllMemberUuids_returnsUniqueUuids() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // When: Get all UUIDs
        var uuids = lookup.getAllMemberUuids();

        // Then: Should have 4 unique UUIDs
        assertThat(uuids).hasSize(4);
    }

    @Test
    void testGetMemberByUuid_findsCorrectMember() throws Exception {
        // Given: 4-node cluster
        cluster = new TestClusterBuilder()
            .cardinality(4)
            .build();
        cluster.bootstrapAndStart(Duration.ofMillis(5), 30);

        var view = cluster.getView(0);
        var lookup = new FirefliesMemberLookup(view);

        // Get a member and its UUID
        var member = cluster.getMember(1);
        var uuid = FirefliesMemberLookup.digestToUuid(member.getId());

        // When: Look up by UUID
        var found = lookup.getMemberByUuid(uuid);

        // Then: Should find the same member
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(member.getId());
    }
}
