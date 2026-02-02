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
package com.hellblazer.luciferase.sparse.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * B2: Vendor-Specific Tuning Profile
 *
 * Represents a complete tuning profile for a GPU vendor, including:
 * - Multiple candidate configurations for auto-tuning
 * - Default configuration for fallback
 * - Device-specific overrides for known GPUs
 * - Vendor-specific OpenCL build options
 *
 * @author hal.hildebrand
 */
public record VendorTuningProfile(
    GPUVendor vendor,
    String description,
    List<CandidateConfig> candidates,
    WorkgroupConfig defaultConfig,
    Map<String, WorkgroupConfig> deviceOverrides,
    String vendorBuildOptions
) {

    private static final Logger log = LoggerFactory.getLogger(VendorTuningProfile.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Candidate configuration for auto-tuning
     * Simplified version without occupancy/throughput (calculated dynamically)
     */
    public record CandidateConfig(
        int workgroupSize,
        int maxTraversalDepth,
        String notes
    ) {
        /**
         * Convert to full WorkgroupConfig using capabilities for calculations
         */
        public WorkgroupConfig toWorkgroupConfig(GPUCapabilities capabilities) {
            return WorkgroupConfig.withParameters(workgroupSize, maxTraversalDepth, capabilities);
        }
    }

    /**
     * Load vendor profile from classpath resource
     *
     * @param vendor GPU vendor
     * @return loaded profile or empty if not found
     */
    public static Optional<VendorTuningProfile> load(GPUVendor vendor) {
        var resourcePath = String.format("/tuning/%s-profile.json", vendor.name().toLowerCase());
        try (var is = VendorTuningProfile.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Vendor profile not found: {}", resourcePath);
                return Optional.empty();
            }

            return Optional.of(parseProfile(is, vendor));
        } catch (IOException e) {
            log.warn("Failed to load vendor profile {}: {}", vendor, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse profile from input stream
     */
    private static VendorTuningProfile parseProfile(InputStream is, GPUVendor expectedVendor) throws IOException {
        var root = objectMapper.readTree(is);

        // Validate vendor matches
        var vendorName = root.get("vendor").asText();
        var vendor = GPUVendor.valueOf(vendorName.toUpperCase());
        if (vendor != expectedVendor) {
            throw new IOException("Vendor mismatch: expected " + expectedVendor + ", got " + vendor);
        }

        // Parse description
        var description = root.has("description") ? root.get("description").asText() : "";

        // Parse candidates
        var candidates = parseCandidates(root.get("candidates"));

        // Parse default config
        var defaultConfig = parseDefaultConfig(root.get("defaultConfig"));

        // Parse device overrides
        var deviceOverrides = parseDeviceOverrides(root.get("deviceOverrides"));

        // Parse vendor build options
        var vendorBuildOptions = root.has("vendorBuildOptions")
            ? root.get("vendorBuildOptions").asText() : "";

        log.info("Loaded {} profile: {} candidates, {} device overrides",
                vendor, candidates.size(), deviceOverrides.size());

        return new VendorTuningProfile(
            vendor, description, candidates, defaultConfig, deviceOverrides, vendorBuildOptions
        );
    }

    /**
     * Parse candidate configurations
     */
    private static List<CandidateConfig> parseCandidates(JsonNode candidatesNode) {
        if (candidatesNode == null || !candidatesNode.isArray()) {
            return List.of();
        }

        var candidates = new ArrayList<CandidateConfig>();
        for (var node : candidatesNode) {
            var config = new CandidateConfig(
                node.get("workgroupSize").asInt(),
                node.get("maxTraversalDepth").asInt(),
                node.has("notes") ? node.get("notes").asText() : ""
            );
            candidates.add(config);
        }
        return Collections.unmodifiableList(candidates);
    }

    /**
     * Parse default configuration
     */
    private static WorkgroupConfig parseDefaultConfig(JsonNode node) {
        if (node == null) {
            return new WorkgroupConfig(64, 16, 0.70f, 1.0f, "Fallback default");
        }

        return new WorkgroupConfig(
            node.get("workgroupSize").asInt(),
            node.get("maxTraversalDepth").asInt(),
            (float) node.get("expectedOccupancy").asDouble(),
            (float) node.get("expectedThroughput").asDouble(),
            node.has("notes") ? node.get("notes").asText() : ""
        );
    }

    /**
     * Parse device-specific overrides
     */
    private static Map<String, WorkgroupConfig> parseDeviceOverrides(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }

        var overrides = new HashMap<String, WorkgroupConfig>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            var key = entry.getKey().toUpperCase().replaceAll("[^A-Z0-9]", "_");
            var value = entry.getValue();

            var config = new WorkgroupConfig(
                value.get("workgroupSize").asInt(),
                value.get("maxTraversalDepth").asInt(),
                (float) value.get("expectedOccupancy").asDouble(),
                (float) value.get("expectedThroughput").asDouble(),
                value.has("notes") ? value.get("notes").asText() : ""
            );
            overrides.put(key, config);
        }
        return Collections.unmodifiableMap(overrides);
    }

    /**
     * Get configuration for a specific device model
     *
     * @param deviceModel Device model name (e.g., "RTX 4090", "RX 6900 XT")
     * @return device-specific config or default config
     */
    public WorkgroupConfig getConfigForDevice(String deviceModel) {
        if (deviceModel == null || deviceModel.isEmpty()) {
            return defaultConfig;
        }

        var normalizedModel = deviceModel.toUpperCase().replaceAll("[^A-Z0-9]", "_");

        // Try exact match
        if (deviceOverrides.containsKey(normalizedModel)) {
            return deviceOverrides.get(normalizedModel);
        }

        // Try partial match
        for (var entry : deviceOverrides.entrySet()) {
            if (normalizedModel.contains(entry.getKey()) || entry.getKey().contains(normalizedModel)) {
                return entry.getValue();
            }
        }

        return defaultConfig;
    }

    /**
     * Get all candidate configs as WorkgroupConfigs using device capabilities
     *
     * @param capabilities GPU capabilities for occupancy/throughput calculation
     * @return list of candidate WorkgroupConfigs
     */
    public List<WorkgroupConfig> getCandidateConfigs(GPUCapabilities capabilities) {
        return candidates.stream()
            .map(c -> c.toWorkgroupConfig(capabilities))
            .toList();
    }

    /**
     * Validate profile structure
     *
     * @throws IllegalStateException if profile is invalid
     */
    public void validate() {
        if (vendor == null) {
            throw new IllegalStateException("Vendor is required");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("At least one candidate config is required");
        }
        if (defaultConfig == null) {
            throw new IllegalStateException("Default config is required");
        }

        // Validate all candidate configs
        for (var candidate : candidates) {
            if (candidate.workgroupSize <= 0 || candidate.workgroupSize > 256) {
                throw new IllegalStateException("Invalid workgroupSize: " + candidate.workgroupSize);
            }
            if (candidate.maxTraversalDepth < 8 || candidate.maxTraversalDepth > 32) {
                throw new IllegalStateException("Invalid maxTraversalDepth: " + candidate.maxTraversalDepth);
            }
        }
    }
}
