package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostChannel;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import com.hellblazer.luciferase.simulation.ghost.InMemoryGhostChannel;
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Enhanced Bubble with tetrahedral bounds, spatial index, and VON integration.
 * Acts as orchestrator composing focused components for clean separation of concerns.
 * <p>
 * Enhancements over basic Bubble:
 * - Internal Tetree spatial index for entity storage (BubbleEntityStore)
 * - BubbleBounds for tetrahedral bounding volumes (BubbleBoundsTracker)
 * - VON neighbor tracking for distributed bubble discovery (BubbleVonCoordinator)
 * - Frame time monitoring with split detection (BubbleFrameMonitor)
 * - Ghost entity synchronization (BubbleGhostCoordinator)
 * <p>
 * Component Architecture:
 * - BubbleFrameMonitor: Performance tracking and split detection
 * - BubbleVonCoordinator: VON neighbor discovery
 * - BubbleBoundsTracker: Spatial extent management (implements EntityChangeListener)
 * - BubbleEntityStore: Entity lifecycle and spatial queries (notifies EntityChangeListeners)
 * - BubbleGhostCoordinator: Ghost channel and state management
 * <p>
 * Observer Pattern: BubbleEntityStore notifies BubbleBoundsTracker via EntityChangeListener
 * interface, enabling loose coupling for bounds updates on entity changes.
 * <p>
 * Thread-safe for concurrent entity operations via component delegation.
 *
 * @author hal.hildebrand
 */
public class EnhancedBubble {

    private final UUID id;
    private final byte spatialLevel;
    private final RealTimeController realTimeController;
    private final BubbleFrameMonitor frameMonitor;
    private final BubbleVonCoordinator vonCoordinator;
    private final BubbleBoundsTracker boundsTracker;
    private final BubbleEntityStore entityStore;
    private final BubbleGhostCoordinator ghostCoordinator;

