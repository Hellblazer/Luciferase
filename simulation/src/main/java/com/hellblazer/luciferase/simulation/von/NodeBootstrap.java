/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Production node bootstrap — composes the distributed simulation node (RDR-017).
 * <p>
 * RDR-017 Q0 is decided: the node {@code main} lives in this repo and {@code simulation} owns the
 * production node assembly. This class is the entry point and the assembly seam.
 * <p>
 * <b>Phase 0 (Luciferase-vhhu0) — this class:</b>
 * <ul>
 *   <li>resolves {@code nodeId} from the Fireflies member via {@link #resolveNodeId(Digest)}
 *       ({@code FirefliesMemberLookup.digestToUuid} — gate C1, the canonical member&rarr;UUID
 *       derivation; deterministic across restarts, which WAL recovery depends on);</li>
 *   <li>derives the per-node WAL directory {@code .luciferase/wal/<nodeId>/} via
 *       {@link #walDir(Path, UUID)};</li>
 *   <li>assembles the lifecycle graph via {@link #assemble} — {@code SocketConnectionManagerAdapter}
 *       and {@code PersistenceManagerAdapter} at Layer 0, bubbles at Layer 1 depending on
 *       {@code PersistenceManager}.</li>
 * </ul>
 * <b>Deferred to later phases:</b> calling {@code PersistenceManager.recover()} fail-loud and relocating
 * its schedulers ({@code PersistenceManagerAdapter.doStart()}) is P1 (Luciferase-pf1iu); injecting the WAL
 * into {@code BubbleMigrator} and the durability round-trip is P2 (Luciferase-1693b). Live Fireflies view
 * construction (and therefore a fully self-starting {@link #main}) lands with the persistence wiring in P1.
 *
 * @author hal.hildebrand
 */
public final class NodeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NodeBootstrap.class);

    private NodeBootstrap() {
    }

    /**
     * Resolve the node identity from a Fireflies member id.
     * <p>
     * Gate C1: this is {@code FirefliesMemberLookup.digestToUuid}, the canonical member&rarr;UUID
     * derivation across the codebase (first 16 bytes of the {@link Digest}). It is deterministic across
     * restarts, which is a correctness requirement: the WAL directory identity
     * ({@code .luciferase/wal/<nodeId>/}) must resolve to the same path on restart or recovery silently
     * finds no log and starts fresh. Do NOT substitute {@code UUID.nameUUIDFromBytes} — it produces a
     * different value from the same {@link Digest}.
     *
     * @param memberId the Fireflies member id ({@code member.getId()})
     * @return the deterministic node UUID
     */
    public static UUID resolveNodeId(Digest memberId) {
        return FirefliesMemberLookup.digestToUuid(Objects.requireNonNull(memberId, "memberId cannot be null"));
    }

    /**
     * Resolve the node identity from a Fireflies {@link Member}.
     *
     * @param member the Fireflies member
     * @return the deterministic node UUID
     */
    public static UUID resolveNodeId(Member member) {
        return resolveNodeId(Objects.requireNonNull(member, "member cannot be null").getId());
    }

    /**
     * Derive the per-node WAL directory: {@code <base>/.luciferase/wal/<nodeId>/}.
     *
     * @param base   the base directory (typically the process working directory)
     * @param nodeId the resolved node identity
     * @return the WAL directory path
     */
    public static Path walDir(Path base, UUID nodeId) {
        Objects.requireNonNull(base, "base cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        return base.resolve(".luciferase").resolve("wal").resolve(nodeId.toString());
    }

    /**
     * Wire the node lifecycle graph: register the connection-manager and persistence adapters at Layer 0
     * and configure bubbles (created subsequently via {@link Manager#createBubble()}) to depend on
     * {@code PersistenceManager} (Layer 1).
     * <p>
     * Must be called before any {@code createBubble} so the bubble dependency resolves.
     *
     * @param manager    the VON manager owning the lifecycle coordinator
     * @param scmAdapter the connection-manager adapter (Layer 0)
     * @param pmAdapter  the persistence adapter (Layer 0)
     */
    public static void assemble(Manager manager, SocketConnectionManagerAdapter scmAdapter,
                                PersistenceManagerAdapter pmAdapter) {
        Objects.requireNonNull(manager, "manager cannot be null");
        Objects.requireNonNull(scmAdapter, "scmAdapter cannot be null");
        Objects.requireNonNull(pmAdapter, "pmAdapter cannot be null");

        manager.registerInfrastructure(scmAdapter);
        manager.registerInfrastructure(pmAdapter);
        manager.setBubbleDependencies(List.of(pmAdapter.name()));
        log.info("Node lifecycle assembled: {} and {} at Layer 0; bubbles depend on {}",
                 scmAdapter.name(), pmAdapter.name(), pmAdapter.name());
    }

    /**
     * Production node entry point.
     * <p>
     * Phase 0 skeleton: the live startup path constructs the Fireflies view, resolves the local member,
     * derives {@code nodeId}/{@code walDir}, constructs the {@code PersistenceManager} + adapters, calls
     * {@link #assemble}, and starts the coordinator. That wiring depends on the persistence
     * recover()/scheduler work delivered in RDR-017 P1 (Luciferase-pf1iu); until then this entry point
     * fails loud rather than silently starting an unrecoverable node.
     *
     * @param args command-line arguments (unused in P0)
     */
    public static void main(String[] args) {
        throw new UnsupportedOperationException(
            "Node live-startup wiring (Fireflies view, PersistenceManager.recover() fail-loud, scheduler "
            + "relocation) is delivered in RDR-017 P1 (Luciferase-pf1iu). P0 provides the assembly seam: "
            + "NodeBootstrap.resolveNodeId / walDir / assemble.");
    }
}
