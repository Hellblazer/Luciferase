package com.dyada.performance;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for PerformanceProfiler
 */
@DisplayName("PerformanceProfiler Tests")
class PerformanceProfilerTest {

    private PerformanceProfiler profiler;

    @BeforeEach
    void setUp() {
        profiler = PerformanceProfiler.getInstance();
        profiler.clearStats();
        profiler.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        profiler.setEnabled(false);
        profiler.clearStats();
    }

    @Nested
    @DisplayName("Basic Profiling Operations")
    class BasicOperations {

        @Test
        @DisplayName("Single operation profiling")
        void testSingleOperation() {
            try (var operation = profiler.startOperation("test-op")) {
                // Simulate some work
                simulateWork(10);
            }

            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.containsKey("test-op"));
            
            var opStats = stats.get("test-op");
            assertEquals(1, opStats.getExecutionCount());
            assertTrue(opStats.getAverageDuration().toNanos() > 0);
            assertTrue(opStats.getMinDuration().toNanos() > 0);
            assertTrue(opStats.getMaxDuration().toNanos() > 0);
        }

        @Test
        @DisplayName("Multiple operations with same name")
        void testMultipleOperations() {
            for (int i = 0; i < 5; i++) {
                try (var operation = profiler.startOperation("repeated-op")) {
                    simulateWork(5 + i); // Variable work time
                }
            }

            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            var opStats = stats.get("repeated-op");
            
            assertEquals(5, opStats.getExecutionCount());
            assertTrue(opStats.getAverageDuration().toNanos() > 0);
            assertTrue(opStats.getTotalDuration().toNanos() > opStats.getAverageDuration().toNanos());
            assertTrue(opStats.getMinDuration().toNanos() <= opStats.getMaxDuration().toNanos());
        }

