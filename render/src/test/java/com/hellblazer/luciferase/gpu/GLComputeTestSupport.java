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
package com.hellblazer.luciferase.gpu;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Platform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Base for headless OpenGL 4.3 compute tests that run under a real GL context.
 *
 * <p><b>Why this exists (Luciferase-ai9tw):</b> the GL compute renderers (ComputeShaderRenderer,
 * ESVTComputeRenderer, OctreeGPUMemory, ESVTGPUMemory) need an OpenGL 4.3 context (compute shaders +
 * SSBOs) that this project's dev machines (Apple Silicon, capped at OpenGL 4.1) cannot provide. CI's
 * {@code mesa-software-test} job (gpu-shader-validation.yml) provides a software GL 4.5 context via
 * Mesa llvmpipe ({@code LIBGL_ALWAYS_SOFTWARE=1}, {@code MESA_GL_VERSION_OVERRIDE=4.5}, xvfb), so
 * these tests are the way to actually CI-verify the GL compute paths instead of shipping them
 * review-only.</p>
 *
 * <p>Tests extend this class and run their GL work inside {@link #runWithGLContext(GLTestBody)},
 * which creates a hidden GL 4.3 window, makes its context current on the calling thread, runs the
 * body, and tears everything down — all on one thread, mirroring the proven single-method pattern in
 * {@code ESVTGPUIntegrationTest} (a cross-method static context risks running on a different JUnit
 * thread than the one it was made current on). If no GL 4.3 context can be created (macOS 4.1 cap, no
 * display, software GL absent) the body is skipped via {@link Assumptions}, never failed.</p>
 *
 * <p>The class is {@code @Tag("gl-software")} so the mesa job can select it with
 * {@code -Dgroups=gl-software}, and {@code @EnabledIfEnvironmentVariable(RUN_GPU_TESTS=true)} so it
 * stays inert in normal local / CI runs (no GL context there). Both are inherited by subclasses.</p>
 *
 * @author hal.hildebrand
 */
@Tag("gl-software")
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true",
        disabledReason = "GL 4.3 compute context required — run under the mesa-software-test CI job "
                + "(LIBGL_ALWAYS_SOFTWARE=1, MESA_GL_VERSION_OVERRIDE=4.5) or RUN_GPU_TESTS=true on GL-4.3 hardware")
public abstract class GLComputeTestSupport {

    /** A unit of test work that runs with a current OpenGL 4.3 context. */
    @FunctionalInterface
    protected interface GLTestBody {
        void run() throws Exception;
    }

    /**
     * Create a hidden OpenGL 4.3 context, run {@code body} with it current on this thread, then
     * destroy it. Skips (does not fail) when a GL 4.3 context is unavailable.
     */
    protected void runWithGLContext(GLTestBody body) throws Exception {
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);

        boolean glfwInitialized = false;
        long window = NULL;
        try {
            if (!glfwInit()) {
                Assumptions.assumeTrue(false, "GLFW initialization failed — no GL context available");
                return;
            }
            glfwInitialized = true;

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            if (Platform.get() == Platform.MACOSX) {
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            }

            window = glfwCreateWindow(64, 64, "gl-compute-test", NULL, NULL);
            if (window == NULL) {
                // macOS caps at GL 4.1; compute shaders need 4.3. Skip rather than fail.
                Assumptions.assumeTrue(false,
                        "Could not create a GL 4.3 context (macOS caps at 4.1; needs Mesa software GL or GL-4.3 hardware)");
                return;
            }

            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.printf("GL context: vendor=%s renderer=%s version=%s%n",
                    glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));

            body.run();
        } finally {
            if (window != NULL) {
                glfwMakeContextCurrent(NULL);
                glfwDestroyWindow(window);
            }
            if (glfwInitialized) {
                glfwTerminate();
            }
            glfwSetErrorCallback(null);
            if (errorCallback != null) {
                errorCallback.free();
            }
        }
    }
}
