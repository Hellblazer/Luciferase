package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.delos.GossipAdapter;

import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Replicated bubble forest using gossip for distributed synchronization.
 * <p>
 * Thread-safe concurrent data structure that maintains bubble metadata across
 * a cluster using last-write-wins (LWW) conflict resolution based on timestamps.
 * <p>
 * Key features:
 * - Thread-safe concurrent access via ConcurrentHashMap
 * - Last-write-wins conflict resolution (timestamp-based)
 * - Gossip broadcast for updates
 * - Query by server or spatial bounds
 * - O(1) lookups by bubble ID
 *
 * @author hal.hildebrand
 */
public class ReplicatedForest {

    private static final String TOPIC = "bubble-forest-sync";

    private final ConcurrentHashMap<UUID, BubbleEntry> entries;
    private final GossipAdapter gossip;

    /**
     * Create a new replicated forest with gossip integration.
     *
     * @param gossip the gossip adapter for cluster communication
     */
    public ReplicatedForest(GossipAdapter gossip) {
        this.entries = new ConcurrentHashMap<>();
        this.gossip = gossip;

        // Subscribe to gossip updates from other nodes
        gossip.subscribe(TOPIC, this::handleGossipMessage);
    }

    /**
     * Add or update a bubble entry.
     * <p>
     * Uses last-write-wins conflict resolution: only updates if new timestamp
     * is greater than existing timestamp.
     * <p>
     * Broadcasts update via gossip to cluster.
     *
     * @param entry the bubble entry to put
     */
    public void put(BubbleEntry entry) {
        // LWW conflict resolution
        var shouldBroadcast = new boolean[]{false};
        entries.compute(entry.bubbleId(), (id, existing) -> {
            if (existing == null || entry.timestamp() > existing.timestamp()) {
                shouldBroadcast[0] = true;
                return entry;
            }
            // Reject older update
            return existing;
        });

        // Broadcast AFTER compute() to avoid recursive update
        if (shouldBroadcast[0]) {
            broadcastUpdate(entry);
        }
    }

    /**
     * Get a bubble entry by ID.
     *
     * @param bubbleId the bubble ID
     * @return the entry, or null if not found
     */
    public BubbleEntry get(UUID bubbleId) {
        return entries.get(bubbleId);
    }

    /**
     * Remove a bubble entry.
     *
     * @param bubbleId the bubble ID to remove
     */
    public void remove(UUID bubbleId) {
        entries.remove(bubbleId);
        // Note: removal is local only for now (Phase 0B)
        // Full distributed removal requires tombstones (Phase 1+)
    }

    /**
     * Get the number of entries.
     *
     * @return entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Get all bubbles managed by a specific server.
     *
     * @param serverId the server ID
     * @return list of bubbles on that server
     */
    public List<BubbleEntry> getByServer(UUID serverId) {
        return entries.values().stream()
                      .filter(e -> e.serverId().equals(serverId))
                      .collect(Collectors.toList());
    }

    /**
     * Get all bubbles that overlap with the given spatial bounds.
     * <p>
     * Uses RDGCS bounding box overlap test.
     *
     * @param bounds the query bounds
     * @return list of overlapping bubbles
     */
    public List<BubbleEntry> getByBounds(BubbleBounds bounds) {
        return entries.values().stream()
                      .filter(e -> e.bounds().overlaps(bounds))
                      .collect(Collectors.toList());
    }

    /**
     * Broadcast an entry update via gossip.
     *
     * @param entry the entry to broadcast
     */
    private void broadcastUpdate(BubbleEntry entry) {
        try {
            // Serialize entry to bytes
            var bytes = serialize(entry);

            // Create gossip message (use bubbleId as senderId for tracking)
            var message = new GossipAdapter.Message(entry.bubbleId(), bytes);

            // Broadcast to cluster
            gossip.broadcast(TOPIC, message);

        } catch (IOException e) {
            // Log error but don't fail the put operation
            System.err.println("Failed to broadcast update: " + e.getMessage());
        }
    }

