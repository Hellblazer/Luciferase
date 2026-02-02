/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU Tuning Profile Loader
 *
 * Stream B Phase 6: Load predefined tuning profiles from JSON resource
 * B2: Enhanced to support vendor-specific profile files
 *
 * Provides GPU-specific workgroup configurations from:
 * 1. Vendor-specific profiles (tuning/nvidia-profile.json, etc.)
 * 2. Legacy combined profile (gpu-tuning-profiles.json)
 *
 * @author hal.hildebrand
 */
public class GPUTuningProfileLoader {
    private static final Logger log = LoggerFactory.getLogger(GPUTuningProfileLoader.class);
    private static final String PROFILE_RESOURCE = "/gpu-tuning-profiles.json";

    private final Map<String, WorkgroupConfig> profileCache;
    private final Map<GPUVendor, VendorTuningProfile> vendorProfiles;
    private final ObjectMapper objectMapper;

    /**
     * Create profile loader with empty cache
     */
    public GPUTuningProfileLoader() {
        this.profileCache = new ConcurrentHashMap<>();
        this.vendorProfiles = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();

        // B2: Load vendor-specific profiles first
        loadVendorProfiles();

        // Eagerly load legacy profiles on construction
        loadAllProfilesInternal();
    }

    /**
     * B2: Load vendor-specific profiles from tuning/ directory
     */
    private void loadVendorProfiles() {
        for (var vendor : GPUVendor.values()) {
            if (vendor == GPUVendor.UNKNOWN) {
                continue;
            }

            VendorTuningProfile.load(vendor).ifPresent(profile -> {
                vendorProfiles.put(vendor, profile);
                log.debug("Loaded vendor profile: {}", vendor);
            });
        }
        log.info("Loaded {} vendor-specific tuning profiles", vendorProfiles.size());
    }

    /**
     * Load tuning profile for a specific GPU model
     *
     * @param modelKey GPU model key (e.g., "NVIDIA_RTX_4090")
     * @return profile configuration, or empty if not found
     */
    public Optional<WorkgroupConfig> loadProfile(String modelKey) {
        if (modelKey == null || modelKey.isEmpty()) {
            return Optional.empty();
        }

        // Normalize key: uppercase, replace spaces with underscores
        var normalizedKey = normalizeKey(modelKey);

        // Check cache first
        var cached = profileCache.get(normalizedKey);
        if (cached != null) {
            log.debug("Loaded profile from cache: {}", normalizedKey);
            return Optional.of(cached);
        }

        // If not in cache and profiles were already loaded, not found
        log.debug("Profile not found: {}", normalizedKey);
        return Optional.empty();
    }

    /**
     * Load all available GPU profiles
     *
     * @return map of model key to configuration
     */
    public Map<String, WorkgroupConfig> loadAllProfiles() {
        return new HashMap<>(profileCache);
    }

    /**
     * Load profile for a GPU device based on capabilities
     *
     * B2: Enhanced lookup order:
     * 1. Vendor-specific profile with device override
     * 2. Legacy exact model match
     * 3. Legacy partial model match
     * 4. Vendor-specific profile default
     * 5. Generated config (fallback)
     *
     * @param capabilities GPU capabilities
     * @return profile configuration
     */
    public Optional<WorkgroupConfig> loadProfileForDevice(GPUCapabilities capabilities) {
        // B2: Try vendor-specific profile with device override first
        var vendorProfile = vendorProfiles.get(capabilities.vendor());
        if (vendorProfile != null) {
            var config = vendorProfile.getConfigForDevice(capabilities.model());
            // Check if we got a device-specific override (not just the default)
            if (!config.equals(vendorProfile.defaultConfig())) {
                log.info("Loaded vendor profile override for {}: {}", capabilities.model(), config.notes());
                return Optional.of(config);
            }
        }

        // Try legacy exact match with vendor_model format
        var exactKey = String.format("%s_%s",
            capabilities.vendor().name(),
            capabilities.model().toUpperCase().replaceAll("[^A-Z0-9]", "_")
        );

        var profile = loadProfile(exactKey);
        if (profile.isPresent()) {
            log.info("Loaded exact profile match: {}", exactKey);
            return profile;
        }

        // Try partial model match (e.g., "RTX 4090" -> "RTX_4090")
        var partialKey = capabilities.model().toUpperCase()
            .replaceAll("[^A-Z0-9]", "_");

        for (var key : profileCache.keySet()) {
            if (key.contains(partialKey)) {
                log.info("Loaded partial profile match: {} for {}", key, partialKey);
                return Optional.of(profileCache.get(key));
            }
        }

        // B2: Use vendor profile default if available
        if (vendorProfile != null) {
            log.info("Using vendor profile default for {}", capabilities.vendor());
            return Optional.of(vendorProfile.defaultConfig());
        }

        // Fallback: generate config using WorkgroupConfig factory
        log.info("No profile found for {}, using generated config", capabilities.model());
        return Optional.of(WorkgroupConfig.forDevice(capabilities));
    }

