# viz-render Streaming Subsystem Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the broken viz-render streaming subsystem with a clean, testable architecture that correctly streams spatial regions to browser clients for simulation debugging.

**Architecture:** Hexagonal/ports-and-adapters with a `Transport` interface enabling in-process testing. `SpatialKey<?>` is the universal cache unit with a version-as-dirty-signal (`dirty ≡ keyVersion > cacheVersion`). Phase C (pull/snapshot) and Phase B (push/subscribe) are separate, composable layers.

**Tech Stack:** Java 24 sealed interfaces, pattern-matching switch, `CompletableFuture`, `ConcurrentHashMap`, `CopyOnWriteArrayList`; Maven test runner; JavaScript ES2020 (BigInt, base64); existing ESVO/ESVT builders and `Frustum3D`/`SpatialKey` from the `lucien` module.

**Design reference:** `simulation/doc/viz-render/REDESIGN-2026-02-19.md`

---

## Ground Rules for the Implementer

- **Never** delete existing files. Build new classes alongside the old ones. Old tests must keep compiling and passing throughout.
- **Package base** for all new Java: `com.hellblazer.luciferase.simulation.viz.render`
- **Test command:** `mvn test -pl simulation -Dtest=ClassName` (or `mvn test -pl lucien -Dtest=ClassName` for lucien changes)
- **Coordinate space:** all `Point3f` positions are in internal integer space (0..2^21-1 cast to float) — same convention as `Tetree.insert(Point3f,...)`.
- **Level cap:** TetreeKey is capped at level 10 (CompactTetreeKey). MortonKey supports levels 0–21. The single-long key fits both.
- At the top of every new file add the standard copyright header matching the existing files.

---

## Task 0.1: Fix MortonKey.equals() and hashCode()

**Why first:** Every Map keyed on `SpatialKey<?>` across the new system relies on correct equality. If `MortonKey.equals()` ignores level, different LOD cells silently collide.

**Files:**
- Modify: `lucien/src/main/java/com/hellblazer/luciferase/lucien/octree/MortonKey.java:148-158`
- Modify: `lucien/src/test/java/com/hellblazer/luciferase/lucien/octree/MortonKeyTest.java` (add test)

**Step 1: Audit compareTo for level consistency**

Read `MortonKey.compareTo()` (line ~141). It compares only `mortonCode`. After the fix, `equals` will require matching level too. A `ConcurrentSkipListMap` requires that `compareTo == 0` iff `equals == true`. Because the existing Octree uses `ConcurrentSkipListMap<MortonKey, ...>`, we must include level in compareTo too.

**Step 2: Write the failing test**

Add to `MortonKeyTest.java`:
```java
@Test
void equalsRequiresMatchingLevel() {
    var k0 = new MortonKey(0L, (byte) 0);
    var k5 = new MortonKey(0L, (byte) 5);
    assertNotEquals(k0, k5, "keys at different levels must not be equal");
    assertNotEquals(k0.hashCode(), k5.hashCode(),
        "keys at different levels should have different hashCodes");
}

@Test
void equalsSameLevelAndCode() {
    var a = new MortonKey(12345L, (byte) 7);
    var b = new MortonKey(12345L, (byte) 7);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
}

@Test
void compareToConsistentWithEquals() {
    var a = new MortonKey(0L, (byte) 0);
    var b = new MortonKey(0L, (byte) 5);
    assertNotEquals(0, a.compareTo(b),
        "compareTo must be non-zero when equals is false");
}
```

**Step 3: Run test to verify it fails**

```
mvn test -pl lucien -Dtest=MortonKeyTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: FAIL on `equalsRequiresMatchingLevel` and `compareToConsistentWithEquals`.

**Step 4: Fix equals, hashCode, and compareTo**

In `MortonKey.java`, replace the three methods:

```java
@Override
public int compareTo(MortonKey other) {
    Objects.requireNonNull(other, "Cannot compare to null MortonKey");
    int levelCmp = Byte.compare(this.level, other.level);
    if (levelCmp != 0) return levelCmp;
    return Long.compareUnsigned(this.mortonCode, other.mortonCode);
}

@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof MortonKey mk)) return false;
    return mortonCode == mk.mortonCode && level == mk.level;
}

@Override
public int hashCode() {
    return Objects.hash(mortonCode, level);
}
```

**Step 5: Run all lucien tests**

```
mvn test -pl lucien -Dsurefire.rerunFailingTestsCount=0
```
Expected: all pass. If Octree tests break, it means the existing code relied on the buggy equals — fix the test assertions to use correct level-aware comparisons.

**Step 6: Commit**
```
git add lucien/src/main/java/com/hellblazer/luciferase/lucien/octree/MortonKey.java
git add lucien/src/test/java/com/hellblazer/luciferase/lucien/octree/MortonKeyTest.java
git commit -m "fix(lucien): MortonKey.equals/hashCode/compareTo include level

Keys at different LOD levels representing different spatial cells were
incorrectly considered equal. This caused silent map key collisions in
any multi-level ConcurrentHashMap<SpatialKey<?>, ...>.

References: Luciferase-2ocq"
```

---

## Task 1.1: Protocol Messages (sealed interfaces)

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/ClientMessage.java`
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/ServerMessage.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/protocol/MessagesTest.java`

**Step 1: Write the failing test**

```java
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void clientMessageSwitchIsExhaustive() {
        ClientMessage msg = new ClientMessage.Hello("1.0");
        String result = switch (msg) {
            case ClientMessage.Hello h -> "hello:" + h.version();
            case ClientMessage.SnapshotRequest r -> "snap:" + r.requestId();
            case ClientMessage.Subscribe s -> "sub:" + s.snapshotToken();
            case ClientMessage.ViewportUpdate v -> "viewport";
            case ClientMessage.Unsubscribe u -> "unsub";
        };
        assertEquals("hello:1.0", result);
    }

    @Test
    void serverMessageSwitchIsExhaustive() {
        ServerMessage msg = new ServerMessage.HelloAck("sess-1");
        String result = switch (msg) {
            case ServerMessage.HelloAck a -> "ack:" + a.sessionId();
            case ServerMessage.SnapshotManifest m -> "manifest";
            case ServerMessage.RegionUpdate u -> "update";
            case ServerMessage.RegionRemoved r -> "removed";
            case ServerMessage.SnapshotRequired sr -> "required";
            case ServerMessage.Error e -> "error";
        };
        assertEquals("ack:sess-1", result);
    }
}
```

**Step 2: Run to verify it fails** (classes don't exist yet)
```
mvn test -pl simulation -Dtest=MessagesTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: compile error.

**Step 3: Create ClientMessage.java**

```java
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.lucien.Frustum3D;
import javax.vecmath.Point3f;
import java.util.Map;

/**
 * Messages sent from browser client to server.
 * clientId is never in message payloads — server identifies client from WS session.
 */
public sealed interface ClientMessage permits
    ClientMessage.Hello,
    ClientMessage.SnapshotRequest,
    ClientMessage.Subscribe,
    ClientMessage.ViewportUpdate,
    ClientMessage.Unsubscribe {

    /** Initial handshake. */
    record Hello(String version) implements ClientMessage {}

    /**
     * Request a snapshot manifest for all occupied keys at the given level.
     * @param requestId  echoed back in SnapshotManifest for correlation
     * @param level      LOD level (0–10 for Tet, 0–21 for Morton)
     */
    record SnapshotRequest(String requestId, int level) implements ClientMessage {}

    /**
     * Begin Phase B push subscription.
     * @param snapshotToken  token from the preceding SnapshotManifest
     * @param knownVersions  map of keyString → version from snapshot
     */
    record Subscribe(long snapshotToken, Map<String, Long> knownVersions)
        implements ClientMessage {}

    /** Camera moved — server updates visible set. Throttled by client to ≤100ms. */
    record ViewportUpdate(Frustum3D frustum, Point3f cameraPos, int level)
        implements ClientMessage {}

    record Unsubscribe() implements ClientMessage {}
}
```

**Step 4: Create ServerMessage.java**

```java
package com.hellblazer.luciferase.simulation.viz.render.protocol;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.List;

/** Messages sent from server to browser client. */
public sealed interface ServerMessage permits
    ServerMessage.HelloAck,
    ServerMessage.SnapshotManifest,
    ServerMessage.RegionUpdate,
    ServerMessage.RegionRemoved,
    ServerMessage.SnapshotRequired,
    ServerMessage.Error {

    record HelloAck(String sessionId) implements ServerMessage {}

    /**
     * Manifest for a snapshot: one entry per occupied key.
     * Binary frames follow immediately after, tagged with snapshotToken.
     */
    record SnapshotManifest(
        String requestId,
        long snapshotToken,
        List<RegionEntry> regions
    ) implements ServerMessage {
        /** Version captured at manifest-compilation time (not live). */
        public record RegionEntry(SpatialKey<?> key, long snapshotVersion, long dataSize) {}
    }

    /**
     * A region was built/updated. Binary payload delivered separately via binary frame.
     * Sent when server has fresh content for a subscribed key.
     */
    record RegionUpdate(SpatialKey<?> key, long version) implements ServerMessage {}

    record RegionRemoved(SpatialKey<?> key) implements ServerMessage {}

    /**
     * Server has evicted this key (no entities, cache miss).
     * Client must remove it from knownVersions; per-key re-snapshot on next request.
     */
    record SnapshotRequired(SpatialKey<?> key) implements ServerMessage {}

    record Error(String code, String message) implements ServerMessage {}
}
```

