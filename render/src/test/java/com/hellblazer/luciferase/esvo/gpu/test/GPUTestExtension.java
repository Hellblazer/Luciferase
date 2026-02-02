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
package com.hellblazer.luciferase.esvo.gpu.test;

import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import com.hellblazer.luciferase.esvo.gpu.GPUVendorDetector;
import com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

/**
 * D2: JUnit 5 extension for GPU test lifecycle management.
 * <p>
 * This extension implements:
 * <ul>
 *   <li>ExecutionCondition - conditionally skip tests based on GPU availability and requirements</li>
 *   <li>BeforeEachCallback - log GPU vendor info before each test</li>
 *   <li>AfterTestExecutionCallback - log test results with vendor context</li>
 * </ul>
 * <p>
 * Test execution flow:
 * <ol>
 *   <li>Check RUN_GPU_TESTS environment variable</li>
 *   <li>Check CI environment (skip unless allowInCI=true)</li>
 *   <li>Check OpenCL availability</li>
 *   <li>Check vendor requirements from @GPUTest annotation</li>
 *   <li>Check capability requirements (FP16, FP64, compute units, VRAM)</li>
 *   <li>Execute test if all conditions pass</li>
 * </ol>
 *
 * @author hal.hildebrand
 * @see GPUTest
 */
