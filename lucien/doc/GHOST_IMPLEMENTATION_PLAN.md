# Ghost Layer Implementation Plan

## Overview

This document outlines a detailed implementation plan to achieve parity with t8code's ghost functionality in the lucien spatial index. The plan builds upon the existing `GhostZoneManager` implementation and extends it with element-level topological ghost detection. Communication will use gRPC instead of MPI, and serialization will use Protocol Buffers.

## Phase 1: Neighbor Detection Infrastructure (2-3 weeks)

### 1.1 Neighbor Detection Interface

**Goal**: Create a unified interface for neighbor detection across spatial indices.

**Tasks**:
1. Create `NeighborDetector<Key extends SpatialKey<Key>>` interface
2. Define methods for face, edge, and vertex neighbor detection
3. Add boundary element detection capabilities

**Deliverables**:
```java
public interface NeighborDetector<Key extends SpatialKey<Key>> {
    List<Key> findFaceNeighbors(Key element);
    List<Key> findEdgeNeighbors(Key element);
    List<Key> findVertexNeighbors(Key element);
    boolean isBoundaryElement(Key element, Direction direction);
    List<NeighborInfo<Key>> findNeighborsWithOwners(Key element, GhostType type);
}
```

### 1.2 Octree Neighbor Detection

**Goal**: Implement efficient neighbor detection for Morton-encoded octrees.

**Tasks**:
1. Implement Morton neighbor computation using bit manipulation
2. Add support for different tree levels (coarse/fine neighbors)
3. Optimize using lookup tables for common cases

**Key Algorithms**:
- Direct Morton code manipulation for face neighbors
- Edge neighbor computation using 2D projections
- Vertex neighbor enumeration through coordinate offsets

### 1.3 Tetree Neighbor Detection

**Goal**: Implement neighbor detection for tetrahedral trees.

**Tasks**:
1. Implement face neighbor detection using t8code connectivity tables
2. Add edge and vertex neighbor support
3. Handle type-dependent neighbor relationships

**Challenges**:
- Complex connectivity relationships between tetrahedral types
- O(level) cost of TM-index computation
- Need for caching to improve performance

### 1.4 Boundary Detection

**Goal**: Efficiently detect elements at process boundaries.

**Tasks**:
1. Add process ownership tracking to spatial indices
2. Implement boundary element identification
3. Create efficient data structures for boundary tracking

**Deliverables**:
- `BoundaryTracker` class for maintaining boundary elements
- Integration with existing spatial index operations

## Phase 2: Protocol Buffer Serialization (1-2 weeks)

### 2.1 Ghost Protocol Definitions

**Goal**: Define Protocol Buffer messages for ghost communication.

**Tasks**:
1. Create `ghost.proto` with ghost element messages
2. Define spatial key serialization for Morton and Tetree keys
3. Add batch message formats for efficiency

**Proto Definitions**:
```protobuf
message GhostElement {
  SpatialKey key = 1;
  string entity_id = 2;
  bytes content = 3;
  Point3f position = 4;
  int32 owner_rank = 5;
  int64 global_tree_id = 6;
}

message GhostBatch {
  repeated GhostElement elements = 1;
  int32 source_rank = 2;
  int64 timestamp = 3;
}
```

### 2.2 Serialization Integration

**Goal**: Integrate protobuf with existing ghost classes.

**Tasks**:
1. Add protobuf conversion methods to ghost classes
2. Implement efficient batch serialization
3. Create content serialization strategy

**Design Considerations**:
- Generic content type handling via bytes field
- Efficient spatial key encoding
- Batch optimization for network transport

## Phase 3: gRPC Communication Infrastructure (2-3 weeks)

### 3.1 gRPC Service Definition

**Goal**: Define gRPC services for distributed ghost communication.

**Tasks**:
1. Create `ghost_service.proto` with service definitions
2. Implement server and client stubs
3. Add streaming support for bulk transfers

**Service Definition**:
```protobuf
service GhostExchange {
  // Request ghost elements from remote process
  rpc RequestGhosts(GhostRequest) returns (GhostBatch);
  
  // Stream ghost updates
  rpc StreamGhostUpdates(stream GhostUpdate) returns (stream GhostAck);
  
  // Bulk ghost synchronization
  rpc SyncGhosts(SyncRequest) returns (SyncResponse);
}
```

### 3.2 Ghost Exchange Implementation

