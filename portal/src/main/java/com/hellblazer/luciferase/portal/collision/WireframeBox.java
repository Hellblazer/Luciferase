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
package com.hellblazer.luciferase.portal.collision;

import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;

/**
 * A wireframe box implementation using cylinders for edges.
 * Provides a clean wireframe visualization for bounding boxes and collision shapes.
 *
 * @author hal.hildebrand
 */
public class WireframeBox extends Box {
    
    public WireframeBox(double width, double height, double depth) {
        this(width, height, depth, new PhongMaterial());
    }
    
    public WireframeBox(double width, double height, double depth, Material material) {
        super(width, height, depth);
        setMaterial(material);
        setDrawMode(DrawMode.LINE);
    }
    
}