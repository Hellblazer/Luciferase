/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.inspector;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Manager for screenshot and video recording functionality in inspector apps.
 *
 * <p>Provides:
 * <ul>
 *   <li>Single-shot screenshots with timestamp naming</li>
 *   <li>Sequential frame capture for video recording</li>
 *   <li>Automatic directory creation</li>
 *   <li>Status callbacks for UI updates</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class MediaCaptureManager {

    private static final Logger log = LoggerFactory.getLogger(MediaCaptureManager.class);

    private final String screenshotPrefix;
    private final String framePrefix;
    private final Path screenshotsDir;
    private final Path recordingsDir;

    private boolean isRecording = false;
    private int frameCounter = 0;
    private Consumer<String> statusCallback;

    /**
     * Create a new MediaCaptureManager with default settings.
     *
     * @param prefix Prefix for saved files (e.g., "octree", "esvt")
     */
    public MediaCaptureManager(String prefix) {
        this.screenshotPrefix = prefix + "_screenshot";
        this.framePrefix = prefix + "_frame";
        this.screenshotsDir = Paths.get("screenshots");
        this.recordingsDir = Paths.get("recordings");
    }

    /**
     * Set the callback for status updates.
     *
     * @param callback Consumer that receives status messages
     */
    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    /**
     * Capture a screenshot of the given node.
     *
     * @param node The JavaFX node to capture
     * @param additionalInfo Optional additional info to include in log (e.g., node count)
     * @return Path to saved screenshot, or null if capture failed
     */
    public Path captureScreenshot(Node node, String additionalInfo) {
        try {
            // Create screenshots directory if needed
            if (!Files.exists(screenshotsDir)) {
                Files.createDirectories(screenshotsDir);
                log.info("Created screenshots directory: {}", screenshotsDir.toAbsolutePath());
            }

            // Generate timestamp-based filename
            var formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            var timestamp = LocalDateTime.now().format(formatter);
            var filename = String.format("%s_%s.png", screenshotPrefix, timestamp);
            var outputPath = screenshotsDir.resolve(filename);

            // Capture snapshot
            WritableImage snapshot = node.snapshot(null, null);

            // Convert and save
            var bufferedImage = SwingFXUtils.fromFXImage(snapshot, null);
            ImageIO.write(bufferedImage, "png", outputPath.toFile());

            // Log success
            var stats = String.format("Screenshot saved: %s (%.1f KB, %dx%d)",
                filename,
                outputPath.toFile().length() / 1024.0,
                (int) snapshot.getWidth(),
                (int) snapshot.getHeight());

            if (additionalInfo != null && !additionalInfo.isEmpty()) {
                stats += " " + additionalInfo;
            }

            log.info(stats);
            updateStatus("Screenshot saved - " + filename);

            return outputPath;

        } catch (IOException e) {
            log.error("Failed to save screenshot", e);
            updateStatus("Screenshot failed!");
            return null;
        }
    }

    /**
     * Toggle recording state.
     *
     * @return true if recording started, false if recording stopped
     */
    public boolean toggleRecording() {
        isRecording = !isRecording;

        if (isRecording) {
            // Start recording
            frameCounter = 0;
            updateStatus("Recording started (0 frames)");
            log.info("Recording started - frames will be captured in {} directory", recordingsDir);

            // Create recordings directory
            try {
                if (!Files.exists(recordingsDir)) {
                    Files.createDirectories(recordingsDir);
                    log.info("Created recordings directory: {}", recordingsDir.toAbsolutePath());
                }
            } catch (IOException e) {
                log.error("Failed to create recordings directory", e);
                isRecording = false;
                updateStatus("Recording failed - can't create directory");
            }
        } else {
            // Stop recording
            log.info("Recording stopped - {} frames captured", frameCounter);
            updateStatus(String.format("Recording complete - %d frames", frameCounter));
        }

        return isRecording;
    }

    /**
     * Check if currently recording.
     *
     * @return true if recording is active
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Get the current frame count during recording.
     *
     * @return number of frames captured
     */
    public int getFrameCount() {
        return frameCounter;
    }

    /**
     * Capture a single frame during recording.
     * Should be called from an animation timer when recording is active.
     *
     * @param node The JavaFX node to capture
     */
    public void captureFrame(Node node) {
        if (!isRecording) {
            return;
        }

        try {
            frameCounter++;

            // Generate filename with zero-padded frame number
            var filename = String.format("%s_%06d.png", framePrefix, frameCounter);
            var outputPath = recordingsDir.resolve(filename);

            // Capture and save
            WritableImage snapshot = node.snapshot(null, null);
            var bufferedImage = SwingFXUtils.fromFXImage(snapshot, null);
            ImageIO.write(bufferedImage, "png", outputPath.toFile());

            // Log progress periodically
            if (frameCounter % 10 == 0) {
                log.debug("Recording frame {} captured", frameCounter);
            }

        } catch (IOException e) {
            log.error("Failed to capture frame {}", frameCounter, e);
            // Don't stop recording on single frame failure
        }
    }

    private void updateStatus(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }
}
