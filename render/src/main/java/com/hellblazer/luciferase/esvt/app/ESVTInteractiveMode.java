/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvt.app;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.io.ESVTDeserializer;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;

/**
 * Interactive mode for real-time ESVT visualization and exploration.
 *
 * <p>Provides an interactive environment for:
 * <ul>
 *   <li>Real-time ray-traced rendering of ESVT data</li>
 *   <li>Camera navigation and control</li>
 *   <li>Performance monitoring overlay</li>
 *   <li>Tree structure visualization</li>
 * </ul>
 *
 * <p>Note: Full interactive mode requires LWJGL/OpenGL. This implementation
 * provides a console-based preview mode for environments without GPU access.
 *
 * @author hal.hildebrand
 */
public class ESVTInteractiveMode {
    private static final Logger log = LoggerFactory.getLogger(ESVTInteractiveMode.class);

    private final ESVTCommandLine.Config config;
    private final PrintStream out;

    private ESVTData data;
    private ESVTTraversal traversal;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Camera state
    private final Vector3f cameraPosition = new Vector3f(0, 0, -2);
    private final Vector3f cameraTarget = new Vector3f(0.5f, 0.5f, 0.5f);
    private final Vector3f cameraUp = new Vector3f(0, 1, 0);
    private float cameraFov = 60.0f;

