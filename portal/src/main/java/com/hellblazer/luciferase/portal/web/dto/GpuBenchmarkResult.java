package com.hellblazer.luciferase.portal.web.dto;

/**
 * GPU benchmark results.
 */
public record GpuBenchmarkResult(
        int width,
        int height,
        int iterations,
        double avgRenderTimeMs,
        double minRenderTimeMs,
        double maxRenderTimeMs,
        double raysPerSecond,
        long totalRays,
        String deviceName
) {}
