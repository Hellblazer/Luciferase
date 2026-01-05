# Distributed Animation Architecture

**Last Updated**: 2026-01-05
**Date**: 2026-01-04
**Status**: Design Document (Revised from Load Balancing Architecture)
**Epic**: Luciferase-3xw (requires significant revision)

## Overview

Luciferase implements distributed animation using **N servers hosting M bubbles** where bubbles are spatially mobile animation volumes that move through the simulation space **without leaving their server**. Migration occurs only for **load balancing**, not spatial movement.

## Core Principles

1. **Bubble = Single-Threaded Animator**: Each bubble runs in one VolumeAnimator thread
2. **Server Capacity = Animator Threads**: Server with 8 cores → 8 bubbles max
3. **No Spatial Ownership**: Bubbles can be anywhere in space, server assignment is independent of position
4. **Adaptive Subdivision**: Bubbles split/join based on load, not spatial boundaries
5. **VON Discovery**: Distributed discovery via Voronoi Overlay Network pattern
6. **Replicated Forest**: Each server has full spatial index replica (eventually consistent)

## Architecture Layers

### Layer 5: Load Balancing (Animator Capacity)

**Purpose**: Distribute animator load across server cluster using locality-aware random selection

**Key Components**:
- **Power of 2 Random Choices**: Classic load balancing algorithm
- **Spatial Locality**: Prefer servers in same Tumbler region
- **Capacity Metric**: Animator thread utilization (not entity count)

**Algorithm**:
```java
public Server selectServerForBubble(Bubble bubble, Set<Server> cluster) {
    // Get bubble's spatial position
    Vector3f position = bubble.position();

    // Find Tumbler region containing this position
    TumblerRegion region = spatialTumbler.getRegion(position);

    // Get servers in same region (spatial locality)
    Set<Server> localServers = region.getServers();

    if (localServers.size() >= 2) {
        // Power of 2 random choices within local region
        Server s1 = randomChoice(localServers);
        Server s2 = randomChoice(localServers);

        return s1.utilizationPercent() < s2.utilizationPercent() ? s1 : s2;
    } else {
        // Fall back to global power of 2 choices
        Server s1 = randomChoice(cluster);
        Server s2 = randomChoice(cluster);

        return s1.utilizationPercent() < s2.utilizationPercent() ? s1 : s2;
    }
}
```

**Load Balancing Decision**:
```java
// Monitor server utilization each bucket
if (server.utilizationPercent() > OVERLOAD_THRESHOLD) {  // 80%
    // Select bubble to migrate (highest CPU usage)
    Bubble candidate = server.bubbles().stream()
        .max(Comparator.comparing(Bubble::cpuUtilization))
        .orElseThrow();

    // Find target server (power of 2 + locality)
    Server target = selectServerForBubble(candidate, cluster);

    // Cost-benefit check (same as before)
    float benefit = candidate.cpuUtilization();  // Load reduction
    float cost = estimateMigrationCost(candidate, target);

    if (benefit / cost > BENEFIT_THRESHOLD) {  // 1.5x
        migrateBubble(candidate, target);
    }
}
```

**Spatial Tumbler Integration**:
- **Purpose**: Organize servers into spatial regions for locality-aware load balancing
- **Not for ownership**: Tumblers don't own regions, just group nearby servers
- **Symbolic use**: Help guide server selection to minimize ghost layer latency

```java
public record TumblerRegion(
    TetreeKey regionKey,           // Spatial region identifier
    Set<UUID> serverIds,           // Servers "near" this region
    AABB bounds                    // Spatial boundaries
) {
    // Servers can be in multiple Tumbler regions (overlap allowed)
    public void addServer(UUID serverId) {
        serverIds.add(serverId);
    }
}

// Servers register with Tumbler regions they want to serve
public void serverStartup(Server server) {
    // Server advertises preferred spatial regions
    Set<TumblerRegion> preferredRegions = server.getPreferredRegions();

    for (TumblerRegion region : preferredRegions) {
        spatialTumbler.registerServer(server.id(), region);
    }
}
```

### Layer 4: Bubble Lifecycle (Adaptive Subdivision)

**Purpose**: Manage bubble creation, split, join, and movement

