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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.luciferase.simulation.delos.GossipAdapter;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Integrated Delos cluster node combining:
 * <ul>
 *   <li>Fireflies {@link View} for membership management</li>
 *   <li>{@link FirefliesMembershipView} for membership tracking</li>
 *   <li>{@link DelosGossipAdapter} for application messaging</li>
 * </ul>
 * <p>
 * This is the main integration point for VON/Simulation with Delos.
 * Each spatial simulation node wraps a DelosClusterNode for cluster
 * communication and coordination.
 *
 * @author hal.hildebrand
 */
public class DelosClusterNode {

    private static final Logger log = LoggerFactory.getLogger(DelosClusterNode.class);

    private final View                      view;
    private final ControlledIdentifierMember member;
    private final FirefliesMembershipView   membershipView;
    private final DelosGossipAdapter        gossipAdapter;
    private final Digest                    nodeId;
    private final UUID                      nodeUuid;

    /**
     * Create a DelosClusterNode from an existing View and member.
     *
     * @param view   the Fireflies View
     * @param member the controlled identifier member
     */
    public DelosClusterNode(View view, ControlledIdentifierMember member) {
        this.view = view;
        this.member = member;
        this.nodeId = member.getId();
        this.nodeUuid = digestToUuid(nodeId);
        this.membershipView = new FirefliesMembershipView(view);
        this.gossipAdapter = new DelosGossipAdapter(view, member);

        log.info("Created DelosClusterNode: {} (UUID: {})", nodeId, nodeUuid);
    }

    /**
     * Get the Fireflies View.
     */
    public View getView() {
        return view;
    }

    /**
     * Get the member identity.
     */
    public ControlledIdentifierMember getMember() {
        return member;
    }

    /**
     * Get the node's Digest ID.
     */
    public Digest getNodeId() {
        return nodeId;
    }

    /**
     * Get the node's UUID (derived from Digest).
     * <p>
     * This UUID can be used for application-level identification
     * where UUID is expected (e.g., GossipAdapter messages).
     */
    public UUID getNodeUuid() {
        return nodeUuid;
    }

    /**
     * Get the membership view for tracking cluster members.
     */
    public MembershipView<Member> getMembershipView() {
        return membershipView;
    }

    /**
     * Get the gossip adapter for application messaging.
     */
    public GossipAdapter getGossipAdapter() {
        return gossipAdapter;
    }

    /**
     * Get the underlying DelosGossipAdapter for cluster-level operations.
     */
    public DelosGossipAdapter getDelosGossipAdapter() {
        return gossipAdapter;
    }

    /**
     * Check if the view is active (has active members).
     */
    public boolean isActive() {
        return view.getContext().activeCount() > 0;
    }

    /**
     * Get the number of active members in the view.
     */
    public int getActiveCount() {
        return view.getContext().activeCount();
    }

    /**
     * Convert a Delos Digest to a UUID.
     * <p>
     * Uses the first 16 bytes of the Digest to create a UUID.
     * This provides a stable mapping from Digest to UUID for
     * application-level identification.
     *
     * @param digest the Delos Digest
     * @return a UUID derived from the Digest
     */
    public static UUID digestToUuid(Digest digest) {
        var bytes = digest.getBytes();
        long msb = 0;
        long lsb = 0;

        // Use first 8 bytes for MSB
        for (int i = 0; i < 8 && i < bytes.length; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }

        // Use next 8 bytes for LSB
        for (int i = 8; i < 16 && i < bytes.length; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    @Override
    public String toString() {
        return String.format("DelosClusterNode[%s, active=%d]",
                             nodeId, getActiveCount());
    }
}
