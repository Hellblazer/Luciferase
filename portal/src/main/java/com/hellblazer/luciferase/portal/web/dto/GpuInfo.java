package com.hellblazer.luciferase.portal.web.dto;

/**
 * GPU device information.
 */
public record GpuInfo(
        boolean available,
        String deviceName,
        String vendor,
        String version,
        long globalMemoryBytes,
        int computeUnits,
        long maxWorkGroupSize,
        String deviceType
) {
    public static GpuInfo unavailable() {
        return new GpuInfo(false, null, null, null, 0, 0, 0, null);
    }
}
