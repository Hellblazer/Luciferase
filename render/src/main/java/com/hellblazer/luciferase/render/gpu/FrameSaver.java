/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.stb.STBImageWrite.*;

/**
 * Saves rendered frames to PNG files.
 */
public class FrameSaver {

    private static final Logger log = LoggerFactory.getLogger(FrameSaver.class);

    private final Path outputDir;
    private final int width;
    private final int height;

    /**
     * Create a frame saver that outputs to the specified directory.
     *
     * @param outputDir Directory to save frames (created if not exists)
     * @param width     Frame width
     * @param height    Frame height
     */
    public FrameSaver(Path outputDir, int width, int height) throws IOException {
        this.outputDir = outputDir;
        this.width = width;
        this.height = height;

        Files.createDirectories(outputDir);
        log.info("Frame saver initialized: {} ({}x{})", outputDir, width, height);
    }

    /**
     * Save a frame as PNG.
     *
     * @param pixels Frame pixels as RGBA bytes
     * @param frameNum Frame number (for filename)
     */
    public void save(byte[] pixels, int frameNum) {
        String filename = String.format("frame_%04d.png", frameNum);
        Path filepath = outputDir.resolve(filename);

        // Convert byte array to ByteBuffer (OpenGL format is RGBA)
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length);
        buffer.put(pixels);
        buffer.flip();

        // Note: STB expects RGBA data, which is what we have
        // The flip() converts OpenGL's bottom-left origin to top-left
        int result = STBImageWrite.stbi_write_png(
            filepath.toAbsolutePath().toString(),
            width, height, 4,
            flipVertical(pixels, width, height),
            width * 4
        );

        if (result == 0) {
            log.warn("Failed to save frame: {}", filepath);
        } else if (frameNum % 60 == 0) {
            log.debug("Saved {}", filename);
        }
    }

    /**
     * Flip image vertically (OpenGL stores bottom-up, PNG is top-down).
     *
     * @param pixels Frame pixels as RGBA bytes
     * @param width Width
     * @param height Height
     * @return Flipped pixel data
     */
    private byte[] flipVertical(byte[] pixels, int width, int height) {
        byte[] flipped = new byte[pixels.length];
        int stride = width * 4;

        for (int y = 0; y < height; y++) {
            int srcRow = y * stride;
            int dstRow = (height - 1 - y) * stride;
            System.arraycopy(pixels, srcRow, flipped, dstRow, stride);
        }

        return flipped;
    }
}