    /**
     * B2: Get vendor-specific profile
     *
     * @param vendor GPU vendor
     * @return vendor profile or empty if not loaded
     */
    public Optional<VendorTuningProfile> getVendorProfile(GPUVendor vendor) {
        return Optional.ofNullable(vendorProfiles.get(vendor));
    }

    /**
     * B2: Get all candidate configs for a vendor
     *
     * @param capabilities GPU capabilities
     * @return list of candidate configs, or empty list if no vendor profile
     */
    public List<WorkgroupConfig> getVendorCandidates(GPUCapabilities capabilities) {
        var vendorProfile = vendorProfiles.get(capabilities.vendor());
        if (vendorProfile == null) {
            return List.of();
        }
        return vendorProfile.getCandidateConfigs(capabilities);
    }

    /**
     * B2: Get vendor-specific build options
     *
     * @param vendor GPU vendor
     * @return vendor build options string, or empty string if not available
     */
    public String getVendorBuildOptions(GPUVendor vendor) {
        var vendorProfile = vendorProfiles.get(vendor);
        return vendorProfile != null ? vendorProfile.vendorBuildOptions() : "";
    }

    /**
     * Normalize profile key for consistent lookup
     *
     * Rules:
     * - Convert to uppercase
     * - Replace non-alphanumeric with underscores
     * - Collapse multiple underscores
     *
     * @param key original key
     * @return normalized key
     */
    private String normalizeKey(String key) {
        return key.toUpperCase()
            .replaceAll("[^A-Z0-9]", "_")
            .replaceAll("_+", "_");
    }

    /**
     * Load all profiles from JSON resource into cache
     */
    private void loadAllProfilesInternal() {
        try (InputStream is = getClass().getResourceAsStream(PROFILE_RESOURCE)) {
            if (is == null) {
                log.warn("Profile resource not found: {}", PROFILE_RESOURCE);
                return;
            }

            var root = objectMapper.readTree(is);
            var profiles = root.get("gpu_profiles");

            if (profiles == null || !profiles.isObject()) {
                log.warn("Invalid profile format: missing gpu_profiles object");
                return;
            }

            // Parse each profile
            var iterator = profiles.fields();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var key = normalizeKey(entry.getKey());
                var value = entry.getValue();

                try {
                    var config = parseProfile(value);
                    profileCache.put(key, config);
                    log.debug("Loaded profile: {} -> {} threads, depth {}",
                             key, config.workgroupSize(), config.maxTraversalDepth());
                } catch (Exception e) {
                    log.warn("Failed to parse profile {}: {}", key, e.getMessage());
                }
            }

            log.info("Loaded {} GPU tuning profiles", profileCache.size());

        } catch (IOException e) {
            log.error("Failed to load tuning profiles: {}", e.getMessage());
        }
    }

    /**
     * Parse a single profile from JSON node
     *
     * @param node JSON node containing profile data
     * @return workgroup configuration
     */
    private WorkgroupConfig parseProfile(JsonNode node) {
        var workgroupSize = node.get("workgroupSize").asInt();
        var maxTraversalDepth = node.get("maxTraversalDepth").asInt();
        var expectedOccupancy = (float) node.get("expectedOccupancy").asDouble();
        var expectedThroughput = (float) node.get("expectedThroughput").asDouble();
        var notes = node.get("notes").asText();

        return new WorkgroupConfig(
            workgroupSize,
            maxTraversalDepth,
            expectedOccupancy,
            expectedThroughput,
            notes
        );
    }
}
