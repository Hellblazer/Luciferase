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

package com.hellblazer.luciferase.simulation.consensus.ownership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.von.FirefliesMemberLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Production {@link BubbleOwnershipResolver} backed by a live Fireflies cluster.
 * <p>
 * Integrates four components via narrow injected seams, following the project's
 * constructor-injection / interfaces-not-concretes convention:
 * <ul>
 *   <li>{@code localMemberSupplier} — returns this node's own {@link Member} for
 *       {@link #localMember()}.</li>
 *   <li>{@code nodeResolver} — maps a node {@link UUID} to its optional {@link Member}
 *       for {@link #memberDigestForNode(UUID)}.</li>
 *   <li>{@code bubbleKeyResolver} — maps a bubble {@link UUID} to its {@link TetreeKey}
 *       (or {@code null} if not in grid) for {@link #resolveOwningMember(UUID)}.</li>
 *   <li>{@link MembershipView}{@code <Member>} — for the <em>active-only</em> member set
 *       ({@code activeMembers()}). This MUST NOT be substituted with
 *       {@code FirefliesMemberLookup.getActiveMembers()}, which is misnamed and delegates
 *       to {@code context.allMembers()} — see RDR-020 B4 / RDR-005.</li>
 *   <li>{@link SpatialOwnershipFunction} — the HRW ownership computation.</li>
 * </ul>
 * <p>
 * The narrow-seam constructor is the primary constructor for testability.
 * A convenience constructor adapts a {@link FirefliesMemberLookup} and
 * {@link TetreeBubbleGrid} to those seams for production use.
 * <p>
 * All methods fail loud ({@link IllegalStateException}) on unresolvable inputs so that
 * callers never receive a silently-rejectable digest (RDR-020 Gap 2 / vhbw3 wave-20 requirement).
 */
public final class FirefliesBubbleOwnershipResolver implements BubbleOwnershipResolver {

    private static final Logger log = LoggerFactory.getLogger(FirefliesBubbleOwnershipResolver.class);

    private final Supplier<Member>                 localMemberSupplier;
    private final Function<UUID, Optional<Member>> nodeResolver;
    private final Function<UUID, TetreeKey<?>>     bubbleKeyResolver;
    private final MembershipView<Member>           membershipView;
    private final SpatialOwnershipFunction         ownershipFunction;

    /**
     * Primary (narrow-seam) constructor.  Constructor-injects three narrow function seams,
     * making the class testable without a live Fireflies View or a populated
     * {@link TetreeBubbleGrid}.
     *
     * @param localMemberSupplier  returns this node's {@link Member}; must not be {@code null}
     * @param nodeResolver         maps a node {@link UUID} to its optional {@link Member};
     *                             must not be {@code null}
     * @param bubbleKeyResolver    maps a bubble {@link UUID} to its {@link TetreeKey}, or
     *                             {@code null} if not present; must not be {@code null}
     * @param membershipView       the membership view providing the active-only member stream;
     *                             must not be {@code null}
     * @param ownershipFunction    the HRW (or other) deterministic ownership function;
     *                             must not be {@code null}
     */
    public FirefliesBubbleOwnershipResolver(Supplier<Member> localMemberSupplier,
                                            Function<UUID, Optional<Member>> nodeResolver,
                                            Function<UUID, TetreeKey<?>> bubbleKeyResolver,
                                            MembershipView<Member> membershipView,
                                            SpatialOwnershipFunction ownershipFunction) {
        this.localMemberSupplier = Objects.requireNonNull(localMemberSupplier,
                                                          "localMemberSupplier must not be null");
        this.nodeResolver = Objects.requireNonNull(nodeResolver, "nodeResolver must not be null");
        this.bubbleKeyResolver = Objects.requireNonNull(bubbleKeyResolver, "bubbleKeyResolver must not be null");
        this.membershipView = Objects.requireNonNull(membershipView, "membershipView must not be null");
        this.ownershipFunction = Objects.requireNonNull(ownershipFunction, "ownershipFunction must not be null");
    }

    /**
     * Convenience constructor that adapts a {@link FirefliesMemberLookup} and a
     * {@link TetreeBubbleGrid} to the narrow function seams.  Wired as:
     * <pre>
     *   localMemberSupplier = memberLookup::getLocalMember
     *   nodeResolver        = memberLookup::getMemberByUuid
     *   bubbleKeyResolver   = bubbleGrid::getKeyForBubble
     * </pre>
     *
     * @param memberLookup        the Fireflies member lookup (local member + node UUID mapping)
     * @param membershipView      the membership view providing the active-only member stream
     * @param bubbleGrid          the bubble grid for key resolution
     * @param ownershipFunction   the HRW (or other) deterministic ownership function
     */
    public FirefliesBubbleOwnershipResolver(FirefliesMemberLookup memberLookup,
                                            MembershipView<Member> membershipView,
                                            TetreeBubbleGrid bubbleGrid,
                                            SpatialOwnershipFunction ownershipFunction) {
        this(
            Objects.requireNonNull(memberLookup, "memberLookup must not be null")::getLocalMember,
            memberLookup::getMemberByUuid,
            Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null")::getKeyForBubble,
            membershipView,
            ownershipFunction
        );
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolution:
     * <ol>
     *   <li>Resolve {@code bubbleId} → {@code TetreeKey} via the bubble-key resolver.</li>
     *   <li>Collect the <em>active-only</em> member digests via
     *       {@code membershipView.activeMembers()} (RDR-005 — not the misnamed
     *       {@code FirefliesMemberLookup.getActiveMembers()}).</li>
     *   <li>Delegate to {@link SpatialOwnershipFunction#owner}.</li>
     *   <li>Verify the result is in the active set (defensive; the HRW function guarantees
     *       this invariant, but we fail loud on any violation).</li>
     * </ol>
     * <p>
     * Note: the active set is a snapshot read at call time. A view transition between this call and
     * the moment the resulting proposal reaches {@code validateProposal}/{@code isNodeInView} can make
     * the returned owner stale — the consensus layer's view-ID binding is the authoritative guard and
     * rejects such a proposal visibly (never a mis-route). This two-read TOCTOU is a best-effort
     * boundary, not an atomic guarantee.
     */
    @Override
    public Digest resolveOwningMember(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");

        var key = bubbleKeyResolver.apply(bubbleId);
        if (key == null) {
            throw new IllegalStateException(
                "Cannot resolve owning member: bubble not in grid: " + bubbleId);
        }

        // Active-only set per RDR-005 / B4. Never use FirefliesMemberLookup.getActiveMembers()
        // (misnamed — it returns allMembers()). Map Member → Digest.
        List<Digest> activeDigests = membershipView.activeMembers()
                                                   .map(Member::getId)
                                                   .toList();

        log.debug("Resolving owner for bubble {} (key={}) across {} active members",
                  bubbleId, key, activeDigests.size());

        // HRW throws IllegalStateException when activeDigests is empty — propagates as-is.
        var owner = ownershipFunction.owner(key, activeDigests);

        // Defensive: verify the result is in the active set (should always hold by construction)
        if (!activeDigests.contains(owner)) {
            throw new IllegalStateException(
                "HRW returned a digest not in the active member set — " +
                "ownership function contract violated for bubble " + bubbleId);
        }
        return owner;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns this node's {@link Member#getId()} via the injected {@code localMemberSupplier}.
     */
    @Override
    public Digest localMember() {
        return localMemberSupplier.get().getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Uses the injected {@code nodeResolver} to map the node UUID to its {@link Member}, then
     * returns {@link Member#getId()}.  Fail-loud if no member is found.
     * <p>
     * Note: when wired to {@link FirefliesMemberLookup#getMemberByUuid}, this is built on the
     * misnamed {@code getActiveMembers()} (all-members backing) — this is acceptable here
     * because this method is used only for validating an explicit node-UUID hint, not for
     * determining ownership; the active-only gate is enforced in
     * {@link #resolveOwningMember(UUID)}.
     */
    @Override
    public Digest memberDigestForNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return nodeResolver.apply(nodeId)
                           .map(Member::getId)
                           .orElseThrow(() -> new IllegalStateException(
                               "No Fireflies member found for node UUID: " + nodeId));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Tests {@code member} against the <em>active-only</em> set ({@code membershipView.activeMembers()},
     * RDR-005) — never the misnamed all-members backing.
     */
    @Override
    public boolean isActiveMember(Digest member) {
        Objects.requireNonNull(member, "member must not be null");
        return membershipView.activeMembers()
                             .map(Member::getId)
                             .anyMatch(member::equals);
    }
}