public class GPUTestExtension implements ExecutionCondition, BeforeEachCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger(GPUTestExtension.class);

    private static final String RUN_GPU_TESTS_ENV = "RUN_GPU_TESTS";
    private static final String CI_ENV = "CI";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Find @GPUTest annotation (on method or class)
        var annotation = findAnnotation(context);

        // Step 1: Check RUN_GPU_TESTS environment variable
        var runGpuTests = System.getenv(RUN_GPU_TESTS_ENV);
        if (!"true".equalsIgnoreCase(runGpuTests)) {
            return ConditionEvaluationResult.disabled(
                    "GPU tests disabled: Set RUN_GPU_TESTS=true to enable");
        }

        // Step 2: Check CI environment
        var isCI = "true".equalsIgnoreCase(System.getenv(CI_ENV));
        if (isCI && annotation.isPresent() && !annotation.get().allowInCI()) {
            return ConditionEvaluationResult.disabled(
                    "GPU test skipped in CI environment (allowInCI=false)");
        }

        // Step 3: Check OpenCL availability
        if (!AbstractOpenCLRenderer.isOpenCLAvailable()) {
            return ConditionEvaluationResult.disabled(
                    "OpenCL not available on this system");
        }

        // Step 4: Get GPU detector and check capabilities
        GPUVendorDetector detector;
        try {
            detector = GPUVendorDetector.getInstance();
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "GPU detection failed: " + e.getMessage());
        }

        var detectedVendor = detector.getVendor();
        var capabilities = detector.getCapabilities();

        // Step 5: Check annotation requirements (if present)
        if (annotation.isPresent()) {
            var gpuTest = annotation.get();

            // Check vendor requirement
            var requiredVendor = gpuTest.vendor();
            if (requiredVendor != GPUVendor.UNKNOWN && detectedVendor != requiredVendor) {
                return ConditionEvaluationResult.disabled(
                        String.format("Requires %s GPU, but detected %s", requiredVendor, detectedVendor));
            }

            // Note: FP16/FP64 checks are informational only - GPUCapabilities doesn't track these yet
            // Future enhancement: Query cl_khr_fp16 and cl_khr_fp64 extensions from OpenCL
            if (gpuTest.requiresFloat16()) {
                log.debug("Test requires FP16 - assuming available (not validated)");
            }
            if (gpuTest.requiresFloat64()) {
                log.debug("Test requires FP64 - assuming available (not validated)");
            }

            // Check compute units requirement
            if (capabilities.computeUnits() < gpuTest.minComputeUnits()) {
                return ConditionEvaluationResult.disabled(
                        String.format("Test requires %d compute units, but GPU has %d",
                                gpuTest.minComputeUnits(), capabilities.computeUnits()));
            }

            // Check VRAM requirement
            if (capabilities.globalMemorySize() < gpuTest.minVRAMBytes()) {
                return ConditionEvaluationResult.disabled(
                        String.format("Test requires %d bytes VRAM, but GPU has %d",
                                gpuTest.minVRAMBytes(), capabilities.globalMemorySize()));
            }

            // Check OpenCL version requirement
            var requiredVersion = gpuTest.minOpenCLVersion();
            if (!isVersionSatisfied(capabilities.openCLVersion(), requiredVersion)) {
                return ConditionEvaluationResult.disabled(
                        String.format("Test requires OpenCL %s, but GPU has %s",
                                requiredVersion, capabilities.openCLVersion()));
            }
        }

        // All checks passed
        return ConditionEvaluationResult.enabled(
                String.format("GPU requirements met: %s (%s)",
                        detectedVendor, capabilities.deviceName()));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        try {
            var detector = GPUVendorDetector.getInstance();
            var vendor = detector.getVendor();
            var deviceName = detector.getDeviceName();

            log.info("[{}] Running: {} - {}",
                    vendor,
                    context.getTestClass().map(Class::getSimpleName).orElse("?"),
                    context.getDisplayName());

            // Store vendor info in context for potential use by tests
            context.getStore(ExtensionContext.Namespace.create(GPUTestExtension.class))
                    .put("vendor", vendor);
            context.getStore(ExtensionContext.Namespace.create(GPUTestExtension.class))
                    .put("deviceName", deviceName);

        } catch (Exception e) {
            log.warn("Could not detect GPU vendor for logging: {}", e.getMessage());
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        var vendor = context.getStore(ExtensionContext.Namespace.create(GPUTestExtension.class))
                .get("vendor", GPUVendor.class);

        if (vendor != null) {
            var throwable = context.getExecutionException().orElse(null);
            if (throwable == null) {
                log.debug("[{}] Test passed: {}", vendor, context.getDisplayName());
            } else {
                log.warn("[{}] Test failed: {} - {}",
                        vendor, context.getDisplayName(), throwable.getMessage());
            }
        }
    }

    /**
     * Find @GPUTest annotation on method or enclosing class.
     */
    private Optional<GPUTest> findAnnotation(ExtensionContext context) {
        // Check method first
        var methodAnnotation = context.getTestMethod()
                .flatMap(method -> findAnnotation(method, GPUTest.class));
        if (methodAnnotation.isPresent()) {
            return methodAnnotation;
        }

        // Fall back to class annotation
        return context.getTestClass()
                .flatMap(clazz -> findAnnotation(clazz, GPUTest.class));
    }

    private <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(
            AnnotatedElement element, Class<A> annotationType) {
        var annotation = element.getAnnotation(annotationType);
        return Optional.ofNullable(annotation);
    }

    /**
     * Check if detected OpenCL version satisfies the required version.
     * Handles version strings like "OpenCL 1.2 " or "OpenCL 3.0 CUDA".
     */
    private boolean isVersionSatisfied(String detected, String required) {
        if (detected == null || detected.isEmpty()) {
            return false;
        }

        try {
            // Extract version numbers
            var detectedVersion = extractVersion(detected);
            var requiredVersion = extractVersion(required);

            // Compare major.minor
            if (detectedVersion[0] > requiredVersion[0]) {
                return true;
            }
            if (detectedVersion[0] == requiredVersion[0]) {
                return detectedVersion[1] >= requiredVersion[1];
            }
            return false;

        } catch (Exception e) {
            // If version parsing fails, assume compatible
            log.debug("Could not parse OpenCL version '{}', assuming compatible", detected);
            return true;
        }
    }

    /**
     * Extract major.minor version from string like "OpenCL 1.2" or "1.2".
     */
    private int[] extractVersion(String versionString) {
        // Find first occurrence of digit pattern like "1.2" or "3.0"
        var matcher = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)")
                .matcher(versionString);

        if (matcher.find()) {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            };
        }

        throw new IllegalArgumentException("No version found in: " + versionString);
    }
}
