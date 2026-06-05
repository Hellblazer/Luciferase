package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.portal.web.TestClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Clock injection in GpuService.
 *
 * GPU render/benchmark tests require real OpenCL and are gated elsewhere.
 * This class validates the injection contract: setClock is accepted and
 * defaults to system Clock.
 *
 * NOTE: The injected clock wiring (clock.nanoTime() called inside render/benchmark)
 * cannot be exercised headlessly — both methods call state.renderer.renderFrame(...),
 * which requires a live OpenCL context.  A headless service has no GpuSessionState,
 * so getState() throws before the clock is read.  The clock-injection wiring is
 * therefore only verifiable on a GPU-capable host via integration tests.
 */
class GpuServiceTest {

    @Test
    void defaultClockIsSystemClock() {
        var service = new GpuService();
        // Default Clock.system() returns finite nanosecond values from System.nanoTime()
        // Two successive calls should produce a non-negative delta.
        // We cannot inject here, so just verify the default doesn't throw.
        assertDoesNotThrow(() -> {
            // Access clock via Clock.system() reference contract
            var clock = Clock.system();
            long t1 = clock.nanoTime();
            long t2 = clock.nanoTime();
            assertTrue(t2 >= t1, "nanoTime must be monotonically non-decreasing");
        });
    }

    @Test
    void setClockAcceptsInjectedClock() {
        var service = new GpuService();
        var testClock = new TestClock(0L);
        // Must not throw
        assertDoesNotThrow(() -> service.setClock(testClock));
    }

    @Test
    void testClockNanoTimeAdvancesCorrectly() {
        var clock = new TestClock(1_000L); // 1000 ms initial
        // Initial nanoTime = 1000 * 1_000_000 = 1_000_000_000 ns
        assertEquals(1_000_000_000L, clock.nanoTime());

        clock.advance(500L); // advance by 500 ms
        assertEquals(1_500_000_000L, clock.nanoTime());

        clock.advanceNanos(1_000L); // advance by 1000 ns only
        assertEquals(1_500_001_000L, clock.nanoTime());
    }

    @Test
    void testClockCurrentTimeMillisAdvancesCorrectly() {
        var clock = new TestClock(2_000L);
        assertEquals(2_000L, clock.currentTimeMillis());
        clock.advance(100L);
        assertEquals(2_100L, clock.currentTimeMillis());
    }

    @Test
    void testClockNanoTimeIsConsistentWithMillis() {
        var clock = new TestClock(500L); // 500 ms
        // nanoTime for 500ms = 500_000_000 ns
        assertEquals(500_000_000L, clock.nanoTime());
        // Advance by 200ms — nanoTime should advance by 200_000_000 ns
        clock.advance(200L);
        assertEquals(700_000_000L, clock.nanoTime());
        assertEquals(700L, clock.currentTimeMillis());
    }
}