    /**
     * Cast a ray through the ESVT data.
     */
    private ESVTResult castRay(ESVTRay ray) {
        if (data == null || traversal == null) {
            return new ESVTResult();
        }
        return traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);
    }

    public ESVTInteractiveMode(ESVTCommandLine.Config config) {
        this(config, System.out);
    }

    public ESVTInteractiveMode(ESVTCommandLine.Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    /**
     * Run interactive mode.
     */
    public static int run(ESVTCommandLine.Config config) {
        return new ESVTInteractiveMode(config).execute();
    }

    /**
     * Execute interactive mode.
     */
    public int execute() {
        try {
            printHeader();

            // Load ESVT file
            phase("Loading ESVT File");
            if (!loadESVTFile()) {
                return 1;
            }

            // Check for GPU capability
            phase("Checking Display Capabilities");
            var hasGPU = checkGPUCapability();

            if (hasGPU) {
                // Full interactive mode with OpenGL rendering
                return runGPUMode();
            } else {
                // Console-based preview mode
                return runConsoleMode();
            }

        } catch (Exception e) {
            error("Interactive mode failed: " + e.getMessage());
            log.error("Interactive mode failed", e);
            printFooter(false);
            return 1;
        }
    }

    private boolean loadESVTFile() {
        try {
            var inputPath = Path.of(config.inputFile);
            progress("Loading: " + inputPath);

            var startTime = System.nanoTime();
            var deserializer = new ESVTDeserializer();
            data = deserializer.deserialize(inputPath);
            var loadTimeMs = (System.nanoTime() - startTime) / 1_000_000.0;

            progress("Nodes: " + data.nodeCount());
            progress("Leaves: " + data.leafCount());
            progress("Max depth: " + data.maxDepth());
            progress("Load time: " + String.format("%.2f", loadTimeMs) + " ms");

            traversal = new ESVTTraversal();
            return true;

        } catch (IOException e) {
            error("Failed to load ESVT file: " + e.getMessage());
            return false;
        }
    }

    private boolean checkGPUCapability() {
        try {
            // Try to load LWJGL
            Class.forName("org.lwjgl.glfw.GLFW");
            progress("LWJGL available - GPU mode supported");
            return true;
        } catch (ClassNotFoundException e) {
            progress("LWJGL not available - using console mode");
            return false;
        }
    }

    private int runGPUMode() {
        phase("GPU Interactive Mode");
        progress("Starting OpenGL rendering...");
        progress("Window size: " + config.frameWidth + "x" + config.frameHeight);
        progress("VSync: " + (config.vsync ? "enabled" : "disabled"));
        progress("Fullscreen: " + (config.fullscreen ? "enabled" : "disabled"));

        // Note: Full GPU implementation would use LWJGL/OpenGL
        // This is a placeholder that delegates to console mode
        progress("");
        progress("GPU rendering not yet implemented in this version.");
        progress("Falling back to console preview mode...");
        progress("");

        return runConsoleMode();
    }

    private int runConsoleMode() {
        phase("Console Preview Mode");
        progress("Rendering ASCII preview of ESVT data...");
        progress("");

        // Render ASCII art preview
        renderASCIIPreview();

        // Display stats
        phase("Interactive Controls (Console Mode)");
        displayConsoleControls();

        // Simple interaction loop
        runConsoleInteraction();

        printFooter(true);
        return 0;
    }

    private void renderASCIIPreview() {
        var width = 60;
        var height = 30;

        out.println("  ┌" + "─".repeat(width) + "┐");

        for (int y = 0; y < height; y++) {
            out.print("  │");
            for (int x = 0; x < width; x++) {
                // Cast ray for this pixel
                var u = (float) x / width;
                var v = 1.0f - (float) y / height;

                var ray = generateRay(u, v);
                var result = castRay(ray);

                char c;
                if (result.hit) {
                    // Use different characters based on depth/distance
                    var depth = result.scale;
                    if (depth < 3) {
                        c = '@';
                    } else if (depth < 6) {
                        c = '#';
                    } else if (depth < 9) {
                        c = '*';
                    } else if (depth < 12) {
                        c = '+';
                    } else {
                        c = '.';
                    }
                } else {
                    c = ' ';
                }
                out.print(c);
            }
            out.println("│");
        }

        out.println("  └" + "─".repeat(width) + "┘");
        out.println();
        out.println("  Camera: " + formatVector(cameraPosition) +
                   " → " + formatVector(cameraTarget));
    }

    private ESVTRay generateRay(float u, float v) {
        // Simple perspective camera ray generation
        var forward = new Vector3f();
        forward.sub(cameraTarget, cameraPosition);
        forward.normalize();

        var right = new Vector3f();
        right.cross(forward, cameraUp);
        right.normalize();

        var up = new Vector3f();
        up.cross(right, forward);

        var aspectRatio = (float) config.frameWidth / config.frameHeight;
        var fovScale = (float) Math.tan(Math.toRadians(cameraFov / 2));

        var x = (2.0f * u - 1.0f) * aspectRatio * fovScale;
        var y = (2.0f * v - 1.0f) * fovScale;

        var direction = new Vector3f();
        direction.scaleAdd(x, right, forward);
        direction.scaleAdd(y, up, direction);
        direction.normalize();

        return new ESVTRay(new Point3f(cameraPosition.x, cameraPosition.y, cameraPosition.z), direction);
    }

    private void displayConsoleControls() {
        out.println("  Available commands:");
        out.println("    q, quit     - Exit interactive mode");
        out.println("    r, render   - Re-render preview");
        out.println("    s, stats    - Show performance statistics");
        out.println("    w/a/s/d     - Move camera (forward/left/back/right)");
        out.println("    i/k         - Move camera up/down");
        out.println("    +/-         - Zoom in/out");
        out.println("    h, help     - Show this help");
        out.println();
    }

    private void runConsoleInteraction() {
        running.set(true);

        try (var scanner = new java.util.Scanner(System.in)) {
            out.print("  > ");
            out.flush();

            while (running.get() && scanner.hasNextLine()) {
                var line = scanner.nextLine().trim().toLowerCase();

                switch (line) {
                    case "q", "quit", "exit" -> {
                        running.set(false);
                        progress("Exiting...");
                    }
                    case "r", "render" -> {
                        out.println();
                        renderASCIIPreview();
                    }
                    case "s", "stats" -> showStats();
                    case "w" -> moveCamera(0, 0, 0.1f);
                    case "a" -> moveCamera(-0.1f, 0, 0);
                    case "d" -> moveCamera(0.1f, 0, 0);
                    case "i" -> moveCamera(0, 0.1f, 0);
                    case "k" -> moveCamera(0, -0.1f, 0);
                    case "+" -> { cameraFov = Math.max(10, cameraFov - 5); progress("FOV: " + cameraFov); }
                    case "-" -> { cameraFov = Math.min(120, cameraFov + 5); progress("FOV: " + cameraFov); }
                    case "h", "help", "?" -> displayConsoleControls();
                    case "" -> {} // Ignore empty input
                    default -> progress("Unknown command: " + line + " (type 'help' for commands)");
                }

                if (running.get()) {
                    out.print("  > ");
                    out.flush();
                }
            }
        }
    }

    private void moveCamera(float dx, float dy, float dz) {
        // Calculate movement in camera space
        var forward = new Vector3f();
        forward.sub(cameraTarget, cameraPosition);
        forward.normalize();

        var right = new Vector3f();
        right.cross(forward, cameraUp);
        right.normalize();

        var movement = new Vector3f();
        movement.scaleAdd(dx, right, movement);
        movement.scaleAdd(dy, cameraUp, movement);
        movement.scaleAdd(dz, forward, movement);

        cameraPosition.add(movement);
        cameraTarget.add(movement);

        progress("Camera: " + formatVector(cameraPosition));
    }

    private void showStats() {
        out.println();
        out.println("  ESVT Statistics:");
        out.println("  ─────────────────────────────────────");
        out.println("    Total nodes:   " + data.nodeCount());
        out.println("    Leaf nodes:    " + data.leafCount());
        out.println("    Internal:      " + data.internalCount());
        out.println("    Max depth:     " + data.maxDepth());
        out.println("    Grid res:      " + data.gridResolution());
        out.println("    Contours:      " + data.contourCount());
        out.println("    Far pointers:  " + data.farPointerCount());
        out.println();
        out.println("  Camera State:");
        out.println("    Position:      " + formatVector(cameraPosition));
        out.println("    Target:        " + formatVector(cameraTarget));
        out.println("    FOV:           " + cameraFov + "°");
        out.println();

        // Do a quick benchmark
        var rays = 10000;
        var hits = 0;
        var startTime = System.nanoTime();

        for (int i = 0; i < rays; i++) {
            var u = (float) Math.random();
            var v = (float) Math.random();
            var ray = generateRay(u, v);
            if (castRay(ray).hit) {
                hits++;
            }
        }

        var elapsed = (System.nanoTime() - startTime) / 1_000_000.0;
        var raysPerSec = rays / (elapsed / 1000.0);

        out.println("  Quick Benchmark:");
        out.println("    Rays tested:   " + rays);
        out.println("    Hit rate:      " + String.format("%.1f%%", 100.0 * hits / rays));
        out.println("    Time:          " + String.format("%.2f", elapsed) + " ms");
        out.println("    Rays/sec:      " + formatNumber(raysPerSec));
        out.println();
    }

    private String formatVector(Vector3f v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    // Formatting helpers

    private void printHeader() {
        if (config.quiet) return;
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║                 ESVT Interactive Mode                        ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
    }

    private void printFooter(boolean success) {
        if (config.quiet) return;
        out.println();
        if (success) {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                   Session Complete                           ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        } else {
            out.println("╔══════════════════════════════════════════════════════════════╗");
            out.println("║                   Session Failed                             ║");
            out.println("╚══════════════════════════════════════════════════════════════╝");
        }
        out.println();
    }

    private void phase(String name) {
        if (config.quiet) return;
        out.println();
        out.println("► " + name);
        out.println("─".repeat(62));
    }

    private void progress(String message) {
        if (config.quiet) return;
        out.println("  " + message);
    }

    private void error(String message) {
        out.println("  ✗ ERROR: " + message);
    }

    private static String formatNumber(double n) {
        if (n >= 1_000_000_000) {
            return String.format("%.1f B", n / 1_000_000_000);
        } else if (n >= 1_000_000) {
            return String.format("%.1f M", n / 1_000_000);
        } else if (n >= 1_000) {
            return String.format("%.1f K", n / 1_000);
        } else {
            return String.format("%.0f", n);
        }
    }
}
