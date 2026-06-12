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
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.balancing.fault.FaultConfiguration;
import com.hellblazer.luciferase.lucien.balancing.fault.InMemoryPartitionTopology;
import com.hellblazer.luciferase.lucien.balancing.fault.SimpleFaultHandler;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.consensus.ownership.FirefliesBubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.consensus.ownership.RendezvousOwnershipFunction;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.lifecycle.BubbleMigratorAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.RecoveryIntegrationAdapter;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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
     * Construct a {@link Manager} armed for the composed-node path (RDR-017 HIGH-2,
     * Luciferase-n6jrh.1): {@code createBubble} fails loud until {@link #assemble} completes, so
     * a bubble cannot silently register at Layer 0 in the window between Manager construction
     * and assembly. The live {@link #main} wiring MUST construct its manager through this
     * factory; the plain {@link Manager} constructors are the legacy/standalone path with no
     * such guard.
     *
     * @param transportRegistry Transport registry for P2P communication
     * @param spatialLevel      Tetree refinement level for bubbles
     * @param targetFrameMs     Target frame time for simulation
     * @param aoiRadius         Area of Interest radius for neighbor detection
     * @param clock             Clock for timestamps (use TestClock for testing)
     * @return a Manager that rejects {@code createBubble} until assembled
     */
    public static Manager armedManager(LocalServerTransport.Registry transportRegistry,
                                       byte spatialLevel, long targetFrameMs, float aoiRadius,
                                       Clock clock) {
        return new Manager(transportRegistry, spatialLevel, targetFrameMs, aoiRadius, clock, true);
    }

    /**
     * Wire the node lifecycle graph: register the connection-manager and persistence adapters at Layer 0
     * and configure bubbles (created subsequently via {@link Manager#createBubble()}) to depend on
     * {@code PersistenceManager} (Layer 1).
     * <p>
     * Must be called before any {@code createBubble} so the bubble dependency resolves. Composed
     * production nodes should pass a manager constructed via {@link #armedManager} so creates in
     * the pre-assembly window fail loud instead of landing at Layer 0; an unarmed manager is
     * accepted (test/standalone paths) but logged.
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

        if (!manager.requiresAssembly()) {
            log.warn("assemble() called on an unarmed Manager: bubbles created before this point "
                     + "registered at Layer 0 with no guard. Composed production nodes must construct "
                     + "via NodeBootstrap.armedManager (Luciferase-n6jrh.1).");
        }
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
     * <b>Shutdown ordering (Luciferase-n6jrh.2).</b> The migrator is lifecycle-registered as a
     * {@link BubbleMigratorAdapter} at Layer 1 (dependency: {@code PersistenceManager}), so
     * {@code Manager.close()} drains it — in-flight migrations run to completion, new ones are
     * rejected — <b>before</b> the coordinator stops the persistence layer and closes the WAL.
     * An in-flight migration therefore can never call {@code logMigrationCommit()} against a
     * closed WAL (the {@code ENTITY_DEPARTURE}-without-{@code COMMIT} split-brain precondition
     * RDR-016 R2 guards). Callers no longer need to stop the migrator manually; after
     * {@code Manager.close()} the migrator is drained and cannot be restarted.
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
        // Lifecycle-integrate the migrator ABOVE the persistence layer so reverse-order shutdown
        // drains in-flight migrations before the WAL closes (Luciferase-n6jrh.2).
        manager.registerInfrastructure(new BubbleMigratorAdapter(migrator));
        // Defense for the over-budget case: the coordinator's per-component stop timeout does not
        // cancel a still-running drain, so the persistence layer may be reached while migrations
        // are alive. The gate makes that safe — the WAL is checkpoint-truncated ONLY when the
        // migration executor has fully terminated; otherwise it is closed crash-safe and retained,
        // so an interrupted migration's half-bracket recovers as MIGRATING_OUT instead of being
        // silently truncated away.
        pmAdapter.setCleanShutdownGate(migrator::isTerminated);
        log.info("Migration durability wired: BubbleMigrator → {} (lifecycle Layer 1, clean-shutdown gated)",
                 pmAdapter.name());
    }

    /**
     * The assembled partition fault subsystem (RDR-021): a started {@link SimpleFaultHandler} and
     * {@link InMemoryPartitionTopology} backing a {@link RecoveryIntegration} subscribed to the
     * live VON {@link Manager}.
     * <p>
     * <b>Shutdown ordering contract (mirrors the migrator-before-WAL contract on
     * {@link #assemble(Manager, SocketConnectionManagerAdapter, PersistenceManagerAdapter,
     * BubbleMigrator)}).</b> {@link #close()} MUST run <b>before</b> the VON manager stops — it
     * unsubscribes the recovery integration from VON and fault events (so no event fires into a
     * half-torn handler), then stops the fault handler. Since RDR-021 S3 (Luciferase-0frcy.135.4)
     * this ordering is lifecycle-integrated: {@link #assembleFaultTolerance} registers a
     * {@code RecoveryIntegrationAdapter} whose dynamic bubble dependencies order it to stop ahead
     * of every bubble adapter, so {@code Manager.close()} drives this {@code close()}
     * deterministically. Calling {@code close()} manually beforehand remains safe (idempotent).
     *
     * @param recovery     the VON↔fault-recovery integration, subscribed at construction
     * @param faultHandler the started fault handler driving partition status
     * @param topology     the partition topology the recovery integration registers into
     */
    public record FaultSubsystem(RecoveryIntegration recovery, SimpleFaultHandler faultHandler,
                                 InMemoryPartitionTopology topology) implements AutoCloseable {

        public FaultSubsystem {
            Objects.requireNonNull(recovery, "recovery cannot be null");
            Objects.requireNonNull(faultHandler, "faultHandler cannot be null");
            Objects.requireNonNull(topology, "topology cannot be null");
        }

        /**
         * Tear down in dependency order: unsubscribe the recovery integration (VON + fault-event
         * listeners) first, then stop the fault handler.
         * <p>
         * Idempotency is compositional: {@code RecoveryIntegration.close()} is idempotent because
         * listener removal on an absent element is a no-op and the retained subscription's
         * {@code unsubscribe()} is guarded by {@code SimpleFaultHandler.SimpleSubscription}'s
         * {@code active} flag; {@code SimpleFaultHandler.stop()} is CAS-guarded.
         * <p>
         * <b>Sole-subscriber invariant.</b> {@code SimpleFaultHandler.stop()} clears the
         * <em>entire</em> subscriber list, not just this subsystem's subscription. This subsystem
         * assumes it is the sole manager of {@code faultHandler}'s subscribers; any subscriber
         * registered externally via {@code faultHandler().subscribeToChanges(...)} is silently
         * dropped here, not notified of shutdown.
         */
        @Override
        public void close() {
            recovery.close();
            faultHandler.stop();
        }
    }

    /**
     * Construct and wire the partition fault subsystem against the node's VON manager (RDR-021).
     * <p>
     * Hand-wires {@code SimpleFaultHandler + InMemoryPartitionTopology + RecoveryIntegration}; the
     * {@link RecoveryIntegration} constructor subscribes to VON events and fault-handler partition
     * changes, closing the loop: a VON {@code Leave} for a registered bubble escalates partition
     * health ({@code reportSyncFailure}: HEALTHY&rarr;SUSPECTED on the first, SUSPECTED&rarr;FAILED on
     * the second), and a later VON {@code Join}/{@code GhostSync} marks it healthy — the
     * FAILED&rarr;HEALTHY transition that drives {@code vonManager.joinAt} rejoin of the partition's
     * bubbles.
     * <p>
     * Locked design decisions (RDR-021 §Decision Rationale — do not reopen):
     * <ul>
     *   <li>{@link SimpleFaultHandler}, NOT {@code DefaultFaultHandler}: the latter's
     *       SUSPECTED&rarr;FAILED transition requires a periodic {@code checkTimeouts()} poller the
     *       bootstrap does not have — partitions would stall at SUSPECTED.</li>
     *   <li>No {@code PartitionRecovery} strategy is registered: {@code RecoveryIntegration}
     *       recovers via {@code markHealthy}-on-VON-join and never calls
     *       {@code initiateRecovery}.</li>
     *   <li>{@code faultHandler.start()} is load-bearing: {@code notifySubscribers} drops events
     *       when the handler is not running, which would silently kill the recovery chain.</li>
     * </ul>
     * Per-bubble registration ({@code recovery().registerBubble(bubbleId, partitionId)} with the
     * node's identity as the partition id) is driven from the bubble-creation path — RDR-021 S2
     * (Luciferase-0frcy.135.3).
     * <p>
     * <b>Lifecycle integration (RDR-021 S3).</b> The subsystem is registered with the manager's
     * coordinator as a {@link RecoveryIntegrationAdapter} whose dynamic dependencies on the live
     * {@code EnhancedBubble-*} adapters order it to stop <b>ahead of every bubble</b>:
     * {@code Manager.close()} unsubscribes the recovery integration before any bubble's
     * {@code broadcastLeave()} can re-enter it as a VON event (which would otherwise self-report
     * sync failures against the node's own partitions during normal shutdown).
     *
     * @param manager the live VON manager the recovery integration subscribes to
     * @param clock   the clock for fault-handler and recovery timestamps (TestClock in tests)
     * @return the assembled subsystem, lifecycle-registered; {@code Manager.close()} stops it
     */
    public static FaultSubsystem assembleFaultTolerance(Manager manager, Clock clock) {
        Objects.requireNonNull(manager, "manager cannot be null");
        Objects.requireNonNull(clock, "clock cannot be null");

        var faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        // setClock BEFORE start(): no report/markHealthy call may ever stamp Clock.system() time.
        faultHandler.setClock(clock);
        faultHandler.start();
        var topology = new InMemoryPartitionTopology();
        var recovery = new RecoveryIntegration(manager, topology, faultHandler, clock);
        var subsystem = new FaultSubsystem(recovery, faultHandler, topology);
        var coordinator = manager.coordinator();
        coordinator.registerAndStart(new RecoveryIntegrationAdapter(subsystem, coordinator));
        log.info("Partition fault subsystem assembled: SimpleFaultHandler (started) + InMemoryPartitionTopology + RecoveryIntegration subscribed to VON manager; lifecycle participant {} registered", RecoveryIntegrationAdapter.NAME);
        return subsystem;
    }

    /**
     * Create a bubble and register it with the partition fault subsystem (RDR-021 S2).
     * <p>
     * The registration seam lives here, at the composition layer: {@code Manager.createBubble}
     * dispatches no creation event, so the fault subsystem cannot observe bubble creation itself
     * (RDR-021 S0, Luciferase-0frcy.135.1). For the single-process node the partition id is the
     * node's own identity ({@link #resolveNodeId}) — every locally created bubble belongs to the
     * node's partition.
     * <p>
     * {@code Manager.createBubble(UUID)} (deterministic/externally-assigned bubble ids) is
     * intentionally not wrapped — out of RDR-021 S2 scope; a caller using it must call
     * {@code recovery.registerBubble} itself.
     *
     * @param manager     the VON manager that creates and owns the bubble
     * @param recovery    the recovery integration to register the bubble with
     * @param partitionId the owning partition — the node's identity for the single-process node
     * @return the created, registered bubble
     */
    public static Bubble createRegisteredBubble(Manager manager, RecoveryIntegration recovery,
                                                UUID partitionId) {
        Objects.requireNonNull(manager, "manager cannot be null");
        Objects.requireNonNull(recovery, "recovery cannot be null");
        Objects.requireNonNull(partitionId, "partitionId cannot be null");

        var bubble = manager.createBubble();
        // If registerBubble threw, the bubble would stay in the manager unregistered (its VON
        // events outside fault scope). Acceptable: registerBubble throws only on null arguments,
        // both checked above.
        recovery.registerBubble(bubble.id(), partitionId);
        log.debug("Created bubble {} registered to partition {}", bubble.id(), partitionId);
        return bubble;
    }

    /**
     * Unregister a bubble from the fault subsystem, then remove it from the VON (RDR-021 S2).
     * <p>
     * <b>Ordering is load-bearing: unregister BEFORE {@code Manager.leave}.</b> A departing
     * bubble's {@code broadcastLeave()} notifies its neighbors; in-process neighbors dispatch the
     * resulting VON {@code Leave} back through the manager's listeners. If the bubble were still
     * registered, the node would report a sync failure against its own partition on every graceful
     * local removal — escalating partition health during normal operation. Unregistering first
     * makes that notification a deliberate silent no-op (the unregistered-bubble error contract,
     * RDR-021 §Technical Design).
     *
     * @param manager  the VON manager owning the bubble
     * @param recovery the recovery integration the bubble was registered with
     * @param bubble   the bubble to remove
     */
    public static void removeRegisteredBubble(Manager manager, RecoveryIntegration recovery,
                                              Bubble bubble) {
        Objects.requireNonNull(manager, "manager cannot be null");
        Objects.requireNonNull(recovery, "recovery cannot be null");
        Objects.requireNonNull(bubble, "bubble cannot be null");

        recovery.unregisterBubble(bubble.id());
        manager.leave(bubble);
        log.debug("Removed bubble {} (unregistered before leave)", bubble.id());
    }

    /**
     * Assemble the bubble→node ownership resolver (RDR-022) — production form.
     * <p>
     * Constructs a {@link FirefliesBubbleOwnershipResolver} over a live
     * {@link FirefliesMemberLookup}: local member via {@code memberLookup::getLocalMember}, node-UUID
     * hint resolution via {@code memberLookup::getMemberByUuid} (RDR-020 B4), and the
     * {@link RendezvousOwnershipFunction} (HRW) fixed internally — the one canonical ownership
     * function; a parameterized function would invite divergent ownership across a cluster and break
     * the every-node-computes-identically property (RDR-022 locked decision 1).
     * <p>
     * <b>The resolver is lifecycle-passive</b> (stateless, no subscriptions, nothing to close —
     * RDR-022 A2): it is returned, not registered with the lifecycle coordinator, and needs no
     * shutdown ordering.
     * <p>
     * <b>{@code bubbleKeyResolver} is caller-supplied</b> (RDR-022 A4): the bootstrap/{@link Manager}
     * domain owns no {@code TetreeBubbleGrid}; a caller that owns one passes
     * {@code grid::getKeyForBubble}.
     * <p>
     * <b>Active-only invariant (RDR-020 B4 / RDR-005):</b> {@code membershipView} is the
     * <em>active-members</em> source consulted for ownership; the misnamed
     * {@code FirefliesMemberLookup.getActiveMembers()} (all-members backed) is never used for it.
     * <p>
     * <b>Injection contract (normative):</b> a consumer assembly point that constructs
     * {@code TopologyConsensusCoordinator}, {@code OptimisticMigratorImpl}, or
     * {@code DistributedBubbleNode} MUST inject an assembled resolver via the consumer's
     * {@code setOwnershipResolver(...)} before first consensus use; the consumers' existing
     * fail-loud guards enforce this. No production consumer is constructed by this bootstrap today
     * — consumer assembly lands with the multi-node arcs (Luciferase-s23eu); this factory is the
     * canonical place they obtain the resolver from.
     *
     * @param memberLookup      the Fireflies member lookup (local member + canonical node-UUID
     *                          mapping); requires a live Fireflies view at <em>first use</em> —
     *                          assembly wires method references lazily and does not dereference
     *                          the view
     * @param membershipView    the active-only membership source for ownership resolution
     * @param bubbleKeyResolver maps a bubble {@code UUID} to its {@code TetreeKey} ({@code null} if
     *                          unknown — the resolver fails loud); caller-supplied, e.g.
     *                          {@code grid::getKeyForBubble}
     * @return the assembled, lifecycle-passive resolver
     * @throws NullPointerException if any argument is null — {@code memberLookup} is validated at
     *                              the factory (it is dereferenced here for method references);
     *                              {@code membershipView}/{@code bubbleKeyResolver} are validated
     *                              by the resolver's constructor (the throw site in the stack)
     */
    public static BubbleOwnershipResolver assembleOwnershipResolver(
            FirefliesMemberLookup memberLookup,
            MembershipView<Member> membershipView,
            Function<UUID, TetreeKey<?>> bubbleKeyResolver) {
        Objects.requireNonNull(memberLookup, "memberLookup cannot be null");
        return assembleOwnershipResolver(memberLookup::getLocalMember, memberLookup::getMemberByUuid,
                                         bubbleKeyResolver, membershipView);
    }

    /**
     * Assemble the bubble→node ownership resolver (RDR-022) — narrow-seam form.
     * <p>
     * Matches {@link FirefliesBubbleOwnershipResolver}'s primary constructor minus the
     * {@code SpatialOwnershipFunction}, which this factory fixes to {@link RendezvousOwnershipFunction}
     * (RDR-022 locked decision 1). Enables assembly without a live Fireflies view — the MVV /
     * test-assembled-node path (RDR-022 A3); production callers use the
     * {@link #assembleOwnershipResolver(FirefliesMemberLookup, MembershipView, Function)} overload.
     * All seams are validated fail-loud by the resolver's constructor.
     *
     * @param localMemberSupplier returns this node's own {@link Member}
     * @param nodeResolver        canonical node-UUID → {@link Member} mapping (RDR-020 B4)
     * @param bubbleKeyResolver   bubble {@code UUID} → {@code TetreeKey} ({@code null} if unknown)
     * @param membershipView      the active-only membership source for ownership resolution
     * @return the assembled, lifecycle-passive resolver
     */
    public static BubbleOwnershipResolver assembleOwnershipResolver(
            Supplier<Member> localMemberSupplier,
            Function<UUID, Optional<Member>> nodeResolver,
            Function<UUID, TetreeKey<?>> bubbleKeyResolver,
            MembershipView<Member> membershipView) {
        var resolver = new FirefliesBubbleOwnershipResolver(localMemberSupplier, nodeResolver,
                                                            bubbleKeyResolver, membershipView,
                                                            new RendezvousOwnershipFunction());
        log.info("Bubble ownership resolver assembled: FirefliesBubbleOwnershipResolver + "
                 + "RendezvousOwnershipFunction (HRW); lifecycle-passive, not registered");
        return resolver;
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
