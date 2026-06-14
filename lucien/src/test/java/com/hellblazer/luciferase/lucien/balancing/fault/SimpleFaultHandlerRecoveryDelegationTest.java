/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-9af5v: {@link SimpleFaultHandler#initiateRecovery(UUID)} previously
 * delegated to the deprecated {@link PartitionRecovery#initiateRecovery(UUID)} default, which always
 * returns a failed future with {@link UnsupportedOperationException}. As a result, NO registered
 * recovery strategy could ever succeed through this handler. The fix delegates to
 * {@code recover(UUID, FaultHandler)}, matching {@link DefaultFaultHandler}.
 */
class SimpleFaultHandlerRecoveryDelegationTest {

    /** Recovery strategy parameterized on outcome, recording whether {@code recover} was invoked. */
    private static final class RecordingRecovery implements PartitionRecovery {
        enum Outcome { SUCCEED, FAIL, THROW }

        final AtomicBoolean recoverCalled = new AtomicBoolean(false);
        private final FaultConfiguration configuration;
        private final Outcome outcome;
        private final boolean canRecover;

        RecordingRecovery(FaultConfiguration configuration, Outcome outcome, boolean canRecover) {
            this.configuration = configuration;
            this.outcome = outcome;
            this.canRecover = canRecover;
        }

        @Override
        public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            recoverCalled.set(true);
            return switch (outcome) {
                case SUCCEED -> CompletableFuture.completedFuture(
                    RecoveryResult.success(partitionId, 1L, getStrategyName(), 1));
                case FAIL -> CompletableFuture.completedFuture(
                    RecoveryResult.failure(partitionId, 1L, getStrategyName(), 1, "test failure", null));
                case THROW -> CompletableFuture.failedFuture(new RuntimeException("boom"));
            };
        }

        @Override
        public boolean canRecover(UUID partitionId, FaultHandler handler) {
            return canRecover;
        }

        @Override
        public String getStrategyName() {
            return "recording-test-recovery";
        }

        @Override
        public FaultConfiguration getConfiguration() {
            return configuration;
        }
    }

    private SimpleFaultHandler handlerWith(RecordingRecovery recovery, UUID partitionId) {
        var handler = new SimpleFaultHandler(recovery.getConfiguration());
        handler.reportPartitionFailed(partitionId); // registers state in FAILED status
        handler.registerRecovery(partitionId, recovery);
        return handler;
    }

    @Test
    void initiateRecoveryDelegatesToRecoverAndSucceeds() throws Exception {
        var config = FaultConfiguration.defaultConfig();
        var partitionId = UUID.randomUUID();
        var recovery = new RecordingRecovery(config, RecordingRecovery.Outcome.SUCCEED, true);
        var handler = handlerWith(recovery, partitionId);

        var result = handler.initiateRecovery(partitionId).get(5, TimeUnit.SECONDS);

        // Under the old code this future completed exceptionally with UnsupportedOperationException
        // (get() would throw); recover() was never reached.
        assertTrue(result, "Recovery must succeed via recover(UUID, FaultHandler)");
        assertTrue(recovery.recoverCalled.get(),
            "initiateRecovery must delegate to recover(), not the deprecated initiateRecovery default");
    }

    @Test
    void initiateRecoveryReturnsFalseWhenStrategyDeclines() throws Exception {
        var config = FaultConfiguration.defaultConfig();
        var partitionId = UUID.randomUUID();
        var recovery = new RecordingRecovery(config, RecordingRecovery.Outcome.SUCCEED, false);
        var handler = handlerWith(recovery, partitionId);

        var result = handler.initiateRecovery(partitionId).get(5, TimeUnit.SECONDS);

        assertFalse(result, "Recovery must report false when canRecover() is false");
        assertFalse(recovery.recoverCalled.get(),
            "recover() must not be called when the strategy declines via canRecover()");
    }

    @Test
    void initiateRecoveryContainsExceptionalRecoveryAsFalse() throws Exception {
        var config = FaultConfiguration.defaultConfig();
        var partitionId = UUID.randomUUID();
        var recovery = new RecordingRecovery(config, RecordingRecovery.Outcome.THROW, true);
        var handler = handlerWith(recovery, partitionId);

        // The returned future must complete normally with false, not propagate the exception.
        var result = handler.initiateRecovery(partitionId).get(5, TimeUnit.SECONDS);

        assertFalse(result, "An exceptional recover() must be contained and reported as false");
    }

    @Test
    void initiateRecoveryReturnsFalseOnRecoveryFailureResult() throws Exception {
        var config = FaultConfiguration.defaultConfig();
        var partitionId = UUID.randomUUID();
        var recovery = new RecordingRecovery(config, RecordingRecovery.Outcome.FAIL, true);
        var handler = handlerWith(recovery, partitionId);

        var result = handler.initiateRecovery(partitionId).get(5, TimeUnit.SECONDS);

        assertFalse(result, "A RecoveryResult.failure must map to false (non-vacuous success check)");
        assertTrue(recovery.recoverCalled.get(), "recover() must have been invoked");
    }
}
