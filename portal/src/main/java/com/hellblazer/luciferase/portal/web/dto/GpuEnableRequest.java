package com.hellblazer.luciferase.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request to enable GPU mode for a session.
 */
public record GpuEnableRequest(
        Integer frameWidth,
        Integer frameHeight
) {
    @JsonIgnore
    public int getFrameWidthOrDefault() {
        return frameWidth != null ? frameWidth : 800;
    }

    @JsonIgnore
    public int getFrameHeightOrDefault() {
        return frameHeight != null ? frameHeight : 600;
    }
}
