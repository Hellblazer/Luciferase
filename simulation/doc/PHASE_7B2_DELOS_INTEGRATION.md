# Phase 7B.2: Delos Socket Transport Integration

**Status**: Complete
**Date**: 2026-01-09
**Bead**: Luciferase-xkbj

## Overview

Phase 7B.2 implements network-based ghost entity transmission between simulation bubbles using the Delos SocketTransport framework. This enables distributed multi-bubble simulations with cross-bubble entity synchronization.

## Components Implemented

### 1. DelosSocketTransport

**File**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/ghost/DelosSocketTransport.java`

Network-based implementation of the GhostChannel interface:
- Implements existing `GhostChannel<StringEntityID, EntityData>` interface
- Uses `EntityUpdateEvent` + `EventSerializer` for wire protocol
- Supports batched transmission (queue locally, flush at bucket boundaries)
- Thread-safe for concurrent ghost queuing and reception
- Phase 7B.2: Simulated network for testing (in-memory delivery)
- Phase 7B.3: Will integrate actual Delos SocketTransport

**Key Methods**:
```java
// Queue ghost for transmission
void queueGhost(UUID targetBubbleId, SimulationGhostEntity<StringEntityID, EntityData> ghost);

// Send batch immediately (called internally by flush)
void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts);

// Flush pending ghosts at bucket boundary
void flush(long bucket);

// Register handler for incoming ghosts
void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> handler);
```

### 2. EnhancedBubble Integration

**File**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/bubble/EnhancedBubble.java`

Updated to accept optional GhostChannel parameter:

**New Constructor**:
```java
public EnhancedBubble(
    UUID id,
    byte spatialLevel,
    long targetFrameMs,
    RealTimeController realTimeController,
    GhostChannel<StringEntityID, EntityData> ghostChannel
)
```

**Backward Compatibility**:
- Existing constructors unchanged
- Default: `new InMemoryGhostChannel<>()` (testing/single-bubble)
- Optional: `new DelosSocketTransport(bubbleId)` (distributed)

**Accessor**:
```java
GhostChannel<StringEntityID, EntityData> getGhostChannel();
```

## Usage Examples

### Single Bubble (Default)
```java
// Uses InMemoryGhostChannel by default
var bubble = new EnhancedBubble(id, spatialLevel, targetFrameMs);
```

### Distributed Multi-Bubble
```java
// Bubble 1 with Delos transport
var transport1 = new DelosSocketTransport(bubbleId1);
var bubble1 = new EnhancedBubble(
    bubbleId1,
    spatialLevel,
    targetFrameMs,
    controller1,
    transport1
);

// Bubble 2 with Delos transport
var transport2 = new DelosSocketTransport(bubbleId2);
var bubble2 = new EnhancedBubble(
    bubbleId2,
    spatialLevel,
    targetFrameMs,
    controller2,
    transport2
);

// Connect transports (Phase 7B.2: simulated network)
transport1.connectTo(transport2);
transport2.connectTo(transport1);

// Register handler for incoming ghosts
transport1.onReceive((sourceBubbleId, ghosts) -> {
    for (var ghost : ghosts) {
        // Process ghost in bubble1
        bubbleGhostManager.handleGhost(ghost);
    }
});

// Queue ghost for transmission
var ghost = createGhostEntity(...);
transport1.queueGhost(bubbleId2, ghost);

// Flush at bucket boundary (every 100ms)
transport1.flush(currentBucket);
```

## Testing

### DelosSocketTransportTest
**File**: `simulation/src/test/java/com/hellblazer/luciferase/simulation/ghost/DelosSocketTransportTest.java`

**Coverage**: 10 comprehensive tests
1. ✅ `testCreation()` - Transport initialization
2. ✅ `testQueueGhost()` - Ghost queuing
3. ✅ `testSerializationOnQueue()` - EntityUpdateEvent serialization
4. ✅ `testLocalTransmission()` - Event transmission (simulated network)
5. ✅ `testRemoteReception()` - Event reception and deserialization
6. ✅ `testRoundTrip()` - Full serialize → send → deserialize cycle
7. ✅ `testBatchFlushing()` - Multiple ghosts batched correctly
8. ✅ `testLatencyBounds()` - Transmission latency < 100ms
9. ✅ `testErrorHandling()` - Connection failures handled gracefully
10. ✅ `testLifecycle()` - open() / close() work correctly