**Goal**: Implement distributed ghost exchange using gRPC.

**Tasks**:
1. Create `GhostExchangeService` implementation using virtual threads
2. Add synchronous ghost request handling with virtual thread pools
3. Implement streaming updates for real-time sync using blocking I/O

**Key Components**:
- `GhostServiceServer`: Handles incoming ghost requests using virtual threads
- `GhostServiceClient`: Manages outgoing requests with synchronous calls
- `GhostSyncManager`: Coordinates distributed synchronization using virtual thread executors

### 3.3 Virtual Threads Architecture

**Goal**: Leverage Java virtual threads for simplified concurrent gRPC operations.

**Design Principles**:
1. Use synchronous gRPC calls with virtual thread executors
2. Eliminate async callbacks and CompletableFuture complexity
3. Leverage blocking I/O patterns with virtual thread scalability

**Implementation Strategy**:
```java
// Virtual thread executor for ghost operations
var ghostExecutor = Executors.newVirtualThreadPerTaskExecutor();

// Synchronous ghost request handling
public GhostBatch requestGhosts(GhostRequest request) {
    // Direct synchronous call - virtual threads handle scalability
    return ghostServiceClient.requestGhosts(request);
}

// Streaming with virtual threads
public void streamGhostUpdates(Stream<GhostUpdate> updates) {
    updates.forEach(update -> {
        ghostExecutor.submit(() -> {
            // Synchronous processing per update
            processGhostUpdate(update);
        });
    });
}
```

### 3.4 Network Topology Management

**Goal**: Manage connections between distributed processes using virtual threads.

**Tasks**:
1. Implement service discovery mechanism with virtual thread pools
2. Create connection pooling for efficiency
3. Add fault tolerance and reconnection logic using blocking calls

**Features**:
- Dynamic peer discovery using synchronous calls
- Load-balanced connections with virtual thread scaling
- Graceful degradation on failures without callback complexity

## Phase 4: Spatial Index Integration (2-3 weeks)

### 4.1 AbstractSpatialIndex Extensions

**Goal**: Integrate ghost functionality into the core spatial index.

**Tasks**:
1. Add ghost configuration to spatial index creation
2. Implement ghost-aware query methods
3. Add hooks for automatic ghost updates

**API Extensions**:
```java
public abstract class AbstractSpatialIndex<Key, ID, Content> {
    // Ghost configuration
    public void setGhostType(GhostType type);
    public void setGhostCreationAlgorithm(GhostAlgorithm algorithm);
    
    // Ghost operations
    public void createGhostLayer();
    public void updateGhostLayer();
    public GhostLayer<Key, ID, Content> getGhostLayer();
    
    // Ghost-aware queries
    public List<ID> findEntitiesIncludingGhosts(Key key);
    public List<NeighborResult<ID>> findNeighborsIncludingGhosts(Point3f position, float radius);
}
```

### 4.2 Forest Integration

**Goal**: Integrate ghost management with forest operations.

**Tasks**:
1. Add distributed forest support
2. Implement ghost updates on adaptation
3. Create consistent partitioning with ghosts

**Key Features**:
- Automatic ghost recreation after repartitioning
- Consistent ghost state during adaptation
- Efficient incremental updates

### 4.3 Update Triggers

**Goal**: Automatically maintain ghost consistency.

**Tasks**:
1. Add update hooks to modification operations
2. Implement incremental ghost updates
3. Create batch update optimization

**Trigger Points**:
- After bulk insertions
- Following tree adaptation
- On explicit user request
- During repartitioning

## Phase 5: Optimization and Testing (2-3 weeks)

### 5.1 Performance Optimization

**Goal**: Achieve performance comparable to t8code.

**Tasks**:
1. Implement memory pooling for ghost elements
2. Add neighbor caching for frequently accessed elements
3. Optimize serialization with zero-copy techniques
4. Profile and eliminate bottlenecks

**Performance Targets**:
- Ghost creation: < 10% overhead vs local operations
- Data exchange: > 80% network utilization
- Memory usage: < 2x local element storage

### 5.2 Comprehensive Testing

**Goal**: Ensure correctness and robustness.

**Tasks**:
1. Unit tests for all components
2. Integration tests with MPI
3. Scalability tests up to 1000 processes
4. Stress tests with dynamic adaptations

**Test Scenarios**:
- Balanced and unbalanced trees
- Different ghost types
- Various data sizes
- Failure scenarios

