package com.hellblazer.luciferase.portal.web.dto;

/**
 * Result of GPU render operation.
 */
public record GpuRenderResult(
        int width,
        int height,
        String format,      // "RGBA"
        String encoding,    // "base64" or "raw"
        String imageData,   // base64 encoded if encoding is "base64"
        long renderTimeNs,
        int rayCount
) {}
