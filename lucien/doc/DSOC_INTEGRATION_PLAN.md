# Dynamic Scene Occlusion Culling - Integration Plan

## Overview

This document provides a detailed plan for integrating Dynamic Scene Occlusion Culling (DSOC) into the Luciferase spatial index. The plan is structured in four phases, with each phase building upon the previous one while maintaining backward compatibility.

## Status

- **Phase 1**: ✅ COMPLETED (See [DSOC_PHASE1_IMPLEMENTATION_REPORT.md](DSOC_PHASE1_IMPLEMENTATION_REPORT.md))
- **Phase 2**: ✅ COMPLETED (See [DSOC_PHASE2_IMPLEMENTATION_REPORT.md](DSOC_PHASE2_IMPLEMENTATION_REPORT.md))
- **Phase 3**: ⏳ PENDING
- **Phase 4**: ⏳ PENDING

## Phase 1: Temporal Infrastructure (2-3 weeks) ✅ COMPLETED

### 1.1 Velocity Tracking

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/entity/`

Create new classes:

```java
// EntityDynamics.java
public class EntityDynamics {
    private final Vector3f velocity = new Vector3f();
    private final Vector3f acceleration = new Vector3f();
    private final CircularBuffer<TimestampedPosition> movementHistory;
    private long lastUpdateFrame;
    
    public void updatePosition(Point3f newPos, long frameNumber) {
        // Calculate velocity from position delta
        // Update movement history
        // Estimate acceleration
    }
    
    public Vector3f predictVelocity(int framesAhead) {
        // Use movement history for prediction
    }
}
```

**Modifications**:
- Extend `EntityManager` to maintain `Map<ID, EntityDynamics>`
- Update `EntityState` to include optional `EntityDynamics` reference
- Modify `updateEntityPosition()` to calculate velocity
- Added automatic dynamics updates when `autoDynamicsEnabled` is true
- Added `FrameManager` support for consistent time tracking
- Created `EntityManagerAutoDynamicsTest` to validate integrated behavior

### 1.2 Temporal Bounding Volumes

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/occlusion/`

```java
// TemporalBoundingVolume.java
public class TemporalBoundingVolume<ID> {
    private final ID entityId;
    private final EntityBounds expandedBounds;
    private final long creationFrame;
    private final long validityDuration;
    private final Point3f anchorPosition;
    private final Vector3f maxVelocity;
    private final TBVStrategy strategy;
    
    public boolean isValid(long currentFrame) {
        return (currentFrame - creationFrame) < validityDuration;
    }
    
    public EntityBounds getBoundsAtFrame(long frame) {
        // Calculate bounds based on time elapsed
    }
}

// TBVStrategy.java
public interface TBVStrategy {
    TemporalBoundingVolume createTBV(EntityDynamics dynamics, 
                                     EntityBounds currentBounds,
                                     long currentFrame);
}

// Concrete strategies
public class FixedDurationTBVStrategy implements TBVStrategy { }
public class AdaptiveTBVStrategy implements TBVStrategy { }
public class PredictiveTBVStrategy implements TBVStrategy { }
```

### 1.3 Frame Management

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/`

```java
// FrameManager.java
public class FrameManager {
    private final AtomicLong currentFrame = new AtomicLong(0);
    private final long startTime = System.nanoTime();
    
    public long nextFrame() {
        return currentFrame.incrementAndGet();
    }
    
    public long getCurrentFrame() {
        return currentFrame.get();
    }
    
    public double getFrameTime() {
        return (System.nanoTime() - startTime) / 1_000_000_000.0;
    }
}
```

### 1.4 Configuration

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/occlusion/`

```java
// DSOCConfiguration.java
public class DSOCConfiguration {
    // TBV Creation
    private int defaultTBVDuration = 30;
    private float tbvExpansionFactor = 1.2f;
    private int maxTBVsPerEntity = 3;
    
    // Adaptive Expiration
    private float velocityThreshold = 0.1f;
    private int minAdaptiveDuration = 10;
    private int maxAdaptiveDuration = 120;
    
    // Performance
    private int maxSpatialUpdatesPerFrame = 100;
    private float tbvMergeDistance = 5.0f;
    
    // Occlusion
    private boolean enableHierarchicalOcclusion = true;
    private int zPyramidLevels = 6;
}
```

## Phase 2: Core DSOC Implementation (3-4 weeks) ✅ COMPLETED

### 2.1 Enhanced Spatial Nodes

**Modifications to**: `SpatialNodeImpl.java`

```java
public class OcclusionAwareSpatialNode<ID> extends SpatialNodeImpl<ID> {
    // Occlusion metadata
    private volatile float occlusionScore = 0.0f;
    private volatile long lastOcclusionFrame = -1;
    private volatile boolean isOccluder = false;
    
    // TBV storage
    private final CopyOnWriteArrayList<TemporalBoundingVolume<ID>> tbvs;
    
    public void addTBV(TemporalBoundingVolume<ID> tbv) {
        tbvs.add(tbv);
    }
    
    public void pruneExpiredTBVs(long currentFrame) {
        tbvs.removeIf(tbv -> !tbv.isValid(currentFrame));
    }
}
```

