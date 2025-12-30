package com.hellblazer.luciferase.portal.web.dto;

/**
 * GPU usage statistics for a session.
 */
public record GpuStats(
        boolean gpuEnabled,
        int frameWidth,
        int frameHeight,
        long framesRendered,
        long totalRenderTimeNs,
        double avgRenderTimeMs,
        long totalRaysTraced,
        double raysPerSecond
) {
    public static GpuStats disabled() {
        return new GpuStats(false, 0, 0, 0, 0, 0, 0, 0);
    }
}
