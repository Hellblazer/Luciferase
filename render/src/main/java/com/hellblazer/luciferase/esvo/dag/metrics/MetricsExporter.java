/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

/**
 * Interface for exporting compression and cache metrics to external destinations.
 * <p>
 * Implementations provide formatting (CSV, JSON, etc.) and output mechanisms
 * (files, databases, network endpoints).
 *
 * @author hal.hildebrand
 */
public interface MetricsExporter extends AutoCloseable {

    /**
     * Export compression metrics.
     *
     * @param metrics compression metrics to export
     * @throws NullPointerException     if metrics is null
     * @throws IllegalStateException    if exporter is closed
     * @throws MetricsExportException   if export fails
     */
    void exportCompression(CompressionMetrics metrics);

    /**
     * Export cache metrics.
     *
     * @param metrics cache metrics to export
     * @throws NullPointerException     if metrics is null
     * @throws IllegalStateException    if exporter is closed
     * @throws MetricsExportException   if export fails
     */
    void exportCache(CacheMetrics metrics);

    /**
     * Close the exporter and release resources.
     * <p>
     * Idempotent - multiple calls have no effect.
     */
    @Override
    void close();

    /**
     * Exception thrown when metric export fails.
     */
    class MetricsExportException extends RuntimeException {
        public MetricsExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