    /**
     * Handle incoming gossip message.
     *
     * @param message the gossip message
     */
    private void handleGossipMessage(GossipAdapter.Message message) {
        try {
            // Deserialize entry
            var entry = deserialize(message.payload());

            // Apply with LWW conflict resolution (no re-broadcast)
            entries.compute(entry.bubbleId(), (id, existing) ->
                (existing == null || entry.timestamp() > existing.timestamp()) ? entry : existing
            );

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Failed to handle gossip message: " + e.getMessage());
        }
    }

    /**
     * Serialize a BubbleEntry to bytes for gossip transport.
     * <p>
     * Wire format:
     * - bubbleId: 16 bytes (2 longs)
     * - serverId: 16 bytes (2 longs)
     * - rdgMin: 12 bytes (3 ints)
     * - rdgMax: 12 bytes (3 ints)
     * - timestamp: 8 bytes (long)
     * <p>
     * Total: 64 bytes
     *
     * @param entry the entry to serialize
     * @return byte array
     */
    private byte[] serialize(BubbleEntry entry) throws IOException {
        try (var baos = new ByteArrayOutputStream();
             var dos = new DataOutputStream(baos)) {

            // UUIDs (32 bytes)
            dos.writeLong(entry.bubbleId().getMostSignificantBits());
            dos.writeLong(entry.bubbleId().getLeastSignificantBits());
            dos.writeLong(entry.serverId().getMostSignificantBits());
            dos.writeLong(entry.serverId().getLeastSignificantBits());

            // Bounds - RDGCS coordinates (24 bytes)
            dos.writeInt(entry.bounds().rdgMin().x);
            dos.writeInt(entry.bounds().rdgMin().y);
            dos.writeInt(entry.bounds().rdgMin().z);
            dos.writeInt(entry.bounds().rdgMax().x);
            dos.writeInt(entry.bounds().rdgMax().y);
            dos.writeInt(entry.bounds().rdgMax().z);

            // Timestamp (8 bytes)
            dos.writeLong(entry.timestamp());

            return baos.toByteArray();
        }
    }

    /**
     * Deserialize a BubbleEntry from bytes.
     * <p>
     * Note: reconstructs BubbleBounds from RDGCS coordinates.
     * The TetreeKey is not transmitted (bounds are stored as RDGCS min/max only).
     *
     * @param bytes the byte array
     * @return deserialized entry
     */
    private BubbleEntry deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (var bais = new ByteArrayInputStream(bytes);
             var dis = new DataInputStream(bais)) {

            // UUIDs
            var bubbleId = new UUID(dis.readLong(), dis.readLong());
            var serverId = new UUID(dis.readLong(), dis.readLong());

            // Bounds - RDGCS coordinates
            var rdgMinX = dis.readInt();
            var rdgMinY = dis.readInt();
            var rdgMinZ = dis.readInt();
            var rdgMaxX = dis.readInt();
            var rdgMaxY = dis.readInt();
            var rdgMaxZ = dis.readInt();

            // Timestamp
            var timestamp = dis.readLong();

            // Reconstruct bounds from RDGCS centroid
            var centerX = (rdgMinX + rdgMaxX) / 2;
            var centerY = (rdgMinY + rdgMaxY) / 2;
            var centerZ = (rdgMinZ + rdgMaxZ) / 2;

            // Convert RDGCS center to Cartesian via Tetrahedral coordinate system
            var tetrahedral = new com.hellblazer.luciferase.portal.Tetrahedral();
            var rdgCenter = new javax.vecmath.Point3i(centerX, centerY, centerZ);
            var cartesianCenter = tetrahedral.toCartesian(rdgCenter);

            // Create bounds from centroid position
            var bounds = BubbleBounds.fromEntityPositions(List.of(
                new javax.vecmath.Point3f(
                    (float) cartesianCenter.getX(),
                    (float) cartesianCenter.getY(),
                    (float) cartesianCenter.getZ()
                )
            ));

            return new BubbleEntry(bubbleId, serverId, bounds, timestamp);
        }
    }
}
