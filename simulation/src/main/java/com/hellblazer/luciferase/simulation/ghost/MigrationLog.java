package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.ghost.*;

import com.hellblazer.luciferase.simulation.bubble.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Migration log with idempotency token tracking.
 * <p>
 * Prevents duplicate entity migrations using idempotency tokens:
 * - Record migrations with unique tokens
 * - Detect duplicate migration attempts (same token)
 * - Track complete migration history per entity
 * - Support cleanup of old migration records
 * <p>
 * Idempotency guarantees:
 * - Same entity + same token = no-op (migration already applied)
 * - Same entity + different token = allowed (new migration)
 * - Different entities + same token = independent (both allowed)
 * <p>
 * Use case: Network partition causes duplicate migration messages:
 * <pre>
 * // First message arrives
 * boolean applied1 = log.recordMigration(entity, token, source, target, bucket);
 * // applied1 = true
 *
 * // Duplicate message arrives (same token)
 * boolean applied2 = log.recordMigration(entity, token, source, target, bucket);
 * // applied2 = false (idempotency - already applied)
 * </pre>
 * <p>
 * Thread-safe: All operations use concurrent data structures and synchronization.
 *
 * @author hal.hildebrand
 */
public class MigrationLog {

    /**
     * Migration record with idempotency token.
     * <p>
     * Immutable record of a single entity migration event.
     *
     * @param entityId     Entity that migrated
     * @param token        Idempotency token (prevents duplicates)
     * @param sourceBubble Source bubble UUID
     * @param targetBubble Target bubble UUID
     * @param bucket       Bucket when migration occurred
     */
    public record MigrationRecord(
        EntityID entityId,
        UUID token,
        UUID sourceBubble,
        UUID targetBubble,
        long bucket
    ) {
    }

    // entityId -> List of migration records (chronological order)
    private final Map<EntityID, List<MigrationRecord>> migrationHistory;

    // entityId -> Set of tokens (for duplicate detection)
    private final Map<EntityID, Set<UUID>> entityTokens;

    /**
     * Create a new migration log.
     */
    public MigrationLog() {
        this.migrationHistory = new ConcurrentHashMap<>();
        this.entityTokens = new ConcurrentHashMap<>();
    }

    /**
     * Record a migration with idempotency token.
     * <p>
     * Returns false if this exact migration was already recorded (same entity + token).
     *
     * @param entityId     Entity being migrated
     * @param token        Idempotency token (unique per migration attempt)
     * @param sourceBubble Source bubble
     * @param targetBubble Target bubble
     * @param bucket       Bucket when migration occurred
     * @return true if migration recorded, false if duplicate token
     */
    public boolean recordMigration(
        EntityID entityId,
        UUID token,
        UUID sourceBubble,
        UUID targetBubble,
        long bucket
    ) {
        // Check for duplicate token
        var tokens = entityTokens.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet());

        synchronized (tokens) {
            if (tokens.contains(token)) {
                return false;  // Duplicate - already recorded
            }
            tokens.add(token);
        }

        // Record migration
        var record = new MigrationRecord(entityId, token, sourceBubble, targetBubble, bucket);

        var history = migrationHistory.computeIfAbsent(
            entityId,
            k -> Collections.synchronizedList(new ArrayList<>())
        );

        synchronized (history) {
            history.add(record);
        }

        return true;
    }

    /**
     * Check if a migration token is a duplicate.
     *
     * @param entityId Entity to check
     * @param token    Token to check
     * @return true if this token was already used for this entity
     */
    public boolean isDuplicate(EntityID entityId, UUID token) {
        var tokens = entityTokens.get(entityId);
        if (tokens == null) {
            return false;
        }
        synchronized (tokens) {
            return tokens.contains(token);
        }
    }

    /**
     * Get complete migration history for an entity.
     * <p>
     * Returns list in chronological order (by bucket).
     *
     * @param entityId Entity to query
     * @return List of migration records (chronological order)
     */
    public List<MigrationRecord> getMigrationHistory(EntityID entityId) {
        var history = migrationHistory.get(entityId);
        if (history == null) {
            return List.of();
        }

        synchronized (history) {
            return new ArrayList<>(history);  // Defensive copy
        }
    }

    /**
     * Get most recent migration for an entity.
     *
     * @param entityId Entity to query
     * @return Optional containing latest migration, or empty if no migrations
     */
    public Optional<MigrationRecord> getLatestMigration(EntityID entityId) {
        var history = migrationHistory.get(entityId);
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }

        synchronized (history) {
            // History is already in chronological order, get last
            return Optional.of(history.get(history.size() - 1));
        }
    }

    /**
     * Cleanup migrations before a specific bucket.
     * <p>
     * Removes old migration records to free memory.
     *
     * @param bucket Cleanup migrations before this bucket
     * @return Number of migration records removed
     */
    public int cleanupBefore(long bucket) {
        int removed = 0;

        var entitiesToRemove = new ArrayList<EntityID>();

        for (var entry : migrationHistory.entrySet()) {
            var entityId = entry.getKey();
            var history = entry.getValue();

            synchronized (history) {
                // Remove records before bucket
                var beforeCleanup = history.size();
                history.removeIf(record -> record.bucket < bucket);
                removed += (beforeCleanup - history.size());

                // If no history left, mark entity for removal
                if (history.isEmpty()) {
                    entitiesToRemove.add(entityId);
                }
            }
        }

        // Remove entities with no remaining history
        for (var entityId : entitiesToRemove) {
            migrationHistory.remove(entityId);
            entityTokens.remove(entityId);
        }

        return removed;
    }

    /**
     * Clear all migration records.
     */
    public void clear() {
        migrationHistory.clear();
        entityTokens.clear();
    }

    /**
     * Get total number of migration records.
     *
     * @return Total migration count across all entities
     */
    public int getMigrationCount() {
        return migrationHistory.values().stream()
            .mapToInt(history -> {
                synchronized (history) {
                    return history.size();
                }
            })
            .sum();
    }

    /**
     * Get number of unique entities with migration history.
     *
     * @return Number of entities that have migrated
     */
    public int getUniqueEntityCount() {
        return migrationHistory.size();
    }

    /**
     * Get all entities with migration history.
     *
     * @return Set of entity IDs
     */
    public Set<EntityID> getAllMigratedEntities() {
        return new HashSet<>(migrationHistory.keySet());
    }

    /**
     * Get migrations within a bucket range.
     *
     * @param startBucket Start bucket (inclusive)
     * @param endBucket   End bucket (inclusive)
     * @return List of migration records in range
     */
    public List<MigrationRecord> getMigrationsBetween(long startBucket, long endBucket) {
        return migrationHistory.values().stream()
            .flatMap(history -> {
                synchronized (history) {
                    return history.stream();
                }
            })
            .filter(record -> record.bucket >= startBucket && record.bucket <= endBucket)
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return String.format("MigrationLog{entities=%d, migrations=%d}",
                            getUniqueEntityCount(), getMigrationCount());
    }
}