**Bubble Characteristics**:
- **Disjoint**: No parent-child hierarchy
- **Single-threaded**: One VolumeAnimator thread per bubble
- **Internal spatial index**: Tetree or Octree within bubble volume
- **Adaptive sizing**: Split when overloaded, join when bubbles merge

**Capacity Model**:
```java
public record ServerCapacity(
    int maxAnimatorThreads,    // CPU cores (e.g., 8)
    int activeAnimators,       // Current bubble count
    Map<UUID, Float> cpuPerBubble  // CPU % per bubble
) {
    public float utilizationPercent() {
        return (activeAnimators / (float) maxAnimatorThreads) * 100.0f;
    }

    public boolean canHostBubble() {
        return activeAnimators < maxAnimatorThreads;
    }
}

public record BubbleCapacity(
    int entityCount,
    float frameTimeMs,         // Actual processing time
    float targetFrameTimeMs    // 10ms for 100 FPS
) {
    public boolean needsSplit() {
        // 20% over budget triggers split
        return frameTimeMs > targetFrameTimeMs * 1.2f;
    }

    public float cpuUtilization() {
        return frameTimeMs / targetFrameTimeMs;  // 1.0 = 100%
    }
}
```

**Bubble Split (Adaptive)**:
```java
// Triggered when animator can't meet frame rate target
if (bubble.needsSplit()) {
    // Spatial subdivision within bubble
    List<Bubble> newBubbles = adaptiveSplit(bubble);

    // newBubbles count depends on entity distribution
    // Could be 2, 4, 8, etc. based on spatial clustering

    // Distribute entities to new bubbles based on position
    for (Entity entity : bubble.entities()) {
        Bubble target = findContainingBubble(entity.position(), newBubbles);
        target.addEntity(entity);
    }

    // Assign new bubbles to servers
    for (Bubble newBubble : newBubbles) {
        Server server = selectServerForBubble(newBubble, cluster);
        server.addBubble(newBubble);
    }

    // Destroy original bubble
    bubble.shutdown();
}

private List<Bubble> adaptiveSplit(Bubble bubble) {
    // Use bubble's internal spatial index to find entity clusters
    SpatialIndex index = bubble.getSpatialIndex();

    // Analyze entity distribution
    List<EntityCluster> clusters = index.findClusters(
        minEntitiesPerCluster: 100,
        maxDistance: 50.0f  // meters
    );

    if (clusters.isEmpty()) {
        // Uniform distribution - use geometric subdivision
        return geometricSplit(bubble, 8);  // Octant subdivision
    } else {
        // Create one bubble per cluster
        List<Bubble> newBubbles = new ArrayList<>();
        for (EntityCluster cluster : clusters) {
            Bubble newBubble = new Bubble(
                position: cluster.centroid(),
                bounds: cluster.bounds(),
                targetFrameRate: bubble.targetFrameRate()
            );
            newBubbles.add(newBubble);
        }
        return newBubbles;
    }
}
```

**Bubble Join**:
```java
// When bubbles bump into each other
public void onBubblesCollide(Bubble b1, Bubble b2) {
    // Check if they should merge (interaction affinity)
    float affinity = calculateAffinity(b1, b2);

    if (affinity > MERGE_THRESHOLD) {  // 0.6
        // Merge entities into single bubble
        Bubble merged = new Bubble(
            position: midpoint(b1.position(), b2.position()),
            bounds: union(b1.bounds(), b2.bounds()),
            targetFrameRate: b1.targetFrameRate()
        );

        merged.addEntities(b1.entities());
        merged.addEntities(b2.entities());

        // Assign to server (prefer less loaded of the two)
        Server server = b1.server().utilizationPercent() < b2.server().utilizationPercent()
            ? b1.server()
            : b2.server();

        server.addBubble(merged);

        // Destroy original bubbles
        b1.shutdown();
        b2.shutdown();
    }
}

private float calculateAffinity(Bubble b1, Bubble b2) {
    int crossBubbleInteractions = 0;
    int totalInteractions = 0;

    // Count interactions between bubbles
    for (Entity e1 : b1.entities()) {
        for (Entity e2 : b2.entities()) {
            if (e1.interactsWith(e2)) {
                crossBubbleInteractions++;
            }
        }
        totalInteractions += e1.getInteractionCount();
    }

    for (Entity e2 : b2.entities()) {
        totalInteractions += e2.getInteractionCount();
    }

    return totalInteractions > 0
        ? (float) crossBubbleInteractions / totalInteractions
        : 0.0f;
}
```

