/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Callback interface for ghost layer synchronization events.
 *
 * <p>Implementations receive notifications when ghost element synchronization
 * succeeds or fails between partitions. This enables fault detection based on
 * ghost layer communication patterns.
 *
 * <p><b>Thread-Safe</b>: Implementations must be thread-safe for concurrent
 * ghost sync notifications from multiple ghost managers.
 */
public interface GhostSyncCallback {

    /**
     * Called when ghost sync to target partition succeeds.
     *
     * <p>Indicates successful synchronization of ghost elements with the
     * specified partition. Can be used to mark partition as healthy.
     *
     * @param targetRank partition rank that sync succeeded with
     */
    void onSyncSuccess(int targetRank);

    /**
     * Called when ghost sync to target partition fails.
     *
     * <p>Indicates failure to synchronize ghost elements with the specified
     * partition. Can be used to detect failures or degraded communication.
     *
     * @param targetRank partition rank that sync failed with
     * @param cause exception that caused the failure
     */
    void onSyncFailure(int targetRank, Exception cause);
}
