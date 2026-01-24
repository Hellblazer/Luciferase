package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Controlled failure injection framework for E2E distributed system testing.
 * <p>
 * Provides deterministic and probabilistic fault injection across multiple dimensions:
 * <ul>
 *   <li>Partition failures (single, burst, cascading)</li>
 *   <li>Network faults (packet loss, latency, timeouts)</li>
 *   <li>Clock skew/drift for time-dependent scenarios</li>
 *   <li>Resource constraints (memory pressure, thread limits)</li>
 * </ul>
 * <p>
 * All injected faults are recorded for post-test verification and debugging.
 * <p>
 * <b>Thread Safety</b>: This class is thread-safe. Multiple threads can inject
 * faults concurrently.
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var injector = new FaultInjector(testClock);
 *
 * // Inject partition failure
 * injector.injectPartitionFailure(partitionId, 100); // 100ms delay
 *
 * // Inject network fault
 * injector.injectPacketLoss(0.10); // 10% loss rate
 * injector.injectLatency(500); // 500ms additional latency
 *
 * // Inject clock skew
 * injector.injectClockSkew(1000); // 1 second skew
 *
 * // Get all injected faults for verification
 * var faults = injector.getInjectedFaults();
 * }</pre>
 */
public class FaultInjector {

    private final TestClock clock;
    private final List<InjectedFault> injectedFaults;
    private final ExecutorService executor;
    private final Map<String, Consumer<UUID>> partitionFailureHandlers;
    private final AtomicInteger faultCounter;

    // Network fault state
    private volatile double packetLossRate = 0.0;
    private volatile long additionalLatencyMs = 0;
    private volatile boolean networkPartitioned = false;

    // Resource constraint state
    private volatile long memoryPressureMB = 0;
    private volatile int threadPoolLimit = Integer.MAX_VALUE;

    // Clock manipulation state
    private volatile long clockSkewMs = 0;
    private volatile boolean clockDrifting = false;
    private volatile long driftRateMs = 0; // ms per second

    /**
     * Create FaultInjector with specified TestClock.
     *
     * @param clock test clock for time control
     */
    public FaultInjector(TestClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.injectedFaults = new CopyOnWriteArrayList<>();
        this.executor = Executors.newCachedThreadPool();
        this.partitionFailureHandlers = new ConcurrentHashMap<>();
        this.faultCounter = new AtomicInteger(0);
    }