**Internal Spatial Indexing**:
```java
// Each bubble has its own spatial index
public class Bubble {
    private final SpatialIndex<TetreeKey, UUID, EntityContent> spatialIndex;
    private final VolumeAnimator animator;
    private final AABB bounds;

    public Bubble(Vector3f position, AABB bounds, float targetFrameRate) {
        this.bounds = bounds;

        // Create spatial index for this bubble's volume
        this.spatialIndex = new Tetree<>(
            bounds.min(),
            bounds.max(),
            maxEntitiesPerNode: 64
        );

        // Single-threaded animator
        this.animator = new VolumeAnimator(
            targetFrameRate: targetFrameRate,
            spatialIndex: spatialIndex
        );
    }

    public void addEntity(Entity entity) {
        // Add to internal spatial index
        spatialIndex.insert(entity.id(), entity.position(), entity.content());

        // Register with animator
        animator.trackEntity(entity);
    }

    // Frame processing (single-threaded)
    public void tick(long bucket) {
        long start = System.nanoTime();

        // Process all entities in this bubble
        animator.processBucket(bucket);

        long duration = System.nanoTime() - start;
        float frameTimeMs = duration / 1_000_000.0f;

        // Check if overloaded
        if (frameTimeMs > targetFrameTimeMs * 1.2f) {
            scheduleSplit();
        }
    }
}
```

**Capacity Limit**:
```java
// If adaptive subdivision can't meet frame rate, reject load
if (bubble.needsSplit()) {
    List<Bubble> newBubbles = adaptiveSplit(bubble);

    // Check if split helps
    float estimatedFrameTimePerBubble = bubble.frameTimeMs() / newBubbles.size();

    if (estimatedFrameTimePerBubble > targetFrameTimeMs * 1.2f) {
        // Even after split, still overloaded
        // This means entity density is too high for single-threaded processing

        log.error("Cannot subdivide bubble {} enough to meet frame rate. " +
                  "Entity density too high. Current: {} entities in {} m³. " +
                  "Frame time: {}ms (target: {}ms). Rejecting new entities.",
            bubble.id(), bubble.entityCount(), bubble.volume(),
            estimatedFrameTimePerBubble, targetFrameTimeMs);

        // Reject new entity spawns in this region
        bubble.setAcceptingEntities(false);

        // Alternative: Reduce simulation fidelity
        // bubble.reduceFidelity();

        return;
    }

    // Split successful, proceed
    distributeBubbles(newBubbles);
}
```

### Layer 3: VON Overlay Network (Distributed Discovery)

**Purpose**: Enable distributed bubble discovery without global registry

**VON Protocols**:
- **JOIN**: New bubble announces itself and discovers neighbors
- **MOVE**: Bubble updates position and checks for new neighbors
- **LEAVE**: Bubble shutdown notification

