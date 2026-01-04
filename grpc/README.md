# gRPC Module

**Last Updated**: 2026-01-04
**Status**: Current

Protocol Buffer definitions and gRPC services for distributed spatial computing.

## Overview

The gRPC module provides the communication layer for Luciferase's distributed features. It defines Protocol Buffer messages for efficient serialization of spatial data structures and gRPC services for network communication between nodes in a distributed spatial computing system.

## Features

### Protocol Buffer Definitions

- **Spatial Data Types**: Efficient serialization of octrees, tetrees, and spatial indices
- **Entity Messages**: Compact representation of entities with positions and metadata
- **Query Messages**: Spatial queries including range, k-NN, ray intersection, frustum
- **Update Messages**: Entity movement, insertion, deletion, bulk operations
- **Synchronization**: Distributed state synchronization protocols

### gRPC Services

- **SpatialIndexService**: Remote spatial index operations
- **EntityService**: Entity management across nodes
- **QueryService**: Distributed spatial queries
- **SyncService**: State synchronization and replication
- **StreamService**: Real-time entity update streams

## Architecture

### Message Definitions

```text

grpc/
├── src/main/proto/
│   ├── spatial/
│   │   ├── common.proto         # Common spatial data types
│   │   ├── octree.proto         # Octree-specific messages
│   │   ├── tetree.proto         # Tetree-specific messages
│   │   └── index.proto          # Spatial index operations
│   ├── entity/
│   │   ├── entity.proto         # Entity definitions
│   │   ├── movement.proto       # Movement updates
│   │   └── bulk.proto           # Bulk operations
│   ├── query/
│   │   ├── range.proto          # Range queries
│   │   ├── knn.proto            # k-NN queries
│   │   ├── ray.proto            # Ray intersection
│   │   └── frustum.proto        # Frustum culling
│   └── service/
│       ├── spatial_service.proto # Spatial index service
│       ├── entity_service.proto  # Entity management service
│       └── sync_service.proto    # Synchronization service

```text

## Protocol Buffer Schemas

### Entity Message

```protobuf

message Entity {
    string id = 1;
    Position position = 2;
    BoundingBox bounds = 3;
    bytes metadata = 4;
    int64 timestamp = 5;
}

message Position {
    float x = 1;
    float y = 2;
    float z = 3;
}

message BoundingBox {
    Position min = 1;
    Position max = 2;
}

```text

### Spatial Query

```protobuf

message RangeQuery {
    Position center = 1;
    float radius = 2;
    int32 max_results = 3;
}

message KNNQuery {
    Position point = 1;
    int32 k = 2;
    float max_distance = 3;
}

message RayQuery {
    Position origin = 1;
    Vector3 direction = 2;
    float max_distance = 3;
}

```text

### Service Definition

```protobuf

service SpatialIndexService {
    rpc Insert(InsertRequest) returns (InsertResponse);
    rpc Update(UpdateRequest) returns (UpdateResponse);
    rpc Delete(DeleteRequest) returns (DeleteResponse);
    rpc RangeQuery(RangeQueryRequest) returns (stream Entity);
    rpc KNNQuery(KNNQueryRequest) returns (KNNQueryResponse);
    rpc Subscribe(SubscribeRequest) returns (stream EntityUpdate);
}

```text

## Usage Examples

### Client Implementation

```java

// Create gRPC channel
var channel = ManagedChannelBuilder
    .forAddress("localhost", 50051)
    .usePlaintext()
    .build();

// Create stub
var spatialStub = SpatialIndexServiceGrpc.newBlockingStub(channel);

// Insert entity
var entity = Entity.newBuilder()
    .setId("entity-123")
    .setPosition(Position.newBuilder()
        .setX(10.0f)
        .setY(20.0f)
        .setZ(30.0f))
    .build();

var response = spatialStub.insert(
    InsertRequest.newBuilder()
        .setEntity(entity)
        .build());

```text

### Server Implementation

```java

public class SpatialIndexServiceImpl 
    extends SpatialIndexServiceGrpc.SpatialIndexServiceImplBase {
    
    private final SpatialIndex index;
    
    @Override
    public void insert(InsertRequest request, 
                      StreamObserver<InsertResponse> observer) {
        var entity = request.getEntity();
        index.insert(
            entity.getId(),
            new Vector3f(
                entity.getPosition().getX(),
                entity.getPosition().getY(),
                entity.getPosition().getZ()
            )
        );
        
        observer.onNext(InsertResponse.newBuilder()
            .setSuccess(true)
            .build());
        observer.onCompleted();
    }
}

```text

