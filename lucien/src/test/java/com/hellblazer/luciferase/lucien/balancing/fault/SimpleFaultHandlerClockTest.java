package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every PartitionChangeEvent.timestamp and PartitionState.lastSeenMs
 * comes from the injected Clock, not System.currentTimeMillis().
 *
 * Bead Luciferase-7wzml.104: clock injection completion for SimpleFaultHandler.
 */
class SimpleFaultHandlerClockTest {

    private static final long FIXED_TIME = 123_456_789L;

    private TestClock            clock;
    private SimpleFaultHandler   handler;
    private UUID                 pid;
    private List<PartitionChangeEvent> events;

    @BeforeEach
    void setUp() {
        clock = new TestClock(FIXED_TIME);
        handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.setClock(clock);
        handler.start();

        events = new ArrayList<>();
        handler.subscribeToChanges(events::add);

        pid = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        handler.stop();
    }

    // ---- markHealthy: auto-register path ----

    @Test
    void markHealthy_autoRegister_timestampFromClock() {
        handler.markHealthy(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(FIXED_TIME);

        var view = handler.getPartitionView(pid);
        assertThat(view).isNotNull();
        assertThat(view.lastSeenMs()).isEqualTo(FIXED_TIME);
    }

    @Test
    void markHealthy_autoRegister_advancedClock_timestampFromClock() {
        clock.setTime(999L);
        handler.markHealthy(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(999L);
    }

    // ---- markHealthy: status-change path (FAILED -> HEALTHY) ----

    @Test
    void markHealthy_statusChange_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        // Drive to FAILED
        handler.reportBarrierTimeout(pid);
        handler.reportBarrierTimeout(pid);
        events.clear();

        clock.setTime(77_777L);
        handler.markHealthy(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(77_777L);

        var view = handler.getPartitionView(pid);
        assertThat(view.lastSeenMs()).isEqualTo(77_777L);
    }

    // ---- reportBarrierTimeout (escalate path) ----

    @Test
    void reportBarrierTimeout_escalate_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        clock.setTime(200L);
        handler.reportBarrierTimeout(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(200L);

        var view = handler.getPartitionView(pid);
        assertThat(view.lastSeenMs()).isEqualTo(200L);
    }

    @Test
    void reportBarrierTimeout_secondEscalate_timestampFromClock() {
        handler.markHealthy(pid);
        clock.setTime(100L);
        handler.reportBarrierTimeout(pid); // HEALTHY -> SUSPECTED
        events.clear();

        clock.setTime(300L);
        handler.reportBarrierTimeout(pid); // SUSPECTED -> FAILED

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(300L);

        var view = handler.getPartitionView(pid);
        assertThat(view.lastSeenMs()).isEqualTo(300L);
    }

    // ---- reportSyncFailure ----

    @Test
    void reportSyncFailure_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        clock.setTime(555L);
        handler.reportSyncFailure(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(555L);
    }

    // ---- reportHeartbeatFailure(UUID, UUID) ----

    @Test
    void reportHeartbeatFailure_withNode_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        clock.setTime(888L);
        handler.reportHeartbeatFailure(pid, UUID.randomUUID());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(888L);
    }

    // ---- reportHeartbeatFailure(UUID) ----

    @Test
    void reportHeartbeatFailure_noNode_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        clock.setTime(444L);
        handler.reportHeartbeatFailure(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(444L);
    }

    // ---- reportPartitionFailed (forceFailed path) ----

    @Test
    void reportPartitionFailed_timestampFromClock() {
        handler.markHealthy(pid);
        events.clear();

        clock.setTime(1_000_000L);
        handler.reportPartitionFailed(pid);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(1_000_000L);

        var view = handler.getPartitionView(pid);
        assertThat(view.lastSeenMs()).isEqualTo(1_000_000L);
    }

    // ---- notifyRecoveryComplete ----

    @Test
    void notifyRecoveryComplete_success_timestampFromClock() {
        handler.markHealthy(pid);
        handler.reportPartitionFailed(pid);
        events.clear();

        clock.setTime(5_000L);
        handler.notifyRecoveryComplete(pid, true);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(5_000L);

        var view = handler.getPartitionView(pid);
        assertThat(view.lastSeenMs()).isEqualTo(5_000L);
    }

    @Test
    void notifyRecoveryComplete_failure_timestampFromClock() {
        handler.markHealthy(pid);
        handler.reportPartitionFailed(pid);
        events.clear();

        clock.setTime(6_000L);
        handler.notifyRecoveryComplete(pid, false);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).timestamp()).isEqualTo(6_000L);
    }

    // ---- PartitionState ctor (via computeIfAbsent in reportBarrierTimeout) ----

    @Test
    void newPartitionState_viaReport_lastSeenMsFromClock() {
        // pid not yet registered - reportBarrierTimeout creates it via computeIfAbsent
        clock.setTime(42L);
        handler.reportBarrierTimeout(pid);

        var view = handler.getPartitionView(pid);
        assertThat(view).isNotNull();
        // The PartitionState was created at clock=42; lastSeenMs updated by escalate at clock=42
        assertThat(view.lastSeenMs()).isEqualTo(42L);
    }
}
