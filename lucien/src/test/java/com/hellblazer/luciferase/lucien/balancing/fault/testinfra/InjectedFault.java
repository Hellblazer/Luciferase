package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.Map;
import java.util.Objects;

/**
 * Record of an injected fault for post-test verification.
 * <p>
 * Immutable record containing all details about a fault injection event:
 * timestamp, type, and fault-specific parameters.
 * <p>
 * Used by {@link FaultSimulator} to maintain a complete history of all
 * injected faults for debugging and validation purposes.
 *
 * @param faultId unique fault identifier (monotonically increasing)
 * @param timestamp injection timestamp in milliseconds
 * @param type fault type (partition failure, network fault, etc.)
 * @param parameters fault-specific parameters (e.g., partitionId, lossRate)
 */
public record InjectedFault(
    int faultId,
    long timestamp,
    FaultType type,
    Map<String, Object> parameters
) {
    /**
     * Compact constructor with validation.
     */
    public InjectedFault {
        if (faultId <= 0) {
            throw new IllegalArgumentException("faultId must be positive, got: " + faultId);
        }
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(parameters, "parameters cannot be null");

        // Defensive copy for immutability
        parameters = Map.copyOf(parameters);
    }

    /**
     * Get fault parameter by key.
     *
     * @param key parameter key
     * @return parameter value, or null if not found
     */
    public Object getParameter(String key) {
        return parameters.get(key);
    }

    /**
     * Get fault parameter with type cast.
     *
     * @param key parameter key
     * @param type expected parameter type
     * @param <T> parameter type
     * @return parameter value cast to type, or null if not found
     * @throws ClassCastException if parameter cannot be cast to type
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, Class<T> type) {
        var value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * Check if parameter exists.
     *
     * @param key parameter key
     * @return true if parameter exists
     */
    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }

    @Override
    public String toString() {
        return String.format("InjectedFault[id=%d, time=%d, type=%s, params=%s]",
            faultId, timestamp, type, parameters);
    }
}
