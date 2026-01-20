/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based metrics exporter supporting CSV and JSON formats.
 * <p>
 * Thread-safe implementation with graceful error handling. Failed exports
 * log warnings but don't throw exceptions.
 *
 * @author hal.hildebrand
 */
public class FileMetricsExporter implements MetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(FileMetricsExporter.class);

    private final Path outputPath;
    private final Format format;
    private final Lock lock = new ReentrantLock();
    private boolean closed = false;
    private boolean headerWritten = false;
    private boolean firstJsonEntry = true;

    /**
     * Export format enumeration.
     */
    public enum Format {
        CSV,
        JSON
    }

    /**
     * Create a new file metrics exporter.
     *
     * @param outputPath path to output file
     * @param format     export format (CSV or JSON)
     */
    public FileMetricsExporter(Path outputPath, Format format) {
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath cannot be null");
        this.format = Objects.requireNonNull(format, "format cannot be null");

        // Create parent directories if needed
        try {
            var parent = outputPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Initialize file for JSON format
            if (format == Format.JSON) {
                Files.writeString(outputPath, "[", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to initialize exporter at {}: {}", outputPath, e.getMessage());
        }
    }

    @Override
    public void exportCompression(CompressionMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Exporter is closed");
            }

            if (format == Format.CSV) {
                exportCompressionCSV(metrics);
            } else {
                exportCompressionJSON(metrics);
            }
        } catch (IllegalStateException e) {
            throw e; // Re-throw state exceptions
        } catch (Exception e) {
            log.warn("Failed to export compression metrics: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void exportCache(CacheMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Exporter is closed");
            }

            if (format == Format.CSV) {
                exportCacheCSV(metrics);
            } else {
                exportCacheJSON(metrics);
            }
        } catch (IllegalStateException e) {
            throw e; // Re-throw state exceptions
        } catch (Exception e) {
            log.warn("Failed to export cache metrics: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return; // Idempotent
            }

            // Close JSON array
            if (format == Format.JSON) {
                try {
                    Files.writeString(outputPath, "\n]", StandardOpenOption.APPEND);
                } catch (IOException e) {
                    log.warn("Failed to close JSON array: {}", e.getMessage());
                }
            }

            closed = true;
        } finally {
            lock.unlock();
        }
    }

    private void exportCompressionCSV(CompressionMetrics metrics) throws IOException {
        if (!headerWritten) {
            var header = "timestamp,sourceNodeCount,compressedNodeCount,uniqueInternalNodes,uniqueLeafNodes,"
                         + "compressionRatio,compressionPercent,memorySavedBytes,buildTimeMs\n";
            Files.writeString(outputPath, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            headerWritten = true;
        }

        var line = String.format("%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d\n", metrics.timestamp_value(),
                                 metrics.sourceNodeCount(), metrics.compressedNodeCount(),
                                 metrics.uniqueInternalNodes(), metrics.uniqueLeafNodes(), metrics.compressionRatio(),
                                 metrics.compressionPercent(), metrics.memorySavedBytes(),
                                 metrics.buildTime().toMillis());

        Files.writeString(outputPath, line, StandardOpenOption.APPEND);
    }

    private void exportCompressionJSON(CompressionMetrics metrics) throws IOException {
        if (!firstJsonEntry) {
            Files.writeString(outputPath, ",", StandardOpenOption.APPEND);
        }
        firstJsonEntry = false;

        var json = String.format(
        """
        {
          "timestamp": %d,
          "sourceNodeCount": %d,
          "compressedNodeCount": %d,
          "uniqueInternalNodes": %d,
          "uniqueLeafNodes": %d,
          "compressionRatio": %.2f,
          "compressionPercent": %.2f,
          "memorySavedBytes": %d,
          "buildTimeMs": %d
        }""", metrics.timestamp_value(), metrics.sourceNodeCount(), metrics.compressedNodeCount(),
        metrics.uniqueInternalNodes(), metrics.uniqueLeafNodes(), metrics.compressionRatio(),
        metrics.compressionPercent(), metrics.memorySavedBytes(), metrics.buildTime().toMillis());

        Files.writeString(outputPath, json, StandardOpenOption.APPEND);
    }

    private void exportCacheCSV(CacheMetrics metrics) throws IOException {
        if (!headerWritten) {
            var header = "hitCount,missCount,evictionCount,hitRate\n";
            Files.writeString(outputPath, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            headerWritten = true;
        }

        var line = String.format("%d,%d,%d,%.4f\n", metrics.hitCount(), metrics.missCount(), metrics.evictionCount(),
                                 metrics.hitRate());

        Files.writeString(outputPath, line, StandardOpenOption.APPEND);
    }

    private void exportCacheJSON(CacheMetrics metrics) throws IOException {
        if (!firstJsonEntry) {
            Files.writeString(outputPath, ",", StandardOpenOption.APPEND);
        }
        firstJsonEntry = false;

        var json = String.format(
        """
        {
          "hitCount": %d,
          "missCount": %d,
          "evictionCount": %d,
          "hitRate": %.4f
        }""", metrics.hitCount(), metrics.missCount(), metrics.evictionCount(), metrics.hitRate());

        Files.writeString(outputPath, json, StandardOpenOption.APPEND);
    }
}
