/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostChannel;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the BubbleGhostCoordinator TickListener leak (Luciferase-zwyf2). The
 * coordinator registers a tick listener in its constructor; close() must deregister it so the
 * controller's listener count returns to baseline on bubble dissolution.
 *
 * @author hal.hildebrand
 */
class BubbleGhostCoordinatorLeakTest {

    /** Minimal no-op GhostChannel; the coordinator only invokes onReceive() during construction. */
    private static final class StubGhostChannel implements GhostChannel<StringEntityID, EntityData> {
        @Override
        public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<StringEntityID, EntityData> ghost) {
        }

        @Override
        public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts) {
        }

        @Override
        public void flush(long bucket) {
        }

        @Override
        public void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> handler) {
        }

        @Override
        public boolean isConnected(UUID targetBubbleId) {
            return false;
        }

        @Override
        public int getPendingCount(UUID targetBubbleId) {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void closeDeregistersTickListener() {
        var controller = new RealTimeController(UUID.randomUUID(), "leak-test", 100);
        int baseline = controller.getTickListenerCount();

        var bounds = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 10, 0L, 0L));
        var coordinator = new BubbleGhostCoordinator(new StubGhostChannel(), () -> bounds, controller);
        assertEquals(baseline + 1, controller.getTickListenerCount(),
                     "constructor must register exactly one tick listener");

        coordinator.close();
        assertEquals(baseline, controller.getTickListenerCount(),
                     "close() must deregister the tick listener so the count returns to baseline");
    }
}
