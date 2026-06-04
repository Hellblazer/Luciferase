# Simulation Module — Deep Multi-Agent Code Review

**Date**: 2026-06-03  
**Method**: 14 code-review-expert + 10 substantive-critic agents (partitioned by subsystem), every Critical/High adversarially verified against source (205 agent invocations, ~11M tokens).  
**Result**: 304 raw findings → **163 confirmed** (22 Critical, 107 High, 32 Medium, 2 Low), 18 refuted as false-positives, 117 Medium/Low unverified.


> Confirmed = a second independent agent re-opened the cited source and corroborated the defect. Refuted list at the end.


## Confirmed CRITICAL


### C1. BubbleDynamicsManager.mergeBubbles: entityBubbles Reverse-Map Corrupted After Merge
- **sim:bubble/BubbleDynamicsManager.java:265-302** · correctness · unit=bubble-core · flagged by expert
- **Problem**: mergeBubbles() first calls largerEntities.addAll(smallerEntities) then iterates smallerEntities to populate entityBubbles[entity]=larger, then calls unregisterBubble(smaller). unregisterBubble() calls bubbles.remove(smaller) which returns the smallerEntities set object (which still holds its original elements), then iterates it and calls entityBubbles.remove(entity) for each — deleting the just-written entityBubbles[entity]=larger entries. After merge, any entity originally in the smaller bubble has no entry in entityBubbles. Downstream operations setEntityAffinity() and getDriftingEntities() read entityBubbles and will either throw ('Entity not in specified bubble') or return wrong results. The existing testBubbleMerge test does not exercise entityBubbles after merge, so this is currently hidden.
- **Fix**: In unregisterBubble(), remove only affinities and the entry from bubbles; do NOT remove from entityBubbles (that mapping was just updated). Alternatively, in mergeBubbles, call unregisterBubble before updating entityBubbles and do the entityBubbles update separately. Add a test that calls setEntityAffinity on a transferred entity post-merge.
- **Verifier**: Inspected BubbleDynamicsManager.java lines 265-307 (mergeBubbles) and the body of unregisterBubble.

mergeBubbles sequence:
1. Line 293: `largerEntities.addAll(smallerEntities)` — entities copied into larger set
2. Lines 296-298: `entityBubbles.put(entity, larger)` for each entity in smallerEntities — reverse map updated to point to larger
3. Line 301: `unregisterBubble(smaller)` called

unregiste

### C2. EnhancedBubbleMigrationIntegration.detectAndInitiateMigrations is hollow — all migration logic commented out
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:160-201** · correctness · unit=bubble-migration · flagged by expert
- **Problem**: The entire actionable body of detectAndInitiateMigrations is commented out. The method queries getEntitiesCrossingBoundaries() and checks FSM state, but then does nothing: the optimistic migrator is never called, no FSM transition is triggered (OWNED→MIGRATING_OUT), no target bubble is determined, no EntityDepartureEvent is sent. The method only increments totalMigrationsInitiated++ and logs. Entities physically crossing bubble boundaries will never be migrated. The comment in the class Javadoc describes a fully functional workflow that does not exist.
- **Fix**: Uncomment and implement the commented-out block. At minimum: (1) call migrationOracle.getTargetBubble(position) or use TetrahedralContainmentChecker.locateDestinationBubble(position), (2) call optimisticMigrator.initiateOptimisticMigration(entityId, targetBubble), (3) call migrationFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT), (4) send EntityDepartureEvent to the target. If this is intentionally deferred, guard it with a fail-loud assertion or log.error so the silence is not mistaken for correct behaviour.
- **Verifier**: Lines 183-189 of EnhancedBubbleMigrationIntegration.java confirm the defect exactly as described. The three load-bearing calls are all commented out:
  // UUID targetBubble = migrationOracle.getTargetBubble(position);
  // optimisticMigrator.initiateOptimisticMigration(entityId, targetBubble);
  // migrationFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
while `totalMigrationsInitiat

### C3. transition() is a non-atomic read-check-write — violates single-owner invariant
- **sim:causality/EntityMigrationStateMachine.java:450-489** · concurrency · unit=causality · flagged by expert
- **Problem**: transition() reads the current state with `entityStates.get(entityId)` (line 450), then validates the transition, then writes `entityStates.put(entityId, newState)` (line 489). ConcurrentHashMap guarantees atomic per-key operations, but two independent get-then-put sequences on the same key are not atomic. Two threads can both observe the entity in MIGRATING_IN and both execute the OWNED commit, or both observe MIGRATING_OUT and both execute DEPARTED. This directly violates the stated critical invariant: 'Exactly one bubble globally can have OWNED or MIGRATING_IN state for any entity at any given time.' The migration is a 2PC protocol where a double-commit means two bubbles both believe they own the entity, silently corrupting global state with no detection.
- **Fix**: Replace the get/validate/put triple with a single `entityStates.compute(entityId, (k, currentState) -> { ... validate and return newState or currentState ... })` call. The entire validation and state update must execute atomically inside the compute lambda. The migration context update (migrationContexts.putIfAbsent / remove) should also be folded inside or handled with the same atomicity guarantee.
- **Verifier**: Inspected lines 450–489 of EntityMigrationStateMachine.java. The field `entityStates` is a plain `ConcurrentHashMap<Object, EntityMigrationState>` (line 80). The `transition()` method does: `get(entityId)` at line 450, validates, then `put(entityId, newState)` at line 489 — three separate map operations with no atomicity across them. A `StampedLock` exists (line 127) but is used exclusively for ti

### C4. StateCounts switch silently drops ROLLBACK_OWNED — throws IllegalArgumentException on every call during rollback
- **sim:causality/EntityMigrationStateMachine.java:669-692** · correctness · unit=causality · flagged by expert
- **Problem**: getStateCounts() counts entities for OWNED, MIGRATING_OUT, DEPARTED, MIGRATING_IN, and GHOST but has no case for ROLLBACK_OWNED. The `total` is set to `snapshot.size()` (line 689), which includes ROLLBACK_OWNED entities. The compact record constructor (lines 640-648) enforces `total == owned + migratingOut + departed + migratingIn + ghost`, and throws IllegalArgumentException if not equal. Since the view-change path and timeout rollback path both route entities through ROLLBACK_OWNED, any call to getStateCounts() during or shortly after a rollback throws an exception. This will crash any monitoring or coordination logic that calls getStateCounts() at view-change time, which is exactly the high-contention moment.
- **Fix**: Add `case ROLLBACK_OWNED -> ownedCount++;` (since ROLLBACK_OWNED is a temporary 'locally owned' state per isLocallyOwned()), or add a separate `rollbackOwned` field to StateCounts. The compact record constructor must be updated to include it in the invariant check.
- **Verifier**: Independently confirmed. At line 527, `entityStates` is updated to `ROLLBACK_OWNED` during view-change rollback. The switch at lines 680-686 has no case for `ROLLBACK_OWNED`, so those entities increment none of the counters. At line 689, `total = snapshot.size()` counts them. The compact record constructor at lines 640-648 computes `sum = owned + migratingOut + departed + migratingIn + ghost` and 

### C5. StateCounts Constructor Throws IAE Whenever Any Entity Is In ROLLBACK_OWNED State
- **sim:causality/EntityMigrationStateMachine.java:638-692** · correctness · unit=causality · flagged by critic
- **Problem**: The StateCounts compact constructor validates total == owned + migratingOut + departed + migratingIn + ghost. The getStateCounts() method at line 680 builds these counts with a switch over EntityMigrationState values. There is no case ROLLBACK_OWNED in the switch, so entities in that state are counted in total (= snapshot.size()) but added to no bucket. When any entity is in ROLLBACK_OWNED state, total > sum, and the constructor throws IllegalArgumentException. onViewChange() routinely transitions MIGRATING_OUT entities to ROLLBACK_OWNED. Any caller of getStateCounts() during or after a view change will receive an uncaught IAE.
- **Fix**: Add a rollbackOwned field to StateCounts and include it in the sum invariant check, OR add case ROLLBACK_OWNED -> rollbackOwnedCount++ and include rollbackOwnedCount in the sum. Also add rollbackOwned to the record parameter list so callers can observe it. Add a test that puts an entity in ROLLBACK_OWNED state and calls getStateCounts().
- **Verifier**: Inspected lines 669-692 of EntityMigrationStateMachine.java. The switch at lines 680-686 handles OWNED, MIGRATING_OUT, DEPARTED, MIGRATING_IN, and GHOST — but has no case for ROLLBACK_OWNED. Line 689 sets total = snapshot.size(), which counts every entity regardless of state. The compact constructor at lines 640-648 asserts total == owned + migratingOut + departed + migratingIn + ghost and throws 

### C6. MigrationCoordinator dispatches all 2PC operations via reflection — method failures silently orphan entities
- **sim:causality/MigrationCoordinator.java:391-426** · distributed · unit=causality · flagged by expert
- **Problem**: sendPrepareRequest(), sendCommitRequest(), and sendAbortRequest() all resolve the method via `getClass().getMethod(...)` and invoke via reflection. Any failure (method not found, wrong parameter types, invocation exception) is caught and logged but does not roll back the entity's coordination state. After a failed sendCommitRequest(), the entity's CoordinationState is already set to COMMIT_SENT (line 339) and then removed from coordinatedEntities (line 345) — the source FSM has transitioned to DEPARTED but no CommitRequest was delivered, and the target is permanently stuck in MIGRATING_IN waiting for a commit that will never arrive. This is the RDR-004 D3 silent-data-loss pattern: a network operation silently fails, leaving the distributed system in an inconsistent state.
- **Fix**: Replace reflection with a proper typed interface for CrossProcessMigration. The coordinator should declare a field typed to that interface and fail at construction rather than at runtime. If reflection must be retained for mock support, sendCommitRequest() must return a boolean success indicator, and failure must prevent the FSM from completing the MIGRATING_OUT -> DEPARTED transition (either reject it or queue a compensating ROLLBACK_OWNED transition).
- **Verifier**: Lines 338-345 confirm the defect exactly as described. `sendCommitRequest(entityId, state.targetBubble)` is called first (line 338), but its implementation (lines 405-413) catches all exceptions and only logs them — it returns void and has no way to signal failure. Lines 339 and 345 then unconditionally advance state to COMMIT_SENT and remove the entity from coordinatedEntities. There is no guard:

### C7. No voter-identity deduplication — single Byzantine voter can reach quorum alone
- **sim:consensus/committee/CommitteeBallotBox.java:67-76, 141-144** · correctness · unit=consensus · flagged by expert
- **Problem**: CommitteeBallotBox.addVote() calls state.votes.add(vote.approved()) without ever recording which voter cast the vote. VoteState holds a HashMultiset<Boolean> that accepts duplicate entries. A Byzantine committee member can call submitVote repeatedly with the same voterId and drive YES (or NO) to the quorum threshold entirely by themselves. The quorum formula (toleranceLevel()+1) is only correct if each member contributes exactly one vote; the implementation provides no such guarantee.
- **Fix**: Add a Set<Digest> seenVoters field to VoteState. In addVote(), reject the vote (return silently) if !seenVoters.add(vote.voterId()). CommitteeVotingProtocol.recordVote() already checks that the voter is in the committee set, but that check belongs to the protocol layer; the ballot box must also deduplicate to be safe as a standalone component.
- **Verifier**: Inspected /Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/com/hellblazer/luciferase/simulation/consensus/committee/CommitteeBallotBox.java lines 67-76 and 141-144. VoteState contains only `final HashMultiset<Boolean> votes = HashMultiset.create()` — no Set of seen voter IDs. addVote() does `state.votes.add(vote.approved())` with no deduplication guard. completeIfQuorum() fires when `

### C8. No voter deduplication — single Byzantine member can reach quorum unilaterally
- **sim:consensus/committee/CommitteeBallotBox.java:67-76** · security · unit=consensus · flagged by critic
- **Problem**: VoteState uses `HashMultiset<Boolean>` which accepts duplicate additions without any deduplication guard. The `addVote()` method calls `state.votes.add(vote.approved())` with no check on whether the voter ID already voted. A single compromised committee member can submit the same vote N times to reach quorum entirely on its own. The committee membership check in CommitteeVotingProtocol.recordVote() (line 102) verifies that the voter is in the committee, but the same voter can call recordVote() N times with the same proposalId and voterId and each call adds a fresh multiset entry. All ByzantineFailureTest scenarios use distinct voter IDs, so the actual single-voter repeat-vote attack is never exercised.
- **Fix**: Add `Set<Digest> votedMembers` to VoteState. In addVote(), check `if (votedMembers.contains(voterId)) return;` before adding to the multiset, then `votedMembers.add(voterId)`. The dedup check and multiset add must both be inside the synchronized(state) block that already exists.
- **Verifier**: Confirmed by direct inspection. CommitteeVotingProtocol.recordVote() (line 102) checks committee membership but has no "already voted" guard. CommitteeBallotBox.addVote() (line 72) does state.votes.add(vote.approved()) unconditionally — no per-voter deduplication. VoteState (lines 141-143) holds only HashMultiset<Boolean> and CompletableFuture<Boolean>; there is no Set of voter IDs. A single Byzan

