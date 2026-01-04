package com.hellblazer.luciferase.simulation;

/**
 * Tracks entity authority (epoch and version) for distributed update ordering.
 * <p>
 * Used to detect and reject stale updates in distributed simulation:
 * - Epoch: Increments when entity migrates to new bubble (ownership transfer)
 * - Version: Increments on each update within current epoch
 * <p>
 * Update acceptance rules:
 * - Higher epoch always wins (newer ownership, regardless of version)
 * - Within same epoch, higher version wins (more recent update)
 * - Equal epoch+version = duplicate or concurrent update (reject)
 * <p>
 * Example timeline:
 * <pre>
 * Bubble A: (0,0) → (0,1) → (0,2) → (0,3)
 *                                     ↓ migrate
 * Bubble B:                         (1,0) → (1,1) → (1,2)
 *
 * Update (0,4) from Bubble A is STALE (lower epoch than (1,x))
 * Update (1,0) from Bubble B is NEWER than any (0,x)
 * </pre>
 *
 * @param epoch   Ownership epoch (increments on migration)
 * @param version Update version within epoch (increments on each update)
 * @author hal.hildebrand
 */
public record EntityAuthority(long epoch, long version) {

    /**
     * Create new authority with incremented version.
     * <p>
     * Use when entity is updated within current bubble.
     *
     * @return New authority with version incremented
     */
    public EntityAuthority incrementVersion() {
        return new EntityAuthority(epoch, version + 1);
    }

    /**
     * Create new authority with incremented epoch and reset version.
     * <p>
     * Use when entity migrates to new bubble (ownership transfer).
     *
     * @return New authority with epoch incremented, version reset to 0
     */
    public EntityAuthority incrementEpoch() {
        return new EntityAuthority(epoch + 1, 0);
    }

    /**
     * Check if this authority is newer than another.
     * <p>
     * Comparison rules:
     * 1. Higher epoch is always newer (ownership transfer)
     * 2. Within same epoch, higher version is newer
     * 3. Equal epoch+version is NOT newer (duplicate/concurrent)
     *
     * @param other Authority to compare against
     * @return true if this authority is strictly newer
     */
    public boolean isNewerThan(EntityAuthority other) {
        if (this.epoch > other.epoch) {
            return true;  // Higher epoch always wins
        }
        if (this.epoch < other.epoch) {
            return false;  // Lower epoch always loses
        }
        // Same epoch: compare versions
        return this.version > other.version;
    }
}
