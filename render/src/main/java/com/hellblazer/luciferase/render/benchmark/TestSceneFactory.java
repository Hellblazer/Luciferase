/*
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.render.benchmark;

import com.hellblazer.luciferase.render.benchmark.scenes.*;

/**
 * Factory for creating standard test scenes for Phase 5a.5 benchmarking.
 *
 * <p>Provides convenient methods for instantiating the five core test scenarios:
 * <ul>
 *   <li>SkyScene: High coherence (>= 0.9), parallel rays</li>
 *   <li>GeometryScene: Low coherence (<= 0.4), divergent rays</li>
 *   <li>MixedScene: Configurable mix (default 60/40 sky/geometry)</li>
 *   <li>CameraMovementScene: Multi-frame cache validation</li>
 *   <li>LargeFrameScene: 4K stress test</li>
 * </ul>
 *
 * @see SkyScene
 * @see GeometryScene
 * @see MixedScene
 * @see CameraMovementScene
 * @see LargeFrameScene
 */
public class TestSceneFactory {

    /**
     * Standard test frame size (256x256 = 65,536 rays).
     */
    public static final int STANDARD_FRAME_SIZE = 256;

    /**
     * 4K frame width (3840 pixels).
     */
    public static final int FRAME_4K_WIDTH = 3840;

    /**
     * 4K frame height (2160 pixels).
     */
    public static final int FRAME_4K_HEIGHT = 2160;

    /**
     * Create a sky scene with standard frame size.
     *
     * @return SkyScene with 256x256 frame, all parallel rays
     */
    public static SkyScene createSkyScene() {
        return new SkyScene(STANDARD_FRAME_SIZE, STANDARD_FRAME_SIZE);
    }

    /**
     * Create a geometry scene with standard frame size.
     *
     * @return GeometryScene with 256x256 frame, all divergent rays
     */
    public static GeometryScene createGeometryScene() {
        return new GeometryScene(STANDARD_FRAME_SIZE, STANDARD_FRAME_SIZE);
    }

    /**
     * Create a mixed scene with standard frame size and default 60/40 split.
     *
     * @return MixedScene with 60% sky, 40% geometry
     */
    public static MixedScene createMixedScene() {
        return new MixedScene(STANDARD_FRAME_SIZE, STANDARD_FRAME_SIZE, 0.6);
    }

    /**
     * Create a mixed scene with custom sky ratio.
     *
     * @param skyRatio Proportion of frame showing sky (0.0 to 1.0)
     * @return MixedScene with specified split
     */
    public static MixedScene createMixedScene(double skyRatio) {
        return new MixedScene(STANDARD_FRAME_SIZE, STANDARD_FRAME_SIZE, skyRatio);
    }

    /**
     * Create a camera movement scene with standard frame size.
     *
     * @return CameraMovementScene with 10 frames
     */
    public static CameraMovementScene createCameraMovementScene() {
        return new CameraMovementScene(STANDARD_FRAME_SIZE, STANDARD_FRAME_SIZE);
    }

    /**
     * Create a large frame (4K) scene.
     *
     * <p><b>NOTE</b>: Requires minimum 1GB JVM heap (-Xmx1g).
     *
     * @return LargeFrameScene with 3840x2160 resolution
     */
    public static LargeFrameScene createLargeFrameScene() {
        return new LargeFrameScene(FRAME_4K_WIDTH, FRAME_4K_HEIGHT);
    }
}
