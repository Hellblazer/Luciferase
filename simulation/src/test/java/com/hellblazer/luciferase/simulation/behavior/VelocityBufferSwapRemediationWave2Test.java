package com.hellblazer.luciferase.simulation.behavior;

import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.2: non-atomic double-buffer swap in FlockingBehavior and
 * PreyBehavior. The two-step swap (previous = current; current = new map) left a window where
 * previous and current aliased the same map. The fix holds both maps behind a single volatile
 * record so the swap is one atomic write — previous and current must never be the same instance.
 */
class VelocityBufferSwapRemediationWave2Test {

    @Test
    void flockingSwapNeverAliasesPreviousAndCurrent() throws Exception {
        assertSwapNeverAliases(new FlockingBehavior(), FlockingBehavior.class);
    }

    @Test
    void preySwapNeverAliasesPreviousAndCurrent() throws Exception {
        assertSwapNeverAliases(new PreyBehavior(), PreyBehavior.class);
    }

    private void assertSwapNeverAliases(Object behavior, Class<?> type) throws Exception {
        var buffersField = type.getDeclaredField("buffers");
        buffersField.setAccessible(true);
        var swap = type.getMethod("swapVelocityBuffers");

        var failed = new AtomicBoolean(false);
        var ready = new CountDownLatch(1);
        var stop = new AtomicBoolean(false);

        // Reader thread continuously inspects the buffers record for the aliasing invariant.
        var reader = new Thread(() -> {
            ready.countDown();
            while (!stop.get()) {
                try {
                    var buffers = buffersField.get(behavior);
                    var previous = (Map<?, ?>) buffers.getClass().getMethod("previous").invoke(buffers);
                    var current = (Map<?, ?>) buffers.getClass().getMethod("current").invoke(buffers);
                    if (previous == current) {
                        failed.set(true);
                        return;
                    }
                } catch (Exception e) {
                    failed.set(true);
                    return;
                }
            }
        });
        reader.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));

        // Hammer the swap from this thread.
        for (int i = 0; i < 200_000 && !failed.get(); i++) {
            swap.invoke(behavior);
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(failed.get(),
                    "previous and current velocity buffers must never alias the same map during swap");

        // Sanity: a fresh swap yields two distinct empty maps.
        swap.invoke(behavior);
        var buffers = buffersField.get(behavior);
        @SuppressWarnings("unchecked")
        var current = (Map<String, Vector3f>) buffers.getClass().getMethod("current").invoke(buffers);
        assertTrue(current.isEmpty(), "current buffer must be a fresh empty map after swap");
    }
}