**Step 5: Run test**
```
mvn test -pl simulation -Dtest=MessagesTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: PASS.

**Step 6: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/protocol/MessagesTest.java
git commit -m "feat(viz-render): add sealed ClientMessage/ServerMessage protocol hierarchies

References: Luciferase-uxbq"
```

---

## Task 1.2: Transport Interface + InProcessTransport

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/Transport.java`
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/InProcessTransport.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/InProcessTransportTest.java`

**Step 1: Write the failing test**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;

class InProcessTransportTest {

    @Test
    void serverCanReceiveAndSend() throws InterruptedException {
        var session = new InProcessTransport();
        var serverSide = session.serverTransport();
        var clientSide = session.clientView();

        // Client sends, server receives
        clientSide.sendToServer(new ClientMessage.Hello("1.0"));
        var received = serverSide.nextClientMessage(100, TimeUnit.MILLISECONDS);
        assertInstanceOf(ClientMessage.Hello.class, received);

        // Server sends, client receives
        serverSide.send(new ServerMessage.HelloAck("sess-42"));
        var sent = clientSide.nextServerMessage(100, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class, sent);
    }

    @Test
    void binaryFramesRoutedSeparately() throws InterruptedException {
        var session = new InProcessTransport();
        var server = session.serverTransport();
        var client = session.clientView();

        byte[] frame = {0x45, 0x53, 0x56, 0x52};
        server.sendBinary(frame);
        var received = client.nextBinaryFrame(100, TimeUnit.MILLISECONDS);
        assertArrayEquals(frame, received);
    }
}
```

**Step 2: Run to verify compile error**
```
mvn test -pl simulation -Dtest=InProcessTransportTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 3: Create Transport.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.ClientMessage;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction over WebSocket connection. Enables in-process testing without network.
 * The server reads ClientMessages from the client and writes ServerMessages + binary frames.
 */
public interface Transport {
    /** Send a JSON control message to the client. */
    void send(ServerMessage msg);

    /** Send a pre-encoded binary frame to the client. */
    void sendBinary(byte[] frame);

    /**
     * Block until a ClientMessage arrives, or timeout.
     * Returns null on timeout.
     */
    ClientMessage nextClientMessage(long timeout, TimeUnit unit) throws InterruptedException;

    /** Close this transport. */
    void close();
}
```

**Step 4: Create InProcessTransport.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.ClientMessage;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process Transport for deterministic testing. No network, no serialization.
 * Use serverTransport() as the server-side handle and clientView() for test assertions.
 */
public final class InProcessTransport {

    private final BlockingQueue<ClientMessage> fromClient = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerMessage> toClient   = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]>        binaryToClient = new LinkedBlockingQueue<>();

    /** The handle the server-side code (session handler) holds. */
    public Transport serverTransport() {
        return new Transport() {
            @Override public void send(ServerMessage msg) { toClient.add(msg); }
            @Override public void sendBinary(byte[] frame) { binaryToClient.add(frame); }
            @Override public ClientMessage nextClientMessage(long timeout, TimeUnit unit)
                throws InterruptedException { return fromClient.poll(timeout, unit); }
            @Override public void close() {}
        };
    }

    /** The handle test code holds to simulate client behaviour. */
    public ClientView clientView() { return new ClientView(); }

    public final class ClientView {
        public void sendToServer(ClientMessage msg)       { fromClient.add(msg); }
        public ServerMessage nextServerMessage(long t, TimeUnit u) throws InterruptedException {
            return toClient.poll(t, u);
        }
        public byte[] nextBinaryFrame(long t, TimeUnit u) throws InterruptedException {
            return binaryToClient.poll(t, u);
        }
    }
}
```

**Step 5: Run test**
```
mvn test -pl simulation -Dtest=InProcessTransportTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: PASS.

**Step 6: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/Transport.java
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/InProcessTransport.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/InProcessTransportTest.java
git commit -m "feat(viz-render): add Transport interface and InProcessTransport for testing

References: Luciferase-uxbq"
```

---

## Task 2.1: SpatialIndexFacade Interface + Skeleton Tests

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/SpatialIndexFacade.java`

**Step 1: Create SpatialIndexFacade.java**

No test here — it's an interface. But we write a compile-check test in Task 2.2.

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import javax.vecmath.Point3f;
import java.util.List;
import java.util.Set;

/**
 * Owns entity positions and the spatial partition map.
 *
 * <p>Coordinates are in internal integer space (0..2^21-1 cast to float),
 * the same convention as Tetree/Octree insert().
 *
 * <p>Replaces AdaptiveRegionManager's entity-tracking responsibility.
 * DirtyTracker is a separate concern that consumes keysContaining().
 */
public interface SpatialIndexFacade {

    /** Insert a new entity at the given position. */
    void put(long entityId, Point3f position);

    /** Move an existing entity. No-op if entityId unknown. */
    void move(long entityId, Point3f newPosition);

    /** Remove an entity. No-op if entityId unknown. */
    void remove(long entityId);

    /**
     * All spatial cells containing the given point at each level in [minLevel, maxLevel].
     * Used by DirtyTracker when an entity is put/moved/removed.
     */
    Set<SpatialKey<?>> keysContaining(Point3f point, int minLevel, int maxLevel);

    /**
     * Current entity positions within the given cell.
     * Called by RegionBuilder at build time (not queue time — avoids TOCTOU).
     * Returns an empty list for cells with no entities.
     */
    List<Point3f> positionsAt(SpatialKey<?> key);

    /**
     * All occupied cells at the given level whose AABB intersects the frustum.
     * Used by SubscriptionManager to compute the visible set per viewport update.
     */
    Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level);

    /** All occupied cells at the given level (for snapshot / backfill). */
    Set<SpatialKey<?>> allOccupiedKeys(int level);

    /** Total number of tracked entities. */
    int entityCount();
}
```

**Step 2: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/SpatialIndexFacade.java
git commit -m "feat(viz-render): add SpatialIndexFacade interface

References: Luciferase-uxbq"
```

---

## Task 2.2: TetreeSpatialIndexFacade

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/TetreeSpatialIndexFacade.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/TetreeSpatialIndexFacadeTest.java`

**Step 1: Write the failing tests**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import static org.junit.jupiter.api.Assertions.*;

class TetreeSpatialIndexFacadeTest {

    private TetreeSpatialIndexFacade facade;

    @BeforeEach
    void setUp() { facade = new TetreeSpatialIndexFacade(6, 10); }
    //                                                    minLevel, maxDirtyLevel

    @Test
    void putAndRetrieve() {
        facade.put(1L, new Point3f(100, 100, 100));
        assertEquals(1, facade.entityCount());
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 6, 6);
        assertFalse(keys.isEmpty());
        keys.forEach(k -> assertInstanceOf(TetreeKey.class, k));
    }

    @Test
    void positionsAtContainsInsertedEntity() {
        var pos = new Point3f(200, 200, 200);
        facade.put(2L, pos);
        var keys = facade.keysContaining(pos, 6, 6);
        assertFalse(keys.isEmpty());
        var cell = keys.iterator().next();
        var positions = facade.positionsAt(cell);
        assertTrue(positions.stream().anyMatch(p -> p.epsilonEquals(pos, 0.1f)));
    }

    @Test
    void moveUpdatesCell() {
        var oldPos = new Point3f(100, 100, 100);
        var newPos = new Point3f(5000, 5000, 5000);
        facade.put(3L, oldPos);
        facade.move(3L, newPos);
        var oldKeys = facade.keysContaining(oldPos, 6, 6);
        // old cell should now be empty (or the entity is no longer there)
        oldKeys.forEach(k -> assertFalse(facade.positionsAt(k).stream()
            .anyMatch(p -> p.epsilonEquals(oldPos, 0.1f))));
    }

    @Test
    void removeDecrementsCount() {
        facade.put(4L, new Point3f(300, 300, 300));
        facade.remove(4L);
        assertEquals(0, facade.entityCount());
    }

    @Test
    void allOccupiedKeysAtLevel() {
        facade.put(5L, new Point3f(100, 100, 100));
        facade.put(6L, new Point3f(5000, 5000, 5000));
        var occupied = facade.allOccupiedKeys(6);
        assertFalse(occupied.isEmpty());
        occupied.forEach(k -> assertEquals(6, k.getLevel()));
    }
}
```

**Step 2: Run to verify compile error**
```
mvn test -pl simulation -Dtest=TetreeSpatialIndexFacadeTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 3: Create TetreeSpatialIndexFacade.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SpatialIndexFacade backed by Tet.locatePointBeyRefinementFromRoot.
 *
 * <p>Internal coordinate space: 0..2^21-1 cast to float, same as Tetree.insert().
 * Level cap: 0–10 (CompactTetreeKey range).
 */
public final class TetreeSpatialIndexFacade implements SpatialIndexFacade {

    private final int minLevel;
    private final int maxDirtyLevel;  // inclusive upper bound for keysContaining

    // entityId → current position
    private final ConcurrentHashMap<Long, Point3f> entityPositions = new ConcurrentHashMap<>();
    // key → set of entityIds in that cell
    private final ConcurrentHashMap<SpatialKey<?>, Set<Long>> cellOccupants = new ConcurrentHashMap<>();

    public TetreeSpatialIndexFacade(int minLevel, int maxDirtyLevel) {
        this.minLevel = minLevel;
        this.maxDirtyLevel = Math.min(maxDirtyLevel, 10); // cap at CompactTetreeKey max
    }

