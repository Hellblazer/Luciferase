# V3: GhostZoneManager Extension Strategy Decision

**Date**: 2026-01-04
**Bead**: Luciferase-22n (Phase 0)
**Decision**: Option B - Create `SimulationGhostManager` wrapper

---

## Context

The Simulation Bubbles architecture requires ghost entities to carry additional simulation-specific metadata beyond what the generic `GhostZoneManager` provides.

**Current `GhostEntity` fields** (from `lucien` module):

- `entityId` - Entity identifier
- `content` - Entity content/state
- `position` - 3D position
- `bounds` - Entity bounds
- `sourceTreeId` - Originating spatial tree
- `timestamp` - Creation timestamp

**Required simulation fields**:

- `sourceBubbleId` (UUID) - Which bubble owns this entity (enables distributed bubble discovery)
- `bucket` (long) - Simulation time bucket for causal ordering
- `epoch` (long) - Entity authority epoch for stale update detection
- `version` (long) - Entity version within epoch

---

## Options Considered

### Option A: Extend GhostEntity with Simulation Fields

**Approach**: Add simulation fields directly to `GhostZoneManager.GhostEntity`

```java
public static class GhostEntity<ID extends EntityID, Content> {
    // Existing fields...
    private final UUID sourceBubbleId;  // NEW
    private final long bucket;          // NEW
    private final long epoch;           // NEW
    private final long version;         // NEW
}
```

**Pros**:

- Single source of truth
- No wrapper overhead
- Direct access to all fields

**Cons**:

- ❌ Couples `lucien` module to simulation concepts
- ❌ Breaks separation of concerns
- ❌ Makes GhostZoneManager less generic/reusable
- ❌ Simulation fields unused in non-simulation use cases
- ❌ Violates module dependency direction (lucien is lower-level)

---

### Option B: Create SimulationGhostManager Wrapper ✅ **SELECTED**

**Approach**: Wrap `GhostZoneManager` with simulation-specific logic

```java
// In simulation module
public class SimulationGhostManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    private final GhostZoneManager<Key, ID, Content> ghostManager;
    private final Map<ID, SimulationGhostMetadata> metadata;

    public record SimulationGhostEntity<ID, Content>(
        GhostEntity<ID, Content> ghost,    // Delegate to GhostZoneManager
        UUID sourceBubbleId,                // Simulation-specific
        long bucket,                        // Simulation-specific
        long epoch,                         // Simulation-specific
        long version                        // Simulation-specific
    ) {}
}
```

**Pros**:

- ✅ Clean separation of concerns
- ✅ `lucien` module remains generic
- ✅ Simulation module depends on lucien (correct direction)
- ✅ GhostZoneManager reusable for other use cases
- ✅ Easier to test simulation logic in isolation
- ✅ Record pattern provides immutability

**Cons**:

- Additional abstraction layer (minor)
- Need to maintain mapping between ghost and metadata (solvable via concurrent map)

---

## Decision: Option B

**Rationale**:

1. **Module Separation**: `lucien` is core spatial indexing, `simulation` is higher-level application logic
2. **Reusability**: GhostZoneManager may be used for non-simulation scenarios (rendering, physics, AI)
3. **Testability**: Simulation logic can be tested without spatial index complexity
4. **Maintainability**: Changes to simulation metadata don't require touching lucien
5. **API Verification**: `GhostZoneManager(Forest, float)` constructor VERIFIED (matches plan requirement ✅)

---

## Implementation Design

### Core Classes

