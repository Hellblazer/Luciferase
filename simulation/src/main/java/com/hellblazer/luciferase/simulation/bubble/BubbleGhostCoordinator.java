package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostChannel;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;

/**
 * Coordinates ghost entity synchronization with remote bubbles.
 * Manages GhostChannel and GhostStateManager lifecycle.
 * <p>
 * This component handles:
 * - Ghost reception via GhostChannel
 * - Ghost state tracking and dead reckoning via GhostStateManager
 * - Tick-based ghost updates
 * <p>
 * Thread-safe via GhostStateManager internal synchronization.
 *
 * @author hal.hildebrand
 */
public class BubbleGhostCoordinator implements AutoCloseable {

    private final GhostChannel<StringEntityID, EntityData> ghostChannel;
    private final GhostStateManager ghostStateManager;

    /**
     * Controller and tick-listener references retained so {@link #close()} can deregister the
     * listener on bubble dissolution. Without this, the listener keeps the coordinator alive and
     * keeps firing on the surviving controller's CopyOnWriteArrayList forever (Luciferase-zwyf2).
     */
    private final RealTimeController realTimeController;
    private final RealTimeController.TickListener tickListener;

    /**
     * Create a ghost coordinator with channel and state manager.
     *
     * @param ghostChannel        GhostChannel for cross-bubble ghost transmission
     * @param boundsSupplier      Supplier of the bubble's current bounds; re-read on every
     *                            dead-reckoning clamp so ghosts track live bounds rather than a
     *                            stale construction-time snapshot
     * @param realTimeController  RealTimeController for tick listener registration
     */
    public BubbleGhostCoordinator(GhostChannel<StringEntityID, EntityData> ghostChannel,
                                  java.util.function.Supplier<BubbleBounds> boundsSupplier,
                                  RealTimeController realTimeController) {
        this.ghostChannel = ghostChannel;
        this.ghostStateManager = new GhostStateManager(boundsSupplier, 1000); // max 1000 ghosts

        // Register ghost reception handler using GhostChannel interface
        ghostChannel.onReceive((sourceBubbleId, ghosts) -> {
            for (var ghost : ghosts) {
                // Convert SimulationGhostEntity to EntityUpdateEvent for GhostStateManager
                // Note: Phase 7B.2 sets velocity to (0,0,0) placeholder
                var event = new com.hellblazer.luciferase.simulation.events.EntityUpdateEvent(
                    ghost.entityId(),
                    ghost.position(),
                    new javax.vecmath.Point3f(0f, 0f, 0f), // Placeholder velocity
                    ghost.timestamp(),
                    ghost.bucket() // Use bucket as lamport clock
                );
                ghostStateManager.updateGhost(sourceBubbleId, event);
            }
        });

        // Register tick listener with RealTimeController for ghost updates. Retain the reference so
        // close() can deregister it on bubble dissolution (Luciferase-zwyf2).
        this.realTimeController = realTimeController;
        this.tickListener = (simTime, lamportClock) -> {
            // Update ghost states via dead reckoning on each tick
            tickGhosts(simTime);
        };
        realTimeController.addTickListener(tickListener);
    }

    /**
     * Deregister the tick listener so a dissolved bubble's coordinator stops firing on the
     * surviving controller and can be garbage-collected. Idempotent (Luciferase-zwyf2).
     */
    @Override
    public void close() {
        realTimeController.removeTickListener(tickListener);
    }

    /**
     * Get the ghost channel for cross-bubble communication.
     *
     * @return GhostChannel instance
     */
    public GhostChannel<StringEntityID, EntityData> getGhostChannel() {
        return ghostChannel;
    }

    /**
     * Get the ghost state manager.
     * Provides access to ghost tracking and dead reckoning.
     *
     * @return GhostStateManager instance
     */
    public GhostStateManager getGhostStateManager() {
        return ghostStateManager;
    }

    /**
     * Tick ghost state on simulation step.
     * Updates ghost positions via dead reckoning and culls stale ghosts.
     * Called automatically by registered tick listener.
     *
     * @param currentTime Current simulation time (milliseconds)
     */
    public void tickGhosts(long currentTime) {
        ghostStateManager.tick(currentTime);
    }
}