    @Override
    public void put(long entityId, Point3f position) {
        entityPositions.put(entityId, new Point3f(position));
        keysContaining(position, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void move(long entityId, Point3f newPosition) {
        var oldPos = entityPositions.get(entityId);
        if (oldPos == null) return;
        // Remove from old cells
        keysContaining(oldPos, minLevel, maxDirtyLevel)
            .forEach(k -> {
                var occupants = cellOccupants.get(k);
                if (occupants != null) {
                    occupants.remove(entityId);
                    if (occupants.isEmpty()) cellOccupants.remove(k, occupants);
                }
            });
        // Add to new cells
        entityPositions.put(entityId, new Point3f(newPosition));
        keysContaining(newPosition, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void remove(long entityId) {
        var pos = entityPositions.remove(entityId);
        if (pos == null) return;
        keysContaining(pos, minLevel, maxDirtyLevel)
            .forEach(k -> {
                var occupants = cellOccupants.get(k);
                if (occupants != null) {
                    occupants.remove(entityId);
                    if (occupants.isEmpty()) cellOccupants.remove(k, occupants);
                }
            });
    }

    @Override
    public Set<SpatialKey<?>> keysContaining(Point3f point, int minLvl, int maxLvl) {
        var result = new HashSet<SpatialKey<?>>();
        int cap = Math.min(maxLvl, 10);
        for (int L = minLvl; L <= cap; L++) {
            var tet = Tet.locatePointBeyRefinementFromRoot(point.x, point.y, point.z, (byte) L);
            result.add(tet.tmIndex());
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public List<Point3f> positionsAt(SpatialKey<?> key) {
        var occupants = cellOccupants.get(key);
        if (occupants == null || occupants.isEmpty()) return List.of();
        var result = new ArrayList<Point3f>();
        for (var entityId : occupants) {
            var pos = entityPositions.get(entityId);
            if (pos != null) result.add(new Point3f(pos));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() != level) continue;
            if (frustumIntersects(key, frustum)) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<SpatialKey<?>> allOccupiedKeys(int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() == level && !cellOccupants.get(key).isEmpty()) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int entityCount() { return entityPositions.size(); }

    private static boolean frustumIntersects(SpatialKey<?> key, Frustum3D frustum) {
        if (!(key instanceof TetreeKey<?> tk)) return false;
        var verts = Tet.tetrahedron(tk).coordinates();  // Point3i[4]
        float minX = verts[0].x, maxX = verts[0].x;
        float minY = verts[0].y, maxY = verts[0].y;
        float minZ = verts[0].z, maxZ = verts[0].z;
        for (var v : verts) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
        }
        return frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
```

**Step 4: Run tests**
```
mvn test -pl simulation -Dtest=TetreeSpatialIndexFacadeTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: all 5 tests PASS.

**Step 5: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/TetreeSpatialIndexFacade.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/TetreeSpatialIndexFacadeTest.java
git commit -m "feat(viz-render): add TetreeSpatialIndexFacade

References: Luciferase-uxbq"
```

---

## Task 2.3: OctreeSpatialIndexFacade

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/OctreeSpatialIndexFacade.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/OctreeSpatialIndexFacadeTest.java`

**Step 1: Write the failing tests** — same shape as TetreeSpatialIndexFacadeTest but assert `MortonKey.class` instead:

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import static org.junit.jupiter.api.Assertions.*;

class OctreeSpatialIndexFacadeTest {

    private OctreeSpatialIndexFacade facade;

    @BeforeEach void setUp() { facade = new OctreeSpatialIndexFacade(4, 12); }

    @Test
    void putAndRetrieve() {
        facade.put(1L, new Point3f(100, 100, 100));
        assertEquals(1, facade.entityCount());
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        assertFalse(keys.isEmpty());
        keys.forEach(k -> assertInstanceOf(MortonKey.class, k));
    }

    @Test
    void positionsAtContainsInsertedEntity() {
        var pos = new Point3f(500, 500, 500);
        facade.put(2L, pos);
        var keys = facade.keysContaining(pos, 4, 4);
        var cell = keys.iterator().next();
        var positions = facade.positionsAt(cell);
        assertTrue(positions.stream().anyMatch(p -> p.epsilonEquals(pos, 0.1f)));
    }

    @Test
    void allOccupiedKeysAtLevel() {
        facade.put(3L, new Point3f(100, 100, 100));
        var occupied = facade.allOccupiedKeys(4);
        assertFalse(occupied.isEmpty());
        occupied.forEach(k -> assertEquals(4, k.getLevel()));
    }
}
```

**Step 2: Create OctreeSpatialIndexFacade.java**

Structure mirrors `TetreeSpatialIndexFacade`. Key differences:
- `keysContaining` uses `MortonKey.fromCoordinates((int)pos.x, (int)pos.y, (int)pos.z, (byte)L)`
- `frustumIntersects` uses `MortonCurve.decode` + `Constants.lengthAtLevel`
- Level cap from `Constants.getMaxRefinementLevel()` (not hard-coded 10)

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** SpatialIndexFacade backed by MortonKey. Supports levels 0–21. */
public final class OctreeSpatialIndexFacade implements SpatialIndexFacade {

    private final int minLevel;
    private final int maxDirtyLevel;

    private final ConcurrentHashMap<Long, Point3f>          entityPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SpatialKey<?>, Set<Long>> cellOccupants = new ConcurrentHashMap<>();

    public OctreeSpatialIndexFacade(int minLevel, int maxDirtyLevel) {
        this.minLevel = minLevel;
        this.maxDirtyLevel = Math.min(maxDirtyLevel, Constants.getMaxRefinementLevel());
    }

    @Override
    public void put(long entityId, Point3f position) {
        entityPositions.put(entityId, new Point3f(position));
        keysContaining(position, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void move(long entityId, Point3f newPosition) {
        var oldPos = entityPositions.get(entityId);
        if (oldPos == null) return;
        keysContaining(oldPos, minLevel, maxDirtyLevel)
            .forEach(k -> { var s = cellOccupants.get(k);
                if (s != null) { s.remove(entityId); if (s.isEmpty()) cellOccupants.remove(k, s); }});
        entityPositions.put(entityId, new Point3f(newPosition));
        keysContaining(newPosition, minLevel, maxDirtyLevel)
            .forEach(k -> cellOccupants.computeIfAbsent(k, x -> new CopyOnWriteArraySet<>())
                                       .add(entityId));
    }

    @Override
    public void remove(long entityId) {
        var pos = entityPositions.remove(entityId);
        if (pos == null) return;
        keysContaining(pos, minLevel, maxDirtyLevel)
            .forEach(k -> { var s = cellOccupants.get(k);
                if (s != null) { s.remove(entityId); if (s.isEmpty()) cellOccupants.remove(k, s); }});
    }

    @Override
    public Set<SpatialKey<?>> keysContaining(Point3f point, int minLvl, int maxLvl) {
        var result = new HashSet<SpatialKey<?>>();
        int cap = Math.min(maxLvl, Constants.getMaxRefinementLevel());
        for (int L = minLvl; L <= cap; L++) {
            result.add(MortonKey.fromCoordinates(
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.x)),
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.y)),
                Math.max(0, Math.min(Constants.MAX_COORD, (int) point.z)),
                (byte) L));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public List<Point3f> positionsAt(SpatialKey<?> key) {
        var occupants = cellOccupants.get(key);
        if (occupants == null || occupants.isEmpty()) return List.of();
        var result = new ArrayList<Point3f>();
        for (var id : occupants) { var p = entityPositions.get(id); if (p != null) result.add(new Point3f(p)); }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Set<SpatialKey<?>> keysVisible(Frustum3D frustum, int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() != level) continue;
            if (frustumIntersects(key, frustum)) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<SpatialKey<?>> allOccupiedKeys(int level) {
        var result = new HashSet<SpatialKey<?>>();
        for (var key : cellOccupants.keySet()) {
            if (key.getLevel() == level && !cellOccupants.get(key).isEmpty()) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int entityCount() { return entityPositions.size(); }

    private static boolean frustumIntersects(SpatialKey<?> key, Frustum3D frustum) {
        if (!(key instanceof MortonKey mk)) return false;
        var c = MortonCurve.decode(mk.getMortonCode());
        var s = (float) Constants.lengthAtLevel(mk.getLevel());
        return frustum.intersectsAABB(c[0], c[1], c[2], c[0] + s, c[1] + s, c[2] + s);
    }
}
```

**Step 3: Run tests**
```
mvn test -pl simulation -Dtest=OctreeSpatialIndexFacadeTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/OctreeSpatialIndexFacade.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/OctreeSpatialIndexFacadeTest.java
git commit -m "feat(viz-render): add OctreeSpatialIndexFacade

References: Luciferase-uxbq"
```

---

## Task 3.1: DirtyTracker

Tracks `keyVersion` per `SpatialKey<?>`. Version 0 = never dirtied.

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/DirtyTracker.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/DirtyTrackerTest.java`

**Step 1: Write tests**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirtyTrackerTest {

    @Test
    void freshKeyVersionIsZero() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(0L, (byte) 5);
        assertEquals(0L, tracker.version(key));
    }

    @Test
    void bumpIncrementsVersion() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(42L, (byte) 5);
        assertEquals(1L, tracker.bump(key));
        assertEquals(2L, tracker.bump(key));
        assertEquals(2L, tracker.version(key));
    }

    @Test
    void isDirtyWhenKeyVersionExceedsCacheVersion() {
        var tracker = new DirtyTracker();
        var key = new MortonKey(1L, (byte) 4);
        assertFalse(tracker.isDirty(key, 0L), "not dirty at version 0");
        tracker.bump(key);
        assertTrue(tracker.isDirty(key, 0L), "dirty after bump vs cache=0");
        assertFalse(tracker.isDirty(key, 1L), "not dirty once cache catches up");
    }

    @Test
    void bumpAllNotifiesAllAffectedKeys() {
        var tracker = new DirtyTracker();
        var k1 = new MortonKey(1L, (byte) 5);
        var k2 = new MortonKey(2L, (byte) 5);
        tracker.bumpAll(java.util.Set.of(k1, k2));
        assertEquals(1L, tracker.version(k1));
        assertEquals(1L, tracker.version(k2));
    }
}
```

**Step 2: Run to verify compile error**
```
mvn test -pl simulation -Dtest=DirtyTrackerTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 3: Create DirtyTracker.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks keyVersion per SpatialKey.
 * dirty ≡ version(key) > cacheVersion(key).
 * Version 0 means "never dirtied".
 */
public final class DirtyTracker {

    private final ConcurrentHashMap<SpatialKey<?>, AtomicLong> keyVersions = new ConcurrentHashMap<>();

    /** Current version for key. Returns 0 if never dirtied. */
    public long version(SpatialKey<?> key) {
        var counter = keyVersions.get(key);
        return counter == null ? 0L : counter.get();
    }

    /** Increment version for key. Returns new version. */
    public long bump(SpatialKey<?> key) {
        return keyVersions.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
    }

    /** Bump all keys in the collection. Used when an entity moves. */
    public void bumpAll(Collection<SpatialKey<?>> keys) {
        keys.forEach(this::bump);
    }

    /** True iff version(key) > cacheVersion. */
    public boolean isDirty(SpatialKey<?> key, long cacheVersion) {
        return version(key) > cacheVersion;
    }
}
```

**Step 4: Run tests**
```
mvn test -pl simulation -Dtest=DirtyTrackerTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: all 4 PASS.

**Step 5: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/DirtyTracker.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/DirtyTrackerTest.java
git commit -m "feat(viz-render): add DirtyTracker with version-as-dirty-signal

References: Luciferase-uxbq"
```

---

## Task 4.1: Update BinaryFrameCodec Header Fields

The header is already 24 bytes. Only two field names change: `lod` → `key_type` at byte 5, and `mortonCode` → `key` at byte 8. The codec also needs to accept `SpatialKey<?>` instead of the implicit `RegionId`.

**Files:**
- Modify: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/BinaryFrameCodec.java`
- Modify: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/ProtocolConstants.java`
- Modify: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/protocol/BinaryFrameCodecTest.java`

**Step 1: Add constants to ProtocolConstants.java**

Add after the existing constants:
```java
// key_type byte values
public static final byte KEY_TYPE_MORTON = 0x01;
public static final byte KEY_TYPE_TET    = 0x02;  // CompactTetreeKey, level 0-10
```

**Step 2: Add new encode/decode overload accepting SpatialKey<?>**

In `BinaryFrameCodec.java`, add:

```java
/**
 * Encode a frame using a SpatialKey<?> directly.
 * keyLong is the single-long representation:
 *   MortonKey  → getMortonCode()
 *   TetreeKey  → getLowBits()  (always CompactTetreeKey at protocol level)
 */
public static ByteBuffer encodeWithKey(
        SpatialKey<?> key, RegionBuilder.BuildType type,
        long buildVersion, byte[] data) {
    var buffer = ByteBuffer.allocate(ProtocolConstants.FRAME_HEADER_SIZE + data.length);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    writeHeader(buffer, key, type, buildVersion, data.length);
    buffer.position(ProtocolConstants.FRAME_HEADER_SIZE);
    buffer.put(data);
    buffer.position(0);
    return buffer;
}

private static void writeHeader(ByteBuffer buf, SpatialKey<?> key,
                                 RegionBuilder.BuildType type, long buildVersion, int dataSize) {
    buf.putInt(0, ProtocolConstants.FRAME_MAGIC);
    buf.put(4, formatCode(type));
    buf.put(5, keyTypeByte(key));
    buf.put(6, key.getLevel());
    buf.put(7, (byte) 0);
    buf.putLong(8, keyLong(key));
    buf.putInt(16, (int) buildVersion);
    buf.putInt(20, dataSize);
}

private static byte keyTypeByte(SpatialKey<?> key) {
    return switch (key) {
        case com.hellblazer.luciferase.lucien.octree.MortonKey mk ->
            ProtocolConstants.KEY_TYPE_MORTON;
        case com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> tk ->
            ProtocolConstants.KEY_TYPE_TET;
        default -> throw new IllegalArgumentException("Unknown key type: " + key.getClass());
    };
}

private static long keyLong(SpatialKey<?> key) {
    return switch (key) {
        case com.hellblazer.luciferase.lucien.octree.MortonKey mk -> mk.getMortonCode();
        case com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey tk -> tk.getLowBits();
        default -> throw new IllegalArgumentException("ExtendedTetreeKey not supported at wire level");
    };
}
```

Also update `FrameHeader` record to rename the `lod` field to `keyType`:
Find: `public record FrameHeader(int magic, byte format, byte lod, byte level, long mortonCode, int buildVersion, int dataSize)`
Replace: `public record FrameHeader(int magic, byte format, byte keyType, byte level, long key, int buildVersion, int dataSize)`

Update `decodeHeader` accordingly (rename variables).

**Step 3: Update BinaryFrameCodecTest.java**

Find any assertions on `header.lod()` and rename to `header.keyType()`. Find assertions on `header.mortonCode()` and rename to `header.key()`. Run the test to confirm it still passes.

**Step 4: Run tests**
```
mvn test -pl simulation -Dtest=BinaryFrameCodecTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: PASS.

**Step 5: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/protocol/
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/protocol/BinaryFrameCodecTest.java
git commit -m "feat(viz-render): update BinaryFrameCodec to use SpatialKey<?> and key_type field

References: Luciferase-uxbq, Luciferase-sot7"
```

---

## Task 5.1: Migrate RegionBuilder to SpatialKey<?>

`BuildRequest` currently takes `RegionId` + `List<Point3f>` + `RegionBounds`. New design: `SpatialKey<?>` only — positions fetched from `SpatialIndexFacade` at build time.

**Files:**
- Modify: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/RegionBuilder.java`
- Modify: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/RegionBuilderTest.java`

**Step 1: Add new BuildRequest overload**

Do NOT remove the old `BuildRequest` record yet — old tests depend on it. Add a parallel new record:

```java
/** New-architecture build request keyed on SpatialKey<?>. */
public record KeyedBuildRequest(
        SpatialKey<?> key,
        Priority priority,
        long timestamp
) implements Comparable<KeyedBuildRequest> {
    public enum Priority { VISIBLE, RECENTLY_VISIBLE, BACKFILL }
    @Override public int compareTo(KeyedBuildRequest other) {
        int pc = priority.compareTo(other.priority);
        if (pc != 0) return pc;
        return Long.compare(timestamp, other.timestamp);
    }
}
```

**Step 2: Add new BuiltKeyedRegion record**

```java
/** Result of a KeyedBuildRequest. */
public record BuiltKeyedRegion(
        SpatialKey<?> key,
        BuildType type,
        byte[] serializedData,
        long buildVersion
) {}
```

**Step 3: Add new build method**

```java
/**
 * Build a region for a SpatialKey<?>, fetching positions from the facade at call time.
 * This is the new-architecture entry point. Positions are fetched HERE (not at queue time)
 * to avoid TOCTOU: the build uses the world state as of when the build actually executes.
 */
public CompletableFuture<BuiltKeyedRegion> buildKeyed(
        SpatialKey<?> key, SpatialIndexFacade facade, long buildVersion) {
    var future = new CompletableFuture<BuiltKeyedRegion>();
    buildPool.submit(() -> {
        try {
            var positions = facade.positionsAt(key);
            var type = key instanceof com.hellblazer.luciferase.lucien.tetree.TetreeKey ?
                       BuildType.ESVT : BuildType.ESVO;
            var data = doBuildFromPositions(key, positions, type);
            future.complete(new BuiltKeyedRegion(key, type, data, buildVersion));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    });
    return future;
}
```

Add helper `doBuildFromPositions(SpatialKey<?> key, List<Point3f> positions, BuildType type)` that adapts to the existing ESVO/ESVT builder calls. Reuse the body of `doBuild` — extract the shared builder logic.

**Step 4: Write a focused test for buildKeyed**

```java
@Test
void buildKeyedProducesDataForOccupiedCell() throws Exception {
    var builder = new RegionBuilder(2, 10, 8, 64);
    var facade = new OctreeSpatialIndexFacade(4, 8);
    facade.put(1L, new Point3f(100, 100, 100));
    var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
    var key = keys.iterator().next();
    var result = builder.buildKeyed(key, facade, 1L).get(5, TimeUnit.SECONDS);
    assertNotNull(result);
    assertNotNull(result.serializedData());
    assertTrue(result.serializedData().length > 0);
    assertEquals(key, result.key());
}
```

**Step 5: Run**
```
mvn test -pl simulation -Dtest=RegionBuilderTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 6: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/RegionBuilder.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/RegionBuilderTest.java
git commit -m "feat(viz-render): add buildKeyed() to RegionBuilder for SpatialKey<?> API

Positions fetched from SpatialIndexFacade at build time to avoid TOCTOU.
Old build(BuildRequest) retained for backward compatibility.

References: Luciferase-uxbq, Luciferase-flc3"
```

---

## Task 6.1: BuildQueue with inFlight Deduplication

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/BuildQueue.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/BuildQueueTest.java`

**Step 1: Write tests**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BuildQueueTest {

    @Test
    void duplicateSubmissionsDeduped() throws InterruptedException {
        var facade = new OctreeSpatialIndexFacade(4, 8);
        facade.put(1L, new Point3f(100, 100, 100));
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        var key = keys.iterator().next();

        var tracker = new DirtyTracker();
        tracker.bump(key);

        var buildCount = new AtomicInteger(0);
        var queue = new BuildQueue(facade, tracker, new RegionBuilder(1, 10, 8, 64),
            (k, v, data) -> buildCount.incrementAndGet());  // onBuildComplete callback

        // Submit twice — should only build once
        queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);

        queue.awaitBuilds().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, buildCount.get(), "duplicate submissions must be deduplicated");
    }

    @Test
    void staleVersionDiscarded() throws Exception {
        var facade = new OctreeSpatialIndexFacade(4, 8);
        facade.put(1L, new Point3f(200, 200, 200));
        var keys = facade.keysContaining(new Point3f(200, 200, 200), 4, 4);
        var key = keys.iterator().next();

        var tracker = new DirtyTracker();
        tracker.bump(key);

        var completedCount = new AtomicInteger(0);
        var queue = new BuildQueue(facade, tracker, new RegionBuilder(1, 10, 8, 64),
            (k, v, data) -> completedCount.incrementAndGet());

        queue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        tracker.bump(key);  // version changes before build completes — build is stale

        queue.awaitBuilds().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, completedCount.get(), "stale build must be discarded");
    }
}
```

**Step 2: Create BuildQueue.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Deduplicating build dispatcher. At most one in-flight build per SpatialKey<?>.
 * Positions are fetched from facade AT BUILD TIME (not submission time).
 */
public final class BuildQueue {

    @FunctionalInterface
    public interface BuildCompleteCallback {
        void onComplete(SpatialKey<?> key, long buildVersion, byte[] data);
    }

    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final RegionBuilder builder;
    private final BuildCompleteCallback callback;
    private final ConcurrentHashMap<SpatialKey<?>, CompletableFuture<Void>> inFlight =
        new ConcurrentHashMap<>();

    public BuildQueue(SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                      RegionBuilder builder, BuildCompleteCallback callback) {
        this.facade = facade;
        this.dirtyTracker = dirtyTracker;
        this.builder = builder;
        this.callback = callback;
    }

    /**
     * Submit a build for key if not already in-flight.
     * No-op if a build for this key is already running.
     */
    public void submit(SpatialKey<?> key, RegionBuilder.KeyedBuildRequest.Priority priority) {
        inFlight.computeIfAbsent(key, k -> {
            long expectedVersion = dirtyTracker.version(k);
            return builder.buildKeyed(k, facade, expectedVersion)
                .whenComplete((result, err) -> {
                    inFlight.remove(k);
                    if (result == null) return;  // build failed
                    // Discard stale: if version changed since we started, don't deliver
                    if (dirtyTracker.version(k) != result.buildVersion()) return;
                    callback.onComplete(k, result.buildVersion(), result.serializedData());
                })
                .thenApply(r -> (Void) null);
        });
    }

    /**
     * Returns a future that completes when all currently in-flight builds finish.
     * Safe to call from tests: snapshot of inFlight at call time.
     */
    public CompletableFuture<Void> awaitBuilds() {
        var futures = inFlight.values().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(futures);
    }
}
```

**Step 3: Run tests**
```
mvn test -pl simulation -Dtest=BuildQueueTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: both PASS.

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/BuildQueue.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/BuildQueueTest.java
git commit -m "feat(viz-render): add BuildQueue with inFlight deduplication

References: Luciferase-uxbq"
```

---

## Task 6.2: StreamingCache

Stores `CacheEntry(version, data)` per key. The version guard in `onBuildComplete` prevents stale entries.

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCache.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCacheTest.java`

**Step 1: Write tests**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamingCacheTest {

    @Test
    void freshCacheReturnsEmpty() {
        var cache = new StreamingCache();
        var key = new MortonKey(0L, (byte) 5);
        assertNull(cache.get(key));
        assertEquals(0L, cache.cacheVersion(key));
    }

    @Test
    void putAndGet() {
        var cache = new StreamingCache();
        var key = new MortonKey(1L, (byte) 5);
        byte[] data = {1, 2, 3};
        cache.put(key, 1L, data);
        var entry = cache.get(key);
        assertNotNull(entry);
        assertEquals(1L, entry.version());
        assertArrayEquals(data, entry.data());
    }

    @Test
    void cacheVersionTracked() {
        var cache = new StreamingCache();
        var key = new MortonKey(2L, (byte) 5);
        assertEquals(0L, cache.cacheVersion(key));
        cache.put(key, 3L, new byte[]{});
        assertEquals(3L, cache.cacheVersion(key));
    }
}
```

**Step 2: Create StreamingCache.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe cache of built region data, keyed on SpatialKey<?>. */
public final class StreamingCache {

    public record CacheEntry(long version, byte[] data) {}

    private final ConcurrentHashMap<SpatialKey<?>, CacheEntry> entries = new ConcurrentHashMap<>();

    public CacheEntry get(SpatialKey<?> key) { return entries.get(key); }

    public void put(SpatialKey<?> key, long version, byte[] data) {
        entries.put(key, new CacheEntry(version, data));
    }

    public void remove(SpatialKey<?> key) { entries.remove(key); }

    /** Returns 0L if key has never been built. */
    public long cacheVersion(SpatialKey<?> key) {
        var entry = entries.get(key);
        return entry == null ? 0L : entry.version();
    }
}
```

**Step 3: Run tests**
```
mvn test -pl simulation -Dtest=StreamingCacheTest -Dsurefire.rerunFailingTestsCount=0
```

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCache.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCacheTest.java
git commit -m "feat(viz-render): add StreamingCache

References: Luciferase-uxbq"
```

---

## Task 7.1: Test Infrastructure

**Files:**
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingPipelineFixture.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/WorldFixture.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/TestFrustums.java`

These are test support classes — no production code, so no failing tests first.

**Step 1: Create WorldFixture.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import javax.vecmath.Point3f;
import java.util.Random;

/** Fluent builder for populating a SpatialIndexFacade with test entities. */
public final class WorldFixture {
    private final SpatialIndexFacade facade;

    private WorldFixture(SpatialIndexFacade facade) { this.facade = facade; }

    public static WorldFixture tetree(int minLevel, int maxLevel) {
        return new WorldFixture(new TetreeSpatialIndexFacade(minLevel, maxLevel));
    }

    public static WorldFixture octree(int minLevel, int maxLevel) {
        return new WorldFixture(new OctreeSpatialIndexFacade(minLevel, maxLevel));
    }

    /** Insert n entities at random positions using the given seed. */
    public WorldFixture withRandomEntities(int count, long seed) {
        var rng = new Random(seed);
        int max = 1 << 21;  // MAX_EXTENT
        for (int i = 0; i < count; i++) {
            facade.put(i, new Point3f(rng.nextInt(max), rng.nextInt(max), rng.nextInt(max)));
        }
        return this;
    }

    /** Insert a single entity at exact position. */
    public WorldFixture withEntity(long id, float x, float y, float z) {
        facade.put(id, new Point3f(x, y, z));
        return this;
    }

    public SpatialIndexFacade build() { return facade; }
}
```

**Step 2: Create TestFrustums.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/** Pre-built Frustum3D instances for tests. All coordinates in internal space (0..2^21-1). */
public final class TestFrustums {
    private static final int MAX = 1 << 21;

    private TestFrustums() {}

    /** A frustum that encompasses the entire world. */
    public static Frustum3D fullScene() {
        return Frustum3D.fromCameraState(
            new Point3f(MAX / 2f, MAX / 2f, MAX * 2f),  // camera far above centre
            new Vector3f(0, 0, -1),                      // looking down Z
            new Vector3f(0, 1, 0),                       // up = Y
            90f, 1.0f, 1f, (float)(MAX * 3));            // wide FOV, far clip
    }

    /** A tight frustum around the origin corner. */
    public static Frustum3D origin() {
        return Frustum3D.fromCameraState(
            new Point3f(0, 0, 5000),
            new Vector3f(0, 0, -1),
            new Vector3f(0, 1, 0),
            60f, 1.0f, 1f, 10000f);
    }
}
```

**Note:** If `Frustum3D.fromCameraState` doesn't exist, check the actual Frustum3D constructor. Run:
```
grep -n "public.*Frustum3D\|static.*frustum\|fromCamera\|new Frustum" \
  lucien/src/main/java/com/hellblazer/luciferase/lucien/Frustum3D.java | head -15
```
Adapt the factory method call accordingly.

**Step 3: Create StreamingPipelineFixture.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Wires together SpatialIndexFacade + DirtyTracker + BuildQueue + StreamingCache
 * for deterministic pipeline testing. Call tick() to advance one streaming cycle.
 */
public final class StreamingPipelineFixture {

    public final SpatialIndexFacade world;
    public final DirtyTracker dirtyTracker;
    public final StreamingCache cache;
    public final BuildQueue buildQueue;

    public StreamingPipelineFixture(SpatialIndexFacade world) {
        this.world = world;
        this.dirtyTracker = new DirtyTracker();
        this.cache = new StreamingCache();
        var builder = new RegionBuilder(2, 20, 8, 64);
        this.buildQueue = new BuildQueue(world, dirtyTracker, builder,
            (key, version, data) -> cache.put(key, version, data));
    }

    /** Mark dirty and submit build for a key. */
    public void submitBuild(SpatialKey<?> key) {
        dirtyTracker.bump(key);
        buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
    }

    /** Wait for all in-flight builds. */
    public void awaitBuilds() {
        try {
            buildQueue.awaitBuilds().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Build timed out", e);
        }
    }
}
```

**Step 4: Compile-check by running the whole simulation test suite**
```
mvn test -pl simulation -Dsurefire.rerunFailingTestsCount=0
```
Expected: all existing tests still PASS, new fixture classes compile.

**Step 5: Commit**
```
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingPipelineFixture.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/WorldFixture.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/TestFrustums.java
git commit -m "test(viz-render): add StreamingPipelineFixture, WorldFixture, TestFrustums

References: Luciferase-uxbq"
```

---

## Task 8.1: SubscriptionManager

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/SubscriptionManager.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/SubscriptionManagerTest.java`

**Step 1: Write tests**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionManagerTest {

    @Test
    void subscribeRegistersClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        manager.subscribe("sess-1", transport.serverTransport(),
            Map.of(), TestFrustums.fullScene(), 5);
        assertEquals(1, manager.activeClientCount());
    }

    @Test
    void pushDeliversMsgToSubscriber() throws InterruptedException {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        manager.subscribe("sess-1", transport.serverTransport(),
            Map.of(), TestFrustums.fullScene(), 5);

        var key = new MortonKey(42L, (byte) 5);
        manager.push("sess-1", key, 1L);

        var msg = client.nextServerMessage(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, msg);
        assertEquals(key, ((ServerMessage.RegionUpdate) msg).key());
    }

    @Test
    void unsubscribeRemovesClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        manager.subscribe("sess-1", transport.serverTransport(),
            Map.of(), TestFrustums.fullScene(), 5);
        manager.unsubscribe("sess-1");
        assertEquals(0, manager.activeClientCount());
    }

    @Test
    void knownVersionTrackedPerClient() {
        var manager = new SubscriptionManager();
        var transport = new InProcessTransport();
        var key = new MortonKey(7L, (byte) 5);
        manager.subscribe("sess-1", transport.serverTransport(),
            Map.of(keyString(key), 3L), TestFrustums.fullScene(), 5);
        assertEquals(3L, manager.knownVersion("sess-1", key));
    }

    private static String keyString(MortonKey k) {
        return "oct:" + k.getLevel() + ":" + java.util.Base64.getEncoder()
            .encodeToString(java.nio.ByteBuffer.allocate(8).putLong(k.getMortonCode()).array());
    }
}
```

**Step 2: Create SubscriptionManager.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Manages active client subscriptions and push delivery. */
public final class SubscriptionManager {

    public record ClientState(
        String sessionId,
        Transport transport,
        ConcurrentHashMap<String, Long> knownVersions,  // keyString → version
        Frustum3D frustum,
        int level
    ) {}

    private final ConcurrentHashMap<String, ClientState> clients = new ConcurrentHashMap<>();
    // Ordered list for fair rotation in streaming cycle
    private final CopyOnWriteArrayList<String> orderedIds = new CopyOnWriteArrayList<>();

    public void subscribe(String sessionId, Transport transport,
                          Map<String, Long> knownVersions, Frustum3D frustum, int level) {
        var state = new ClientState(sessionId, transport,
            new ConcurrentHashMap<>(knownVersions), frustum, level);
        clients.put(sessionId, state);
        orderedIds.addIfAbsent(sessionId);
    }

    public void unsubscribe(String sessionId) {
        clients.remove(sessionId);
        orderedIds.remove(sessionId);
    }

    public void updateViewport(String sessionId, Frustum3D frustum, int level) {
        var old = clients.get(sessionId);
        if (old == null) return;
        clients.put(sessionId, new ClientState(
            old.sessionId(), old.transport(), old.knownVersions(), frustum, level));
    }

    /** Push a RegionUpdate (JSON control msg) to a specific client. */
    public void push(String sessionId, SpatialKey<?> key, long version) {
        var state = clients.get(sessionId);
        if (state == null) return;
        state.transport().send(new ServerMessage.RegionUpdate(key, version));
        state.knownVersions().put(keyString(key), version);
    }

    /** Push a binary frame to a specific client. */
    public void pushBinary(String sessionId, byte[] frame) {
        var state = clients.get(sessionId);
        if (state != null) state.transport().sendBinary(frame);
    }

    /** Push a RegionUpdate to ALL subscribers (used by streaming cycle). */
    public void broadcast(SpatialKey<?> key, long version) {
        clients.keySet().forEach(id -> push(id, key, version));
    }

    public long knownVersion(String sessionId, SpatialKey<?> key) {
        var state = clients.get(sessionId);
        if (state == null) return 0L;
        return state.knownVersions().getOrDefault(keyString(key), 0L);
    }

    public int activeClientCount() { return clients.size(); }

    /** Snapshot of session IDs in insertion order for fair rotation. */
    public List<String> orderedSessionIds() { return List.copyOf(orderedIds); }

    public ClientState get(String sessionId) { return clients.get(sessionId); }

    /** Encode a SpatialKey<?> to a stable string map key. */
    public static String keyString(SpatialKey<?> key) {
        var b64 = java.util.Base64.getEncoder();
        return switch (key) {
            case com.hellblazer.luciferase.lucien.octree.MortonKey mk ->
                "oct:" + mk.getLevel() + ":" + b64.encodeToString(
                    java.nio.ByteBuffer.allocate(8).putLong(mk.getMortonCode()).array());
            case com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> tk ->
                "tet:" + tk.getLevel() + ":" + b64.encodeToString(
                    java.nio.ByteBuffer.allocate(8).putLong(tk.getLowBits()).array());
            default -> throw new IllegalArgumentException("Unknown key type");
        };
    }
}
```

**Step 3: Run tests**
```
mvn test -pl simulation -Dtest=SubscriptionManagerTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: all 4 PASS.

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/SubscriptionManager.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/SubscriptionManagerTest.java
git commit -m "feat(viz-render): add SubscriptionManager with push delivery and fair-rotation support

References: Luciferase-uxbq"
```

---

## Task 9.1: StreamingSession — Phase C (Snapshot) Handler

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingSession.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingSessionPhaseCTest.java`

**Step 1: Write the Phase C test**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSessionPhaseCTest {

    @Test
    void helloAckSentOnHello() throws InterruptedException {
        var fixture = makeFixture();
        fixture.client.sendToServer(new ClientMessage.Hello("1.0"));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);
        var ack = fixture.client.nextServerMessage(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class, ack);
    }

    @Test
    void snapshotManifestSentForOccupiedKeys() throws InterruptedException {
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 100, 100, 100)
            .withEntity(2L, 5000, 5000, 5000)
            .build();
        var fixture = makeFixtureWithWorld(facade);

        fixture.client.sendToServer(new ClientMessage.Hello("1.0"));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);
        fixture.client.nextServerMessage(200, TimeUnit.MILLISECONDS); // discard HelloAck

        fixture.client.sendToServer(new ClientMessage.SnapshotRequest("req-1", 4));
        fixture.session.processNext(200, TimeUnit.MILLISECONDS);

        var manifest = fixture.client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.SnapshotManifest.class, manifest);
        var m = (ServerMessage.SnapshotManifest) manifest;
        assertEquals("req-1", m.requestId());
        assertFalse(m.regions().isEmpty(), "manifest must contain occupied regions");
    }

    // --- helpers ---
    record Fixture(StreamingSession session, InProcessTransport.ClientView client) {}

    static Fixture makeFixture() {
        var facade = WorldFixture.octree(4, 8).build();
        return makeFixtureWithWorld(facade);
    }

    static Fixture makeFixtureWithWorld(SpatialIndexFacade facade) {
        var transport = new InProcessTransport();
        var tracker = new DirtyTracker();
        var cache = new StreamingCache();
        var builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, tracker, builder,
            (k, v, d) -> cache.put(k, v, d));
        var subscriptions = new SubscriptionManager();
        var session = new StreamingSession(
            "sess-test", transport.serverTransport(),
            facade, tracker, cache, buildQueue, subscriptions);
        return new Fixture(session, transport.clientView());
    }
}
```

**Step 2: Create StreamingSession.java** (Phase C only — Phase B added in Task 9.2)

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles one client WebSocket session.
 * Reads ClientMessages from transport, dispatches to Phase C/B handlers.
 */
public final class StreamingSession {

    private final String sessionId;
    private final Transport transport;
    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final StreamingCache cache;
    private final BuildQueue buildQueue;
    private final SubscriptionManager subscriptions;

    private static final AtomicLong TOKEN_COUNTER = new AtomicLong(0);

    public StreamingSession(String sessionId, Transport transport,
                            SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                            StreamingCache cache, BuildQueue buildQueue,
                            SubscriptionManager subscriptions) {
        this.sessionId    = sessionId;
        this.transport    = transport;
        this.facade       = facade;
        this.dirtyTracker = dirtyTracker;
        this.cache        = cache;
        this.buildQueue   = buildQueue;
        this.subscriptions = subscriptions;
    }

    /**
     * Read and dispatch one ClientMessage. Blocks up to timeout.
     * Returns false if timed out with no message.
     */
    public boolean processNext(long timeout, TimeUnit unit) throws InterruptedException {
        var msg = transport.nextClientMessage(timeout, unit);
        if (msg == null) return false;
        dispatch(msg);
        return true;
    }

    private void dispatch(ClientMessage msg) {
        switch (msg) {
            case ClientMessage.Hello h           -> handleHello(h);
            case ClientMessage.SnapshotRequest r -> handleSnapshotRequest(r);
            case ClientMessage.Subscribe s       -> handleSubscribe(s);
            case ClientMessage.ViewportUpdate v  -> handleViewportUpdate(v);
            case ClientMessage.Unsubscribe u     -> handleUnsubscribe();
        }
    }

    // ─── Phase C ────────────────────────────────────────────────────────────

    private void handleHello(ClientMessage.Hello h) {
        transport.send(new ServerMessage.HelloAck(sessionId));
    }

    private void handleSnapshotRequest(ClientMessage.SnapshotRequest req) {
        long token = TOKEN_COUNTER.incrementAndGet();
        var occupied = facade.allOccupiedKeys(req.level());

        // Capture versions NOW — this is the freeze point
        var entries = occupied.stream()
            .map(k -> new ServerMessage.SnapshotManifest.RegionEntry(
                k, dirtyTracker.version(k), estimateSize(k)))
            .toList();

        transport.send(new ServerMessage.SnapshotManifest(req.requestId(), token, entries));

        // Send binary frames for each key that has cached data
        for (var entry : entries) {
            var cached = cache.get(entry.key());
            if (cached != null) {
                var frame = com.hellblazer.luciferase.simulation.viz.render.protocol
                    .BinaryFrameCodec.encodeWithKey(
                        entry.key(), regionType(entry.key()), cached.version(), cached.data());
                transport.sendBinary(frame.array());
            } else {
                // Trigger a build — frame delivered when complete
                buildQueue.submit(entry.key(), RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            }
        }
    }

    // ─── Phase B ────────────────────────────────────────────────────────────

    private void handleSubscribe(ClientMessage.Subscribe s) {
        subscriptions.subscribe(sessionId, transport, s.knownVersions(),
            null /* frustum from next viewport update */, 0);

        // onSubscribe catchup: push any keys that changed since snapshot
        s.knownVersions().forEach((keyStr, knownVersion) -> {
            // keyStr is "oct:5:..." or "tet:8:..." — reverse lookup not straightforward
            // The subscription table tracks this by key string; actual push happens
            // in the streaming cycle when keysVisible is computed.
            // Nothing to do here synchronously — streaming cycle handles it.
        });
        // TODO: Full catchup loop requires parsing keyStr back to SpatialKey<?> —
        //       implement in streaming cycle for now (acceptable for Phase B MVP).
    }

    private void handleViewportUpdate(ClientMessage.ViewportUpdate v) {
        subscriptions.updateViewport(sessionId, v.frustum(), v.level());
    }

    private void handleUnsubscribe() {
        subscriptions.unsubscribe(sessionId);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static long estimateSize(SpatialKey<?> key) { return 0L; /* filled by build */ }

    private static RegionBuilder.BuildType regionType(SpatialKey<?> key) {
        return key instanceof com.hellblazer.luciferase.lucien.tetree.TetreeKey ?
               RegionBuilder.BuildType.ESVT : RegionBuilder.BuildType.ESVO;
    }
}
```

**Step 3: Run tests**
```
mvn test -pl simulation -Dtest=StreamingSessionPhaseCTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: both PASS.

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingSession.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingSessionPhaseCTest.java
git commit -m "feat(viz-render): add StreamingSession with Phase C snapshot handler

References: Luciferase-uxbq"
```

---

## Task 9.2: Streaming Cycle (Phase B Push Loop)

**Files:**
- Create: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCycle.java`
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCycleTest.java`

**Step 1: Write the test**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingCycleTest {

    @Test
    void dirtyKeyDeliveredToSubscriber() throws Exception {
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 100, 100, 100).build();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        var tracker = new DirtyTracker();
        var cache = new StreamingCache();
        var builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, tracker, builder,
            (k, v, d) -> {
                cache.put(k, v, d);
            });
        var subscriptions = new SubscriptionManager();