### 2.2 Visibility State Management

**Location**: `lucien/src/main/java/com/hellblazer/luciferase/lucien/occlusion/`

```java
// VisibilityStateManager.java
public class VisibilityStateManager<ID> {
    private final Map<ID, VisibilityState> entityStates;
    private final Map<ID, TemporalBoundingVolume<ID>> activeTBVs;
    
    public enum VisibilityState {
        VISIBLE,
        HIDDEN_WITH_TBV,
        HIDDEN_EXPIRED,
        UNKNOWN
    }
    
    public void updateVisibility(ID entityId, boolean isVisible, long frame) {
        // State machine logic
    }
    
    public void createTBV(ID entityId, EntityDynamics dynamics, 
                         EntityBounds bounds, long frame) {
        // TBV creation logic
    }
}
```

### 2.3 Modified Update Logic

**Modifications to**: `AbstractSpatialIndex.java`

```java
@Override
public void updateEntity(ID entityId, Point3f newPosition, byte level) {
    EntityState<ID, ?> state = entityManager.getState(entityId);
    EntityDynamics dynamics = entityManager.getDynamics(entityId);
    long currentFrame = frameManager.getCurrentFrame();
    
    // Update dynamics
    dynamics.updatePosition(newPosition, currentFrame);
    
    // Check if we need TBV
    VisibilityState visState = visibilityManager.getState(entityId);
    if (visState == VisibilityState.HIDDEN_WITH_TBV) {
        // Update TBV instead of immediate movement
        updateTBV(entityId, dynamics, currentFrame);
        return;
    }
    
    // Perform normal update
    performSpatialUpdate(entityId, newPosition, level);
    
    // Create TBV if entity becomes hidden
    if (shouldCreateTBV(entityId, dynamics)) {
        visibilityManager.createTBV(entityId, dynamics, 
                                   state.bounds(), currentFrame);
    }
}
```

### 2.4 LCA Movement Optimization

**New class**: `LCAMovementOptimizer.java`

```java
public class LCAMovementOptimizer<Key extends SpatialKey<Key>> {
    public Key findLCA(Key oldKey, Key newKey) {
        // Find lowest common ancestor
    }
    
    public void optimizedMove(AbstractSpatialIndex<Key, ?, ?> index,
                            ID entityId, Key oldKey, Key newKey) {
        Key lca = findLCA(oldKey, newKey);
        // Only update affected subtree
    }
}
```

## Phase 3: Occlusion Integration (3-4 weeks)

### 3.1 Hierarchical Occlusion

**New class**: `HierarchicalOcclusionCuller.java`

```java
public class HierarchicalOcclusionCuller<Key extends SpatialKey<Key>, ID, Content> {
    private final HierarchicalZBuffer zBuffer;
    private final OcclusionStatistics stats;
    
    public List<FrustumIntersection<ID, Content>> 
            cullWithOcclusion(AbstractSpatialIndex<Key, ID, Content> index,
                            Frustum3D frustum,
                            long currentFrame) {
        List<FrustumIntersection<ID, Content>> visible = new ArrayList<>();
        
        // Front-to-back traversal with occlusion
        traverseWithOcclusion(index.getRoot(), frustum, 
                            zBuffer, visible, currentFrame);
        
        return visible;
    }
    
    private void traverseWithOcclusion(SpatialNode node, 
                                     Frustum3D frustum,
                                     HierarchicalZBuffer zBuffer,
                                     List<FrustumIntersection<ID, Content>> visible,
                                     long frame) {
        // Check node visibility
        if (!frustum.intersects(node.getBounds())) return;
        
        // Check occlusion
        if (zBuffer.isOccluded(node.getBounds())) {
            node.setOcclusionScore(1.0f);
            return;
        }
        
        // Process entities and TBVs
        processNodeContents(node, frustum, zBuffer, visible, frame);
        
        // Recursive traversal
        for (SpatialNode child : node.getChildren()) {
            traverseWithOcclusion(child, frustum, zBuffer, visible, frame);
        }
    }
}
```

### 3.2 Modified Frustum Culling

**Modifications to**: `AbstractSpatialIndex.java`

```java
@Override
public List<FrustumIntersection<ID, Content>> 
        frustumCullVisible(Frustum3D frustum) {
    if (dsocConfig.enableHierarchicalOcclusion) {
        return occlusionCuller.cullWithOcclusion(this, frustum, 
                                               frameManager.getCurrentFrame());
    } else {
        return performStandardFrustumCulling(frustum);
    }
}
```

### 3.3 TBV Processing

**New method in**: `HierarchicalOcclusionCuller.java`

```java
private void processTBVs(OcclusionAwareSpatialNode<ID> node,
                        Frustum3D frustum,
                        List<FrustumIntersection<ID, Content>> visible,
                        long currentFrame) {
    // Prune expired TBVs
    node.pruneExpiredTBVs(currentFrame);
    
    // Check each TBV
    for (TemporalBoundingVolume<ID> tbv : node.getTBVs()) {
        EntityBounds tbvBounds = tbv.getBoundsAtFrame(currentFrame);
        
        if (frustum.intersects(tbvBounds)) {
            // TBV is visible - need to update entity
            ID entityId = tbv.getEntityId();
            visibilityManager.updateVisibility(entityId, true, currentFrame);
            
            // Force position update
            forceEntityUpdate(entityId);
        }
    }
}
```

