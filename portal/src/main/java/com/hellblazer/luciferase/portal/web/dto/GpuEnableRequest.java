package com.hellblazer.luciferase.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request to enable GPU mode for a session.
 */
public record GpuEnableRequest(
        Integer frameWidth,
        Integer frameHeight
) {
    /** Maximum allowed dimension (width or height) in pixels. */
    public static final int MAX_FRAME_DIMENSION = 4096;

    @JsonIgnore
    public int getFrameWidthOrDefault() {
        int w = frameWidth != null ? frameWidth : 800;
        if (w <= 0 || w > MAX_FRAME_DIMENSION) {
            throw new IllegalArgumentException(
                    "frameWidth must be in [1, " + MAX_FRAME_DIMENSION + "], got: " + w);
        }
        return w;
    }

    @JsonIgnore
    public int getFrameHeightOrDefault() {
        int h = frameHeight != null ? frameHeight : 600;
        if (h <= 0 || h > MAX_FRAME_DIMENSION) {
            throw new IllegalArgumentException(
                    "frameHeight must be in [1, " + MAX_FRAME_DIMENSION + "], got: " + h);
        }
        return h;
    }
}