### C9. DistributedBubbleNode.initiateRemoteMigration passes bubble.id() instead of targetNodeId
- **sim:distributed/network/DistributedBubbleNode.java:119-120** · correctness · unit=distributed-grid-net · flagged by critic
- **Problem**: initiateRemoteMigration(UUID entityId, UUID targetNodeId) calls migrationIntegration.getOptimisticMigrator().initiateOptimisticMigration(entityId, bubble.id()) — passing this node's own bubble ID as the targetBubbleId parameter instead of targetNodeId. The entity is registered as migrating TO itself. The source FSM advances to MIGRATING_OUT waiting for a MIGRATING_IN transition from the real target that will never arrive. The entity is permanently orphaned in MIGRATING_OUT. targetNodeId is checked for reachability but never passed to the migration subsystem.
- **Fix**: Change bubble.id() to targetNodeId. Signature of OptimisticMigrator.initiateOptimisticMigration is (UUID entityId, UUID targetBubbleId), so the call should be .initiateOptimisticMigration(entityId, targetNodeId).
- **Verifier**: Line 120 of DistributedBubbleNode.java calls `migrationIntegration.getOptimisticMigrator().initiateOptimisticMigration(entityId, bubble.id())`. The method signature is `initiateRemoteMigration(UUID entityId, UUID targetNodeId)` — `targetNodeId` is the caller-supplied destination. It is used only for the reachability guard (line 113) and a log line (line 122), but never passed to the migration subs

### C10. GrpcBubbleNetworkChannel.acknowledgeViewSynchrony extracts wrong field for sourceNodeId
- **sim:distributed/network/GrpcBubbleNetworkChannel.java:525** · distributed · unit=distributed-grid-net · flagged by critic
- **Problem**: The handler extracts sourceNodeId = UUID.fromString(request.getTargetBubbleId()) but the ack originates from the target bubble — its identity is in TargetBubbleId. When sendViewSynchronyAck() serialises a ViewSynchronyAck (line 384-395), it writes event.getSourceBubbleId() into the proto's sourceBubbleId field. The receiver should read proto.getSourceBubbleId() to identify who sent the ack. Reading TargetBubbleId instead gives the caller a wrong UUID. ackListener.onViewSynchronyAck(sourceNodeId, ack) fires against a phantom ID; the source FSM's MIGRATING_OUT→DEPARTED transition is never matched to the real entity, leaving it orphaned.
- **Fix**: Change request.getTargetBubbleId() to request.getSourceBubbleId() at line 525. Cross-check convertFromProto(ViewSynchronyAck proto, UUID sourceNodeId) to confirm the sourceNodeId passed in actually matches who sent the ack.
- **Verifier**: Line 525: `var sourceNodeId = UUID.fromString(request.getTargetBubbleId());`. The proto is populated by `convertToProto(ViewSynchronyAck)` at lines 386-395: `setSourceBubbleId(event.getSourceBubbleId())` (line 389) and `setTargetBubbleId(event.getTargetBubbleId())` (line 390). The ack originates from a node whose identity is in `sourceBubbleId`. The receiver should read `request.getSourceBubbleId(

### C11. BubbleGhostManager: incoming ghost lifecycle.onCreate never called — onUpdate is a no-op for all received ghosts
- **sim:ghost/BubbleGhostManager.java:253-255** · correctness · unit=ghost · flagged by critic
- **Problem**: handleGhostBatch() at line 254 calls `lifecycle.onUpdate(ghost.entityId().toDebugString(), ghost.timestamp())` for each received ghost. GhostLifecycleStateMachine.onUpdate() is implemented with computeIfPresent(), which is a no-op when the key is absent. lifecycle.onCreate() is only called in notifyEntityNearBoundary() (line 196), which is the sender path. On the receiver side, onCreate() is never called. Therefore every lifecycle.onUpdate() call in handleGhostBatch() silently does nothing: the lifecycle state machine tracks zero state for any received ghost. Staleness detection, TTL expiry, and the CREATED→ACTIVE state transition are all non-functional for received ghosts.
- **Fix**: In handleGhostBatch(), before calling lifecycle.onUpdate(), check if the entity is already registered and call onCreate() if not: if (lifecycle.getState(ghost.entityId().toDebugString()) == null) { lifecycle.onCreate(ghost.entityId().toDebugString(), fromBubbleId, ghost.timestamp()); }
- **Verifier**: Confirmed by direct inspection. `BubbleGhostManager.handleGhostBatch()` (lines 253-255) calls `lifecycle.onUpdate(ghost.entityId().toDebugString(), ghost.timestamp())` for every received ghost. `GhostLifecycleStateMachine.onUpdate()` (lines 237-251) uses `states.computeIfPresent(entityId, ...)` — a no-op when the key is absent. `lifecycle.onCreate()` is called only at line 196, inside `notifyEntit