## Phase 4: Advanced Features (2-3 weeks)

### 4.1 Movement Prediction

**New class**: `MovementPredictor.java`

```java
public class MovementPredictor {
    public PredictedTrajectory predictMovement(EntityDynamics dynamics,
                                              int framesAhead) {
        // Kalman filter or similar prediction
    }
    
    public class PredictedTrajectory {
        private final List<Point3f> predictedPositions;
        private final List<Float> confidenceScores;
        
        public EntityBounds getConfidenceBounds(float confidenceLevel) {
            // Calculate bounds containing confidence% of predictions
        }
    }
}
```

### 4.2 TBV Merging

**New class**: `TBVMerger.java`

```java
public class TBVMerger<ID> {
    public List<TemporalBoundingVolume<ID>> 
            mergeTBVs(List<TemporalBoundingVolume<ID>> tbvs,
                     float mergeDistance) {
        // Spatial clustering of nearby TBVs
        List<TBVCluster> clusters = clusterTBVs(tbvs, mergeDistance);
        
        // Create merged TBVs
        return clusters.stream()
            .map(this::createMergedTBV)
            .collect(Collectors.toList());
    }
}
```

### 4.3 Performance Monitoring

**New class**: `DSOCMetrics.java`

```java
public class DSOCMetrics {
    private final AtomicLong tbvsCreated = new AtomicLong();
    private final AtomicLong tbvsExpired = new AtomicLong();
    private final AtomicLong occlusionCulled = new AtomicLong();
    private final AtomicLong falsePositiveTBVs = new AtomicLong();
    
    public void recordMetrics() {
        // Log performance metrics
    }
}
```

## Integration Steps

### Step 1: Create Feature Branch
```bash
git checkout -b feature/dsoc-integration
```

### Step 2: Implement Phase 1
1. Add velocity tracking to EntityManager
2. Create TBV classes and strategies
3. Add frame management
4. Create configuration system

### Step 3: Test Phase 1
```java
// DSOCPhase1Test.java
public class DSOCPhase1Test {
    @Test
    void testVelocityTracking() { }
    
    @Test
    void testTBVCreation() { }
    
    @Test
    void testFrameManagement() { }
}
```

### Step 4: Implement Phase 2
1. Extend spatial nodes with occlusion data
2. Add visibility state management
3. Modify update logic for TBVs
4. Implement LCA optimization

### Step 5: Benchmark Phase 2
```java
// DSOCPerformanceBenchmark.java
@BenchmarkMode(Mode.Throughput)
public class DSOCPerformanceBenchmark {
    @Benchmark
    public void benchmarkWithDSOC() { }
    
    @Benchmark
    public void benchmarkWithoutDSOC() { }
}
```

### Step 6: Implement Phase 3
1. Create hierarchical occlusion culler
2. Integrate with frustum culling
3. Add TBV processing logic

### Step 7: Validate Correctness
```java
// DSOCCorrectnessTest.java
public class DSOCCorrectnessTest {
    @Test
    void testNoFalseNegatives() { }
    
    @Test
    void testTBVVisibility() { }
}
```

### Step 8: Implement Phase 4
1. Add movement prediction
2. Implement TBV merging
3. Add performance monitoring

## Risk Mitigation

### Performance Risks
- **Mitigation**: Feature flags to disable DSOC
- **Monitoring**: Comprehensive benchmarks at each phase

### Compatibility Risks
- **Mitigation**: All changes extend existing classes
- **Testing**: Maintain full backward compatibility tests

### Complexity Risks
- **Mitigation**: Incremental implementation
- **Documentation**: Update docs at each phase

## Success Criteria

### Phase 1
- Velocity tracking with < 5% overhead
- TBV creation/expiration working correctly
- All existing tests pass

### Phase 2
- Movement updates create TBVs when appropriate
- LCA optimization reduces update cost by 20%+
- Visibility state transitions work correctly

### Phase 3
- Occlusion culling reduces rendered entities by 30%+
- No false negatives (missing visible entities)
- Frustum culling performance improves

### Phase 4
- Movement prediction reduces false positive TBVs by 50%
- TBV merging reduces memory usage by 20%
- Complete metrics dashboard

## Timeline

- **Week 1-2**: Phase 1 implementation
- **Week 3**: Phase 1 testing and documentation
- **Week 4-6**: Phase 2 implementation
- **Week 7**: Phase 2 benchmarking
- **Week 8-10**: Phase 3 implementation
- **Week 11**: Phase 3 validation
- **Week 12-13**: Phase 4 implementation
- **Week 14**: Final integration testing
- **Week 15**: Documentation and release

## Conclusion

This plan provides a systematic approach to integrating Dynamic Scene Occlusion Culling into Luciferase. The phased implementation ensures each component is properly tested before moving forward, while maintaining backward compatibility throughout. The modular design allows features to be enabled/disabled as needed, providing flexibility for different use cases.