        // Subscribe with frustum that contains the entity
        subscriptions.subscribe("s1", transport.serverTransport(),
            java.util.Map.of(), TestFrustums.fullScene(), 4);

        // Make a key dirty
        var keys = facade.keysContaining(new Point3f(100, 100, 100), 4, 4);
        var key = keys.iterator().next();
        tracker.bump(key);

        // Wait for build, then run one streaming cycle
        buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
        buildQueue.awaitBuilds().get(5, TimeUnit.SECONDS);

        var cycle = new StreamingCycle(facade, tracker, cache, buildQueue, subscriptions);
        cycle.tick(100_000_000L); // 100ms budget

        var msg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, msg,
            "dirty key in frustum must be pushed after tick");
    }
}
```

**Step 2: Create StreamingCycle.java**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.simulation.viz.render.protocol.BinaryFrameCodec;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ServerMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One tick of the Phase B streaming loop.
 *
 * <p>Fair rotation: maintains cursor across ticks so no client is starved.
 * Time-bounded: stops when deadlineNanos elapsed since tick start.
 */
public final class StreamingCycle {

    private final SpatialIndexFacade facade;
    private final DirtyTracker dirtyTracker;
    private final StreamingCache cache;
    private final BuildQueue buildQueue;
    private final SubscriptionManager subscriptions;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public StreamingCycle(SpatialIndexFacade facade, DirtyTracker dirtyTracker,
                          StreamingCache cache, BuildQueue buildQueue,
                          SubscriptionManager subscriptions) {
        this.facade        = facade;
        this.dirtyTracker  = dirtyTracker;
        this.cache         = cache;
        this.buildQueue    = buildQueue;
        this.subscriptions = subscriptions;
    }

    /** Run one streaming cycle with a nanosecond deadline budget. */
    public void tick(long deadlineNanos) {
        long start = System.nanoTime();
        List<String> sessionIds = subscriptions.orderedSessionIds();
        if (sessionIds.isEmpty()) return;

        int n = sessionIds.size();
        int start_idx = cursor.get() % n;

        for (int i = 0; i < n; i++) {
            if (System.nanoTime() - start > deadlineNanos) break;
            int idx = (start_idx + i) % n;
            cursor.set(idx);
            processClient(sessionIds.get(idx), start, deadlineNanos);
        }
    }

    private void processClient(String sessionId, long cycleStart, long deadlineNanos) {
        var state = subscriptions.get(sessionId);
        if (state == null || state.frustum() == null) return;

        var visible = facade.keysVisible(state.frustum(), state.level());
        for (var key : visible) {
            if (System.nanoTime() - cycleStart > deadlineNanos) break;
            long knownVersion = subscriptions.knownVersion(sessionId, key);
            long currentVersion = dirtyTracker.version(key);
            if (currentVersion <= knownVersion) continue;  // not dirty for this client

            var cached = cache.get(key);
            if (cached != null && cached.version() == currentVersion) {
                // Content is fresh — push JSON notification + binary frame
                subscriptions.push(sessionId, key, currentVersion);
                var frame = BinaryFrameCodec.encodeWithKey(
                    key, regionType(key), currentVersion, cached.data());
                subscriptions.pushBinary(sessionId, frame.array());
            } else if (!buildQueue.isInFlight(key)) {
                buildQueue.submit(key, RegionBuilder.KeyedBuildRequest.Priority.VISIBLE);
            }
        }
    }

    private static RegionBuilder.BuildType regionType(SpatialKey<?> key) {
        return key instanceof com.hellblazer.luciferase.lucien.tetree.TetreeKey ?
               RegionBuilder.BuildType.ESVT : RegionBuilder.BuildType.ESVO;
    }
}
```