**Discovery Flow**:
```java
// 1. Bubble creation (VON JOIN)
public void createBubble(Bubble bubble, Server server) {
    // Add to local server
    server.addBubble(bubble);

    // Announce via Delos gossip
    BubbleAnnouncement msg = new BubbleAnnouncement(
        bubbleId: bubble.id(),
        position: bubble.position(),
        bounds: bubble.bounds(),
        serverId: server.id(),
        timestamp: System.currentTimeMillis()
    );

    delos.broadcast(msg);  // Gossip to all servers

    // Update local Forest replica
    localForest.addBubble(bubble.id(), bubble.position(), server.id());
}

// 2. Receive announcement (VON JOIN response)
public void handleBubbleAnnouncement(BubbleAnnouncement msg) {
    // Update local Forest replica
    localForest.addBubble(msg.bubbleId(), msg.position(), msg.serverId());

    // Check if announced bubble is near our bubbles
    for (Bubble myBubble : localBubbles) {
        float distance = myBubble.position().distance(msg.position());

        if (distance < INTERACTION_RANGE) {
            // VON neighbor discovered
            myBubble.addVonNeighbor(msg.bubbleId());

            // If different server, establish ghost sync
            if (!msg.serverId().equals(localServerId)) {
                ghostLayer.establishSync(
                    localBubble: myBubble.id(),
                    remoteBubble: msg.bubbleId(),
                    remoteServer: msg.serverId()
                );
            }
        }
    }
}

// 3. Bubble movement (VON MOVE)
public void bubbleMoved(Bubble bubble, Vector3f newPosition) {
    Vector3f oldPosition = bubble.position();
    bubble.setPosition(newPosition);

    // Update local Forest replica
    localForest.updateBubblePosition(bubble.id(), newPosition);

    // Broadcast position update
    BubbleMovement msg = new BubbleMovement(
        bubbleId: bubble.id(),
        oldPosition: oldPosition,
        newPosition: newPosition,
        serverId: localServerId,
        timestamp: System.currentTimeMillis()
    );

    delos.broadcast(msg);

    // Check for new neighbors (boundary crossing)
    Set<UUID> nearbyBubbles = localForest.queryBubblesWithinRange(
        newPosition,
        INTERACTION_RANGE
    );

    for (UUID nearbyBubble : nearbyBubbles) {
        if (!bubble.getVonNeighbors().contains(nearbyBubble)) {
            // New neighbor discovered
            bubble.addVonNeighbor(nearbyBubble);

            UUID remoteServer = localForest.getBubbleServer(nearbyBubble);
            if (!remoteServer.equals(localServerId)) {
                ghostLayer.establishSync(
                    bubble.id(),
                    nearbyBubble,
                    remoteServer
                );
            }
        }
    }

    // Remove distant neighbors
    for (UUID neighbor : bubble.getVonNeighbors()) {
        Vector3f neighborPos = localForest.getBubblePosition(neighbor);
        if (newPosition.distance(neighborPos) > INTERACTION_RANGE * 1.5f) {
            bubble.removeVonNeighbor(neighbor);

            UUID remoteServer = localForest.getBubbleServer(neighbor);
            if (!remoteServer.equals(localServerId)) {
                ghostLayer.removeSync(bubble.id(), neighbor);
            }
        }
    }
}

// 4. Bubble destruction (VON LEAVE)
public void destroyBubble(Bubble bubble) {
    // Announce shutdown
    BubbleShutdown msg = new BubbleShutdown(
        bubbleId: bubble.id(),
        serverId: localServerId,
        timestamp: System.currentTimeMillis()
    );

    delos.broadcast(msg);

    // Remove from local Forest replica
    localForest.removeBubble(bubble.id());

    // Shutdown ghost syncs
    for (UUID neighbor : bubble.getVonNeighbors()) {
        UUID remoteServer = localForest.getBubbleServer(neighbor);
        if (!remoteServer.equals(localServerId)) {
            ghostLayer.removeSync(bubble.id(), neighbor);
        }
    }

    // Shutdown animator
    bubble.animator().shutdown();
}
```

**Delos Gossip Integration**:
```java
// Delos provides broadcast primitive
public interface DelosGossip {
    void broadcast(Message msg);
    void subscribe(MessageType type, Consumer<Message> handler);
}

// Register handlers
delos.subscribe(MessageType.BUBBLE_ANNOUNCEMENT, this::handleBubbleAnnouncement);
delos.subscribe(MessageType.BUBBLE_MOVEMENT, this::handleBubbleMovement);
delos.subscribe(MessageType.BUBBLE_SHUTDOWN, this::handleBubbleShutdown);
```

### Layer 2: Replicated Forest Spatial Index

**Purpose**: Provide fast local queries for bubble discovery with eventual consistency

**Replication Model**:
- **Full replica on each server**: Every server has complete Forest
- **Eventually consistent**: Updates propagate via Delos gossip
- **Fast local queries**: No network round-trips for spatial queries
- **No single point of failure**: Any server can answer queries

