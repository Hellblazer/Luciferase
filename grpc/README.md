# gRPC Module

**Last Updated**: 2026-01-04
**Status**: Current

Protocol Buffer definitions for ghost layer synchronization in distributed spatial indices

## Overview

The gRPC module provides Protocol Buffer message definitions and gRPC services for synchronizing ghost layer data between distributed Forest instances. Ghost layers represent boundary entities from neighboring spatial partitions that are cached locally for efficient cross-partition queries.

## Features

- **Ghost Element Serialization**: Efficient Protocol Buffer messages for spatial entities
- **Batch Operations**: Support for bulk ghost synchronization
- **Real-Time Streaming**: Bidirectional streaming for live ghost updates
- **Multi-Key Support**: Handles both Morton (Octree) and Tetree keys
- **Timestamped Updates**: Incremental synchronization support

## Architecture

```text
grpc/
└── src/main/proto/lucien/
    └── ghost.proto              # Ghost layer Protocol Buffer definitions
```

## Protocol Buffer Definitions

### Message Types

**Core Types:**

- `Point3f` - 3D position (x, y, z)
- `EntityBounds` - AABB bounding box (min, max)
- `SpatialKey` - Polymorphic key (MortonKey | TetreeKey)

**Ghost Messages:**

- `GhostElement` - Complete ghost entity with position, bounds, content, ownership
- `GhostBatch` - Collection of ghost elements for bulk transfer
- `GhostRequest` - Request for ghost elements from boundary keys
- `GhostUpdate` - Insert/update/remove notification
- `GhostRemoval` - Entity removal notification
- `GhostAck` - Acknowledgment with success/error status

**Synchronization:**

- `SyncRequest` - Request ghosts changed since timestamp
- `SyncResponse` - Batch response with sync time and element count
- `StatsRequest` / `StatsResponse` - Ghost layer statistics

### gRPC Service

```protobuf
service GhostExchange {
    rpc RequestGhosts(GhostRequest) returns (GhostBatch);
    rpc StreamGhostUpdates(stream GhostUpdate) returns (stream GhostAck);
    rpc SyncGhosts(SyncRequest) returns (SyncResponse);
    rpc GetGhostStats(StatsRequest) returns (StatsResponse);
}
```

## Usage Example

### Generated Java Classes

Protocol Buffers generates Java classes in package `com.hellblazer.luciferase.lucien.forest.ghost.proto`:

- `GhostElement`
- `GhostBatch`
- `GhostExchangeGrpc` (service stub)
- etc.

### Client Example

```java
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;
import io.grpc.ManagedChannelBuilder;

// Create gRPC channel
var channel = ManagedChannelBuilder
    .forAddress("remote-host", 50051)
    .usePlaintext()
    .build();

// Create blocking stub
var ghostStub = GhostExchangeGrpc.newBlockingStub(channel);

// Request ghosts for boundary keys
var request = GhostRequest.newBuilder()
    .setRequesterRank(0)
    .setRequesterTreeId(12345)
    .setGhostType(GhostType.FACES)
    .addBoundaryKeys(SpatialKey.newBuilder()
        .setMorton(MortonKey.newBuilder()
            .setMortonCode(0x123456789ABCDEFL)
            .build())
        .build())
    .build();

GhostBatch batch = ghostStub.requestGhosts(request);
for (GhostElement ghost : batch.getElementsList()) {
    System.out.println("Ghost entity: " + ghost.getEntityId());
}
```

### Server Example

```java
import io.grpc.stub.StreamObserver;

public class GhostExchangeService
    extends GhostExchangeGrpc.GhostExchangeImplBase {

    private final Forest forest;

    @Override
    public void requestGhosts(GhostRequest request,
                            StreamObserver<GhostBatch> responseObserver) {
        // Collect ghosts from forest ghost layer
        var ghosts = forest.collectGhosts(
            request.getBoundaryKeysList(),
            request.getGhostType()
        );

        var batch = GhostBatch.newBuilder()
            .addAllElements(ghosts)
            .setSourceRank(forest.getRank())
            .setSourceTreeId(forest.getTreeId())
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
            .build();

        responseObserver.onNext(batch);
        responseObserver.onCompleted();
    }
}
```

## Integration with Lucien Forest

The gRPC ghost messages are used by Lucien's Forest module for distributed spatial index coordination:

- **GhostLayer**: Manages boundary entities from neighboring processes
- **Forest**: Uses GhostExchange service to synchronize ghost elements
- **RemoteGhostProvider**: gRPC client for fetching remote ghosts
- **GhostLayerSynchronizer**: Periodic sync of ghost layer state

See [lucien/doc/GHOST_API.md](../lucien/doc/GHOST_API.md) for details.

## Build and Code Generation

```bash
# Generate Protocol Buffer Java code
mvn protobuf:compile

# Compile module
mvn compile -pl grpc

# Run tests
mvn test -pl grpc
```

Generated files are placed in `target/generated-sources/protobuf/`.

## Dependencies

- **gRPC-Java**: Core gRPC runtime (`io.grpc`)
- **Protocol Buffers**: Message serialization (`com.google.protobuf`)
- **grpc-protobuf**: gRPC Protocol Buffer integration
- **grpc-stub**: Service stub generation

## Performance Characteristics

- **Message Size**: Ghost elements are 100-500 bytes depending on content size
- **Serialization**: ~1-2 μs per ghost element
- **Batch Transfer**: Efficient for 100-10,000 elements per batch
- **Streaming**: Bi-directional for real-time updates with backpressure

## Ghost Type Levels

The `GhostType` enum defines the depth of ghost layer:

- `NONE` (0): No ghost layer
- `FACES` (1): Face neighbors only (most common)
- `EDGES` (2): Face and edge neighbors
- `VERTICES` (3): Face, edge, and vertex neighbors (complete)

Higher levels provide more complete ghost coverage but increase memory and network costs.

## License

AGPL-3.0 - See [LICENSE](../LICENSE) for details