**Results**: All 10 tests passing (100% success rate)

## Serialization Details

### Wire Format
- Binary serialization via `EntityUpdateEvent` + `EventSerializer`
- Format version: `0x01` (Phase 7B.1)
- Big-endian network byte order
- Fields transmitted:
  - Entity ID (UTF-8 string, length-prefixed)
  - Position (3 × float32)
  - Velocity (3 × float32) - **Phase 7B.2: Zero placeholder**
  - Timestamp (int64)
  - Lamport Clock (int64)

### Type Conversions
```java
// Send side: SimulationGhostEntity → EntityUpdateEvent → bytes
var event = new EntityUpdateEvent(
    ghost.entityId(),
    ghost.position(),
    new Point3f(0f, 0f, 0f), // TODO Phase 7B.3: Extract velocity
    ghost.timestamp(),
    ghost.bucket() // Lamport clock
);
var bytes = serializer.toBytes(event);

// Receive side: bytes → EntityUpdateEvent → SimulationGhostEntity
var event = serializer.fromBytes(bytes);
var ghost = new SimulationGhostEntity<>(
    new GhostZoneManager.GhostEntity<>(
        event.entityId(),
        new EntityData<>(event.entityId(), event.position(), (byte) 10, null),
        event.position(),
        bounds,
        "remote-" + sourceBubbleId
    ),
    sourceBubbleId,
    event.lamportClock(), // bucket
    0L, // epoch (Phase 7B.3 will transmit)
    1L  // version
);
```

## Phase 7B.2 Limitations

### Velocity Not Tracked
- EntityData doesn't yet have velocity field
- Using `Point3f(0f, 0f, 0f)` as placeholder
- **Phase 7B.3**: Add velocity tracking for dead reckoning

### Epoch Not Transmitted
- EntityUpdateEvent doesn't include epoch/version
- Hardcoded to `0L` on receiver side
- **Phase 7B.3**: Add full EntityAuthority tracking

### Simulated Network
- `connectTo()` method for in-memory delivery
- Tests validate serialization mechanics
- **Phase 7B.3**: Replace with actual Delos SocketTransport

## Next Steps (Phase 7B.3)

1. **Velocity Tracking**:
   - Add velocity field to EntityData
   - Update entity movement to track velocity
   - Use velocity for dead reckoning on receiver

2. **Authority Tracking**:
   - Transmit epoch/version in EntityUpdateEvent
   - Implement stale update detection
   - Handle authority conflicts

3. **Actual Delos Integration**:
   - Replace simulated network with Delos SocketTransport
   - Configure UDP/TCP transport
   - Handle connection lifecycle (open/close/reconnect)

4. **Dead Reckoning**:
   - DistributedEntityTracker integration
   - Extrapolate position between updates
   - Reduce network traffic

## Success Criteria ✅

- [x] DelosSocketTransport implements GhostChannel interface
- [x] Events transmitted and received correctly (round-trip validation)
- [x] All 10 DelosSocketTransportTest tests passing
- [x] Latency < 100ms on local network
- [x] No breaking changes to EnhancedBubble API
- [x] Code compiles cleanly (no warnings related to new code)
- [x] Custom binary serialization used (EventSerializer from Phase 7B.1)

## Files Modified

### New Files
- `simulation/src/main/java/com/hellblazer/luciferase/simulation/ghost/DelosSocketTransport.java`
- `simulation/src/test/java/com/hellblazer/luciferase/simulation/ghost/DelosSocketTransportTest.java`
- `simulation/doc/PHASE_7B2_DELOS_INTEGRATION.md`

### Modified Files
- `simulation/src/main/java/com/hellblazer/luciferase/simulation/bubble/EnhancedBubble.java`
  - Added GhostChannel field
  - Added constructor with optional GhostChannel parameter
  - Added getGhostChannel() accessor
  - Backward compatible (no breaking changes)

## References
- **Phase 7B.1**: EntityUpdateEvent + EventSerializer (custom binary format)
- **Phase 7A**: RealTimeController + EnhancedBubble integration patterns
- **Existing Infrastructure**:
  - `GhostChannel` interface
  - `SimulationGhostEntity` (ghost wrapper with simulation metadata)
  - `BubbleGhostManager` (coordinates batch transmission)
  - `InMemoryGhostChannel` (testing implementation)
