/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first implementation of FileMetricsExporter - CSV/JSON export functionality.
 *
 * @author hal.hildebrand
 */
class MetricsExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void testCSVExportCompressionMetrics() throws Exception {
        var outputFile = tempDir.resolve("compression_metrics.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        exporter.exportCompression(metrics);
        exporter.close();

        assertTrue(Files.exists(outputFile));
        var lines = Files.readAllLines(outputFile);

        // CSV header + 1 data line
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("timestamp"));
        assertTrue(lines.get(0).contains("sourceNodeCount"));
        assertTrue(lines.get(0).contains("compressedNodeCount"));

        var dataLine = lines.get(1);
        assertTrue(dataLine.contains("100")); // sourceNodeCount
        assertTrue(dataLine.contains("500")); // compressedNodeCount
    }

    @Test
    void testCSVExportCacheMetrics() throws Exception {
        var outputFile = tempDir.resolve("cache_metrics.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordHit();
        collector.recordMiss();

        exporter.exportCache(collector.getMetrics());
        exporter.close();

        assertTrue(Files.exists(outputFile));
        var lines = Files.readAllLines(outputFile);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("hitCount"));
        assertTrue(lines.get(0).contains("missCount"));

        var dataLine = lines.get(1);
        assertTrue(dataLine.contains("2")); // hitCount
        assertTrue(dataLine.contains("1")); // missCount
    }

    @Test
    void testJSONExportCompressionMetrics() throws Exception {
        var outputFile = tempDir.resolve("compression_metrics.json");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.JSON);

        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        exporter.exportCompression(metrics);
        exporter.close();

        assertTrue(Files.exists(outputFile));
        var content = Files.readString(outputFile);

        assertTrue(content.contains("\"sourceNodeCount\": 500"));
        assertTrue(content.contains("\"compressedNodeCount\": 100"));
        assertTrue(content.contains("\"compressionRatio\":"));
    }

    @Test
    void testJSONExportCacheMetrics() throws Exception {
        var outputFile = tempDir.resolve("cache_metrics.json");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.JSON);

        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordMiss();

        exporter.exportCache(collector.getMetrics());
        exporter.close();

        assertTrue(Files.exists(outputFile));
        var content = Files.readString(outputFile);

        assertTrue(content.contains("\"hitCount\": 1"));
        assertTrue(content.contains("\"missCount\": 1"));
        assertTrue(content.contains("\"hitRate\":"));
    }

    @Test
    void testAppendModeCSV() throws Exception {
        var outputFile = tempDir.resolve("append_test.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        var metrics1 = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        var metrics2 = new CompressionMetrics(200, 600, 20, 60, Duration.ofMillis(456));

        exporter.exportCompression(metrics1);
        exporter.exportCompression(metrics2);
        exporter.close();

        var lines = Files.readAllLines(outputFile);
        assertEquals(3, lines.size()); // Header + 2 data lines
        assertTrue(lines.get(1).contains("100"));
        assertTrue(lines.get(2).contains("200"));
    }

    @Test
    void testAppendModeJSON() throws Exception {
        var outputFile = tempDir.resolve("append_test.json");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.JSON);

        var metrics1 = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        var metrics2 = new CompressionMetrics(200, 600, 20, 60, Duration.ofMillis(456));

        exporter.exportCompression(metrics1);
        exporter.exportCompression(metrics2);
        exporter.close();

        var content = Files.readString(outputFile);
        assertTrue(content.contains("\"sourceNodeCount\": 500"));
        assertTrue(content.contains("\"sourceNodeCount\": 600"));
    }

    @Test
    void testParentDirectoryCreation() throws Exception {
        var nestedPath = tempDir.resolve("deeply/nested/path/metrics.csv");
        var exporter = new FileMetricsExporter(nestedPath, FileMetricsExporter.Format.CSV);

        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        exporter.exportCompression(metrics);
        exporter.close();

        assertTrue(Files.exists(nestedPath));
        assertTrue(Files.isDirectory(nestedPath.getParent()));
    }

    @Test
    void testCloseIdempotence() throws Exception {
        var outputFile = tempDir.resolve("close_test.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        exporter.close();
        exporter.close(); // Should not throw
        exporter.close(); // Should not throw
    }

    @Test
    void testExportAfterCloseThrows() throws Exception {
        var outputFile = tempDir.resolve("closed_export.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);
        exporter.close();

        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        assertThrows(IllegalStateException.class, () -> {
            exporter.exportCompression(metrics);
        });
    }

    @Test
    void testConcurrentExportsCSV() throws Exception {
        var outputFile = tempDir.resolve("concurrent.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    var metrics = new CompressionMetrics(index, index * 5, 1, 1,
                                                          Duration.ofMillis(index));
                    exporter.exportCompression(metrics);
                } catch (Exception e) {
                    fail("Export failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        exporter.close();

        var lines = Files.readAllLines(outputFile);
        assertEquals(51, lines.size()); // Header + 50 data lines
    }

    @Test
    void testGracefulErrorHandling() {
        // Invalid path (read-only root on Unix)
        var invalidPath = Path.of("/dev/null/invalid/path.csv");
        var exporter = new FileMetricsExporter(invalidPath, FileMetricsExporter.Format.CSV);

        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));

        // Should log warning but not throw
        assertDoesNotThrow(() -> exporter.exportCompression(metrics));
        assertDoesNotThrow(() -> exporter.close());
    }

    @Test
    void testNullMetricsHandling() throws Exception {
        var outputFile = tempDir.resolve("null_test.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        assertThrows(NullPointerException.class, () -> {
            exporter.exportCompression(null);
        });

        assertThrows(NullPointerException.class, () -> {
            exporter.exportCache(null);
        });
    }

    @Test
    void testCSVHeaderOnlyWrittenOnce() throws Exception {
        var outputFile = tempDir.resolve("header_test.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        for (int i = 0; i < 5; i++) {
            var metrics = new CompressionMetrics(i, i * 5, 1, 1, Duration.ofMillis(i));
            exporter.exportCompression(metrics);
        }
        exporter.close();

        var lines = Files.readAllLines(outputFile);
        var headerCount = lines.stream().filter(line -> line.contains("timestamp")).count();
        assertEquals(1, headerCount); // Header written only once
    }

    @Test
    void testJSONArrayFormatting() throws Exception {
        var outputFile = tempDir.resolve("json_array.json");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.JSON);

        var metrics1 = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        var metrics2 = new CompressionMetrics(200, 600, 20, 60, Duration.ofMillis(456));

        exporter.exportCompression(metrics1);
        exporter.exportCompression(metrics2);
        exporter.close();

        var content = Files.readString(outputFile);
        assertTrue(content.startsWith("[")); // JSON array
        assertTrue(content.endsWith("]"));
        assertTrue(content.contains(",")); // Comma between entries
    }

    @Test
    void testMixedMetricsExport() throws Exception {
        var outputFile = tempDir.resolve("mixed_metrics.csv");
        var exporter = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);

        var compressionMetrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordMiss();

        exporter.exportCompression(compressionMetrics);
        exporter.exportCache(collector.getMetrics());
        exporter.close();

        assertTrue(Files.exists(outputFile));
        var lines = Files.readAllLines(outputFile);
        assertTrue(lines.size() >= 2); // At least some data
    }

    @Test
    void testFileOverwrite() throws Exception {
        var outputFile = tempDir.resolve("overwrite_test.csv");

        // First export
        var exporter1 = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);
        var metrics1 = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(123));
        exporter1.exportCompression(metrics1);
        exporter1.close();

        // Second export (should overwrite)
        var exporter2 = new FileMetricsExporter(outputFile, FileMetricsExporter.Format.CSV);
        var metrics2 = new CompressionMetrics(200, 600, 20, 60, Duration.ofMillis(456));
        exporter2.exportCompression(metrics2);
        exporter2.close();

        var lines = Files.readAllLines(outputFile);
        assertEquals(2, lines.size()); // Header + 1 data line (overwritten)
        assertTrue(lines.get(1).contains("200")); // New data
        assertFalse(lines.get(1).contains("100")); // Old data gone
    }
}