**Note:** `BuildQueue.isInFlight(key)` is a new method — add it:
```java
public boolean isInFlight(SpatialKey<?> key) { return inFlight.containsKey(key); }
```

**Step 3: Run**
```
mvn test -pl simulation -Dtest=StreamingCycleTest -Dsurefire.rerunFailingTestsCount=0
```
Expected: PASS.

**Step 4: Commit**
```
git add simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCycle.java
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingCycleTest.java
git commit -m "feat(viz-render): add StreamingCycle with time-budgeted fair-rotation Phase B loop

References: Luciferase-uxbq"
```

---

## Task 10.1: Verify Full Simulation Module Builds and Tests Pass

Before wiring up the WebSocket server, confirm the complete build is green.

**Step 1:**
```
mvn test -pl simulation -Dsurefire.rerunFailingTestsCount=0
```
Expected: all tests PASS, zero compilation errors. If old tests break (e.g. `RegionStreamerTest`, `AdaptiveRegionManagerTest`), do NOT modify the new code — fix the old tests to work with the updated `MortonKey.equals()` or other changes. The old classes are **not** deleted.

**Step 2: Commit any fixes**
```
git commit -m "fix(viz-render): update test assertions for MortonKey level-aware equality"
```

---

## Task 11.1: Client JavaScript — ProtocolClient

