package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

/**
 * Categories of faults that can be injected by {@link FaultSimulator}.
 * <p>
 * Each type represents a different dimension of failure that can occur
 * in a distributed system.
 */
public enum FaultType {
    /**
     * Partition failure - complete loss of a partition node.
     * <p>
     * Parameters:
     * <ul>
     *   <li>partitionId (UUID): failed partition identifier</li>
     *   <li>delayMs (long): delay before failure occurs</li>
     * </ul>
     */
    PARTITION_FAILURE,

    /**
     * Probabilistic packet loss - random network message drops.
     * <p>
     * Parameters:
     * <ul>
     *   <li>lossRate (double): packet loss rate [0.0, 1.0]</li>
     * </ul>
     */
    PACKET_LOSS,

    /**
     * Fixed network latency - additional delay on all messages.
     * <p>
     * Parameters:
     * <ul>
     *   <li>latencyMs (long): additional latency in milliseconds</li>
     * </ul>
     */
    NETWORK_LATENCY,

    /**
     * Network partition - complete disconnect between subsets.
     * <p>
     * Parameters:
     * <ul>
     *   <li>partitioned (boolean): network partition active</li>
     * </ul>
     */
    NETWORK_PARTITION,

    /**
     * Clock skew - time offset between nodes.
     * <p>
     * Parameters:
     * <ul>
     *   <li>skewMs (long): clock offset in milliseconds</li>
     * </ul>
     */
    CLOCK_SKEW,

    /**
     * Clock drift - gradual clock desynchronization.
     * <p>
     * Parameters:
     * <ul>
     *   <li>driftRateMs (long): drift rate in ms per second</li>
     * </ul>
     */
    CLOCK_DRIFT,

    /**
     * Memory pressure - reduced available memory.
     * <p>
     * Parameters:
     * <ul>
     *   <li>pressureMB (long): memory pressure in megabytes</li>
     * </ul>
     */
    MEMORY_PRESSURE,

    /**
     * Thread pool starvation - limited thread availability.
     * <p>
     * Parameters:
     * <ul>
     *   <li>maxThreads (int): maximum thread pool size</li>
     * </ul>
     */
    THREAD_STARVATION
}
