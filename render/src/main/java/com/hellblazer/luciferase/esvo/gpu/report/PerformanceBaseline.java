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
package com.hellblazer.luciferase.esvo.gpu.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * D4: GPU Performance Baseline for vendor-specific performance tracking.
 * <p>
 * Stores baseline metrics per vendor/device combination for regression detection.
 * Performance should remain within 5% of baseline to pass validation.
 * <p>
 * Baseline files are stored as JSON in: render/src/test/resources/baselines/
 * <p>
 * File naming: {vendor}_{device_normalized}.json
 * Example: nvidia_rtx3080.json, apple_m4_max.json
 *
 * @author hal.hildebrand
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PerformanceBaseline(
    GPUVendor vendor,
    String deviceName,
    String driverVersion,
    String openCLVersion,
    LocalDateTime timestamp,
    TestParameters testParameters,
    Map<String, KernelMetrics> metrics
) {
    private static final Logger log = LoggerFactory.getLogger(PerformanceBaseline.class);
    private static final ObjectMapper MAPPER = createMapper();

    /** Default tolerance for performance comparison (5%) */
    public static final double DEFAULT_TOLERANCE = 0.05;

    /**
     * Test parameters used during baseline collection
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestParameters(
        int frameWidth,
        int frameHeight,
        int iterations,
        int warmupIterations
    ) {
        /** Default test parameters for consistent baselines */
        public static TestParameters defaults() {
            return new TestParameters(1920, 1080, 100, 10);
        }
    }

    /**
     * Performance metrics for a single kernel
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KernelMetrics(
        double raysPerSecond,
        double avgFrameTimeMs,
        double p99FrameTimeMs
    ) {
        /**
         * Check if measured metrics are within tolerance of this baseline
         *
         * @param measured The measured metrics to compare
         * @param tolerance Allowed deviation (0.05 = 5%)
         * @return true if within tolerance
         */
        public boolean isWithinTolerance(KernelMetrics measured, double tolerance) {
            if (raysPerSecond == 0) return true; // No baseline to compare

            var delta = Math.abs(measured.raysPerSecond - raysPerSecond) / raysPerSecond;
            return delta <= tolerance;
        }

        /**
         * Calculate percentage difference from this baseline
         *
         * @param measured The measured metrics
         * @return Percentage difference (positive = faster, negative = slower)
         */
        public double percentageDifference(KernelMetrics measured) {
            if (raysPerSecond == 0) return 0.0;
            return ((measured.raysPerSecond - raysPerSecond) / raysPerSecond) * 100.0;
        }
    }

    /**
     * Load baseline from file path
     *
     * @param path Path to baseline JSON file
     * @return Loaded baseline or null if not found
     */
    public static PerformanceBaseline load(Path path) {
        if (!Files.exists(path)) {
            log.debug("Baseline file not found: {}", path);
            return null;
        }

        try (var input = Files.newInputStream(path)) {
            return load(input);
        } catch (IOException e) {
            log.warn("Failed to load baseline from {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Load baseline from input stream
     *
     * @param input Input stream containing JSON
     * @return Loaded baseline
     * @throws IOException if parsing fails
     */
    public static PerformanceBaseline load(InputStream input) throws IOException {
        return MAPPER.readValue(input, PerformanceBaseline.class);
    }

    /**
     * Load baseline for a specific vendor and device from classpath
     *
     * @param vendor GPU vendor
     * @param deviceName Device name (will be normalized for filename)
     * @return Loaded baseline or null if not found
     */
    public static PerformanceBaseline loadFromClasspath(GPUVendor vendor, String deviceName) {
        var filename = generateFilename(vendor, deviceName);
        var resourcePath = "/baselines/" + filename;

        try (var input = PerformanceBaseline.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                log.debug("No baseline found on classpath: {}", resourcePath);
                return null;
            }
            return load(input);
        } catch (IOException e) {
            log.warn("Failed to load baseline from classpath {}: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /**
     * Save baseline to file path
     *
     * @param path Path to save to
     * @throws IOException if saving fails
     */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = Files.newOutputStream(path)) {
            save(output);
        }
    }

    /**
     * Save baseline to output stream
     *
     * @param output Output stream to write JSON to
     * @throws IOException if writing fails
     */
    public void save(OutputStream output) throws IOException {
        MAPPER.writeValue(output, this);
    }

    /**
     * Convert to JSON string
     *
     * @return JSON representation
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize baseline", e);
        }
    }

    /**
     * Get metrics for a specific kernel
     *
     * @param kernelName Name of the kernel (e.g., "dag_ray_traversal")
     * @return Metrics or null if not found
     */
    public KernelMetrics getKernelMetrics(String kernelName) {
        return metrics != null ? metrics.get(kernelName) : null;
    }

    /**
     * Check if all kernel metrics are within tolerance of measured values
     *
     * @param measuredMetrics Map of kernel name to measured metrics
     * @param tolerance Allowed deviation
     * @return true if all kernels within tolerance
     */
    public boolean allWithinTolerance(Map<String, KernelMetrics> measuredMetrics, double tolerance) {
        if (metrics == null || metrics.isEmpty()) return true;

        for (var entry : metrics.entrySet()) {
            var measured = measuredMetrics.get(entry.getKey());
            if (measured != null && !entry.getValue().isWithinTolerance(measured, tolerance)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generate filename for this baseline
     *
     * @return Filename like "nvidia_rtx3080.json"
     */
    public String generateFilename() {
        return generateFilename(vendor, deviceName);
    }

    /**
     * Generate normalized filename for vendor/device
     *
     * @param vendor GPU vendor
     * @param deviceName Device name
     * @return Filename like "nvidia_rtx3080.json"
     */
    public static String generateFilename(GPUVendor vendor, String deviceName) {
        var normalized = normalizeDeviceName(deviceName);
        return vendor.name().toLowerCase() + "_" + normalized + ".json";
    }

    /**
     * Normalize device name for use in filename
     * Converts to lowercase, replaces spaces with underscores, removes special chars
     *
     * @param deviceName Original device name
     * @return Normalized name safe for filenames
     */
    public static String normalizeDeviceName(String deviceName) {
        if (deviceName == null) return "unknown";
        return deviceName.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "")
            .replaceAll("_+", "_");
    }

    /**
     * Create a builder for constructing baselines
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PerformanceBaseline
     */
    public static class Builder {
        private GPUVendor vendor;
        private String deviceName;
        private String driverVersion;
        private String openCLVersion;
        private LocalDateTime timestamp = LocalDateTime.now();
        private TestParameters testParameters = TestParameters.defaults();
        private Map<String, KernelMetrics> metrics = Map.of();

        public Builder vendor(GPUVendor vendor) {
            this.vendor = vendor;
            return this;
        }

        public Builder deviceName(String deviceName) {
            this.deviceName = deviceName;
            return this;
        }

        public Builder driverVersion(String driverVersion) {
            this.driverVersion = driverVersion;
            return this;
        }

        public Builder openCLVersion(String openCLVersion) {
            this.openCLVersion = openCLVersion;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder testParameters(TestParameters testParameters) {
            this.testParameters = testParameters;
            return this;
        }

        public Builder metrics(Map<String, KernelMetrics> metrics) {
            this.metrics = metrics;
            return this;
        }

        public PerformanceBaseline build() {
            return new PerformanceBaseline(
                vendor, deviceName, driverVersion, openCLVersion,
                timestamp, testParameters, metrics
            );
        }
    }

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