    /**
     * Register partition failure handler.
     * <p>
     * Handler will be invoked when partition failure is injected.
     *
     * @param handlerName unique handler name
     * @param handler callback to invoke on partition failure
     */
    public void registerPartitionFailureHandler(String handlerName, Consumer<UUID> handler) {
        Objects.requireNonNull(handlerName, "handlerName cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        partitionFailureHandlers.put(handlerName, handler);
    }

    /**
     * Inject partition failure with optional delay.
     * <p>
     * If delay is 0, partition fails immediately. Otherwise, failure is scheduled
     * after the specified delay.
     *
     * @param partitionId partition UUID to fail
     * @param delayMs delay in milliseconds (0 for immediate)
     * @return InjectedFault record for verification
     */
    public InjectedFault injectPartitionFailure(UUID partitionId, long delayMs) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be non-negative, got: " + delayMs);
        }

        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.PARTITION_FAILURE,
            Map.of("partitionId", partitionId, "delayMs", delayMs)
        );

        injectedFaults.add(fault);

        if (delayMs == 0) {
            // Immediate failure
            invokePartitionFailureHandlers(partitionId);
        } else {
            // Delayed failure
            executor.submit(() -> {
                try {
                    Thread.sleep(delayMs);
                    invokePartitionFailureHandlers(partitionId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        return fault;
    }

    /**
     * Inject multiple partition failures with staggered delays.
     * <p>
     * Useful for cascading failure scenarios where failures occur in sequence.
     *
     * @param partitions list of partition UUIDs to fail
     * @param delayBetweenMs delay between successive failures
     * @return list of InjectedFault records
     */
    public List<InjectedFault> injectCascadingFailures(List<UUID> partitions, long delayBetweenMs) {
        Objects.requireNonNull(partitions, "partitions cannot be null");
        if (delayBetweenMs < 0) {
            throw new IllegalArgumentException("delayBetweenMs must be non-negative, got: " + delayBetweenMs);
        }

        var faults = new ArrayList<InjectedFault>();
        for (var i = 0; i < partitions.size(); i++) {
            var delay = i * delayBetweenMs;
            var fault = injectPartitionFailure(partitions.get(i), delay);
            faults.add(fault);
        }

        return faults;
    }

    /**
     * Inject burst of concurrent partition failures.
     * <p>
     * All partitions fail simultaneously at the specified time.
     *
     * @param partitions set of partition UUIDs to fail
     * @param delayMs delay before burst occurs
     * @return list of InjectedFault records
     */
    public List<InjectedFault> injectBurstFailures(Set<UUID> partitions, long delayMs) {
        Objects.requireNonNull(partitions, "partitions cannot be null");
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be non-negative, got: " + delayMs);
        }

        var faults = new ArrayList<InjectedFault>();
        for (var partition : partitions) {
            var fault = injectPartitionFailure(partition, delayMs);
            faults.add(fault);
        }

        return faults;
    }

    /**
     * Inject probabilistic packet loss.
     * <p>
     * Network messages will be randomly dropped with the specified probability.
     * Note: Actual packet dropping must be implemented by network layer using
     * {@link #shouldDropPacket()}.
     *
     * @param lossRate packet loss rate (0.0 to 1.0)
     * @return InjectedFault record
     */
    public InjectedFault injectPacketLoss(double lossRate) {
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("lossRate must be in [0.0, 1.0], got: " + lossRate);
        }

        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.PACKET_LOSS,
            Map.of("lossRate", lossRate)
        );

        injectedFaults.add(fault);
        this.packetLossRate = lossRate;

        return fault;
    }

    /**
     * Inject fixed network latency.
     * <p>
     * All network messages will experience additional delay.
     * Note: Actual delay must be implemented by network layer using
     * {@link #getAdditionalLatencyMs()}.
     *
     * @param latencyMs additional latency in milliseconds
     * @return InjectedFault record
     */
    public InjectedFault injectLatency(long latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be non-negative, got: " + latencyMs);
        }

        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.NETWORK_LATENCY,
            Map.of("latencyMs", latencyMs)
        );

        injectedFaults.add(fault);
        this.additionalLatencyMs = latencyMs;

        return fault;
    }

    /**
     * Inject network partition.
     * <p>
     * Simulates complete network disconnect between subsets of partitions.
     *
     * @return InjectedFault record
     */
    public InjectedFault injectNetworkPartition() {
        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.NETWORK_PARTITION,
            Map.of("partitioned", true)
        );

        injectedFaults.add(fault);
        this.networkPartitioned = true;

        return fault;
    }

    /**
     * Inject clock skew.
     * <p>
     * Jumps the test clock forward or backward by the specified amount.
     *
     * @param skewMs clock skew in milliseconds (positive = ahead, negative = behind)
     * @return InjectedFault record
     */
    public InjectedFault injectClockSkew(long skewMs) {
        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.CLOCK_SKEW,
            Map.of("skewMs", skewMs)
        );

        injectedFaults.add(fault);
        this.clockSkewMs = skewMs;

        // Jump clock using advance (supports negative values for going backward)
        if (skewMs != 0) {
            clock.advance(skewMs);
        }

        return fault;
    }

    /**
     * Inject clock drift.
     * <p>
     * Clock will gradually drift at the specified rate. This simulates gradual
     * clock desynchronization over time.
     *
     * @param driftRateMs drift rate in milliseconds per second
     * @return InjectedFault record
     */
    public InjectedFault injectClockDrift(long driftRateMs) {
        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.CLOCK_DRIFT,
            Map.of("driftRateMs", driftRateMs)
        );

        injectedFaults.add(fault);
        this.clockDrifting = true;
        this.driftRateMs = driftRateMs;

        // Start drift simulation in background
        executor.submit(() -> {
            while (clockDrifting) {
                try {
                    Thread.sleep(1000); // Check every second
                    if (clockDrifting) {
                        clock.advance(driftRateMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        return fault;
    }

    /**
     * Stop clock drift.
     */
    public void stopClockDrift() {
        this.clockDrifting = false;
    }

    /**
     * Inject memory pressure constraint.
     * <p>
     * Simulates reduced available memory. Implementation must monitor memory
     * usage and enforce the constraint.
     *
     * @param pressureMB memory pressure in megabytes
     * @return InjectedFault record
     */
    public InjectedFault injectMemoryPressure(long pressureMB) {
        if (pressureMB < 0) {
            throw new IllegalArgumentException("pressureMB must be non-negative, got: " + pressureMB);
        }

        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.MEMORY_PRESSURE,
            Map.of("pressureMB", pressureMB)
        );

        injectedFaults.add(fault);
        this.memoryPressureMB = pressureMB;

        return fault;
    }

    /**
     * Inject thread pool starvation.
     * <p>
     * Limits available thread pool size. Implementation must enforce the limit.
     *
     * @param maxThreads maximum thread pool size
     * @return InjectedFault record
     */
    public InjectedFault injectThreadStarvation(int maxThreads) {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("maxThreads must be positive, got: " + maxThreads);
        }

        var faultId = faultCounter.incrementAndGet();
        var timestamp = clock.currentTimeMillis();
        var fault = new InjectedFault(
            faultId,
            timestamp,
            FaultType.THREAD_STARVATION,
            Map.of("maxThreads", maxThreads)
        );

        injectedFaults.add(fault);
        this.threadPoolLimit = maxThreads;

        return fault;
    }

    /**
     * Check if packet should be dropped based on current packet loss rate.
     * <p>
     * Uses random probability to determine if packet should be dropped.
     * Thread-safe: Uses ThreadLocalRandom for thread-local randomness.
     *
     * @return true if packet should be dropped
     */
    public boolean shouldDropPacket() {
        if (packetLossRate == 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < packetLossRate;
    }

    /**
     * Get current additional network latency in milliseconds.
     *
     * @return additional latency (0 if no latency fault injected)
     */
    public long getAdditionalLatencyMs() {
        return additionalLatencyMs;
    }

    /**
     * Check if network is currently partitioned.
     *
     * @return true if network partition injected
     */
    public boolean isNetworkPartitioned() {
        return networkPartitioned;
    }

    /**
     * Get current memory pressure in megabytes.
     *
     * @return memory pressure (0 if no pressure injected)
     */
    public long getMemoryPressureMB() {
        return memoryPressureMB;
    }

    /**
     * Get current thread pool limit.
     *
     * @return thread pool limit (Integer.MAX_VALUE if no limit)
     */
    public int getThreadPoolLimit() {
        return threadPoolLimit;
    }

    /**
     * Get all injected faults in chronological order.
     *
     * @return unmodifiable list of injected faults
     */
    public List<InjectedFault> getInjectedFaults() {
        return List.copyOf(injectedFaults);
    }

    /**
     * Get injected faults of specific type.
     *
     * @param type fault type to filter by
     * @return list of matching faults
     */
    public List<InjectedFault> getFaultsByType(FaultType type) {
        Objects.requireNonNull(type, "type cannot be null");
        return injectedFaults.stream()
            .filter(f -> f.type() == type)
            .toList();
    }

    /**
     * Clear all injected faults and reset injector state.
     */
    public void reset() {
        injectedFaults.clear();
        packetLossRate = 0.0;
        additionalLatencyMs = 0;
        networkPartitioned = false;
        memoryPressureMB = 0;
        threadPoolLimit = Integer.MAX_VALUE;
        clockSkewMs = 0;
        clockDrifting = false;
        driftRateMs = 0;
        faultCounter.set(0);
    }

    /**
     * Shutdown injector and cleanup resources.
     * <p>
     * Stops all background tasks and shuts down executor.
     */
    public void shutdown() {
        clockDrifting = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Invoke all registered partition failure handlers.
     */
    private void invokePartitionFailureHandlers(UUID partitionId) {
        for (var handler : partitionFailureHandlers.values()) {
            try {
                handler.accept(partitionId);
            } catch (Exception e) {
                // Log but don't throw - one handler failure shouldn't affect others
                System.err.println("Partition failure handler error: " + e.getMessage());
            }
        }
    }
}