### Streaming Updates

```java

// Subscribe to entity updates
var subscription = spatialStub.subscribe(
    SubscribeRequest.newBuilder()
        .setRegion(BoundingBox.newBuilder()
            .setMin(Position.newBuilder().setX(0).setY(0).setZ(0))
            .setMax(Position.newBuilder().setX(100).setY(100).setZ(100)))
        .build());

// Process stream
subscription.forEachRemaining(update -> {
    switch (update.getTypeCase()) {
        case INSERTED:
            handleInsert(update.getInserted());
            break;
        case MOVED:
            handleMove(update.getMoved());
            break;
        case DELETED:
            handleDelete(update.getDeleted());
            break;
    }
});

```text

## Serialization Performance

### Message Sizes

- Entity: 50-200 bytes (depending on metadata)
- Position: 12 bytes
- BoundingBox: 24 bytes
- Spatial Node: 100-500 bytes
- Query Request: 20-50 bytes

### Throughput

- Serialization: 500K entities/second
- Deserialization: 450K entities/second
- Network transfer: Limited by bandwidth
- Compression: 2-5x reduction with gzip

## Network Optimization

### Connection Pooling

```java

// Configure connection pool
var channel = ManagedChannelBuilder
    .forAddress("localhost", 50051)
    .usePlaintext()
    .maxInboundMessageSize(10 * 1024 * 1024) // 10MB
    .keepAliveTime(30, TimeUnit.SECONDS)
    .keepAliveTimeout(5, TimeUnit.SECONDS)
    .build();

```text

### Load Balancing

```java

// Round-robin load balancing
var channel = ManagedChannelBuilder
    .forTarget("dns:///spatial.service:50051")
    .defaultLoadBalancingPolicy("round_robin")
    .build();

```text

### Compression

```java

// Enable gzip compression
var spatialStub = SpatialIndexServiceGrpc
    .newBlockingStub(channel)
    .withCompression("gzip");

```text

## Security

### TLS Configuration

```java

// Configure TLS
var channel = NettyChannelBuilder
    .forAddress("spatial.service", 443)
    .sslContext(GrpcSslContexts.forClient()
        .trustManager(new File("ca-cert.pem"))
        .build())
    .build();

```text

### Authentication

```java

// Add authentication metadata
var spatialStub = SpatialIndexServiceGrpc
    .newBlockingStub(channel)
    .withCallCredentials(new CallCredentials() {
        @Override
        public void applyRequestMetadata(RequestInfo requestInfo,
                                        Executor appExecutor,
                                        MetadataApplier applier) {
            var metadata = new Metadata();
            metadata.put(AUTH_TOKEN_KEY, "Bearer " + token);
            applier.apply(metadata);
        }
    });

```text

## Integration

### With Von Distribution

```java

// Von uses gRPC for node communication
var vonNode = new VonNode();
vonNode.setGrpcService(new SpatialIndexServiceImpl(spatialIndex));
vonNode.start(50051);

```text

### With Lucien Spatial Indices

```java

// Serialize spatial index nodes
var octreeNode = octree.getNode(key);
var protoNode = OctreeNodeProto.newBuilder()
    .setKey(octreeNode.getKey())
    .setLevel(octreeNode.getLevel())
    .addAllEntities(octreeNode.getEntityIds())
    .build();

```text

## Testing

```bash

# Run gRPC tests

mvn test -pl grpc

# Generate protocol buffer code

mvn protobuf:compile

# Run integration tests

mvn test -pl grpc -Dtest=GrpcIntegrationTest

```text

## Dependencies

- **gRPC-Java**: Core gRPC runtime
- **Protocol Buffers**: Message serialization
- **Netty**: Network transport layer
- **gRPC-Services**: Health checking, reflection

## Performance Metrics

- Message serialization: 2μs per entity
- RPC latency: 1-5ms local, 10-50ms WAN
- Throughput: 50K requests/second per connection
- Stream processing: 100K updates/second

## License

AGPL v3.0 - See LICENSE file for details
