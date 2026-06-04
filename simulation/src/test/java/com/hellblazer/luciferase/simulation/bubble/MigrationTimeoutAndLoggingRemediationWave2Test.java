package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationOracle;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationOracleImpl;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigrator;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigratorImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-2 remediation beads on the migration slice:
 * Luciferase-0frcy.13 (double timeout processing) and .16 (System.err on rollback failure +
 * missing logger).
 */
class MigrationTimeoutAndLoggingRemediationWave2Test {

    // ---- Luciferase-0frcy.13: timeout processing must be per-entity, not batch-per-entity ----

    @Test
    void processTimeoutsDoesNotReinvokeBatchProcessTimeouts() {
        var bubbleId = UUID.randomUUID();
        var entityId = UUID.randomUUID();

        var bubble = new EnhancedBubble(bubbleId, (byte) 10, 100L);
        var viewMonitor = new FirefliesViewMonitor(mock(MembershipView.class), 3);

        EntityMigrationStateMachine realFsm = new EntityMigrationStateMachine(viewMonitor);
        var fsm = spy(realFsm);
        MigrationOracle oracle = new MigrationOracleImpl(2, 2, 2);
        OptimisticMigrator migrator = new OptimisticMigratorImpl();

        // One entity reported as timed-out by checkTimeouts.
        doReturn(List.<Object>of(entityId)).when(fsm).checkTimeouts(anyLong());

        var integration = new EnhancedBubbleMigrationIntegration(
                bubble, fsm, oracle, migrator, viewMonitor, 3);

        integration.processMigrations(1_000L);

        // The integration must NOT call the batch processTimeouts() (which itself re-runs
        // checkTimeouts and would reprocess every timed-out entity N+1 times). It must instead
        // drive a single per-entity ROLLBACK_OWNED transition.
        verify(fsm, never()).processTimeouts(anyLong());
        verify(fsm, times(1)).transition(eq(entityId), eq(EntityMigrationState.ROLLBACK_OWNED));
    }

    // ---- Luciferase-0frcy.16: TetrahedralMigration must use SLF4J, not System.err ----

    @Test
    void tetrahedralMigrationHasSlf4jLoggerAndNoSystemErr() throws Exception {
        Field logField = TetrahedralMigration.class.getDeclaredField("log");
        assertEquals("org.slf4j.Logger", logField.getType().getName(),
                     "TetrahedralMigration must declare an SLF4J Logger field");

        var source = readSource(TetrahedralMigration.class);
        assertFalse(source.contains("System.err"),
                    "TetrahedralMigration must not use System.err (SLF4J mandate)");
        assertFalse(source.contains("System.out"),
                    "TetrahedralMigration must not use System.out (SLF4J mandate)");
    }

    private static String readSource(Class<?> type) throws Exception {
        var rel = type.getName().replace('.', '/') + ".java";
        var path = java.nio.file.Path.of("src/main/java", rel);
        assertTrue(java.nio.file.Files.exists(path), "source not found: " + path);
        return java.nio.file.Files.readString(path);
    }
}