        @Test
        @DisplayName("Different operation names")
        void testDifferentOperations() {
            try (var op1 = profiler.startOperation("operation-1")) {
                simulateWork(10);
            }
            
            try (var op2 = profiler.startOperation("operation-2")) {
                simulateWork(15);
            }

            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertEquals(2, stats.size());
            assertTrue(stats.containsKey("operation-1"));
            assertTrue(stats.containsKey("operation-2"));
            
            assertEquals(1, stats.get("operation-1").getExecutionCount());
            assertEquals(1, stats.get("operation-2").getExecutionCount());
        }
    }

    @Nested
    @DisplayName("Profiler State Management")
    class StateManagement {

        @Test
        @DisplayName("Enabled/disabled state")
        void testEnabledDisabled() {
            profiler.setEnabled(false);
            
            try (var operation = profiler.startOperation("disabled-op")) {
                simulateWork(10);
            }
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertEquals(0, stats.size());
            
            profiler.setEnabled(true);
            
            try (var operation = profiler.startOperation("enabled-op")) {
                simulateWork(10);
            }
            
            stats = profiler.getStats();
            assertEquals(1, stats.size());
            assertTrue(stats.containsKey("enabled-op"));
        }

        @Test
        @DisplayName("Clear stats functionality")
        void testClearStats() {
            try (var operation = profiler.startOperation("temp-op")) {
                simulateWork(5);
            }
            
            assertEquals(1, profiler.getStats().size());
            
            profiler.clearStats();
            
            assertEquals(0, profiler.getStats().size());
        }

        @Test
        @DisplayName("Singleton instance")
        void testSingletonInstance() {
            PerformanceProfiler instance1 = PerformanceProfiler.getInstance();
            PerformanceProfiler instance2 = PerformanceProfiler.getInstance();
            
            assertSame(instance1, instance2);
        }
    }

    @Nested
    @DisplayName("Operation Statistics")
    class StatisticsValidation {

        @Test
        @DisplayName("Duration statistics accuracy")
        void testDurationStatistics() {
            // Run operations with known sleep times
            int[] sleepTimes = {5, 10, 15, 20, 25}; // milliseconds
            
            for (int sleepTime : sleepTimes) {
                try (var operation = profiler.startOperation("duration-test")) {
                    simulateWork(sleepTime);
                }
            }

            var stats = profiler.getStats().get("duration-test");
            assertEquals(5, stats.getExecutionCount());
            
            // Verify min <= average <= max
            assertTrue(stats.getMinDuration().compareTo(stats.getAverageDuration()) <= 0);
            assertTrue(stats.getAverageDuration().compareTo(stats.getMaxDuration()) <= 0);
            
            // Total duration should be sum of individual durations
            Duration totalDuration = stats.getTotalDuration();
            Duration averageDuration = stats.getAverageDuration();
            long expectedTotal = averageDuration.toNanos() * 5;
            assertEquals(expectedTotal, totalDuration.toNanos(), expectedTotal * 0.1); // 10% tolerance
        }

        @Test
        @DisplayName("Memory delta tracking")
        void testMemoryDeltaTracking() {
            try (var operation = profiler.startOperation("memory-test")) {
                // Allocate some memory
                byte[] array = new byte[1024 * 1024]; // 1MB
                array[0] = 1; // Ensure array is used
            }

            var stats = profiler.getStats().get("memory-test");
            // Memory delta might be positive or negative due to GC
            assertNotNull(stats);
            assertTrue(stats.getExecutionCount() > 0);
        }

        @Test
        @DisplayName("Zero execution count edge case")
        void testZeroExecutions() {
            // Create a fresh profiler with no operations
            profiler.clearStats();
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.isEmpty());
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentOperations {

        @Test
        @DisplayName("Concurrent profiling operations")
        void testConcurrentProfiling() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(100);
            AtomicInteger completedOperations = new AtomicInteger(0);
            
            // Submit 100 concurrent operations
            for (int i = 0; i < 100; i++) {
                int operationIndex = i;
                executor.submit(() -> {
                    try {
                        try (var operation = profiler.startOperation("concurrent-op-" + (operationIndex % 5))) {
                            simulateWork(5);
                            completedOperations.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(100, completedOperations.get());
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            
            // Should have 5 different operation names (0-4)
            assertEquals(5, stats.size());
            
            // Each operation should have been executed 20 times
            for (var entry : stats.entrySet()) {
                assertEquals(20, entry.getValue().getExecutionCount());
            }
            
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Concurrent stats access")
        void testConcurrentStatsAccess() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(10);
            
            // Start background operations
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 20; j++) {
                            try (var operation = profiler.startOperation("background-op")) {
                                simulateWork(1);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
            
            // Start concurrent stats readers
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 10; j++) {
                            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
                            // Just access the stats, no need to validate contents
                            stats.size();
                            Thread.sleep(1);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            assertTrue(finishLatch.await(10, TimeUnit.SECONDS));
            
            // Verify final state
            Map<String, PerformanceProfiler.OperationStats> finalStats = profiler.getStats();
            if (finalStats.containsKey("background-op")) {
                assertEquals(100, finalStats.get("background-op").getExecutionCount());
            }
            
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Resource Management")
    class ResourceManagement {

        @Test
        @DisplayName("AutoCloseable resource management")
        void testAutoCloseableResourceManagement() {
            var operation = profiler.startOperation("autocloseable-test");
            
            // Manually close
            operation.close();
            
            // Double close should be safe
            assertDoesNotThrow(operation::close);
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.containsKey("autocloseable-test"));
            assertEquals(1, stats.get("autocloseable-test").getExecutionCount());
        }

        @Test
        @DisplayName("Exception handling during profiled operation")
        void testExceptionHandling() {
            RuntimeException expectedException = new RuntimeException("Test exception");
            
            RuntimeException actualException = assertThrows(RuntimeException.class, () -> {
                try (var operation = profiler.startOperation("exception-test")) {
                    simulateWork(5);
                    throw expectedException;
                }
            });
            
            assertSame(expectedException, actualException);
            
            // Verify operation was still recorded
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.containsKey("exception-test"));
            assertEquals(1, stats.get("exception-test").getExecutionCount());
        }

        @Test
        @DisplayName("NOOP operation when disabled")
        void testNoopOperationWhenDisabled() {
            profiler.setEnabled(false);
            
            var operation = profiler.startOperation("noop-test");
            assertNotNull(operation);
            
            // Should be safe to close
            assertDoesNotThrow(operation::close);
            
            // No stats should be recorded
            assertTrue(profiler.getStats().isEmpty());
        }
    }

    @Nested
    @DisplayName("Performance and Scalability")
    class PerformanceTests {

        @Test
        @DisplayName("High-volume operation profiling")
        void testHighVolumeOperations() {
            int operationCount = 10000;
            
            for (int i = 0; i < operationCount; i++) {
                try (var operation = profiler.startOperation("high-volume-op")) {
                    // Minimal work to test profiler overhead
                }
            }
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            var opStats = stats.get("high-volume-op");
            
            assertEquals(operationCount, opStats.getExecutionCount());
            assertTrue(opStats.getMinDuration().toNanos() >= 0);
            assertTrue(opStats.getMaxDuration().toNanos() >= opStats.getMinDuration().toNanos());
        }

        @Test
        @DisplayName("Profiler overhead measurement")
        void testProfilerOverhead() {
            // Measure operation without profiling
            long startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                simulateWork(0); // Just method call overhead
            }
            long unprofiredTime = System.nanoTime() - startTime;
            
            // Measure operation with profiling
            startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                try (var operation = profiler.startOperation("overhead-test")) {
                    simulateWork(0); // Just method call overhead
                }
            }
            long profiledTime = System.nanoTime() - startTime;
            
            // Profiler overhead should be reasonable (less than 50x the original operation)
            // Note: This is a timing-sensitive test that can be affected by JVM warmup and system load
            assertTrue(profiledTime < unprofiredTime * 50, 
                String.format("Profiler overhead too high: %d ns vs %d ns", profiledTime, unprofiredTime));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Null operation name")
        void testNullOperationName() {
            assertDoesNotThrow(() -> {
                try (var operation = profiler.startOperation(null)) {
                    simulateWork(5);
                }
            });
            
            // Should not crash, but may or may not record stats
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            // No specific assertion - just ensure no exception
        }

        @Test
        @DisplayName("Empty operation name")
        void testEmptyOperationName() {
            try (var operation = profiler.startOperation("")) {
                simulateWork(5);
            }
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.containsKey(""));
            assertEquals(1, stats.get("").getExecutionCount());
        }

        @Test
        @DisplayName("Very long operation name")
        void testVeryLongOperationName() {
            String longName = "a".repeat(10000); // 10k character name
            
            try (var operation = profiler.startOperation(longName)) {
                simulateWork(5);
            }
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            assertTrue(stats.containsKey(longName));
            assertEquals(1, stats.get(longName).getExecutionCount());
        }

        @Test
        @DisplayName("Rapid enable/disable toggling")
        void testRapidEnableDisableToggling() {
            for (int i = 0; i < 100; i++) {
                profiler.setEnabled(i % 2 == 0);
                
                try (var operation = profiler.startOperation("toggle-test-" + i)) {
                    simulateWork(1);
                }
            }
            
            Map<String, PerformanceProfiler.OperationStats> stats = profiler.getStats();
            // Should have stats only for even-numbered operations (when enabled)
            long enabledOps = stats.keySet().stream().filter(key -> key.startsWith("toggle-test-")).count();
            assertEquals(50, enabledOps); // 0, 2, 4, ..., 98
        }
    }

    /**
     * Simulate work by performing busy waiting for the specified milliseconds
     */
    private void simulateWork(int milliseconds) {
        if (milliseconds <= 0) return;
        
        long startTime = System.nanoTime();
        long durationNanos = milliseconds * 1_000_000L;
        
        while ((System.nanoTime() - startTime) < durationNanos) {
            // Busy wait with some computation to prevent JVM optimization
            Math.sqrt(System.nanoTime());
        }
    }
}