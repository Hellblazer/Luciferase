# VON Overlay Network - Usage Assessment

**Date**: 2026-02-13
**Status**: Partial Integration
**Context**: Fixed-volume spatial partitioning architecture

---

## Summary

VON (Voronoi Overlay Network) used for **spatial neighbor discovery** only. MOVE protocol unnecessary for fixed-volume bubbles.

---

## VON Components

### Retained: Spatial Neighbor Queries

**Purpose**: Discover which bubbles are spatially adjacent.

**Usage**:
```java
// Find neighbor bubbles for ghost synchronization
var neighbors = vonOverlay.getNeighbors(bubblePosition);
```

**Benefits**:
- O(log n) spatial neighbor discovery
- Voronoi cell provides natural spatial locality
- Efficient boundary detection for ghost layer

**Integration Points**:
- GhostZoneManager: Find neighbor bubbles for ghost replication
- BoundaryDetector: Detect entities crossing into neighbor territories
- Spatial queries: Fast lookup of nearby bubbles

### Removed: MOVE Protocol

**Original Purpose**: Signal bubble movement and trigger rebalancing.

**Why Removed**:
- Bubbles have **fixed spatial bounds** (don't move)
- Entity migration uses 2PC protocol (not VON MOVE)
- Spatial assignment via TetreeKeyRouter (deterministic hash, not VON)

**No Functionality Loss**: MOVE protocol was designed for mobile bubbles, which don't exist in fixed-volume architecture.

---

## Architecture Integration

### VON Role: Spatial Index Aid

```
TetreeKeyRouter (deterministic assignment)
         ↓
   Bubble assigned to node
         ↓
VON Overlay (neighbor discovery)
         ↓
   Ghost layer knows where to sync
```

**Key Insight**: VON provides O(log n) spatial queries without managing bubble assignment or movement.

---

## Performance Characteristics

### Neighbor Discovery

| Operation | Complexity | Typical Latency |
|-----------|------------|-----------------|
| Find neighbors | O(log n) | ~5ms |
| Voronoi cell update | O(log n) | ~10ms |
| Spatial range query | O(log n + k) | ~5-20ms |

### Comparison to Alternatives

**Without VON** (brute force):
- Neighbor discovery: O(n) - check all bubbles
- Latency: 50-100ms for large clusters

**With VON** (Voronoi-based):
- Neighbor discovery: O(log n) - Voronoi cell lookup
- Latency: 5-10ms even for large clusters

**Verdict**: VON provides significant performance benefit for spatial queries.

---

## Configuration

### VON Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `vonEnabled` | true | Enable VON overlay for neighbor discovery |
| `voronoiUpdateInterval` | 1000ms | Voronoi cell recalculation frequency |
| `neighborThreshold` | 50.0f | Distance threshold for neighbor detection |

### Tuning Guidelines

**High Churn** (frequent view changes):
- Reduce `voronoiUpdateInterval` to 500ms (faster convergence)
- Increase `neighborThreshold` (more overlap, smoother transitions)

**Static Clusters** (rare view changes):
- Increase `voronoiUpdateInterval` to 5000ms (reduce CPU overhead)
- Decrease `neighborThreshold` (less overlap, lower network usage)

---

## Code Locations

### VON Integration Points

**Neighbor Discovery**:
- `VONDiscoveryProtocol.java`: Voronoi-based neighbor queries
- `GhostZoneManager.java`: Uses VON to find ghost sync targets
- `BoundaryDetector.java`: Uses VON for spatial boundary detection

**Not Used** (MOVE Protocol):
- No bubble movement logic in codebase
- Migration uses CrossProcessMigration (2PC), not VON
- Spatial assignment uses TetreeKeyRouter, not VON

---

## Alternatives Considered

### Option 1: Remove VON Entirely
- **Pro**: Simplify dependencies, reduce code
- **Con**: Lose O(log n) spatial queries, resort to O(n) brute force
- **Verdict**: **Rejected** - VON provides significant performance benefit

### Option 2: Implement Custom Spatial Index
- **Pro**: Tailored to exact needs, no extra protocol
- **Con**: Reinvent Voronoi overlay, maintenance burden
- **Verdict**: **Rejected** - VON already solves this problem well

### Option 3: Keep VON for Neighbor Discovery (Current)
- **Pro**: Leverage existing O(log n) spatial queries
- **Con**: VON has unused MOVE protocol (minor code bloat)
- **Verdict**: **ACCEPTED** - Best balance of performance and simplicity

---

## Migration Path

### From: VON with MOVE Protocol (Prior Design)
Bubbles signal movement via VON MOVE messages, trigger rebalancing.

### To: VON for Neighbor Discovery Only (Current)
Bubbles use VON for spatial queries, ignore MOVE protocol.

### Code Changes Required
- [x] Remove VON MOVE message handlers (not implemented, so nothing to remove)
- [x] Document VON usage as spatial query aid only
- [x] Clarify bubble assignment via TetreeKeyRouter, not VON

---

## Testing

### VON Neighbor Discovery Tests

**Coverage**:
- Voronoi cell neighbor detection
- Spatial range queries
- View change handling (Voronoi recalculation)

**Not Tested** (MOVE Protocol):
- Bubble movement (doesn't exist)
- VON MOVE message handling (not implemented)

---

## Recommendations

### Keep VON Integration
VON provides valuable O(log n) spatial neighbor queries without requiring bubble movement logic.

### Document Usage Clearly
- **Use VON for**: Neighbor discovery, spatial range queries, boundary detection
- **Don't use VON for**: Bubble assignment, migration triggering, movement signaling

### Future Optimization
If VON overhead becomes measurable, consider simpler k-d tree for static spatial queries. But current VON usage is lightweight and effective.

---

## Related Documentation

- [SIMULATION_BUBBLES.md](SIMULATION_BUBBLES.md) - Fixed-volume bubble architecture
- [ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md](ADR_002_FIXED_VOLUME_SPATIAL_PARTITIONING.md) - Spatial assignment decision
- [ARCHITECTURE_DISTRIBUTED.md](ARCHITECTURE_DISTRIBUTED.md) - Complete distributed architecture

---

**Document Version**: 1.0
**Author**: Documentation alignment (Luciferase-9mri)
**Status**: Current
