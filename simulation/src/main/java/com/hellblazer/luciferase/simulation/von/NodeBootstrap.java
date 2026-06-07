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
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.persistence.MigrationRecoveryStateSink;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.tumbler.BubbleMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
     * Build the persistence adapter for the node, wiring the WAL recovery sink to the node's migration
     * FSM (RDR-017 P1, §F5).
     * <p>
     * Constructs {@code MigrationRecoveryStateSink(fsm) → PersistenceManager(nodeId, walDir, sink) →
     * PersistenceManagerAdapter}, so that on startup {@code PersistenceManagerAdapter.doStart()} calls
     * {@code recover()} and replays {@code ENTITY_DEPARTURE}/{@code MIGRATION_COMMIT} events into the
     * FSM (reconstructing {@code MIGRATING_OUT}/{@code DEPARTED}) before the schedulers start. Using the
     * real sink rather than {@code RecoveryStateSink.NOOP} is what makes recovery reconstruct state
     * instead of silently discarding replayed events.
     *
     * @param nodeId the resolved node identity (see {@link #resolveNodeId(Digest)})
     * @param walDir the per-node WAL directory (see {@link #walDir(Path, UUID)})
     * @param fsm    the node's entity-migration state machine to reconstruct on recovery
     * @return a persistence adapter ready to register via {@link #assemble}
     * @throws IOException if WAL initialization fails
     */
    public static PersistenceManagerAdapter persistenceAdapter(UUID nodeId, Path walDir,
                                                               EntityMigrationStateMachine fsm) throws IOException {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(walDir, "walDir cannot be null");
        Objects.requireNonNull(fsm, "fsm cannot be null");
        var sink = new MigrationRecoveryStateSink(fsm);
        var pm = new PersistenceManager(nodeId, walDir, sink);
        return new PersistenceManagerAdapter(pm);
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
     * Wire the node lifecycle graph (see {@link #assemble(Manager, SocketConnectionManagerAdapter,
     * PersistenceManagerAdapter)}) and inject the live {@link PersistenceManager} into the migration
     * subsystem so the RDR-016 R2 {@code ENTITY_DEPARTURE}/{@code MIGRATION_COMMIT} WAL bracket fires
     * in the assembled node (RDR-017 P2, §Approach.3).
     * <p>
     * {@code setPersistenceManager} is called <b>after</b> the persistence adapter has been registered
     * and started (the coordinator started in the {@link Manager} constructor starts components on
     * registration), so the migrator never logs against an unrecovered/unstarted WAL.
     * <p>
     * <b>Shutdown ordering contract.</b> The migrator is wired to the persistence manager but is NOT
     * registered as a lifecycle component, so {@code Manager.close()} does not stop it. The caller MUST
     * call {@code migrator.shutdown()} (draining in-flight migrations) <b>before</b> {@code Manager.close()}
     * closes the WAL — otherwise an in-flight migration can call {@code logMigrationCommit()} after the
     * WAL is closed, failing the commit and leaving an {@code ENTITY_DEPARTURE}-without-{@code COMMIT}
     * split-brain precondition (the exact hazard RDR-016 R2 guards). Lifecycle-integrating the migrator
     * (a {@code BubbleMigratorAdapter} stopped ahead of the persistence layer) is tracked for the live
     * {@link #main} wiring — until then {@code main} throws, so the race is unreachable in production.
     *
     * @param manager    the VON manager owning the lifecycle coordinator
     * @param scmAdapter the connection-manager adapter (Layer 0)
     * @param pmAdapter  the persistence adapter (Layer 0)
     * @param migrator   the bubble migrator whose WAL bracket is wired to the started persistence manager
     */
    public static void assemble(Manager manager, SocketConnectionManagerAdapter scmAdapter,
                                PersistenceManagerAdapter pmAdapter, BubbleMigrator migrator) {
        Objects.requireNonNull(migrator, "migrator cannot be null");
        assemble(manager, scmAdapter, pmAdapter);
        // After coordinator.start()/registration: the persistence adapter is RUNNING (recovered,
        // schedulers up), so the migration WAL bracket can durably log against it.
        migrator.setPersistenceManager(pmAdapter.getPersistenceManager());
        log.info("Migration durability wired: BubbleMigrator → {}", pmAdapter.name());
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