**Files:**
- Create: `simulation/src/main/resources/web/protocol-client.js`

(The existing `render-client.js` remains unchanged — `protocol-client.js` is the new implementation.)

**Step 1: Create protocol-client.js**

```javascript
/**
 * viz-render Phase C/B protocol client.
 *
 * Key encoding: { t: 'oct'|'tet', l: level, i: base64Long }
 * keyString: '{t}:{level}:{i}'
 *
 * Auth: pass token as wsUrl query param only temporarily (TODO: upgrade to header when
 * WebSocket API supports custom headers in browsers — use a ticket-exchange pattern).
 * For now, Authorization: Bearer is sent as the FIRST message after connection:
 *   { type: 'AUTH', token: '...' }
 * Server must validate before processing any other message.
 */

export class ProtocolClient extends EventTarget {
    #ws = null;
    #sessionId = null;
    #snapshotToken = null;

    constructor(url, token) {
        super();
        this.url = url;
        this.token = token;
    }

    connect() {
        this.#ws = new WebSocket(this.url);
        this.#ws.binaryType = 'arraybuffer';
        this.#ws.addEventListener('open', () => this.#onOpen());
        this.#ws.addEventListener('message', (e) => this.#onMessage(e));
        this.#ws.addEventListener('close', () => this.dispatchEvent(new Event('disconnect')));
    }

    #onOpen() {
        this.#send({ type: 'HELLO', version: '1.0' });
    }

    #onMessage(event) {
        if (event.data instanceof ArrayBuffer) {
            this.dispatchEvent(Object.assign(new Event('binaryFrame'),
                { frame: event.data }));
            return;
        }
        const msg = JSON.parse(event.data);
        switch (msg.type) {
            case 'HELLO_ACK':
                this.#sessionId = msg.sessionId;
                this.dispatchEvent(Object.assign(new Event('connected'),
                    { sessionId: msg.sessionId }));
                break;
            case 'SNAPSHOT_MANIFEST':
                this.#snapshotToken = msg.snapshotToken;
                this.dispatchEvent(Object.assign(new Event('snapshotManifest'), { msg }));
                break;
            case 'REGION_UPDATE':
                this.dispatchEvent(Object.assign(new Event('regionUpdate'),
                    { key: msg.key, version: BigInt(msg.version) }));
                break;
            case 'REGION_REMOVED':
                this.dispatchEvent(Object.assign(new Event('regionRemoved'), { key: msg.key }));
                break;
            case 'SNAPSHOT_REQUIRED':
                this.dispatchEvent(Object.assign(new Event('snapshotRequired'), { key: msg.key }));
                break;
        }
    }

    requestSnapshot(level) {
        const requestId = crypto.randomUUID();
        this.#send({ type: 'SNAPSHOT_REQUEST', requestId, level });
        return requestId;
    }

    subscribe(knownVersions) {
        if (this.#snapshotToken == null)
            throw new Error('Call requestSnapshot first');
        this.#send({
            type: 'SUBSCRIBE',
            snapshotToken: this.#snapshotToken,
            knownVersions
        });
    }

    updateViewport(frustumData, cameraPosData, level) {
        this.#send({ type: 'VIEWPORT_UPDATE', frustum: frustumData, cameraPos: cameraPosData, level });
    }

    unsubscribe() { this.#send({ type: 'UNSUBSCRIBE' }); }

    #send(msg) { this.#ws?.send(JSON.stringify(msg)); }

    /** Encode a key object to a stable string map key. */
    static keyString(key) { return `${key.t}:${key.l}:${key.i}`; }

    /** Decode a base64 string to BigInt (for version comparison). */
    static base64ToBigInt(b64) {
        const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
        const view = new DataView(bytes.buffer);
        return view.getBigInt64(0, false);  // big-endian
    }
}
```

