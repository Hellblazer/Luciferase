package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-2 remediation beads on SimulationBubble:
 * Luciferase-0frcy.9 (Kairos.setController global side-effect made observable) and
 * .10 (physicsTick() running guard).
 */
class SimulationBubbleRemediationWave2Test {

    // ---- Luciferase-0frcy.9: global Kairos controller overwrite must be tracked/observable ----

    @Test
    @SuppressWarnings("unchecked")
    void secondSimulationBubbleOverwritesAndTracksGlobalController() throws Exception {
        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);

        var sim1 = new SimulationBubble(b1, new RandomWalkBehavior(), 16L, WorldBounds.DEFAULT);

        Field active = SimulationBubble.class.getDeclaredField("ACTIVE_CONTROLLER");
        active.setAccessible(true);
        var ref = (AtomicReference<Object>) active.get(null);
        var afterFirst = ref.get();
        assertNotNull(afterFirst, "constructing a SimulationBubble must register its controller globally");

        // Constructing a second SimulationBubble overwrites the global Kairos controller; the
        // tracker must observe the change (this is the documented last-writer-wins hazard).
        var sim2 = new SimulationBubble(b2, new RandomWalkBehavior(), 16L, WorldBounds.DEFAULT);
        var afterSecond = ref.get();
        assertNotSame(afterFirst, afterSecond,
                      "second SimulationBubble must overwrite the tracked global controller");

        sim1.shutdown();
        sim2.shutdown();
    }

    // ---- Luciferase-0frcy.10: physicsTick() must guard on running before rescheduling ----

    @Test
    void physicsTickHasRunningGuardBeforeReschedule() throws Exception {
        var rel = SimulationBubble.class.getName().replace('.', '/') + ".java";
        var path = java.nio.file.Path.of("src/main/java", rel);
        var source = java.nio.file.Files.readString(path);

        int tickIdx = source.indexOf("public void physicsTick()");
        assertTrue(tickIdx > 0, "physicsTick() must exist");
        var body = source.substring(tickIdx);
        int sleepIdx = body.indexOf("Kronos.sleep(frameRateNs)");
        assertTrue(sleepIdx > 0, "physicsTick() must reschedule via Kronos.sleep");

        // The running guard must appear BEFORE the reschedule so a stopped bubble does not
        // re-queue itself indefinitely.
        var beforeReschedule = body.substring(0, sleepIdx);
        assertTrue(beforeReschedule.contains("SimulationBubble.this.running")
                   && beforeReschedule.contains("return"),
                   "physicsTick() must check !running and return before Kronos.sleep reschedule");
    }
}
