package com.hellblazer.luciferase.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request for GPU-accelerated render.
 */
public record GpuRenderRequest(
        float cameraPosX, float cameraPosY, float cameraPosZ,
        float lookAtX, float lookAtY, float lookAtZ,
        Float fovDegrees,
        String outputFormat // "base64" or "raw"
) {
    @JsonIgnore
    public float getFovOrDefault() {
        return fovDegrees != null ? fovDegrees : 60.0f;
    }

    @JsonIgnore
    public boolean isBase64() {
        return outputFormat == null || "base64".equalsIgnoreCase(outputFormat);
    }
}