**Step 2: Update scene-manager.js**

Replace the existing `SceneManager` with a version-guarded implementation. Key changes:
- `onRegionUpdate(key, version, data)`: guard with `if (BigInt(version) <= knownVersions.get(ks) ?? 0n) return;`
- Add `remove(key)` method
- Use `ProtocolClient.keyString(key)` for map keys

**Step 3: Update voxel-renderer.js**

Add `onCameraChange` event (throttled 100ms):
```javascript
// In the camera/render loop, after computing new frustum:
const now = performance.now();
if (now - this._lastCameraEvent > 100) {
    this._lastCameraEvent = now;
    this.dispatchEvent(Object.assign(new Event('cameraChange'), {
        frustum: this.getCurrentFrustum(),
        cameraPos: this.getCameraPosition(),
        level: this.computeLODLevel()
    }));
}

// LOD level from camera distance (internal coordinate space):
computeLODLevel() {
    const dist = this.getCameraPosition().distanceTo(this.sceneCenter);
    return Math.max(this.minLevel, Math.min(this.maxLevel, Math.floor(Math.log2(dist))));
}
```

**Step 4: Verify with client-test.html**

Open `simulation/src/main/resources/web/client-test.html` in a browser with the server running. Manually verify the Phase C flow: connect → HELLO_ACK → snapshot request → manifest received → binary frames received.