```java
/**
 * Wraps GhostZoneManager with simulation-specific metadata
 */
public class SimulationGhostManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private final GhostZoneManager<Key, ID, Content> ghostManager;
    private final ConcurrentHashMap<ID, SimulationGhostMetadata> metadata;
    private final ExternalBubbleTracker bubbleTracker; // VON discovery

    public SimulationGhostManager(Forest<Key, ID, Content> forest, float ghostZoneWidth) {
        this.ghostManager = new GhostZoneManager<>(forest, ghostZoneWidth);
        this.metadata = new ConcurrentHashMap<>();
        this.bubbleTracker = new ExternalBubbleTracker();
    }

    /**
     * Update ghost with simulation metadata
     */
    public void updateSimulationGhost(ID entityId, String sourceTreeId, Point3f position,
                                      Content content, EntityBounds bounds,
                                      UUID sourceBubbleId, long bucket, long epoch, long version) {
        // Update spatial ghost
        ghostManager.updateGhostEntity(entityId, sourceTreeId, position, content, bounds);

        // Store simulation metadata
        metadata.put(entityId, new SimulationGhostMetadata(sourceBubbleId, bucket, epoch, version));

        // Discover external bubble (VON pattern)
        bubbleTracker.onGhostReceived(sourceBubbleId, sourceTreeId);
    }

    /**
     * Get simulation ghost with full metadata
     */
    public Optional<SimulationGhostEntity<ID, Content>> getSimulationGhost(ID entityId, String sourceTreeId) {
        var ghost = getGhostEntityFromManager(entityId, sourceTreeId);
        if (ghost == null) return Optional.empty();

        var meta = metadata.get(entityId);
        if (meta == null) return Optional.empty();

        return Optional.of(new SimulationGhostEntity<>(
            ghost, meta.sourceBubbleId, meta.bucket, meta.epoch, meta.version
        ));
    }

    /**
     * Remove ghost and metadata
     */
    public void removeSimulationGhost(ID entityId, String sourceTreeId) {
        ghostManager.removeGhostEntity(entityId, sourceTreeId);
        metadata.remove(entityId);
    }

    /**
     * Sync all ghost zones (delegates to GhostZoneManager)
     */
    public void synchronizeAllGhostZones() {
        ghostManager.synchronizeAllGhostZones();
    }
}

/**
 * Simulation-specific metadata for ghost entities
 */
record SimulationGhostMetadata(UUID sourceBubbleId, long bucket, long epoch, long version) {}

/**
 * Complete simulation ghost entity (ghost + metadata)
 */
record SimulationGhostEntity<ID extends EntityID, Content>(
    GhostEntity<ID, Content> ghost,
    UUID sourceBubbleId,
    long bucket,
    long epoch,
    long version
) {
    // Convenience accessors
    public ID entityId() { return ghost.getEntityId(); }
    public Content content() { return ghost.getContent(); }
    public Point3f position() { return ghost.getPosition(); }
    public String sourceTreeId() { return ghost.getSourceTreeId(); }
}
```

---

## Integration Points

### From lucien Module (Verified ✅)

```java
// Constructor verified in GhostZoneManager.java:171
GhostZoneManager<Key, ID, Content> ghostManager =
    new GhostZoneManager<>(forest, ghostZoneWidth);

// Core API methods available
ghostManager.updateGhostEntity(id, sourceTree, pos, content, bounds);
ghostManager.getGhostEntities(treeId);
ghostManager.synchronizeAllGhostZones();
```

### To simulation Module (Phase 1 Deliverable)

```java
// SimulationNode uses SimulationGhostManager
SimulationGhostManager<TetreeKey, EntityID, SimulationContent> simGhosts =
    new SimulationGhostManager<>(forest, ghostZoneWidth);

// Update with simulation metadata
simGhosts.updateSimulationGhost(
    entityId, sourceTree, position, content, bounds,
    sourceBubbleId, currentBucket, epoch, version
);

// Discover external bubbles via VON pattern
List<UUID> mergeCandidates = simGhosts.bubbleTracker.getMergeCandidates(localBubbleId, threshold);
```

---

## Validation Results

| Aspect | Status | Notes |
|--------|--------|-------|
| **GhostZoneManager Constructor** | ✅ VERIFIED | `GhostZoneManager(Forest, float)` exists at line 171 |
| **GhostEntity API** | ✅ VERIFIED | Supports `entityId, content, position, bounds, sourceTreeId` |
| **Update Method** | ✅ VERIFIED | `updateGhostEntity()` available |
| **Synchronization** | ✅ VERIFIED | `synchronizeAllGhostZones()` available |
| **Module Separation** | ✅ DESIGN | Wrapper pattern maintains clean boundaries |

---

## Phase 1 Deliverables (Updated)

Based on this decision, Phase 1 must deliver:

1. `SimulationGhostManager.java` - Wrapper class
2. `SimulationGhostEntity` record - Complete ghost with metadata
3. `SimulationGhostMetadata` record - Simulation-specific fields
4. Integration with `ExternalBubbleTracker` (from Phase 1 plan)

---

## Alternative Approaches Rejected

### Why not inheritance?

```java
// REJECTED: Inheritance approach
class SimulationGhostEntity extends GhostEntity {
    UUID sourceBubbleId;
    long bucket, epoch, version;
}
```

**Rejected because**:

- GhostEntity is a static inner class (can't extend)
- Would still couple lucien to simulation
- Composition is more flexible than inheritance

### Why not just use metadata map?

**Already doing this!** The wrapper pattern IS using a metadata map. The `SimulationGhostManager` wraps `GhostZoneManager` + maintains `ConcurrentHashMap<ID, SimulationGhostMetadata>`.

---

## Conclusion

**Decision**: Create `SimulationGhostManager` wrapper in simulation module.

**Verification**: GhostZoneManager API confirmed compatible. Constructor `GhostZoneManager(Forest, float)` exists and works as planned.

**Next Steps**:

1. Implement `SimulationGhostManager` in Phase 1
2. Test integration with existing GhostZoneManager
3. Verify VON bubble discovery pattern works

**Status**: ✅ V3 COMPLETE - Extension strategy decided with verification