**Data Structure**:
```java
public class ReplicatedForest {
    // Local replica of global bubble positions
    private final ConcurrentHashMap<UUID, BubbleEntry> bubbles;

    // Spatial index for fast position queries
    private final SpatialIndex<TetreeKey, UUID, BubbleEntry> spatialIndex;

    // Gossip protocol for synchronization
    private final DelosGossip gossip;

    public record BubbleEntry(
        UUID bubbleId,
        Vector3f position,
        AABB bounds,
        UUID serverId,
        long lastUpdateTimestamp
    ) {}

    public void addBubble(UUID bubbleId, Vector3f position, UUID serverId) {
        BubbleEntry entry = new BubbleEntry(
            bubbleId,
            position,
            bounds: estimateBounds(position),
            serverId,
            System.currentTimeMillis()
        );

        bubbles.put(bubbleId, entry);
        spatialIndex.insert(bubbleId, position, entry);

        // Gossip update to other servers
        gossip.broadcast(new ForestUpdate(entry));
    }

    public void updateBubblePosition(UUID bubbleId, Vector3f newPosition) {
        BubbleEntry old = bubbles.get(bubbleId);
        if (old == null) return;

        // Remove old position from spatial index
        spatialIndex.remove(bubbleId, old.position());

        // Add new position
        BubbleEntry updated = new BubbleEntry(
            bubbleId,
            newPosition,
            old.bounds(),  // Bounds don't change on movement
            old.serverId(),
            System.currentTimeMillis()
        );

        bubbles.put(bubbleId, updated);
        spatialIndex.insert(bubbleId, newPosition, updated);

        // Gossip update
        gossip.broadcast(new ForestUpdate(updated));
    }

    public Set<UUID> queryBubblesWithinRange(Vector3f position, float range) {
        // Local query (no network)
        return spatialIndex.rangeQuery(position, range)
            .stream()
            .map(entry -> entry.content().bubbleId())
            .collect(Collectors.toSet());
    }

    public UUID getBubbleServer(UUID bubbleId) {
        BubbleEntry entry = bubbles.get(bubbleId);
        return entry != null ? entry.serverId() : null;
    }

    public Vector3f getBubblePosition(UUID bubbleId) {
        BubbleEntry entry = bubbles.get(bubbleId);
        return entry != null ? entry.position() : null;
    }
}
```

**Gossip Synchronization**:
```java
// Handle Forest updates from other servers
public void handleForestUpdate(ForestUpdate update) {
    BubbleEntry existing = bubbles.get(update.entry().bubbleId());

    if (existing == null) {
        // New bubble discovered
        bubbles.put(update.entry().bubbleId(), update.entry());
        spatialIndex.insert(
            update.entry().bubbleId(),
            update.entry().position(),
            update.entry()
        );
    } else if (update.entry().lastUpdateTimestamp() > existing.lastUpdateTimestamp()) {
        // Newer update (last-write-wins)
        spatialIndex.remove(existing.bubbleId(), existing.position());
        bubbles.put(update.entry().bubbleId(), update.entry());
        spatialIndex.insert(
            update.entry().bubbleId(),
            update.entry().position(),
            update.entry()
        );
    }
    // else: Ignore stale update
}
```

**Consistency Guarantees**:
- **Eventual consistency**: All servers eventually converge to same state
- **Last-write-wins**: Timestamp-based conflict resolution
- **Bounded staleness**: Gossip typically propagates within 1-2 seconds
- **Query accuracy**: May see slightly stale positions, but good enough for discovery

### Layer 1: Ghost Layer (Cross-Server Entity Sync)

**Purpose**: Replicate entity state across servers when bubbles on different servers are spatially close

**Trigger Conditions**:
```java
// Ghost sync ONLY when:
// 1. Bubbles on DIFFERENT servers
// 2. Bubbles are spatially CLOSE (within interaction range)

public boolean needsGhostSync(Bubble local, Bubble remote) {
    // Same server → no ghost sync (shared memory)
    if (local.serverId().equals(remote.serverId())) {
        return false;
    }

    // Too far apart → no interaction possible
    float distance = local.position().distance(remote.position());
    if (distance > INTERACTION_RANGE) {
        return false;
    }

    // Different servers + spatially close → ghost sync needed
    return true;
}
```