**Step 5: Commit**
```
git add simulation/src/main/resources/web/protocol-client.js
git add simulation/src/main/resources/web/scene-manager.js
git add simulation/src/main/resources/web/voxel-renderer.js
git commit -m "feat(viz-render): add ProtocolClient JS, update SceneManager and VoxelRenderer

References: Luciferase-uxbq"
```

---

## Task 12.1: End-to-End Integration Test

**Files:**
- Create: `simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingE2ETest.java`

**Step 1: Write the test**

```java
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.viz.render.protocol.*;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: entity inserted → key dirtied → snapshot requested →
 * manifest received → binary frame received → subscribe → region update pushed.
 */
class StreamingE2ETest {

    @Test
    void phaseCAndBFullFlow() throws Exception {
        // Setup world
        var facade = WorldFixture.octree(4, 8)
            .withEntity(1L, 500, 500, 500)
            .build();
        var transport = new InProcessTransport();
        var client = transport.clientView();
        var dirtyTracker = new DirtyTracker();

        // Dirty the key for our entity
        var keys = facade.keysContaining(new Point3f(500, 500, 500), 4, 4);
        var key = keys.iterator().next();
        dirtyTracker.bump(key);

        var cache = new StreamingCache();
        var builder = new RegionBuilder(1, 10, 8, 64);
        var buildQueue = new BuildQueue(facade, dirtyTracker, builder,
            (k, v, d) -> cache.put(k, v, d));
        var subscriptions = new SubscriptionManager();
        var session = new StreamingSession(
            "e2e-sess", transport.serverTransport(),
            facade, dirtyTracker, cache, buildQueue, subscriptions);

        // ── Phase C ──
        client.sendToServer(new ClientMessage.Hello("1.0"));
        session.processNext(200, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.HelloAck.class,
            client.nextServerMessage(200, TimeUnit.MILLISECONDS));

        client.sendToServer(new ClientMessage.SnapshotRequest("req-e2e", 4));
        session.processNext(500, TimeUnit.MILLISECONDS);
        var manifestMsg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.SnapshotManifest.class, manifestMsg);

        // Wait for build + binary frame
        buildQueue.awaitBuilds().get(10, TimeUnit.SECONDS);
        var frame = client.nextBinaryFrame(500, TimeUnit.MILLISECONDS);
        assertNotNull(frame, "binary frame must be delivered after snapshot");

        // ── Phase B ──
        var manifest = (ServerMessage.SnapshotManifest) manifestMsg;
        var knownVersions = new java.util.HashMap<String, Long>();
        for (var entry : manifest.regions()) {
            knownVersions.put(SubscriptionManager.keyString(entry.key()), entry.snapshotVersion());
        }
        client.sendToServer(new ClientMessage.Subscribe(manifest.snapshotToken(), knownVersions));
        session.processNext(200, TimeUnit.MILLISECONDS);

        // Move entity to make cell dirty
        facade.move(1L, new Point3f(501, 501, 501));
        dirtyTracker.bumpAll(facade.keysContaining(new Point3f(501, 501, 501), 4, 4));

        // Run streaming cycle
        buildQueue.awaitBuilds().get(5, TimeUnit.SECONDS);
        var cycle = new StreamingCycle(facade, dirtyTracker, cache, buildQueue, subscriptions);
        subscriptions.updateViewport("e2e-sess", TestFrustums.fullScene(), 4);
        cycle.tick(200_000_000L);

        var updateMsg = client.nextServerMessage(500, TimeUnit.MILLISECONDS);
        assertInstanceOf(ServerMessage.RegionUpdate.class, updateMsg,
            "moved entity should trigger REGION_UPDATE via streaming cycle");
    }
}
```

**Step 2: Run**
```
mvn test -pl simulation -Dtest=StreamingE2ETest -Dsurefire.rerunFailingTestsCount=0
```
Expected: PASS.

**Step 3: Run full simulation test suite**
```
mvn test -pl simulation -Dsurefire.rerunFailingTestsCount=0
```
Expected: all pass.

**Step 4: Final commit + push**
```
git add simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/StreamingE2ETest.java
git commit -m "test(viz-render): add end-to-end integration test for Phase C + Phase B flow

References: Luciferase-uxbq"
git push
```

---

## Implementation Complete

The new architecture is in place alongside the old code. When the old classes (RegionStreamer, AdaptiveRegionManager, ViewportTracker) are no longer needed, they can be deleted in a separate cleanup PR. The new pipeline is:

```
Entity event → SpatialIndexFacade → DirtyTracker → BuildQueue → StreamingCache → SubscriptionManager → Transport
                                                                                 ↑
                                                                         StreamingCycle (tick loop)
```