    /**
     * Create an enhanced bubble with spatial indexing and monitoring.
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     */
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs) {
        this(id, spatialLevel, targetFrameMs, new RealTimeController(id, "bubble-" + id.toString().substring(0, 8)));
    }

    /**
     * Create an enhanced bubble with spatial indexing and monitoring.
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     * @param realTimeController   RealTimeController for simulation time management
     */
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs, RealTimeController realTimeController) {
        this(id, spatialLevel, targetFrameMs, realTimeController, new InMemoryGhostChannel<>());
    }

    /**
     * Create an enhanced bubble with spatial indexing, monitoring, and custom ghost channel.
     * <p>
     * This constructor allows injection of different GhostChannel implementations:
     * - InMemoryGhostChannel: For testing and single-bubble scenarios (default)
     * - DelosSocketTransport: For distributed multi-bubble simulation (Phase 7B.2)
     * <p>
     * <strong>Phase 7B.2 Integration:</strong>
     * <pre>
     * // Use Delos-based network transport
     * var transport = new DelosSocketTransport(bubbleId);
     * var bubble = new EnhancedBubble(id, level, frameMs, controller, transport);
     * </pre>
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     * @param realTimeController   RealTimeController for simulation time management
     * @param ghostChannel         GhostChannel for cross-bubble ghost transmission
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type throughout EnhancedBubble
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs, RealTimeController realTimeController,
                          GhostChannel<StringEntityID, EntityData> ghostChannel) {
        this.id = id;
        this.spatialLevel = spatialLevel;
        this.realTimeController = realTimeController;
        this.frameMonitor = new BubbleFrameMonitor(targetFrameMs);
        this.vonCoordinator = new BubbleVonCoordinator();
        this.boundsTracker = new BubbleBoundsTracker(spatialLevel);
        this.entityStore = new BubbleEntityStore(spatialLevel, realTimeController);

        // Register boundsTracker as listener to entity changes
        this.entityStore.addEntityChangeListener(boundsTracker);

        // Create ghost coordinator with channel, bounds, and real-time controller
        this.ghostCoordinator = new BubbleGhostCoordinator(
            Objects.requireNonNull(ghostChannel, "ghostChannel must not be null"),
            boundsTracker.bounds(),
            realTimeController
        );
    }

    /**
     * Get the bubble ID.
     *
     * @return Unique bubble identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * Get the number of entities in this bubble.
     *
     * @return Entity count
     */
    public int entityCount() {
        return entityStore.entityCount();
    }

    /**
     * Get the spatial level used for tetree refinement.
     *
     * @return Spatial level (tetree refinement depth)
     */
    public byte getSpatialLevel() {
        return spatialLevel;
    }

    /**
     * Get the target frame time budget in milliseconds.
     *
     * @return Target frame time in milliseconds
     */
    public long getTargetFrameMs() {
        return frameMonitor.getTargetFrameMs();
    }

    /**
     * Get the current bounds of this bubble.
     *
     * @return BubbleBounds or null if no entities
     */
    public BubbleBounds bounds() {
        return boundsTracker.bounds();
    }

    /**
     * Get the ghost channel for cross-bubble communication.
     *
     * @return GhostChannel instance
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type
    public GhostChannel<StringEntityID, EntityData> getGhostChannel() {
        return ghostCoordinator.getGhostChannel();
    }

    /**
     * Get the ghost state manager (Phase 7B.3).
     * Provides access to ghost tracking and dead reckoning.
     *
     * @return GhostStateManager instance
     */
    public GhostStateManager getGhostStateManager() {
        return ghostCoordinator.getGhostStateManager();
    }

    /**
     * Tick ghost state on simulation step (Phase 7B.3).
     * Updates ghost positions via dead reckoning and culls stale ghosts.
     * Should be called once per simulation tick.
     *
     * @param currentTime Current simulation time (milliseconds)
     */
    public void tickGhosts(long currentTime) {
        ghostCoordinator.tickGhosts(currentTime);
    }

    /**
     * Get the set of VON neighbors.
     *
     * @return Set of neighbor bubble UUIDs
     */
    public Set<UUID> getVonNeighbors() {
        return vonCoordinator.getVonNeighbors();
    }

    /**
     * Get all entity IDs in this bubble.
     *
     * @return Set of entity IDs
     */
    public Set<String> getEntities() {
        return entityStore.getEntities();
    }

    /**
     * Get all entities with their data (position, content, bucket).
     * This avoids coordinate issues with large radius queries.
     *
     * @return List of all entity records
     */
    public List<EntityRecord> getAllEntityRecords() {
        var bubbleRecords = entityStore.getAllEntityRecords();
        var results = new ArrayList<EntityRecord>();
        for (var record : bubbleRecords) {
            results.add(new EntityRecord(record.id(), record.position(), record.content(), record.addedBucket()));
        }
        return results;
    }

    /**
     * Add an entity to this bubble.
     * Inserts into spatial index and updates bounds.
     * Uses RealTimeController's simulation time for entity timestamp.
     *
     * @param entityId Entity identifier
     * @param position Entity position
     * @param content  Entity content
     */
    public void addEntity(String entityId, Point3f position, Object content) {
        entityStore.addEntity(entityId, position, content);
    }

    /**
     * Remove an entity from this bubble.
     * Removes from spatial index and recalculates bounds.
     *
     * @param entityId Entity to remove
     */
    public void removeEntity(String entityId) {
        entityStore.removeEntity(entityId);
    }

    /**
     * Update an entity's position.
     * Updates spatial index and bounds.
     *
     * @param entityId    Entity to update
     * @param newPosition New position
     */
    public void updateEntityPosition(String entityId, Point3f newPosition) {
        entityStore.updateEntityPosition(entityId, newPosition);
    }

    /**
     * Query entities within a radius of a center point.
     *
     * @param center Center point
     * @param radius Search radius
     * @return List of entities within radius
     */
    public List<EntityRecord> queryRange(Point3f center, float radius) {
        var bubbleRecords = entityStore.queryRange(center, radius);
        var results = new ArrayList<EntityRecord>();
        for (var record : bubbleRecords) {
            results.add(new EntityRecord(record.id(), record.position(), record.content(), record.addedBucket()));
        }
        return results;
    }

    /**
     * Find k nearest neighbors to a query point.
     *
     * @param query Query point
     * @param k     Number of neighbors
     * @return List of k nearest entities
     */
    public List<EntityRecord> kNearestNeighbors(Point3f query, int k) {
        var bubbleRecords = entityStore.kNearestNeighbors(query, k);
        var results = new ArrayList<EntityRecord>();
        for (var record : bubbleRecords) {
            results.add(new EntityRecord(record.id(), record.position(), record.content(), record.addedBucket()));
        }
        return results;
    }

    /**
     * Recalculate bounds from current entity positions.
     */
    public void recalculateBounds() {
        boundsTracker.recalculateBounds();
    }

    /**
     * Get the centroid of this bubble based on entity positions.
     * Returns the average position of all entities, or tetrahedral centroid if no entities.
     *
     * @return Centroid point or null if no bounds
     */
    public Point3D centroid() {
        return boundsTracker.centroid();
    }

    /**
     * Add a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID
     */
    public void addVonNeighbor(UUID neighborId) {
        vonCoordinator.addVonNeighbor(neighborId);
    }

    /**
     * Remove a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID to remove
     */
    public void removeVonNeighbor(UUID neighborId) {
        vonCoordinator.removeVonNeighbor(neighborId);
    }

    /**
     * Record the time taken for a simulation frame.
     *
     * @param frameTimeNs Frame time in nanoseconds
     */
    public void recordFrameTime(long frameTimeNs) {
        frameMonitor.recordFrameTime(frameTimeNs);
    }

    /**
     * Get the current frame utilization as a fraction of the target budget.
     *
     * @return Utilization (0.0 to 1.0+, >1.0 means over budget)
     */
    public float frameUtilization() {
        return frameMonitor.frameUtilization();
    }

    /**
     * Check if this bubble needs to split due to frame time overrun.
     * Split threshold: 120% of target frame time (1.2x budget).
     *
     * @return true if bubble should split
     */
    public boolean needsSplit() {
        return frameMonitor.needsSplit();
    }

    /**
     * Process a single simulation tick for a time bucket.
     *
     * @param bucket Simulation time bucket
     */
    public void tick(long bucket) {
        // Placeholder for simulation tick processing
        // In a full implementation, this would:
        // 1. Process all entities scheduled for this bucket
        // 2. Update entity states
        // 3. Handle interactions
        // 4. Measure frame time
    }

    /**
     * Entity record for query results.
     */
    public record EntityRecord(String id, Point3f position, Object content, long addedBucket) {
    }
}