### C12. P2PGhostChannel — entity ID type always reconstructed as private StringEntityID, causing ClassCastException
- **sim:ghost/P2PGhostChannel.java:349-350** · serialization · unit=ghost · flagged by expert
- **Problem**: fromTransportGhost() reconstructs the entity ID as `(ID) new StringEntityID(tg.entityId())` where StringEntityID is a private record inside P2PGhostChannel. The unchecked cast compiles and succeeds at runtime due to type erasure. However, any downstream handler that type-checks the ghost's entityId (e.g., `(ConcreteEntityID) ghost.entityId()`, `instanceof`, or equality via CompareTo across types) will receive a runtime ClassCastException or incorrect behavior for every ghost received over the P2P channel. This is a RDR-004 class silent-data-corruption: the ghost is delivered but with a fundamentally wrong ID type, silently breaking all callers that rely on the concrete ID type.
- **Fix**: The channel needs an EntityID deserializer registered at construction time (matching the sender's ID type), or the EntityID must be reconstructable from its debug string via a factory. Add a `Function<String, ID> idFactory` constructor parameter and use it in fromTransportGhost. For the specific case where ID is the project's StringEntityID (not the private one), the caller can supply `StringEntityID::new`. For other ID types, the factory must be provided.
- **Verifier**: Confirmed. P2PGhostChannel.java line 350 constructs the ghost entity ID as `(ID) new StringEntityID(tg.entityId())` where `StringEntityID` is a private record inside `P2PGhostChannel` (completely separate from the public `com.hellblazer.luciferase.simulation.entity.StringEntityID`). 

GhostStateListener.java line 154 then does `(StringEntityID) entityId` — importing `com.hellblazer.luciferase.simu

### C13. createEntitySnapshot is a hardcoded stub — all rollbacks restore garbage state
- **sim:distributed/migration/CrossProcessMigration.java:803-808** · correctness · unit=migration · flagged by critic
- **Problem**: Every production migration (non-TestableEntityStore path) calls createEntitySnapshot which unconditionally returns new EntitySnapshot(entityId, new Point3d(0,0,0), "MockContent", source.getBubbleId(), 1L, 1L, timestamp). The source bubble is never queried for actual entity state. When abort/rollback executes, it restores this garbage snapshot to the source. Position is always (0,0,0), content is always 'MockContent', epoch is always 1. In a real cluster, every failed migration loses the entity's actual state, placing a phantom at the origin.
- **Fix**: Implement createEntitySnapshot to actually query source.asLocal().getEntity(entityId) before removal. Take the snapshot before the entity is removed in PREPARE. The snapshot must capture real position, content, epoch, and version.
- **Verifier**: Confirmed at lines 598-611 and 803-808. The `createEntitySnapshot` method at lines 803-808 unconditionally returns `new EntitySnapshot(entityId, new Point3d(0,0,0), "MockContent", source.getBubbleId(), 1L, 1L, timestamp)` — hardcoded garbage. This snapshot is assigned at line 599 before the PREPARE phase. Line 606-611 shows the actual entity removal is also stubbed: `if (source instanceof Testable

### C14. MigrationOracleImpl uses plain HashMap for bubbleMap and bubbleCoordinates with concurrent access
- **sim:distributed/migration/MigrationOracleImpl.java:113-114, 157-161, 188-213, 326-351** · concurrency · unit=migration · flagged by critic
- **Problem**: bubbleMap (Map<CubeBubbleCoordinate, UUID>) and bubbleCoordinates (Map<UUID, CubeBubbleCoordinate>) are initialized as plain HashMap (not ConcurrentHashMap). registerBubble() mutates both maps post-construction without synchronization. checkMigration(), getTargetBubble(), getClosestBubble(), and getEntitiesCrossingBoundaries() read from these maps concurrently. This causes ConcurrentModificationException during iteration (getClosestBubble iterates bubbleCoordinates.entrySet()), or silent stale reads. The class comment claims thread-safety via ConcurrentHashMap for entity positions only, which omits these two non-concurrent maps.
- **Fix**: Change both fields to ConcurrentHashMap. All put/get operations are already non-compound so no additional synchronization is needed beyond the CHM guarantee.
- **Verifier**: Lines 113-114 confirm `bubbleMap` and `bubbleCoordinates` are initialized as `new HashMap<>()`. The class javadoc (lines 52-53) claims thread safety but only ConcurrentHashMap covers `entityPositions` (line 115) and `crossingCache` (line 116). `registerBubble()` (lines 160-161) is a public post-construction mutator with no synchronization. `checkMigration()` reads both maps at lines 198 and 206; `

### C15. WAL Double-Open During Recovery Corrupts Log
- **sim:persistence/PersistenceManager.java:237** · correctness · unit=persistence-misc · flagged by expert
- **Problem**: PersistenceManager.recover() instantiates a new EventRecovery with the running WAL's logDirectory. EventRecovery.recover() then calls new WriteAheadLog(nodeId, logDirectory) (EventRecovery.java:79), which calls initializeLogFile() and opens the same log file with a second FileOutputStream in append mode — while PersistenceManager's own WAL is still open and being written to by the batch-flush scheduler. Two concurrent appenders to the same file produce interleaved partial JSON lines, corrupting the JSONL log irreparably.
- **Fix**: Extract a read-only LogReader that only calls readLogFile/findLogFiles without opening a writable channel. EventRecovery should use that instead of a full WriteAheadLog. Alternatively, stop the batch-flush scheduler and quiesce the WAL before opening recovery, and reopen after.
- **Verifier**: Inspected all three files directly. PersistenceManager.java:237 passes `writeAheadLog.logDirectory` to `new EventRecovery(...)`. EventRecovery.java:79 calls `new WriteAheadLog(nodeId, logDirectory)`, which always calls `initializeLogFile()`. That method opens a `new FileOutputStream(currentLogFile.toFile(), true)` — a second writable file descriptor on the same JSONL log file already held open (wi

### C16. WAL Log File Read Order Is Backwards After Rotation
- **sim:persistence/WriteAheadLog.java:332-346** · correctness · unit=persistence-misc · flagged by expert
- **Problem**: findLogFiles() applies lexicographic .sorted() to log file paths. The base file node-UUID.log (the earliest, containing the first events written before the first rotation) sorts lexicographically AFTER all rotated files node-UUID-1.log, node-UUID-10.log, node-UUID-2.log, because ASCII '-' (45) < '.' (46). Recovery therefore replays the oldest events last, inverting causal order. The sequence-number deduplication in recover() only covers ENTITY_DEPARTURE/MIGRATION_COMMIT, so VIEW_SYNC_ACK and DEFERRED_UPDATE events are replayed out of order.
- **Fix**: Sort by extracted rotation number (numeric): base file = 0, node-UUID-N.log = N. Use a Comparator that parses the suffix and sorts numerically ascending.
- **Verifier**: Line 339 calls `.sorted()` with no comparator, producing lexicographic path order. The naming scheme (line 220) produces `node-UUID-1.log`, `node-UUID-2.log`, … for rotated files and `node-UUID.log` for the original base file. Because ASCII '-' (45) < '.' (46), sorted order is: node-UUID-1.log, node-UUID-10.log, node-UUID-2.log, …, node-UUID.log — placing the base file (oldest events) last. `readA

### C17. BFT consensus is a silent stub — all proposals auto-approved
- **sim:topology/TopologyConsensusCoordinator.java:165-200** · distributed · unit=topology · flagged by expert
- **Problem**: The `consensusProtocol` (ViewCommitteeConsensus) field is injected, null-checked, and guarded, but is never called. requestConsensus() approves every proposal that passes local pre-validation, skipping actual distributed consensus. The comment at line 187 admits this: 'For Phase 9B, we use pre-validation as the consensus mechanism'. The class Javadoc claims 'Byzantine fault-tolerant approval' and 'ViewCommitteeConsensus for voting' — both are false. Any node can unilaterally trigger topology changes without quorum agreement.
- **Fix**: Either (a) remove the consensusProtocol field and Javadoc claims to accurately reflect that this is single-node validation, or (b) wire consensusProtocol into the approval path: `return consensusProtocol.requestConsensus(toMigrationProposal(proposal))`. The stub creates a dangerous false sense of security for distributed deployment.
- **Verifier**: Inspected /Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/com/hellblazer/luciferase/simulation/topology/TopologyConsensusCoordinator.java lines 165-200. The defect is real and exactly as described. consensusProtocol is set via setConsensusProtocol() (line 144) and null-checked with an IllegalStateException guard (lines 168-170), which implies it must be present. However, the field is

### C18. MigrationProtocolMessages silently crash/drop on wire path (RDR-004 class)
- **sim:von/MessageConverter.java:48-101** · serialization · unit=von-core · flagged by critic
- **Problem**: MigrationProtocolMessages (PrepareRequest, PrepareResponse, CommitRequest, CommitResponse, AbortRequest, AbortResponse) are permitted subtypes of Message.sealed (Message.java:55) but are absent from MessageConverter.toTransport()'s switch. The default branch throws IllegalArgumentException. On the receiver side, Bubble.handleMessage() (Bubble.java:422-432) has no case for MigrationProtocolMessages and falls to 'default -> log.warn(...)' — the message is silently discarded. The entire 2PC migration protocol is inoperative when messages pass through SocketTransport serialization. This is the same silent-data-loss class as RDR-004 D3.
- **Fix**: Add converter cases for all six MigrationProtocolMessages subtypes in MessageConverter.toTransport/fromTransport. Add a handler case in Bubble.handleMessage that routes 2PC messages to the appropriate coordinator. Add a round-trip serialization test covering every MigrationProtocolMessages subtype.
- **Verifier**: Independently confirmed. Message.java:52-55 shows `sealed interface Message permits ... MigrationProtocolMessages`. MessageConverter.java:49-68 switches on message type with cases only for GhostSync, JoinRequest, JoinResponse, Move, Leave, Ack, Query, QueryResponse — no case for MigrationProtocolMessages or any of its subtypes. The default branch at line 67 throws `IllegalArgumentException("Unknow

### C19. SocketClient.receiveMessages() permanently deadlocks on ObjectInputStream construction
- **sim:von/transport/SocketClient.java:106-131** · distributed · unit=von-core · flagged by expert
- **Problem**: SocketClient.receiveMessages() creates `new ObjectInputStream(socket.getInputStream())` whose constructor synchronously reads the Java serialization stream header (STREAM_MAGIC + STREAM_VERSION) from the remote side. SocketServer never creates an ObjectOutputStream on its side of the connection — it only reads via ObjectInputStream. The server-side socket input stream therefore never emits those magic bytes. The background receive thread in SocketClient hangs forever at line 107's OIS constructor, silently consuming a thread and a socket. Because the architecture is already unidirectional (client sends to server; server-to-client messages travel via a separate reversed connection), this receive loop is architecturally dead and should not exist. No test exercises it; the test suite happens to pass only because the empty handler `msg -> {}` is passed to client-side SocketConnectionManager and the blocked thread is a daemon thread that never unblocks.
- **Fix**: Remove the receive-side ObjectInputStream, receiveMessages() method, and the receive thread start from SocketClient.connect(). SocketClient is send-only; incoming messages from a peer arrive via that peer's own SocketClient connecting to this node's SocketServer. The messageHandler constructor parameter should also be removed (or relegated to a no-op placeholder pending removal) to signal the unidirectional contract.
- **Verifier**: Confirmed by direct inspection of both files. SocketClient.connect() (line 87) creates ObjectOutputStream on the outgoing socket and starts a receive thread. The receive thread at line 107 calls `new ObjectInputStream(socket.getInputStream())`. The ObjectInputStream constructor blocks synchronously until it reads the Java serialization magic bytes (0xACED 0x0005), which are only emitted by ObjectO

### C20. BubbleBounds silently dropped across network — RDR-004 class silent-data-loss
- **sim:von/MessageConverter.java:159-168, 227-235** · serialization · unit=von-transport · flagged by expert
- **Problem**: `joinRequestFromTransport()` and `moveFromTransport()` hard-code `null` for the `BubbleBounds` parameter (Phase 6A stubs). Callers in production code dereference `bounds` without null guards: `JoinProtocol.findNeighbors()` line 151 calls `index.findOverlapping(joiner.bounds())` — `joiner.bounds()` returns null, causing NPE. `Bubble.handleJoinRequest()` line 444 passes `req.bounds()` directly to `new NeighborState(...)`. `SpatialNeighborIndex` line 285 calls `n.bounds().overlaps(queryBounds)` which NPEs on any neighbor whose bounds arrived via network. The pattern is identical to the RDR-004 D3 `JoinResponse`/`neighbors` silent-drop: a wire format that omits data causes silent NPE (or silent wrong behavior) deep in the consumer.
- **Fix**: Either (a) serialize BubbleBounds in the wire format now (add fields to TransportVonMessage / TransportNeighborInfo and complete Phase 6B), or (b) add null guards in every consumer of `bounds()` on network-received messages, and add a clear protocol-version comment and an assertion that fails loudly rather than producing a null-dereference many frames later. The 'Phase 6B' TODO must be tracked as a blocking defect, not a deferred enhancement.
- **Verifier**: Independently verified the full defect chain:

1. `MessageConverter.java:166` — `joinRequestFromTransport()` hard-codes `null` for BubbleBounds: `new Message.JoinRequest(joinerId, position, null, transport.timestamp())`.

2. `MessageConverter.java:234` — `moveFromTransport()` does the same: `new Message.Move(nodeId, newPosition, null, transport.timestamp())`.

3. `Bubble.java:441-446` — `handleJoi

_(20 distinct Criticals after merging expert/critic corroboration of the same site.)_


## Confirmed HIGH (grouped by subsystem)


### behavior-spatial
- **sim:behavior/CompositeEntityBehavior.java:88-90** [performance] CompositeEntityBehavior.computeVelocity() — O(n^2) linear scan per tick
  - For every entity each tick, computeVelocity() calls `bubble.getAllEntityRecords().stream().filter(r -> r.id().equals(entityId)).findFirst()` to determine entity type. If the bubble has N entities, each of the N calls to computeVelocity() pe
- **sim:behavior/FlockingBehavior.java:158-160** [concurrency] Non-atomic double-buffer swap in FlockingBehavior and PreyBehavior
  - swapVelocityBuffers() performs two sequential volatile writes: `previousVelocities = currentVelocities; currentVelocities = new ConcurrentHashMap<>()`. Between those two assignments, a concurrently executing computeVelocity() thread reads `

### bubble-core
- **sim:bubble/AdaptiveSplitPolicy.java:151-164** [correctness] AdaptiveSplitPolicy.performSplit: creates empty child bubbles — cluster analysis is unused
  - performSplit() creates one EnhancedBubble per cluster in analysis.clusters() but does NOT populate them with entities. It returns empty bubbles. The caller is expected to call redistributeEntities() separately, which uses naive round-robin 
- **sim:bubble/BubbleBoundsTracker.java:94-125** [concurrency] BubbleBoundsTracker: Non-Atomic bounds/entityPositions Update — Concurrent Visibility Race
  - entityPositions (ConcurrentHashMap) and bounds (volatile) are updated in separate non-atomic steps. onEntityAdded puts to entityPositions then updates bounds; a concurrent call to centroid() between those two writes reads an entityPositions
- **sim:bubble/BubbleBoundsTracker.java:118-125** [correctness] BubbleBoundsTracker.onEntityMoved: bounds only expand, never shrink
  - onEntityMoved calls bounds.expand(newPosition) unconditionally regardless of whether the entity moved inward. entityPositions map is updated with the new position, but no shrink occurs. Over the lifetime of a bubble with moving entities, bo
- **sim:bubble/BubbleGhostCoordinator.java:55-60** [resource-leak] BubbleGhostCoordinator: TickListener Leaked on Bubble Destruction
  - BubbleGhostCoordinator registers a TickListener with RealTimeController in its constructor (via a lambda at line 55). The lambda is anonymous and there is no stored reference, so it cannot be removed. EnhancedBubble has no close()/shutdown(
- **sim:bubble/BubbleGhostCoordinator.java:55-59** [resource-leak] BubbleGhostCoordinator: TickListener registered via lambda with no deregistration — resource leak on bubble dissolution
  - A lambda is registered with realTimeController.addTickListener() in the constructor. BubbleGhostCoordinator has no close(), stop(), or deregister() method. After bubble dissolution via merge or split, the BubbleGhostCoordinator is dereferen
- **sim:bubble/BubbleLifecycle.java:74** [correctness] BubbleLifecycle.performJoin: hardcodes spatialLevel=10 and targetFrameMs=10 — source config silently discarded
  - new EnhancedBubble(UUID.randomUUID(), (byte)10, 10L) uses fixed configuration regardless of the source bubbles. In a simulation where bubbles run at varying spatial levels or frame budgets (as BubbleDynamicsConfig supports), the merged bubb
- **sim:bubble/CubeForest.java:85-112** [correctness] CubeForest.classifyPoint: algorithm does not match t8code/Luciferase tetrahedral type ordering
  - classifyPoint() uses an ad-hoc xDominant/yDominant + upperDiagonal algorithm to map points to types 0-5. Per CLAUDE.md and the codebase, the correct t8code type ordering is: t0:x>=z>=y, t1:x>=y>=z, t2:y>=x>=z, t3:y>=z>=x, t4:z>=y>=x, t5:z>=
- **sim:bubble/RealTimeController.java:228-241** [clock-injection] RealTimeController.tickLoop: System.nanoTime() Clock-Injection Violation
  - The base RealTimeController has no injected Clock. tickLoop() calls System.nanoTime() directly at lines 228 and 241 for deadline-based scheduling. BucketSynchronizedController adds a Clock field and overrides tickLoop() correctly, but the b
- **sim:bubble/RealTimeController.java:201-212** [concurrency] RealTimeController.stop(): Deadlock if Called from a TickListener
  - stop() calls running.compareAndSet(true,false) then tickThread.join(1000). It never interrupts tickThread. If any TickListener registered via addTickListener() calls stop() (e.g. in an error-handling path), join() blocks forever: the tick t
- **sim:bubble/RealTimeController.java:228, 241** [clock-injection] RealTimeController: System.nanoTime() used directly — Clock mandate violated
  - tickLoop() uses System.nanoTime() directly for deadline scheduling and sleep-remainder calculation. RealTimeController has no Clock field. The parent-level tick driver — which fires all entity updates and TickListeners — cannot be driven by
- **sim:bubble/SimulationBubble.java:144** [correctness] SimulationBubble: Kairos.setController() Is a Global Static Side-Effect
  - The SimulationBubble constructor calls Kairos.setController(controller) which mutates global (static) PrimeMover state. When multiple SimulationBubble instances are created (e.g. MultiBubbleSimulation, CubeForest with 6 bubbles, or two bubb
- **sim:bubble/SimulationBubble.java:383-407** [correctness] SimulationBubble.physicsTick(): no running guard — perpetual execution after stop()
  - physicsTick() never checks the outer running flag before scheduling the next tick via Kronos.sleep(frameRateNs) + this.physicsTick(). stop() sets running = false and calls controller.stop(), but in-flight PrimeMover events continue executin

### bubble-migration
- **sim:bubble/BubbleBoundsTracker.java:118-125** [correctness] BubbleBoundsTracker.onEntityMoved only expands bounds — bounds grow monotonically, never shrink
  - onEntityMoved only calls bounds.expand(newPosition). It never recalculates from all positions. As entities move away from old extremes, the bounds keep old max extents indefinitely. Eventually every entity in a bubble appears 'outside' its 
- **sim:bubble/BubbleGhostCoordinator.java:33-60** [distributed] GhostStateManager receives stale BubbleBounds snapshot — ghost dead-reckoning clamped to initial (empty) bounds
  - The BubbleGhostCoordinator constructor passes `boundsTracker.bounds()` (a BubbleBounds value) to GhostStateManager. BubbleBounds is an immutable value type and BubbleBoundsTracker's bounds field is volatile; the snapshot captured at constru
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:267-290** [correctness] processTimeouts double-invocation — migrationFsm.processTimeouts called redundantly inside entity loop
  - processTimeouts(simulationTime) calls migrationFsm.checkTimeouts(simulationTime) to get a list of N timed-out entities, then for each entity in that list calls migrationFsm.processTimeouts(simulationTime) again. This causes the FSM to proce
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:160-201** [correctness] EnhancedBubbleMigrationIntegration: all migration actions commented out — migration never executes
  - detectAndInitiateMigrations() detects boundary crossings via MigrationOracle but every action that would actually execute a migration is commented out: optimisticMigrator.initiateOptimisticMigration(), migrationFsm.transition(entityId, MIGR
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:100, 162-168, 318-322** [correctness] String/UUID entity ID type split permanently breaks MIGRATING_IN→OWNED commit path
  - MigrationOracle.getEntitiesCrossingBoundaries() returns Set<String>. The loop variable entityId at line 164 is therefore a String. entityStabilityTicks is Map<UUID, Integer> (line 100). The MigrationStateListener callback onEntityStateTrans
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:267-289** [correctness] processTimeouts(): migrationFsm.processTimeouts() called per-entity inside loop, causing double timeout processing per tick
  - processTimeouts(simulationTime) calls migrationFsm.checkTimeouts(simulationTime) to get a list of timed-out entities, then inside the for-each loop, for each timed-out entity, calls migrationFsm.processTimeouts(simulationTime) again. Entity
- **sim:bubble/EntityDistribution.java:248-259** [correctness] EntityDistribution.findKeyForBubble returns placeholder TetreeKey(0,0,0) — all density-mapped entities get wrong key
  - findKeyForBubble iterates getAllBubbles, finds the matching bubble, but then returns TetreeKey.create((byte) 0, 0L, 0L) as an acknowledged placeholder. Every entity distributed via distributeByDensity() is therefore recorded in entityToBubb
- **sim:bubble/EntityDistribution.java:347-358** [correctness] EntityDistribution.keysAreCompatible uses raw SFC bit arithmetic — validation is meaningless
  - keysAreCompatible checks whether two TetreeKeys are compatible by comparing raw long high/low bit-field differences (highDiff <= 8 && lowDiff <= 8). The high and low bits of a TetreeKey are a Morton/SFC encoding of path-from-root; a differe
- **sim:bubble/RealTimeController.java:228-241** [clock-injection] System.nanoTime in RealTimeController.tickLoop — clock-injection mandate violated
  - tickLoop() calls System.nanoTime() directly twice: to seed the initial deadline and to compute remaining sleep time. This makes tick scheduling non-deterministic in tests and violates the project's mandatory Clock-injection invariant (all t
- **sim:bubble/RealTimeController.java:228, 241** [clock-injection] RealTimeController.tickLoop(): System.nanoTime() called directly — clock injection mandate violated
  - tickLoop() uses System.nanoTime() at lines 228 and 241 for deadline-based scheduling. RealTimeController is the root clock source for ALL bubble simulation timing. The injected Clock interface is present elsewhere but not used for nanoTime 
- **sim:bubble/TetrahedralMigration.java:252** [correctness] TetrahedralMigration.executeMigration(): System.err.println on rollback failure — SLF4J violation + cascade not observable
  - When the PHASE 3 rollback (removeEntity on destination) itself fails, the only record is `System.err.println('Rollback failed for entity ' + entityId)`. This violates the SLF4J mandate. More critically, the duplicate-entity state (entity ex
- **sim:bubble/TetreeGhostSyncAdapter.java:316-320** [clock-injection] GhostEntityHalo 5-arg constructor calls Clock.system() — covert wall-clock in TetreeGhostSyncAdapter
  - processBubbleBoundaryEntities creates GhostEntityHalo via the 5-argument constructor (entityId, content, position, null, bubbleId.toString()). That constructor is defined as `this(..., Clock.system().currentTimeMillis())`, making Clock.syst
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/bubble/EnhancedBubbleMigrationIntegrationTest.java:104-365** [test-gap] EnhancedBubbleMigrationIntegrationTest: tests are vacuous — no entity state verified, System.currentTimeMillis() in tests
  - Every test in this file asserts only one of: assertNotNull(integration), assertTrue(metrics.contains('initiated=')), or assertDoesNotThrow. No test verifies that an entity actually changes state, that a migration FSM transition happens, tha

### causality
- **sim:causality/EntityMigrationStateMachine.java:817-870** [concurrency] processTimeouts() holds StampedLock write lock while calling transition() which calls notifyListeners() synchronously
  - processTimeouts() acquires a StampedLock write lock at line 818 and calls transition() while holding it (lines 838, 853). transition() internally calls notifyListeners() (line 506), which invokes all registered MigrationStateListeners synch
- **sim:causality/EntityMigrationStateMachine.java:132-139** [clock-injection] Clock Injection Via Setter Not Constructor in Three Classes
  - EntityMigrationStateMachine (line 132), EventReprocessor (line 164), and GhostConsistencyValidator (line 107) all initialize clock as private volatile Clock clock = Clock.system() with a setClock() setter. This means any code that construct
- **sim:causality/EventReprocessor.java:164** [clock-injection] Clock injection violation: EventReprocessor, GhostConsistencyValidator, EntityMigrationStateMachine default to Clock.system() with no constructor injection
  - Three production classes in the causality package initialize their clock field as `private volatile Clock clock = Clock.system()`: EventReprocessor (line 164), GhostConsistencyValidator (line 107), and EntityMigrationStateMachine (line 132)

### consensus
- **sim:causality/FirefliesViewMonitor.java:417-422** [correctness] NPE when FirefliesViewMonitor.getCurrentViewId() returns null
  - getCurrentViewId() returns null when membershipView is not a FirefliesMembershipView instance. OptimisticMigratorIntegration.requestMigrationApproval() calls getCurrentViewId() and directly passes the result as the viewId constructor argume
- **sim:consensus/committee/CommitteeBallotBox.java:128** [distributed] Quorum computed from full-cluster context, not committee — permanent liveness failure
  - The quorum formula `context.size() == 1 ? 1 : context.toleranceLevel() + 1` uses the full-cluster DynamicContext. For a 100-node cluster, toleranceLevel()=33, quorum=34. But bftSubset() returns a 7-9 member committee (CommitteeConfig lines 
- **sim:consensus/committee/CommitteeProtoConverter.java:71** [serialization] hexToDigest() hardcodes DigestAlgorithm.DEFAULT — silent vote rejection with non-DEFAULT algorithms
  - CommitteeProtoConverter.hexToDigest() always constructs `new Digest(DigestAlgorithm.DEFAULT, bytes)`. Digest.equals() is algorithm-aware: the comparison at Digest.java:189 is `if (algorithm != other.algorithm) return false`. If the cluster 
- **sim:consensus/committee/CommitteeVotingProtocol.java:94-121, 143-155** [resource-leak] ProposalState leaks in proposals map on normal quorum completion
  - proposals.remove(proposalId) is called only in handleTimeout() (line 144) and rollbackOnViewChange() (line 188). The normal quorum-reached path — recordVote() detects result.isDone() and calls state.timeoutTask.cancel(false) — never removes
- **sim:consensus/committee/CommitteeVotingProtocol.java:94-121** [resource-leak] ProposalState memory leak on normal quorum success path
  - When a proposal reaches quorum via recordVote(), the execution path is: addVote() → future completes → cancel timeoutTask. There is no `proposals.remove(vote.proposalId())` call. The `proposals` ConcurrentHashMap only has entries removed in
- **sim:consensus/committee/ViewCommitteeConsensus.java:79-114** [correctness] Setter injection leaves ViewCommitteeConsensus unguarded against NPE
  - ViewCommitteeConsensus has a no-arg constructor and three separate setter methods (setViewMonitor, setCommitteeSelector, setVotingProtocol). All three fields are null after construction. Any call to requestConsensus() or onViewChange() befo
- **sim:consensus/committee/ViewCommitteeConsensus.java:79-113** [correctness] Setter injection with no lifecycle guard — NPE if wired out of order
  - ViewCommitteeConsensus has a no-arg constructor and three setter methods: setViewMonitor(), setCommitteeSelector(), setVotingProtocol(). If requestConsensus() is called before any of these are set (e.g., a race between service startup and f
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/consensus/committee/ByzantineFailureTest.java:52-251** [test-gap] No test for single-voter multi-vote Byzantine attack
  - All ByzantineFailureTest scenarios use distinct voter IDs for each Byzantine node (`DigestAlgorithm.DEFAULT.digest("byzantine-" + i)`). The actual attack — a single committee member submitting the same vote repeatedly — is never tested. Sin
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/consensus/committee/VirtualSynchronyTest.java:108-138** [test-gap] VirtualSynchronyTest.testAllNodesReceiveSameViewChangeSequence is vacuous
  - The test manually appends viewId objects to three `CopyOnWriteArrayList<Digest>` variables and then asserts they are all equal. No system code is invoked to produce or deliver these view IDs — the test fabricates the expected outcome itself

### distributed-grid-net
- **sim:causality/EntityMigrationStateMachine.java:450-489** [concurrency] EntityMigrationStateMachine.transition() has check-then-act race on state map
  - transition() reads currentState with entityStates.get(entityId) at line 450, validates the transition at line 459, then writes the new state with entityStates.put(entityId, newState) at line 489. Between the get and the put there is no lock
- **sim:distributed/grid/MigrationRouter.java:146-148** [correctness] MigrationDirection.apply() throws IAE for edge cells — latent bomb in public API
  - MigrationRouter.validateTarget() calls direction.apply(source) before calling gridConfig.isValid(). MigrationDirection.apply() constructs new BubbleCoordinate(row + dRow, col + dColumn), and BubbleCoordinate's compact constructor throws Ill
- **sim:distributed/integration/EntityAccountant.java:57-76** [concurrency] EntityAccountant.register()/unregister() bypass the ReentrantLock — invariant corruption
  - moveBetweenBubbles() and validate() both acquire the ReentrantLock before touching entityToBubble and bubbleToEntities, but register() and unregister() do not. register() performs two non-atomic writes: entityToBubble.put(entityId, bubbleId
- **sim:distributed/network/FakeNetworkChannel.java:45** [test-gap] FakeNetworkChannel static NETWORK map is JVM-global, enabling test cross-contamination
  - NETWORK is a static ConcurrentHashMap<UUID, FakeNetworkChannel> shared across all test instances in the same JVM classloader. Under parallel test execution (surefire forkCount=0 or parallel=classes), multiple test classes' @BeforeEach / @Af
- **sim:distributed/network/GrpcBubbleNetworkChannel.java:125-163** [distributed] GrpcBubbleNetworkChannel returns true before RPC completes — no retry, contract violated
  - sendEntityDeparture(), sendViewSynchronyAck(), and sendEntityRollback() all return true immediately after queuing the async RPC (before the stub callback fires). The onError callback only logs the failure. The BubbleNetworkChannel interface
- **sim:distributed/network/GrpcBubbleNetworkChannel.java:139-157,180-198,221-239** [distributed] All sendXxx() methods return true before async RPC completes, silently swallowing errors
  - sendEntityDeparture(), sendViewSynchronyAck(), and sendEntityRollback() all return true immediately after enqueuing the async gRPC stub call — before any response arrives. The onError() callback only logs. The BubbleNetworkChannel interface
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/network/FailureRecoveryTest.java:232-258** [test-gap] FailureRecoveryTest.testCascadingFailureObservation: second setEntityRollbackListener overwrites the observer under test
  - The test sets an EntityRollbackListener at line 232 that sets failureObserved=true. A rollback event is sent at lines 250-252. Then at line 255 a SECOND setEntityRollbackListener call overwrites the first lambda before the async delivery ca
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/network/FailureRecoveryTest.java:321-323** [test-gap] FailureRecoveryTest.testConsistencyUnderConcurrentFailures: vacuous assertions prove nothing
  - The three final assertions are assertTrue(pendingCount >= 0) for migratorA, B, and C. A non-negative pending count is always true — it holds even if every migration failed catastrophically. No actual consistency property is asserted: there 

### ghost
- **sim:ghost/BubbleGhostManager.java:252-254** [correctness] BubbleGhostManager.handleGhostBatch — incoming ghost lifecycle state never created, onUpdate is always a no-op
  - handleGhostBatch() calls `lifecycle.onUpdate(ghost.entityId().toDebugString(), ghost.timestamp())` for each received ghost. GhostLifecycleStateMachine.onUpdate() uses `states.computeIfPresent()` — if no entry for that entityId exists, it si
- **sim:ghost/BubbleGhostManager.java:358-370** [clock-injection] BubbleGhostManager.createGhostEntity() — clock injection missing (project invariant violation)
  - createGhostEntity() uses the 5-argument GhostEntityHalo constructor which internally calls `Clock.system().currentTimeMillis()`. BubbleGhostManager has no Clock field and no setClock() seam. This violates the project's mandatory clock-injec
- **sim:ghost/DuplicateEntityDetector.java:227-255** [distributed] DuplicateEntityDetector.detectAndReconcile(): non-atomic scan then reconcile can cause entity loss
  - scan() at line 228 builds entity-location data at time T1. reconcile() at lines 229-245 acts on that snapshot at time T2. Between T1 and T2, another migration may move the entity to a third bubble. The MigrationLog entry now points to the n
- **sim:ghost/GhostBoundarySync.java:103,207,234,284-285** [resource-leak] GhostBoundarySync.expiredGhosts: unbounded memory leak — clearExpiredGhosts() never called from production code
  - expiredGhosts: Map<ID, Long> is populated on every ghost expiry (line 207) and every memory-limit eviction (line 234). clearExpiredGhosts() exists at line 284 but is never referenced by any production code path — only called in one test. Ov
- **sim:ghost/InMemoryGhostChannel.java:124-133** [concurrency] InMemoryGhostChannel.flush() — ghosts queued during flush window are silently dropped
  - flush() takes a snapshot with `new ArrayList<>(ghosts)` and then calls `ghosts.clear()`. CopyOnWriteArrayList.clear() atomically replaces the internal array with an empty one. Any queueGhost() call completing between the snapshot and the cl
- **sim:ghost/MigrationLog.java:241-250** [distributed] MigrationLog.cleanupBefore(): non-atomic token cleanup allows idempotency record loss
  - cleanupBefore() finds entities whose migration history is now empty and removes them from both migrationHistory and entityTokens. The removal of the entity from entityTokens at line 249 is not atomic with the history becoming empty. A concu
- **sim:ghost/P2PGhostChannel.java:141, 227** [resource-leak] P2PGhostChannel.close() — event listener never removed (permanent leak)
  - The constructor registers the event listener with `vonBubble.addEventListener(this::handleEvent)`. close() calls `vonBubble.removeEventListener(this::handleEvent)`. In Java, each evaluation of `this::handleEvent` produces a new Consumer ins
- **sim:ghost/P2PGhostChannel.java:141,227** [resource-leak] P2PGhostChannel: Event listener is never removed on close()
  - The constructor registers `this::handleEvent` with the Bubble event system at line 141. close() at line 227 calls `vonBubble.removeEventListener(this::handleEvent)`, which creates a new lambda instance at the call site. CopyOnWriteArrayList
- **sim:ghost/P2PGhostChannel.java:349-351** [correctness] P2PGhostChannel: ID type erasure — unchecked cast to caller's ID type produces ClassCastException at use site
  - fromTransportGhost() always reconstructs the entity ID as `(ID) new StringEntityID(tg.entityId())`. The @SuppressWarnings("unchecked") suppresses the compiler's warning. If the P2PGhostChannel is instantiated with any ID type other than Str
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/ghost/Phase3IntegrationTest.java:398-462** [test-gap] Phase3IntegrationTest.testPartitionRecovery(): completely vacuous — no partition is simulated and no meaningful assertion made
  - The test claims to validate 'partition recovery' but (a) the partition is simulated by sending empty ghost batches (`List.of()`), which does not stop ghost delivery, does not degrade NC, and is not a partition; and (b) all three NC assertio

### lifecycle-tick-events
- **sim:lifecycle/LifecycleCoordinator.java:806-821** [correctness] Rollback of STARTING components silently leaks half-initialized state
  - stopLayer() during startup rollback filters for STARTING|RUNNING components (line 807) and calls stop() on them. AbstractLifecycleAdapter.stop() rejects STARTING state with 'Cannot stop from state: STARTING'. The rejection is swallowed via 
- **sim:scheduling/BucketScheduler.java:313-317** [correctness] BucketScheduler.toString() NPE when legacy constructor used
  - The legacy 4-argument constructor (lines 220-232) sets entity=null and controller=null. toString() at line 316 calls entity.getCurrentBucket() unconditionally. Any SLF4J logging that references a legacy BucketScheduler (e.g., in debug or wa
- **sim:tick/SimulationTickOrchestrator.java:126-129** [correctness] Null bubble passed to entityUpdater.updateEntities causes silent NPE on every tick
  - executeTick() calls entityUpdater.updateEntities(bubble, deltaTime) without null-checking the result of bubbleGrid.getBubble(). BubbleGrid.getBubble() documents that it returns null for empty cells ('Returns null if the cell is empty'). Whe

### migration
- **sim:distributed/migration/CrossProcessMigration.java:679-690** [correctness] Total-migration latency measured from COMMIT start, not PREPARE start — metric severely under-reports
  - When the COMMIT phase succeeds, totalLatency is computed at line 688 as `clockSupplier.getAsLong() - phaseStartTime`. But phaseStartTime was last updated at line 634 (`currentState = State.COMMIT; phaseStartTime = clockSupplier.getAsLong()`
- **sim:distributed/migration/CrossProcessMigration.java:803-807** [correctness] Mock snapshot used in rollback path — crash recovery restores garbage entity state
  - createEntitySnapshot() at line 803, which produces the EntitySnapshot used for rollback abort (lines 730-736), always returns a hardcoded synthetic snapshot: position=(0,0,0), content="MockContent", epoch=1, version=1. The comment acknowled
- **sim:distributed/migration/CrossProcessMigration.java:1-810** [distributed] WAL (MigrationLogPersistence) is completely disconnected from CrossProcessMigration — crash recovery promise is void
  - CrossProcessMigration never calls MigrationLogPersistence.recordPrepare(), recordCommit(), or recordAbort(). The class that exists to provide crash recovery is never invoked by the 2PC orchestrator. If a process crashes after PREPARE (entit
- **sim:distributed/migration/CrossProcessMigration.java:665-726** [correctness] abort() totalTimeout check measures ABORT phase duration, not overall transaction duration
  - phaseStartTime is reset to the current time at each COMMIT-to-ABORT transition (lines 668, 679, 698). abort() then computes totalElapsed = clockSupplier.getAsLong() - phaseStartTime (line 716) and compares against config.totalTimeoutMs(). B
- **sim:distributed/migration/CrossProcessMigration.java:772-798** [resource-leak] unlock() exception in failAndUnlock/succeedAndUnlock leaves CompletableFuture permanently incomplete
  - Both succeedAndUnlock() and failAndUnlock() wrap migrationLock.unlock(), decrementConcurrent.run(), and resultFuture.complete() in a single try/catch block. If migrationLock.unlock() throws (e.g. IllegalMonitorStateException due to a PrimeM
- **sim:distributed/migration/MigrationLogPersistence.java:229** [serialization] WAL deserialization will fail at runtime: TransactionState.snapshot is EntitySnapshot (contains Point3d, no Jackson annotations)
  - loadIncomplete() calls `mapper.treeToValue(jsonObject, TransactionState.class)` to deserialize full PREPARE records. TransactionState has a field `EntitySnapshot snapshot` (line 58 of TransactionState.java). EntitySnapshot contains a `Point
- **sim:distributed/migration/MigrationLogPersistence.java:101-106, 131, 155, 179** [correctness] WAL flush is not durable — PrintWriter.flush() does not fsync; crash between flush and process exit loses WAL record
  - The class Javadoc promises 'Atomic writes: Write to temp file, fsync, then rename to prevent corruption'. The implementation does none of this. It opens a plain PrintWriter wrapping an OutputStream, and calls writer.flush() after each write
- **sim:distributed/migration/OptimisticMigratorImpl.java:123-142** [distributed] requestMigrationApproval() always returns true even when consensusIntegration is set
  - When consensusIntegration is non-null, the code comments say 'Delegating migration approval to consensus' but returns CompletableFuture.completedFuture(true) unconditionally with the comment 'For now, default to approved when Digest convers
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/migration/CrossProcessMigrationTest.java:344-346** [test-gap] testConcurrentMigrationsSameEntity is @Disabled — C1 invariant has zero running test coverage
  - The one test designed to verify that concurrent migrations of the same entity are safely serialized (C1: entity migration lock) is disabled. The disable reason cites 'Thread.sleep() in simulateDelay() blocks the entire event loop.' This mea
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/migration/CrossProcessMigrationTest.java:507-570** [test-gap] No test exercises the non-TestableEntityStore production code path in CrossProcessMigration
  - Every test in CrossProcessMigrationTest uses TestBubbleReference, which implements TestableEntityStore. CrossProcessMigration.prepare() and commit() branch on instanceof TestableEntityStore; the else branch for production BubbleReference un

### persistence-misc
- **sim:delos/fireflies/FirefliesMembershipView.java:118-122** [distributed] FirefliesMembershipView.ViewChange.left Is Always Empty in Production
  - handleDelosViewChange resolves leaving member Digests by calling delosChange.context().getMember(digest) where context() is the NEW post-change DynamicContext. Members that have just left are no longer present in this context, so getMember 
- **sim:delos/fireflies/FirefliesMembershipView.java:56-57** [resource-leak] FirefliesMembershipView Leaks Listener Registration — Prevents GC
  - The constructor calls view.register(listenerKey, this::handleDelosViewChange) with a randomly generated UUID key and never calls view.unregister(). The Delos View holds a strong reference to the bound method reference, which closes over thi
- **sim:persistence/EventRecovery.java:72-111** [correctness] Recovery Never Filters Events Against Checkpoint Sequence Number
  - EventRecovery.recover() loads the checkpoint metadata (obtaining its sequenceNumber) but then calls wal.readAllEvents() which returns every event in the log directory unconditionally. The checkpoint sequence number is never used to call rea
- **sim:tumbler/BubbleMigrator.java:107-136** [concurrency] BubbleMigrator.migrate() Check-Then-Act Race Allows Duplicate Migrations
  - The containsKey guard, concurrent-migration count check, and inFlightMigrations.put are three non-atomic operations on a ConcurrentHashMap. Two concurrent threads calling migrate() with the same bubbleId can both observe containsKey=false, 

### render
- **sim:viz/render/RegionBuilder.java:298-317** [clock-injection] Clock injection violation: System.nanoTime() in RegionBuilder.doBuild()
  - doBuild() calls System.nanoTime() directly for build-time measurement despite the class holding an injected Clock. The Clock interface exposes nanoTime() as a default method (delegates to System.nanoTime() in production; overridable in test
- **sim:viz/render/RegionBuilder.java:227-244** [concurrency] RegionBuilder.build() queue-size check-then-act TOCTOU
  - The backpressure check reads queueSize atomically (line 227: queueSize.get() >= maxQueueDepth) but the subsequent offer-to-queue and increment (lines 243-244: buildQueue.offer(request); queueSize.incrementAndGet()) are not atomic with the c

### topology
- **sim:topology/BubbleSplitter.java:175** [correctness] Split proposal validates a phantom plane; execution uses a different strategy-computed plane
  - SplitProposal.validate() verifies that the proposal's embedded splitPlane intersects the entity bounds. But BubbleSplitter.execute() ignores the proposal's split plane at line 175 and computes a fresh one via `strategy.calculate(sourceBubbl
- **sim:topology/BubbleSplitter.java:231-281** [correctness] BubbleSplitter: Entity Leak When Exception Thrown Between Accountant Move and Grid Insertion
  - The entity-move loop at lines 231-249 moves entities into the accountant under newBubbleId via moveBetweenBubbles(), but newBubble is only added to the grid at line 281 (after all entities have been moved and conservation checks have passed
- **sim:topology/BubbleSplitter.java:295** [correctness] Split Metrics Double-Counted: BubbleSplitter and TopologyExecutor Both Record Success/Failure
  - BubbleSplitter.execute() calls metrics.recordSplitSuccess() at line 295 on the success path. TopologyExecutor.execute() also calls metrics.recordSplitSuccess() at line 234 for the same operation. On failure, BubbleSplitter calls metrics.rec
- **sim:topology/TopologyConsensusCoordinator.java:173-197** [concurrency] TOCTOU cooldown race in TopologyConsensusCoordinator
  - canProposeTopologyChange() (line 173) and updateCooldownTimestamps() (line 197) are separate, non-atomic operations on the ConcurrentHashMap. Two concurrent callers proposing topology changes for the same bubble can both observe lastChangeT
- **sim:topology/TopologyConsensusCoordinator.java:186-200** [distributed] BFT Consensus Is a Hollow Stub — All Proposals Unconditionally Approved
  - requestConsensus() never calls consensusProtocol. The comment at line 187 reads 'For Phase 9B, we use pre-validation as the consensus mechanism' and the method returns CompletableFuture.completedFuture(true) without ever invoking consensusP
- **sim:topology/TopologyConsensusCoordinator.java:172-197** [concurrency] TOCTOU Race on Cooldown Check: Two Concurrent Proposals Can Both Pass
  - canProposeTopologyChange() (read of lastChangeTimestamps) and updateCooldownTimestamps() (write to lastChangeTimestamps) are two separate non-atomic operations called sequentially in requestConsensus(). Two threads calling requestConsensus(
- **sim:topology/TopologyExecutor.java:282-283** [correctness] NPE on MoveProposal failure: bubble.bounds().centroid() on null bubble
  - After the move executor returns (success or failure), TopologyExecutor unconditionally calls `bubbleGrid.getBubbleById(move.sourceBubble())` and then `bubble.bounds().centroid()` to build the MoveEvent. When the move fails with 'Source bubb
- **sim:topology/TopologyExecutor.java:233-237** [correctness] Double-counting of split metrics: recordSplitSuccess/Failure called twice per operation
  - BubbleSplitter.execute() calls metrics.recordSplitAttempt() (line 149), metrics.recordSplitFailure(reason) (lines 157, 169, 186, 212), and metrics.recordSplitSuccess() (line 295). TopologyExecutor.execute() also calls metrics.recordSplitSuc
- **sim:topology/TopologyExecutor.java:240-248** [correctness] SplitEvent.entitiesMoved is always zero for any conservation-passing split
  - The entitiesMoved field of SplitEvent is computed as `Math.abs(result.entitiesAfter() - result.entitiesBefore())`. SplitExecutionResult.entitiesBefore is the entity count in the source bubble before the split; entitiesAfter is the total acr
- **sim:topology/TopologyExecutor.java:241-248, 262-269, 285-293, 297-311** [distributed] TopologyExecutor: Events Fired Before Post-Op Validation; Listeners Observe Phantom State on Rollback
  - fireEvent(new SplitEvent(...success=true)) is called at line 241 before the entity-conservation check at lines 297-311. If conservation fails (totalAfter != totalBefore), rollback is triggered at line 307, but listeners have already receive
- **sim:topology/metrics/BoundaryStressAnalyzer.java:99** [concurrency] BoundaryStressAnalyzer: unsynchronized ArrayList.add() races with synchronized iteration
  - recordMigration() at line 99 does `computeIfAbsent(..., k -> new ArrayList<>()).add(timestamp)` without holding the list's own monitor. getMigrationRate() at line 124 and cleanOldEntries() at lines 189/227 synchronize on the same list objec
- **sim:topology/metrics/TopologyMetricsCollector.java:92-95** [clock-injection] TopologyMetricsCollector.setClock() does not propagate clock to DensityMonitor
  - TopologyMetricsCollector.setClock() propagates the injected clock to BoundaryStressAnalyzer but not to DensityMonitor. DensityMonitor retains Clock.system() even after a test clock is injected into the collector. Density state-change events

### von-core
- **sim:von/Bubble.java:380-385** [concurrency] Bubble.close() check-then-act race on volatile boolean — double broadcastLeave
  - The idempotency guard for close() uses a plain volatile boolean:
`private volatile boolean closed = false;`
with the guard:
`if (closed) { return; }
closed = true;`
This is a check-then-act pattern that is not atomic. Two threads calling cl
- **sim:von/RecoveryIntegration.java:171, 346-356** [resource-leak] RecoveryIntegration.close() leaks FaultHandler subscription — permanent event delivery to shut-down object
  - faultHandler.subscribeToChanges(recoveryEventHandler) returns a Subscription handle that provides unsubscribe(). The return value is discarded at line 171. close() contains the comment 'Note: FaultHandler doesn't provide removeEventListener
- **sim:von/SpatialNeighborIndex.java:285, 335** [correctness] SpatialNeighborIndex.findOverlapping / isEnclosingNeighbor dereference null bounds — NPE on empty Bubble
  - BubbleBoundsTracker.bounds() returns null when a Bubble contains no entities. SpatialNeighborIndex.findOverlapping (line 285) executes `.filter(n -> n.bounds().overlaps(queryBounds))` without a null guard. isEnclosingNeighbor (line 335) doe
- **sim:von/transport/SocketTransport.java:291-297** [resource-leak] SocketTransport TickListener temporal leak: orTimeout() registers on a different future than whenComplete()
  - sendToNeighborAsync creates 'var future = new CompletableFuture<>()'. At line 291, checkStability is registered via controller.addTickListener(checkStability). At line 294, cleanup is registered: 'future.whenComplete(... controller.removeTi

### von-transport
- **sim:von/transport/SocketClient.java:142-150, 175-181** [concurrency] SocketClient.close() not synchronized — can corrupt the wire stream mid-write
  - `send()` is `synchronized` (line 142) but `close()` is not (line 175). `close()` sets `connected = false` and then calls `socket.close()` without holding the send lock. Scenario: Thread A is executing `send()`, has passed the `if (!connecte
- **sim:von/transport/SocketClient.java:142-149, 175-181** [concurrency] SocketClient.close() is unsynchronized — concurrent close+send produces stream corruption on the receiver
  - `send()` is `synchronized(this)` but `close()` is not. A concurrent `close()` can set `connected = false` and call `socket.close()` while `send()` holds the lock and is mid-`writeObject`. The socket close interrupts the in-progress write, e
- **sim:von/transport/SocketClient.java:142-149** [resource-leak] ObjectOutputStream reference table never reset — unbounded heap growth on long-lived connections
  - SocketClient reuses a single `ObjectOutputStream` for the lifetime of a connection (created at line 87 in `connect()`). Java's `ObjectOutputStream` maintains an internal back-reference table of every object ever written on the stream to ena
- **sim:von/transport/SocketConnectionManager.java:84-91** [concurrency] connectTo() check-then-act race on ConcurrentHashMap — duplicate connection leak
  - The idempotency check `if (clients.containsKey(remote.processId()))` followed by `clients.put(remote.processId(), client)` is not atomic. Two threads calling `connectTo()` concurrently with the same `processId` can both pass the `containsKe
- **sim:von/transport/SocketTransport.java:242-298, 342-344** [resource-leak] closeAll() does not cancel in-flight ACK futures — tick listeners run for up to 5 seconds post-teardown
  - `closeAll()` (line 342) delegates to `connectionManager.closeAll()` which closes sockets and clears client state, but makes no attempt to cancel pending `sendToNeighborAsync` futures. Each pending future has a `TickListener` registered with
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/von/transport/SocketTransportFirefliesAckTest.java:211-225, 162-182** [test-gap] Vacuous tests for the two most critical ACK behaviors: timeout and view-change failure
  - Test 4 (`testTimeoutWhenViewNeverStabilizes`, lines 211-225) contains only `assertTrue(true, ...)` with a comment admitting it does nothing. Test 2 (`testViewChangeDetection`, lines 162-182) explicitly acknowledges it does not test view-cha
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/von/transport/SocketTransportFirefliesAckTest.java:215-225** [test-gap] testTimeoutWhenViewNeverStabilizes is a vacuous tombstone — zero coverage of the 5-second timeout failsafe
  - The test body is `assertTrue(true, "Timeout behavior documented - implement if critical")`. The 5-second `orTimeout` is the only mechanism preventing permanently hung tick listeners when the Fireflies view never stabilizes (e.g., network pa

## Confirmed MEDIUM (32)

- **sim:behavior/FlockingBehavior.java:170-172** [concurrency/behavior-spatial] cleanupRemovedEntities() mutates map while computeVelocity() iterates it
- **sim:spatial/DeadReckoningEstimator.java:129-160** [concurrency/behavior-spatial] DeadReckoningEstimator.predict() is a non-atomic read-modify-write — concurrent double-correction
- **sim:behavior/PredatorBehavior.java:1-305** [test-gap/behavior-spatial] No unit tests for PredatorBehavior, PreyBehavior, PackHuntingBehavior, CompositeEntityBehavior, or EnhancedVolumeAnimator
- **sim:bubble/RealTimeController.java:192, 201-212** [concurrency/bubble-core] RealTimeController: non-daemon thread with self-deadlock potential in stop()
- **sim:bubble/BucketSynchronizedController.java:77-86** [concurrency/bubble-core] BucketSynchronizedController: TOCTOU race in bucket detection + overrun clock drift
- **sim:bubble/BucketSynchronizedController.java:1-140** [test-gap/bubble-core] BucketSynchronizedController has zero test coverage
- **sim:bubble/TetrahedralContainmentChecker.java:93-106** [performance/bubble-migration] TetrahedralContainmentChecker.findBubbleKey is O(n²) per bubble per tick
- **sim:bubble/EnhancedBubbleMigrationIntegration.java:103-106** [concurrency/bubble-migration] Unsynchronized long counters in EnhancedBubbleMigrationIntegration mutated from multiple threads
- **sim:bubble/TetreeGhostSyncAdapter.java:248-259** [performance/bubble-migration] TetreeGhostSyncAdapter.findBoundaryNeighbors(): per-call TetreeKey re-derivation from centroid — stale keys and per-tick cost
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/bubble/TetrahedralMigrationTest.java:174-214** [test-gap/bubble-migration] TetrahedralMigrationTest: no entity-count invariant verification across migration
- **sim:causality/EntityMigrationStateMachine.java:518-538** [distributed/causality] onViewChange() replaceAll skips per-entity listener notifications; MigrationCoordinator.onViewChangeRollback iterates and removes from same ConcurrentHashMap
- **sim:causality/LamportClockGenerator.java:129-133** [concurrency/causality] LamportClockGenerator.onRemoteEvent() Vector Timestamp and Local Clock Updated Non-Atomically
- **sim:causality/EventReprocessor.java:269-278** [correctness/causality] EventReprocessor maxLookaheadMs Is Computed But Never Enforced
- **sim:consensus/committee/CommitteeServiceImpl.java:53** [resource-leak/consensus] CommitteeServiceImpl.proposalResults grows without bound
- **sim:distributed/network/GrpcBubbleNetworkChannel.java:52** [resource-leak/distributed-grid-net] GrpcBubbleNetworkChannel uses unbounded newCachedThreadPool — OOM/resource exhaustion
- **sim:distributed/grid/GridMultiBubbleSimulation.java:271,394-395** [concurrency/distributed-grid-net] Ghost sync writes outside snapshotLock while getAllEntities reads ghosts inside it
- **sim:ghost/GhostStateManager.java:233-251** [concurrency/ghost] GhostStateManager.updateGhost() — TOCTOU race on maxGhosts limit allows unbounded ghost count
- **sim:ghost/GhostBoundarySync.java:103, 207, 234, 284** [resource-leak/ghost] GhostBoundarySync.expiredGhosts — unbounded memory growth, clearExpiredGhosts() never called
- **sim:ghost/GhostStateManager.java:233-235** [concurrency/ghost] GhostStateManager: TOCTOU race on maxGhosts check allows limit to be exceeded
- **sim:distributed/migration/MigrationOracleImpl.java:113-114, 157-161, 188-210** [concurrency/migration] bubbleMap and bubbleCoordinates are plain HashMap — concurrent reads/writes cause data corruption
- **sim:distributed/migration/MigrationOracleImpl.java:235-249** [concurrency/migration] getEntitiesCrossingBoundaries() non-atomic clear+refill — concurrent callers see empty set window
- **sim:distributed/migration/OptimisticMigratorImpl.java:77-81** [concurrency/migration] OptimisticMigratorImpl metrics are plain long fields — lost increments under concurrent access
- **sim:persistence/CheckpointMetadata.java:44-46** [clock-injection/persistence-misc] Clock-Injection Violation: CheckpointMetadata.now() and EventRecovery Use Instant.now()
- **sim:persistence/EventRecovery.java:142-145** [test-gap/persistence-misc] validateRecoveryIntegrity() Is Vacuously True
- **sim:viz/render/RegionStreamer.java:754-759** [correctness/render] onRegionBuilt() cache-key LOD mismatch silently drops completed builds
- **sim:viz/render/EntityStreamConsumer.java:303-307** [correctness/render] EntityStreamConsumer: null-deref NullPointerException on malformed upstream JSON
- **sim:topology/TopologyExecutor.java:240, 261** [correctness/topology] SplitEvent and MergeEvent Always Report entitiesMoved = 0
- **sim:von/transport/SocketServer.java:70, 107-108, 177-182** [concurrency/von-transport] SocketServer.serverSocket is not volatile — JMM data race between start() and shutdown()
- **sim:von/transport/SocketClient.java:64, 87-88, 142-150** [resource-leak/von-transport] ObjectOutputStream not reset() between messages — unbounded memory growth on long-lived connections
- **sim:von/transport/SocketServer.java:70, 96-131, 177-208** [concurrency/von-transport] SocketServer.serverSocket non-volatile — JMM publication hazard between start() and shutdown()
- **simulation/src/test/java/com/hellblazer/luciferase/simulation/von/transport/SocketTransportFirefliesAckTest.java:163-182** [test-gap/von-transport] testViewChangeDetection does not test view change detection — acknowledged TODO, critical path untested
- **sim:bubble/RealTimeController.java:228, 241** [clock-injection/von-transport] RealTimeController.tickLoop() uses System.nanoTime() — clock-injection mandate violated

## Refuted false-positives (18) — filtered by verification

- (High/von-core) MigrationProtocolMessages entirely unhandled in MessageConverter — runtime IAE if routed via Transport — _Inspected MessageConverter.java lines 48-68: the switch handles 8 concrete Message subtypes and throws IAE on default. MigrationProtocolMessages is permitted by_
- (Critical/von-transport) NPE in sendToNeighborAsync view-ID comparison when getCurrentViewId() returns null — _The finding claims that mock views (non-FirefliesMembershipView) can be passed as `membership`, causing `getCurrentViewId()` to return null. This is factually w_
- (Critical/migration) ALREADY_MIGRATING path leaks concurrent-migration gauge forever — _Lines 538-540: `incrementConcurrent.run()` is called only inside the `if (migrationLock.tryLock())` branch — i.e., only when the lock is successfully acquired._
- (High/migration) Kronos.sleep() called from acquireLock() which lacks @Blocking — PrimeMover continuation semantics violated — _The finding conflates Kronos.sleep() with Kronos.blockingSleep(). Inspecting /tmp/pm-api3/com/hellblazer/primeMover/api/Kronos.java (PrimeMover 1.0.6 sources):_
- (High/migration) Kairos.setController() is a global/thread-local setter called per-migrate() — race condition under concurrent migrations — _Inspected the actual implementation chain: `Kairos.setController(controller)` (line 272) delegates to `Framework.setController((Devi) controller)`, and `Framewo_
- (High/migration) IdempotencyStore uses single map namespace for both toUUID() and migrationKey() tokens — removeMigration leaks full-token entries — _The finding accurately describes the shared-map structure: both checkAndStore() (line 138, uses token.toUUID()) and checkAndStoreMigration() (line 180, uses tok_
- (High/bubble-core) EnhancedBubble Constructor: GhostCoordinator Receives Frozen Initial Bounds Snapshot — _The finding claims "Ghost acceptance decisions made by GhostStateManager will be based on the entire root tetrahedron extent." This is wrong about what bounds i_
- (Critical/lifecycle-tick-events) BucketBarrier latch-swap race causes spurious barrier timeouts — _Inspected lines 101-113 (recordNeighborReady) and lines 125-147 (waitForNeighbors) directly.

The described race requires two simultaneous conditions on Thread_
- (High/lifecycle-tick-events) Kairos.setController() is a static global — multiple BucketSchedulers poison each other — _The finding claims Kairos uses "static/thread-local JVM-global state" but then argues as if it were purely static. Inspecting the source chain:

- `Kairos.setCo_
- (Critical/persistence-misc) WAL Rotation Counter Resets on Restart, Overwriting Existing Log Files — _Inspected lines 317-328 and the rotate() method (lines 204-226) directly.

Rotated filenames are constructed as `node-<UUID>-<rotationCount>.log` (line 220). Wh_
- (Critical/von-transport) RejectedExecutionException uncaught in SocketServer accept loop — accept thread terminates abnormally and leaks socket — _Inspected /Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/com/hellblazer/luciferase/simulation/von/transport/SocketServer.java lines 113-131 and 1_
- (Critical/migration) Kairos.setController() is a static side-effect called per migrate() — global state race with multiple instances — _Inspected CrossProcessMigration.java line 272: `Kairos.setController(controller)` is called before `entity.startMigration()`. The finding claims this writes to_
- (High/consensus) NPE when getCurrentViewId() returns null before first Fireflies view established — _The finding misidentifies which operand would be null. At line 143: `proposal.viewId().equals(currentViewId)` — `currentViewId` (potentially null from `Fireflie_
- (High/distributed-grid-net) MultiDirectionalMigration PREPARE/COMMIT phase gap: entity can migrate twice in one tick — _The finding's core premise is wrong. In `checkMigrations` (lines 86-120), the PREPARE phase (lines 88-108) completes its entire nested loop over all bubbles — s_
- (Critical/bubble-core) SimulationBubble constructor: Kairos.setController() is a process-global write — last writer wins in multi-bubble scenarios — _The finding claims `Kairos.setController()` writes to a "static global slot" causing last-writer-wins. This is false. Inspecting the PrimeMover 1.0.6 source at_
- (High/bubble-core) BubbleBounds.contains() is an AABB test despite documentation claiming tetrahedral containment — _Inspected /Users/hal.hildebrand/git/Luciferase/simulation/src/main/java/com/hellblazer/luciferase/simulation/bubble/BubbleBounds.java lines 218-234 and the full_
- (High/bubble-migration) BubbleBounds.contains() is AABB despite class documenting 'NOT AABB' tetrahedral containment — _Independent inspection of BubbleBounds.java refutes the finding. The class-level Javadoc "Uses TetreeKey + RDGCS coordinates (NOT AABB)" (line 21) refers to the_
- (High/bubble-migration) EntityPhysicsManager.updateBubbleEntities(): remove-then-add entity update creates transient entity loss window — _Inspected MultiBubbleSimulation.tick() (lines 280-290): the tick loop is strictly sequential — Step 1 (updateBubbleEntities, which calls physicsManager.updateBu_

## Appendix: unverified Medium/Low (117) — not adversarially checked

- (Low/behavior-spatial) [correctness] Kronos.sleep() called with potentially negative duration in AnimationFrame.track() (sim:animation/VolumeAnimator.java:163)
- (Low/behavior-spatial) [concurrency] StockNeighborList capacity enforcement TOCTOU allows momentary over-capacity (sim:spatial/StockNeighborList.java:80-91)
- (Low/bubble-core) [maintainability] TetrahedralMigration: System.err.println Instead of SLF4J (sim:bubble/TetrahedralMigration.java:252)
- (Low/causality) [correctness] GhostStateListener passes hardcoded zero-velocity to consistency validator, making validation vacuous for moving entities (sim:causality/GhostStateListener.java:279-298)
- (Low/consensus) [maintainability] Dead CommitteeConfig fields (committeeSizeMin, committeeSizeMax, requiredQuorumRatio) and unused PropagationStrategy (sim:consensus/committee/CommitteeConfig.java:26-35)
- (Low/consensus) [test-gap] Thread.sleep timing dependencies in tests create CI flakiness (simulation/src/test/java/com/hellblazer/luciferase/simulation/consensus/committee/integration/EndToEndMigrationTest.java:195-199)
- (Low/consensus) [maintainability] PropagationStrategy is dead code with no production callers (sim:consensus/committee/PropagationStrategy.java:36-88)
- (Low/distributed-grid-net) [correctness] Ghost TTL off-by-one — actual ghost lifetime is TTL_BUCKETS+1 (sim:distributed/grid/GridGhostSyncAdapter.java:128-135)
- (Low/ghost) [correctness] BubbleGhostManager.onBucketComplete — double invocation of expireStaleGhosts per bucket (sim:ghost/BubbleGhostManager.java:212-220)
- (Low/lifecycle-tick-events) [test-gap] STARTING-state rollback during coordinator startup has no test coverage (sim:lifecycle/LifecycleCoordinator.java:519-559)
- (Low/migration) [correctness] abort() logs 'Restored entity' at DEBUG even when rollback failed — misleading observability (sim:distributed/migration/CrossProcessMigration.java:739-754)
- (Low/migration) [correctness] Test-infrastructure Thread.sleep() in production interface (TestableEntityStore.simulateDelay) (sim:distributed/migration/TestableEntityStore.java:58-65)
- (Low/render) [performance] EntityStreamConsumer: Thread.sleep() in virtual-thread reconnect loop (sim:viz/render/EntityStreamConsumer.java:254, 275)
- (Low/render) [resource-leak] RenderingServer.stop(): viewportTracker not nulled, leaking reference (sim:viz/render/RenderingServer.java:401-420)
- (Low/render) [correctness] MultiBubbleVisualizationServer.getBubbleBounds() always returns hardcoded stub bounds (sim:viz/MultiBubbleVisualizationServer.java:580-592)
- (Low/render) [correctness] StreamingSession: TOKEN_COUNTER is a static field — shared across all sessions and server instances (sim:viz/render/StreamingSession.java:46)
- (Low/topology) [test-gap] Test Suite: 37 System.currentTimeMillis() in Topology Test Proposals — Clock Not Injected (simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/:BubbleMergerTest.java:73,132,165,190,209,234; BubbleSplitterTest.java:75,122,146,168,197; BubbleMoverTest.java:86,114,136,173; MergeIntegrationTest.java:113,178,218,261; MoveIntegrationTest.java:99,152,192,226,260,299; BubbleSplitterStrategyIntegrationTest.java:93,133,171,222,230,304,343; TopologyEvolutionTest.java:78; DynamicTopologyDemo.java:95,125,162,209,279)
- (Low/von-core) [maintainability] MoveProtocol.move() boundary detection is dead code — both branches call identical method (sim:von/MoveProtocol.java:82-88)
- (Low/von-core) [correctness] RecoveryIntegration BFS marks dependent cooldown before recovery executes (sim:von/RecoveryIntegration.java:483-489)
- (Low/von-core) [test-gap] MessageConverterTest does not verify timestamp, epoch, version, or bucket round-trip for GhostSync (simulation/src/test/java/com/hellblazer/luciferase/simulation/von/MessageConverterTest.java:234-340)
- (Low/von-transport) [resource-leak] Non-daemon RealTimeController tick thread can prevent JVM shutdown (sim:bubble/RealTimeController.java:191-193)
- (Medium/behavior-spatial) [correctness] PredatorBehavior.isChasing uses wrong condition — pursuitSpeed applied to wander velocity (sim:behavior/PredatorBehavior.java:152-156)
- (Medium/behavior-spatial) [correctness] PackHuntingBehavior flanker rotation is a 2D XZ rotation — wrong for 3D space (sim:behavior/PackHuntingBehavior.java:316-325)
- (Medium/behavior-spatial) [correctness] FlockingBehavior and PreyBehavior minSpeed check uses pre-clamp speed variable (sim:behavior/FlockingBehavior.java:209-225)
- (Medium/behavior-spatial) [clock-injection] Clock-injection gap: VolumeAnimator.AnimationFrame.lastActive captured before test clock can be injected (sim:animation/VolumeAnimator.java:57,132)
- (Medium/behavior-spatial) [maintainability] VolumeAnimator uses java.util.logging instead of SLF4J (sim:animation/VolumeAnimator.java:37,49)
- (Medium/behavior-spatial) [concurrency] CompositeEntityBehavior uses unsynchronized HashMap for concurrent simulation access (sim:behavior/CompositeEntityBehavior.java:33,59-64,85-106)
- (Medium/bubble-core) [resource-leak] RealTimeController: Non-Daemon Tick Thread Blocks JVM Shutdown (sim:bubble/RealTimeController.java:191-193)
- (Medium/bubble-core) [correctness] BucketSynchronizedController: Silent Tick-Count Gap on Bucket Boundary Jump (sim:bubble/BucketSynchronizedController.java:101-115)
- (Medium/bubble-core) [distributed] BubbleDynamicsManager.transferEntityWithToken: Idempotency Log Written After Transfer (sim:bubble/BubbleDynamicsManager.java:418-426)
- (Medium/bubble-core) [correctness] BubbleLifecycle.performJoin: Wall-Clock Milliseconds Used as Simulation Bucket (sim:bubble/BubbleLifecycle.java:89-93)
- (Medium/bubble-core) [correctness] CubeForest.classifyPoint: Tet Type Classification Mismatches t8code Type Ordering (sim:bubble/CubeForest.java:85-111)
- (Medium/bubble-core) [concurrency] BubbleEntityStore.updateEntityPosition: Non-Atomic get-remove-insert Permits Zombie Entity (sim:bubble/BubbleEntityStore.java:118-138)
- (Medium/bubble-core) [test-gap] Test Gap: mergeBubbles Does Not Validate entityBubbles Reverse Map (simulation/src/test/java/com/hellblazer/luciferase/simulation/bubble/BubbleDynamicsManagerTest.java:142-178)
- (Medium/bubble-core) [test-gap] Test Gap: CubeForest.classifyPoint Not Validated Against t8code Type Boundaries (sim:bubble/CubeForest.java:85-111)
- (Medium/bubble-core) [test-gap] MultiBubbleSimulationMigrationTest.testNoEntityLossDuringMigration: vacuous correctness assertion (simulation/src/test/java/com/hellblazer/luciferase/simulation/bubble/MultiBubbleSimulationMigrationTest.java:100-121)
- (Medium/bubble-core) [maintainability] TetrahedralMigration: System.err.println on rollback failure — SLF4J violation (sim:bubble/TetrahedralMigration.java:252)
- (Medium/bubble-migration) [maintainability] TetrahedralMigration uses System.err for rollback failure logging — SLF4J mandate violated (sim:bubble/TetrahedralMigration.java:252)
- (Medium/bubble-migration) [distributed] TetreeGhostSyncAdapter.findBoundaryNeighbors re-derives bubble TetreeKey via centroid locate — may diverge from registration key (sim:bubble/TetreeGhostSyncAdapter.java:241-269)
- (Medium/bubble-migration) [distributed] Entity visible in two bubbles simultaneously during two-phase migration — ghost sync sees duplicates (sim:bubble/TetrahedralMigration.java:216-266)
- (Medium/bubble-migration) [resource-leak] BubbleGhostCoordinator tick listener registered but never unregistered — potential TickListener leak (sim:bubble/BubbleGhostCoordinator.java:56-59)
- (Medium/bubble-migration) [distributed] TetreeBubbleGrid.removeBubble(): stale Tetree entry allows findNeighbors to return removed bubble via TetreeNeighborFinder (sim:bubble/TetreeBubbleGrid.java:509-515)
- (Medium/bubble-migration) [maintainability] TetreeBubbleFactory.createBubbles(): calculates distribution then discards it — delegates to grid.createBubbles() which recalculates independently (sim:bubble/TetreeBubbleFactory.java:94-106)
- (Medium/causality) [concurrency] CausalityPreserver.canProcess / markProcessed TOCTOU: concurrent threads can both be cleared to process the same event (sim:causality/CausalityPreserver.java:119-175)
- (Medium/causality) [correctness] EventReprocessor.processReady(): withinMaxWindow dead code — force-process path is never reached (sim:causality/EventReprocessor.java:255-300)
- (Medium/causality) [concurrency] LamportClockGenerator.onRemoteEvent() non-atomic vector-timestamp and local-clock update (sim:causality/LamportClockGenerator.java:125-141)
- (Medium/causality) [clock-injection] Test uses System.currentTimeMillis() and System.nanoTime() directly — clock-injection violation in test scope (simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/EntityMigrationTimeoutIntegrationTest.java:476, 492, 494, 505, 507)
- (Medium/causality) [correctness] EventReprocessor eventTracker Keyed by Entity ID Only — Duplicate Detection Broken (sim:causality/EventReprocessor.java:229-230)
- (Medium/causality) [correctness] GhostStateListener Always Passes Zero Velocity to Consistency Validator (sim:causality/GhostStateListener.java:288-291)
- (Medium/causality) [concurrency] CausalityPreserver canProcess / markProcessed Not Atomic (sim:causality/CausalityPreserver.java:119-175)
- (Medium/causality) [test-gap] Concurrency Test for Single-Owner Invariant Has Vacuous Assertion (simulation/src/test/java/com/hellblazer/luciferase/simulation/causality/EntityMigrationStateMachineConcurrencyTest.java:58-90)
- (Medium/consensus) [distributed] View-change / requestConsensus TOCTOU: new proposal can escape rollback window (sim:consensus/committee/ViewCommitteeConsensus.java:163-190, 198-212)
- (Medium/consensus) [concurrency] Zombie VoteState resurrection after concurrent rollback + handleTimeout (sim:consensus/committee/CommitteeVotingProtocol.java:143-155, 177-189)
- (Medium/consensus) [resource-leak] proposalResults cache unbounded growth and incomplete error coverage in CommitteeServiceImpl (sim:consensus/committee/CommitteeServiceImpl.java:52-82, 117-137)
- (Medium/consensus) [clock-injection] System.currentTimeMillis() in test proposal factories — clock injection violated in all committee tests (simulation/src/test/java/com/hellblazer/luciferase/simulation/consensus/committee/CommitteeVotingProtocolTest.java:162, 278)
- (Medium/consensus) [resource-leak] approvedMigrations in OptimisticMigratorIntegration accumulates indefinitely (sim:consensus/committee/OptimisticMigratorIntegration.java:68)
- (Medium/consensus) [clock-injection] Clock injection mandate violated in 53+ test locations (simulation/src/test/java/com/hellblazer/luciferase/simulation/consensus/committee/ViewCommitteeConsensusTest.java:122-468)
- (Medium/consensus) [distributed] No per-entity migration mutex — two concurrent proposals for same entity can both reach quorum (sim:consensus/committee/ViewCommitteeConsensus.java:132-188)
- (Medium/distributed-grid-net) [concurrency] FakeNetworkChannel listener fields not volatile — visibility race under latency>0 (sim:distributed/network/FakeNetworkChannel.java:31-33)
- (Medium/distributed-grid-net) [resource-leak] FakeNetworkChannel ScheduledExecutorService leaked — no AutoCloseable contract on BubbleNetworkChannel (sim:distributed/network/FakeNetworkChannel.java:46-47)
- (Medium/distributed-grid-net) [resource-leak] GrpcBubbleNetworkChannel registers a new JVM shutdown hook on every initialize() call (sim:distributed/network/GrpcBubbleNetworkChannel.java:104-111)
- (Medium/distributed-grid-net) [test-gap] GridMultiBubbleSimulation.initializeVelocities() uses unseeded Random — test non-determinism (sim:distributed/grid/GridMultiBubbleSimulation.java:341-343)
- (Medium/distributed-grid-net) [concurrency] snapshotLock does not protect ghost data — false consistency guarantee in getAllEntities() (sim:distributed/grid/GridMultiBubbleSimulation.java:251-285)
- (Medium/distributed-grid-net) [correctness] initializeVelocities uses unseeded Random — non-deterministic simulation initialization (sim:distributed/grid/GridMultiBubbleSimulation.java:342)
- (Medium/distributed-grid-net) [resource-leak] GrpcBubbleNetworkChannel: unbounded cached thread pool is an OOM vector (sim:distributed/network/GrpcBubbleNetworkChannel.java:52)
- (Medium/distributed-grid-net) [resource-leak] GrpcBubbleNetworkChannel: JVM shutdown hook accumulates on every initialize() call (sim:distributed/network/GrpcBubbleNetworkChannel.java:104-111)
- (Medium/distributed-grid-net) [distributed] isNodeReachable is purely address-registry lookup — does not detect crashed nodes (sim:distributed/network/GrpcBubbleNetworkChannel.java:276-280)
- (Medium/ghost) [concurrency] SameServerOptimizer.enabled — plain boolean, visibility not guaranteed across threads (sim:ghost/SameServerOptimizer.java:61, 162-172)
- (Medium/ghost) [performance] InMemoryGhostChannel.sendBatch — Thread.sleep() in hot path blocks simulation thread (sim:ghost/InMemoryGhostChannel.java:103-109)
- (Medium/ghost) [concurrency] MigrationLog.cleanupBefore — token removed while concurrent recordMigration may lose idempotency guard (sim:ghost/MigrationLog.java:226-251)
- (Medium/ghost) [performance] GhostBoundarySync.onBucketComplete — all tracked ghosts re-sent every 100ms regardless of changes (sim:ghost/GhostBoundarySync.java:156-188)
- (Medium/ghost) [concurrency] SameServerOptimizer.enabled: non-volatile plain boolean — visibility not guaranteed across threads (sim:ghost/SameServerOptimizer.java:61,162)
- (Medium/ghost) [concurrency] GhostStateManager.tick(): ghost removal inside keySet() iteration is O(n) inconsistent under concurrent expiry (sim:ghost/GhostStateManager.java:304-314)
- (Medium/ghost) [correctness] InMemoryGhostChannel.sendBatch(): Thread.sleep in production path blocks calling thread without @Blocking (sim:ghost/InMemoryGhostChannel.java:103-109)
- (Medium/ghost) [clock-injection] GhostStateManagerNullSafetyTest uses System.currentTimeMillis() making staleness assertions non-deterministic (simulation/src/test/java/com/hellblazer/luciferase/simulation/ghost/GhostStateManagerNullSafetyTest.java:70,136,142,173,184)
- (Medium/lifecycle-tick-events) [concurrency] CausalRollback public readers are unlocked — TOCTOU NoSuchElementException (sim:scheduling/CausalRollback.java:214-275)
- (Medium/lifecycle-tick-events) [correctness] EntityUpdateEvent record holds mutable Point3f — false immutability claim (sim:events/EntityUpdateEvent.java:75-99)
- (Medium/lifecycle-tick-events) [correctness] EntityUpdateExecutor uses platform default charset for UUID generation (sim:tick/EntityUpdateExecutor.java:86)
- (Medium/lifecycle-tick-events) [test-gap] No concurrent stress test for BucketBarrier latch-swap race (sim:scheduling/BucketBarrier.java:101-113)
- (Medium/migration) [concurrency] MigrationMetrics.LatencyStats: count read outside synchronized block races with histogram write (sim:distributed/migration/MigrationMetrics.java:195-212)
- (Medium/migration) [clock-injection] Javadoc examples in IdempotencyToken and EntitySnapshot call System.currentTimeMillis() — clock-injection mandate violation in public API contract (sim:distributed/migration/IdempotencyToken.java:33-39)
- (Medium/migration) [concurrency] Non-volatile, non-atomic metrics counters in OptimisticMigratorImpl — data races under concurrent use (sim:distributed/migration/OptimisticMigratorImpl.java:77-80, 116, 176, 205, 220)
- (Medium/migration) [concurrency] getEntitiesCrossingBoundaries: clear-then-populate is not atomic — entities can be lost between clear and populate (sim:distributed/migration/MigrationOracleImpl.java:235-249)
- (Medium/migration) [resource-leak] CrossProcessMigration not AutoCloseable — RealTimeController and ScheduledExecutorService may leak if stop() is not called (sim:distributed/migration/CrossProcessMigration.java:113-139)
- (Medium/migration) [correctness] LatencyStats.getPercentile() inflates metrics with zero-initialized buffer entries when sample count is small (sim:distributed/migration/MigrationMetrics.java:195-211)
- (Medium/migration) [correctness] TestableEntityStore.simulateDelay() is Thread.sleep() in production source tree (sim:distributed/migration/TestableEntityStore.java:58-65)
- (Medium/migration) [test-gap] WAL serialization test always passes null snapshot — Jackson failure with real EntitySnapshot is untested (simulation/src/test/java/com/hellblazer/luciferase/simulation/distributed/migration/MigrationLogPersistenceTest.java:65-72)
- (Medium/persistence-misc) [correctness] WAL Sequence Counter Always Restarts at 0 on Process Restart (sim:persistence/WriteAheadLog.java:102)
- (Medium/persistence-misc) [correctness] SpatialTumbler Region ID Collision for Negative Coordinates (sim:tumbler/SpatialTumbler.java:136-141)
- (Medium/persistence-misc) [performance] BubbleMigrator Blocks ForkJoinPool.commonPool() Thread with Thread.sleep(50) (sim:tumbler/BubbleMigrator.java:138-182)
- (Medium/persistence-misc) [correctness] LatencyTracker Empty-Case Stats Violate min <= max Invariant (sim:metrics/LatencyTracker.java:93-95)
- (Medium/persistence-misc) [concurrency] ObservabilityMetrics.getSnapshot() Is Not a Consistent Snapshot (sim:metrics/ObservabilityMetrics.java:168-200)
- (Medium/persistence-misc) [correctness] SimulationConfig ghostTtlBuckets Truncates Without Warning (sim:config/SimulationConfig.java:164)
- (Medium/render) [concurrency] RateLimiter.allowRequest() non-atomic check-then-add under concurrent load (sim:viz/render/RateLimiter.java:51-65)
- (Medium/render) [clock-injection] RenderingServer constructor hardcodes Clock.system() for entityConsumer, bypassing injected clock (sim:viz/render/RenderingServer.java:103-108)
- (Medium/render) [concurrency] AdaptiveRegionManager.updateEntity(): entity-count limit check-then-add non-atomic (sim:viz/render/AdaptiveRegionManager.java:336-346)
- (Medium/render) [clock-injection] RegionCache Caffeine TTL uses system wall clock — cannot be overridden in tests (sim:viz/render/RegionCache.java:85, 102-107)
- (Medium/render) [test-gap] Missing tests: RegionBuilder queue backpressure, onRegionBuilt LOD path, and RateLimiter concurrency (sim:viz/render/RegionBuilder.java:227-268)
- (Medium/topology) [clock-injection] Pervasive use of System.currentTimeMillis() in test proposals (63 occurrences) (simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/TopologyExecutorTest.java:77)
- (Medium/topology) [resource-leak] ThreadLocal operationHistory never removed — permanent thread-local retention (sim:topology/TopologyExecutor.java:96)
- (Medium/topology) [test-gap] No test coverage: concurrent split+merge, consensus bypass, NPE on failed move (simulation/src/test/java/com/hellblazer/luciferase/simulation/topology/TopologyExecutorTest.java:1-500)
- (Medium/topology) [resource-leak] ThreadLocal operationHistory Never remove()d — Permanent Memory Leak on Thread-Pool Threads (sim:topology/TopologyExecutor.java:96, 213)
- (Medium/topology) [correctness] BubbleMover Is a Complete Stub — Bubble Is Never Actually Relocated (sim:topology/BubbleMover.java:142-163)
- (Medium/topology) [distributed] BubbleRemoved Rollback Uses rootKey() — May Re-Insert Bubble at Wrong Spatial Position (sim:topology/BubbleMerger.java:211-214)
- (Medium/topology) [correctness] ClusteringDetector.parseEntityId Silently Maps Malformed IDs to Wrong UUIDs (sim:topology/metrics/ClusteringDetector.java:166-171)
- (Medium/von-core) [clock-injection] SocketTransport and LocalServerTransport hardwire system clock — clock-injection gap (sim:von/transport/SocketTransport.java:131)
- (Medium/von-core) [resource-leak] RecoveryIntegration recoveryEventHandler permanently subscribed — event-driven activity after close() (sim:von/RecoveryIntegration.java:347-349, 171)
- (Medium/von-core) [serialization] JoinResponse.neighbors uses java.util.Set — deserialization allow-list would reject it (sim:von/MessageConverter.java:199-203)
- (Medium/von-core) [correctness] Manager.joinAndWait listener condition never fires for distributed joins (sim:von/Manager.java:283-313)
- (Medium/von-core) [test-gap] Test gap: no test exercises MigrationProtocolMessages serialization round-trip or transport routing (sim:von/MigrationProtocolMessages.java:56-173)
- (Medium/von-core) [serialization] JoinRequest/Move coordinate precision silently truncated: double → float → double (sim:von/MessageConverter.java:151-153, 219-221)
- (Medium/von-core) [correctness] MoveProtocol boundary/regular notification branches are identical dead code (sim:von/MoveProtocol.java:80-88)
- (Medium/von-core) [distributed] Manager.joinAndWait() latch condition never fires — waits on joiner-self Event.Join that is never emitted (sim:von/Manager.java:283-312)
- (Medium/von-core) [distributed] Crash detection is a dead API — no production call path to LeaveProtocol.handleCrash() (sim:von/LeaveProtocol.java:88-99)
- (Medium/von-transport) [clock-injection] Clock-injection violation: System.nanoTime() in RealTimeController.tickLoop() (sim:bubble/RealTimeController.java:228, 241)
- (Medium/von-transport) [test-gap] testCompositionIntegration uses find-then-bind TOCTOU port allocation (simulation/src/test/java/com/hellblazer/luciferase/simulation/von/transport/SocketTransportCompositionTest.java:65-112, 139-145)
- (Medium/von-transport) [test-gap] sendToNeighborAsync view-change branch dead in all tests — Digest.NONE mock makes lines 271-278 unreachable (sim:von/transport/SocketTransport.java:268-281)
---

## Bead Inventory (grooming complete)

**Epic**: `Luciferase-0frcy` — 95 children, all enriched (acceptance + design w/ verifier-confirmed file:line, root cause, fix, build cmd), labeled `sim-review-2026-06-03` + `theme:*` + `wave:*`.

**Coverage**: 163 confirmed findings → 0 orphans. 22 Critical beads, 53 High beads, 18 Medium beads, 2 Low beads, + 3 sweep roll-ups enumerating 46 homogeneous findings (resource-leak `zwyf2`×17, clock-injection `ml7kc`×13, test-gap `5yh9h`×16) + gRPC/quorum roll-up `ltxta`.

**Waves**: wave:1 = 9 (P0 data-integrity/safety/deadlock — start here), wave:2 = 62 (correctness/concurrency/distributed), wave:3 = 24 (perf + Medium/Low + sweeps).

**Themes**: causality-2pc(7), consensus(5), migration-persist(12), persistence(3), von-wire(8), ghost(6), distributed-net(8), bubble(21), topology(11), lifecycle(3), render(4), behavior(4), high-sweep(3).

Filter examples: `bd list --label=wave:1`, `bd list --label=theme:causality-2pc`, `bd epic status`.

**NOT beaded (deliberate)**: 117 unverified Medium/Low findings live in the appendix above. They were surfaced by review but NOT adversarially verified (verification was scoped to Critical/High). Re-verify before beading if/when wave:3 completes.

---

## Verify Pass 2 + full beading (Medium/Low)

The 117 appendix Medium/Low findings were adversarially verified (Sonnet, 117 agents): **108 confirmed, 9 refuted**. After dedup vs already-beaded sites (17 dropped), the remaining were beaded: **57 individual** Medium/Low beads + **34 sites appended** to the sweep beads (clock-injection `ml7kc` +10, resource-leak `zwyf2` +10, test-gap `5yh9h` +14).

**Final epic `Luciferase-0frcy`: 152 children.** Waves: wave:1=9, wave:2=62, wave:3=81. All confirmed findings (163 Crit/High + 108 Med/Low = 271 confirmed defects) are now tracked. 9+9=18 total refuted across both verify passes.

Refuted in pass 2 (do not chase): JoinResponse Set deser (already allow-listed), LamportClock vector-ts race, CubeForest type-ordering (dup of confirmed), two-phase ghost double-visibility (by-design), StreamingSession static TOKEN_COUNTER, Flocking minSpeed pre-clamp, InMemoryGhostChannel Thread.sleep (test-only ctor), LatencyStats zero-buffer percentile, TetreeBubbleGrid stale-entry.