**Ghost Synchronization**:
```java
public class GhostLayer {
    // Active ghost sync channels
    private final Map<SyncKey, GhostChannel> activeChannels;

    public record SyncKey(UUID localBubble, UUID remoteBubble) {}

    public void establishSync(UUID localBubble, UUID remoteBubble, UUID remoteServer) {
        SyncKey key = new SyncKey(localBubble, remoteBubble);

        if (activeChannels.containsKey(key)) {
            return;  // Already syncing
        }

        GhostChannel channel = new GhostChannel(
            localBubble,
            remoteBubble,
            remoteServer,
            this::sendGhostUpdate,
            this::receiveGhostUpdate
        );

        activeChannels.put(key, channel);

        log.info("Established ghost sync: {} <-> {} (server: {})",
            localBubble, remoteBubble, remoteServer);
    }

    public void removeSync(UUID localBubble, UUID remoteBubble) {
        SyncKey key = new SyncKey(localBubble, remoteBubble);
        GhostChannel channel = activeChannels.remove(key);

        if (channel != null) {
            channel.shutdown();
            log.info("Removed ghost sync: {} <-> {}", localBubble, remoteBubble);
        }
    }

    // Batched ghost updates (every bucket)
    public void onBucketComplete(long bucket) {
        for (GhostChannel channel : activeChannels.values()) {
            channel.sendBatch(bucket);
        }
    }
}

public class GhostChannel {
    private final Map<UUID, GhostEntity> pendingGhosts = new ConcurrentHashMap<>();

    public void addGhost(UUID entityId, Vector3f position, Vector3f velocity) {
        GhostEntity ghost = new GhostEntity(
            entityId,
            position,
            velocity,
            localBubbleId,
            System.currentTimeMillis()
        );

        pendingGhosts.put(entityId, ghost);
    }

    public void sendBatch(long bucket) {
        if (pendingGhosts.isEmpty()) {
            return;
        }

        GhostBatch batch = new GhostBatch(
            sourceBubble: localBubbleId,
            targetBubble: remoteBubbleId,
            ghosts: new ArrayList<>(pendingGhosts.values()),
            bucket: bucket
        );

        sendToRemoteServer(batch);

        pendingGhosts.clear();
    }
}
```

**Same-Server Optimization**:
```java
// When bubbles on same server
public void processInteraction(Bubble b1, Bubble b2) {
    if (b1.serverId().equals(b2.serverId())) {
        // Same server → direct memory access (no ghost layer)
        for (Entity e1 : b1.entities()) {
            for (Entity e2 : b2.entities()) {
                if (e1.position().distance(e2.position()) < INTERACTION_RANGE) {
                    processCollision(e1, e2);  // Direct access
                }
            }
        }
    } else {
        // Different servers → use ghost layer
        for (GhostEntity ghost : ghostLayer.getGhosts(b2.id())) {
            for (Entity e1 : b1.entities()) {
                if (e1.position().distance(ghost.position()) < INTERACTION_RANGE) {
                    processCollision(e1, ghost);  // Ghost interaction
                }
            }
        }
    }
}
```

## Complete Example: Distributed Animation Flow

### Scenario Setup

```
Cluster:
  Server 1: 8 CPU cores → 8 animator threads (capacity: 8 bubbles)
  Server 2: 8 CPU cores → 8 animator threads (capacity: 8 bubbles)
  Server 3: 4 CPU cores → 4 animator threads (capacity: 4 bubbles)

Spatial Tumbler Regions:
  Region A (x=[0, 5000]): Server 1, Server 2
  Region B (x=[5000, 10000]): Server 2, Server 3

Initial State:
  Server 1: 6 bubbles (75% utilized)
  Server 2: 4 bubbles (50% utilized)
  Server 3: 2 bubbles (50% utilized)
```

### Event 1: Bubble Creation

```
1. User spawns 1000 entities at (2000, 2000, 500)

2. Create bubble:
   Bubble B1:
     position: (2000, 2000, 500)
     entityCount: 1000
     bounds: AABB(50m radius)

3. Select server (power of 2 + locality):
   - Position (2000, 2000, 500) → Tumbler Region A
   - Region A servers: {Server 1, Server 2}
   - Random choice 1: Server 1 (75% utilized)
   - Random choice 2: Server 2 (50% utilized)
   - Winner: Server 2 (less loaded)

4. Assign bubble to Server 2:
   Server 2 bubbles: 4 → 5 (62.5% utilized)

5. VON JOIN:
   - Server 2 broadcasts BubbleAnnouncement(B1, (2000,2000,500), Server2)
   - All servers update local Forest replica

6. Discovery:
   - Server 1 checks: Any bubbles near (2000, 2000, 500)?
   - Server 1 has Bubble A at (1980, 2000, 500) - 20m away!
   - Server 1 establishes ghost sync: A <-> B1
```

### Event 2: Bubble Movement