### 5.3 Documentation and Examples

**Goal**: Provide clear usage documentation.

**Tasks**:
1. API documentation with examples
2. Performance tuning guide
3. Migration guide from t8code
4. Sample applications

## Implementation Timeline

| Phase | Duration | Dependencies | Critical Path |
|-------|----------|--------------|---------------|
| Phase 1 | 2-3 weeks | None | Yes |
| Phase 2 | 1-2 weeks | None | No |
| Phase 3 | 2-3 weeks | Phase 2 | Yes |
| Phase 4 | 2-3 weeks | Phase 1, 3 | Yes |
| Phase 5 | 2-3 weeks | Phase 4 | No |

**Total Duration**: 8-11 weeks

## Risk Mitigation

### Technical Risks

1. **gRPC Performance Overhead**
   - Mitigation: Use streaming APIs for bulk transfers
   - Fallback: Implement custom binary protocol if needed

2. **Protobuf Generic Type Handling**
   - Mitigation: Use bytes field with type registry
   - Fallback: Require protobuf-serializable content types

3. **Network Latency Impact**
   - Mitigation: Batch operations and use async patterns
   - Fallback: Implement predictive prefetching

### Schedule Risks

1. **Neighbor Detection Complexity**
   - Mitigation: Start with face neighbors only
   - Buffer: Add 1 week to Phase 1

2. **Testing Infrastructure**
   - Mitigation: Develop test harness early
   - Buffer: Overlap with development phases

## Success Criteria

1. **Functional Parity**
   - Support for face neighbor ghosts
   - Automatic ghost creation and updates
   - Data synchronization capability

2. **Performance**
   - Ghost operations within 20% of t8code
   - Scalable to 1000+ processes
   - Memory overhead < 2x

3. **Usability**
   - Clean API matching Java conventions
   - Comprehensive documentation
   - Working examples

## Current Status (Updated July 13, 2025)

### ✅ Completed Phases

- **Phase 1: Neighbor Detection Infrastructure** - COMPLETED
  - Unified NeighborDetector interface implemented
  - Morton-based octree neighbor detection completed
  - Tetrahedral neighbor detection with full face/edge/vertex support
  - Boundary detection for both spatial indices
  
- **Phase 4: Spatial Index Integration** - COMPLETED
  - Ghost configuration integrated into AbstractSpatialIndex
  - Ghost-aware query methods implemented
  - Automatic ghost update hooks added
  - Forest-level ghost management completed

### 🚧 Next Priority Phases

**Immediate Focus: Phase 2 → Phase 3 → Phase 5**

Since the core infrastructure is complete, we should prioritize the communication layers:

1. **Phase 2: Protocol Buffer Serialization** (1-2 weeks)
   - Complete protobuf conversion methods for ghost classes
   - Implement content serialization strategy
   - Add batch serialization optimization

2. **Phase 3: gRPC Communication Infrastructure** (2-3 weeks)
   - Implement GhostExchangeService server
   - Create GhostServiceClient for remote requests
   - Add streaming support for real-time updates

3. **Phase 5: Testing and Optimization** (2-3 weeks)
   - Integration tests with gRPC
   - Performance benchmarking
   - Memory optimization and scalability testing

## Next Steps

1. **Phase 2 Implementation** - Start with protobuf integration
   - Add `toProtobuf()` and `fromProtobuf()` methods to GhostElement, GhostLayer
   - Implement SpatialKey serialization for MortonKey and TetreeKey
   - Create ContentSerializer interface for generic content handling
   - Add batch serialization methods for efficient network transport

2. **Phase 3 Implementation** - Build gRPC communication layer
   - Implement GhostExchangeServiceImpl server using virtual threads
   - Create GhostServiceClient with synchronous calls and connection pooling
   - Add bidirectional streaming using blocking I/O with virtual threads
   - Implement service discovery and fault tolerance using virtual thread executors

3. **Distributed Testing** - Validate with multi-process scenarios
   - Create multi-process test harness using gRPC
   - Test ghost synchronization across distributed spatial indices
   - Validate performance under concurrent load

4. **Performance Optimization** - Ensure scalability targets are met
   - Profile protobuf serialization overhead
   - Optimize network batching strategies
   - Test memory usage with large ghost sets

This plan has been updated to reflect the substantial progress made on core ghost functionality. The remaining work focuses on distributed communication and optimization.