/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

/**
 * Domain exception for cross-partition balance exchange failures (RDR-007 Phase 0 Inc3).
 *
 * <p>Decouples lucien-core balancing logic from gRPC's {@code StatusRuntimeException}. The grpc adapter
 * classifies transport failures and rethrows as this exception so retry/timeout policy can be expressed
 * in core without a gRPC dependency:
 * <ul>
 *   <li>{@link #isTransient()} — failure may succeed on retry (e.g. gRPC UNAVAILABLE / RESOURCE_EXHAUSTED)</li>
 *   <li>{@link #isTimeout()} — the exchange exceeded its deadline (e.g. gRPC DEADLINE_EXCEEDED)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class BalanceExchangeException extends Exception {

    private final boolean transientFailure;
    private final boolean timeout;

    public BalanceExchangeException(String message, boolean transientFailure, boolean timeout) {
        super(message);
        this.transientFailure = transientFailure;
        this.timeout = timeout;
    }

    public BalanceExchangeException(String message, Throwable cause, boolean transientFailure, boolean timeout) {
        super(message, cause);
        this.transientFailure = transientFailure;
        this.timeout = timeout;
    }

    /**
     * @return true if the failure is transient and the exchange may succeed on retry
     */
    public boolean isTransient() {
        return transientFailure;
    }

    /**
     * @return true if the exchange exceeded its deadline
     */
    public boolean isTimeout() {
        return timeout;
    }
}