```
1. Bubble B1 moves from (2000, 2000, 500) to (3000, 2000, 500)

2. VON MOVE:
   - Server 2 updates local Forest: B1 → (3000, 2000, 500)
   - Server 2 broadcasts BubbleMovement(B1, old=(2000,2000,500), new=(3000,2000,500))

3. All servers update Forest replicas:
   - Eventually consistent (1-2 second propagation)

4. Server 2 checks for new neighbors:
   - Query Forest: bubbles within 100m of (3000, 2000, 500)
   - No new neighbors found

5. Existing ghost sync (A <-> B1):
   - Distance now: 1020m (was 20m)
   - Too far → remove ghost sync
```

### Event 3: Bubble Overload & Split

```
1. Bubble B1 now has 3000 entities (grew via spawns)
   Frame time: 14ms (target: 10ms) → 140% of budget

2. Split decision:
   if (14ms > 10ms * 1.2) → TRUE (split needed)

3. Adaptive split:
   - Analyze entity distribution in B1's spatial index
   - Find 4 clusters:
     Cluster 1: 800 entities at (2950, 2000, 500)
     Cluster 2: 700 entities at (3050, 2000, 500)
     Cluster 3: 750 entities at (3000, 1950, 500)
     Cluster 4: 750 entities at (3000, 2050, 500)

4. Create 4 new bubbles:
   B1a: 800 entities at (2950, 2000, 500)
   B1b: 700 entities at (3050, 2000, 500)
   B1c: 750 entities at (3000, 1950, 500)
   B1d: 750 entities at (3000, 2050, 500)

5. Assign to servers (power of 2 + locality):
   - All positions in Tumbler Region A
   - B1a → Server 2 (current host, has capacity)
   - B1b → Server 1 (power of 2: Server 1=75%, Server 2=62%)
   - B1c → Server 2 (power of 2: Server 1=87%, Server 2=75%)
   - B1d → Server 1 (power of 2: Server 1=87%, Server 2=87%)

6. Result:
   Server 1: 6 → 8 bubbles (100% utilized)
   Server 2: 5 → 6 bubbles (75% utilized)

7. VON JOIN for new bubbles:
   - 4 broadcasts (one per new bubble)
   - Forest replicas updated
   - Ghost syncs established (B1a <-> B1b, B1c <-> B1d)
```

### Event 4: Load Balancing

```
1. Server 1 overloaded:
   utilizationPercent: 100%
   avgCpuPerBubble: 85%

2. Find heaviest bubble on Server 1:
   Bubble B1b: 95% CPU (frame time: 9.5ms)

3. Select target server (power of 2 + locality):
   - B1b position: (3050, 2000, 500) → Tumbler Region A
   - Region A servers: {Server 1, Server 2}
   - Random choice 1: Server 1 (100% utilized)
   - Random choice 2: Server 2 (75% utilized)
   - Winner: Server 2

4. Cost-benefit check:
   benefit: 0.95 (95% CPU reduction for Server 1)
   cost: 0.18 (migration overhead: 180ms)
   ratio: 0.95 / 0.18 = 5.3x > 1.5 ✓

5. Migrate B1b from Server 1 to Server 2:
   - Transfer entity state
   - Update Forest: B1b serverId → Server 2
   - Update ghost syncs
   - Broadcast migration event

6. Result:
   Server 1: 8 → 7 bubbles (87.5% utilized)
   Server 2: 6 → 7 bubbles (87.5% utilized)

7. Latency: 850ms (< 1 second target ✓)
```

## Implementation Phases

### Phase 0: Replicated Forest Foundation
**Duration**: 1 week
**Deliverables**:
- ReplicatedForest class with Delos gossip integration
- BubbleEntry record and spatial index
- Forest update protocol (add/update/remove bubble)
- Unit tests for replication and consistency

### Phase 1: Bubble Lifecycle with Internal Spatial Index
**Duration**: 2 weeks
**Deliverables**:
- Bubble class with internal Tetree/Octree
- VolumeAnimator integration (single-threaded)
- Adaptive split algorithm (cluster detection)
- Bubble join algorithm (affinity-based merge)
- Capacity limit enforcement
- Unit tests for split/join

### Phase 2: VON Discovery Protocol
**Duration**: 2 weeks
**Deliverables**:
- VON JOIN/MOVE/LEAVE implementations
- Delos gossip integration for announcements
- Neighbor discovery and tracking
- Integration tests for discovery flow

### Phase 3: Ghost Layer Cross-Server Sync
**Duration**: 1.5 weeks
**Deliverables**:
- GhostChannel for batched sync
- Same-server optimization (no ghosts needed)
- Ghost TTL and memory limits
- Integration tests for cross-server interaction

### Phase 4: Spatial Tumbler Load Balancing
**Duration**: 1.5 weeks
**Deliverables**:
- TumblerRegion for spatial organization
- Power of 2 random choices algorithm
- Locality-aware server selection
- Migration protocol
- Integration tests for load balancing

### Phase 5: Observability & Tuning
**Duration**: 0.5 weeks
**Deliverables**:
- Metrics export (animator utilization, bubble count, migration stats)
- Logging (sampled, per-bucket metrics)
- Configuration parameters (thresholds, INTERACTION_RANGE, etc.)
- Production runbook

## Configuration Parameters

```java
public record DistributedAnimationConfig(
    // Animator capacity
    float targetFrameTimeMs,           // default: 10.0 (100 FPS)
    float overloadThreshold,           // default: 1.2 (120% of budget)

    // Bubble lifecycle
    int minEntitiesPerBubble,          // default: 100
    int maxEntitiesPerBubble,          // default: 5000
    float bubbleMergeAffinity,         // default: 0.6 (60% cross-bubble interactions)

    // Spatial parameters
    float interactionRange,            // default: 50.0 (meters)
    float ghostZoneWidth,              // default: interactionRange * 1.5

    // Load balancing
    float serverOverloadThreshold,     // default: 0.8 (80% animator utilization)
    float migrationBenefitThreshold,   // default: 1.5 (cost multiplier)
    int migrationCooldownBuckets,      // default: 50 (5 seconds)

    // Forest replication
    int gossipIntervalMs,              // default: 100 (every bucket)
    int forestStalenessThresholdMs,    // default: 5000 (5 seconds)

    // VON discovery
    int vonNeighborCheckIntervalBuckets,  // default: 10 (1 second)
    float vonNeighborMaxDistance          // default: interactionRange * 2.0
) {}
```

## Performance Characteristics

### Animator Capacity
- **Single-threaded performance**: 2,000-5,000 entities per bubble (depends on complexity)
- **Server capacity**: 8 cores = 8 bubbles = 16,000-40,000 entities per server
- **Cluster capacity**: 10 servers = 80 bubbles = 160,000-400,000 entities

### Forest Replication
- **Query latency**: < 1μs (local replica, no network)
- **Update propagation**: 1-2 seconds (Delos gossip)
- **Memory per bubble**: ~200 bytes (position, bounds, metadata)
- **Total memory**: 10,000 bubbles = 2 MB per server

### Ghost Layer
- **Sync latency**: 1-2 buckets (100-200ms)
- **Bandwidth**: ~50 bytes per ghost entity per bucket
- **Memory**: 1000 ghosts per bubble max = ~50 KB per bubble

### Load Balancing
- **Migration latency**: 500-1000ms for typical bubble
- **Decision overhead**: < 1ms per bucket (power of 2 choices)
- **Oscillation prevention**: Cooldown = 5 seconds

## Related Documentation

- `simulation/doc/SIMULATION_BUBBLES.md` - Original design philosophy
- `simulation/doc/ADAPTIVE_VOLUME_SHARDING.md` - SpatialTumbler architecture
- Memory Bank: `Luciferase_active/load-balancing-plan.md` - Original load balancing plan (superseded)
- ChromaDB: `research::von::*` - VON protocol research
- ChromaDB: `decision::architect::adaptive-volume-sharding-2026` - Adaptive sharding decision

## Implementation Status

**Epic**: Luciferase-3xw (REQUIRES MAJOR REVISION)
**Status**: Architecture design complete, implementation pending
**Next Step**: Phase 0 (Replicated Forest Foundation)

---

**Critical Change**: This architecture fundamentally differs from the original load balancing plan. The original assumed **spatial ownership** (nodes own regions), but the correct design uses **animator capacity** (nodes host bubbles regardless of position). This requires revising all Phase 1-5 beads and implementation plans.
