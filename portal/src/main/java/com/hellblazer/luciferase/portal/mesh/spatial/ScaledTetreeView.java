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
package com.hellblazer.luciferase.portal.mesh.spatial;

import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;

/**
 * A TetreeView that uses ScaledCellViews to properly handle the massive
 * coordinate range of spatial indices (0 to 2^21).
 *
 * @author hal.hildebrand
 */
public class ScaledTetreeView extends TetreeView {
    
    /**
     * Create a ScaledTetreeView with default settings.
     * Occupied cells shown as mesh with translucent red material.
     * Parent wireframes shown in black.
     */
    public ScaledTetreeView() {
        super(true, 
              new PhongMaterial(Color.RED.deriveColor(0, 1, 1, 0.6)),
              new PhongMaterial(Color.RED),
              new PhongMaterial(Color.BLACK.deriveColor(0, 1, 1, 0.3)),
              new ScaledCellViews());
    }
    
    /**
     * Create a ScaledTetreeView with specified display mode.
     * 
     * @param showOccupiedAsMesh If true, occupied cells shown as mesh; otherwise as wireframe
     */
    public ScaledTetreeView(boolean showOccupiedAsMesh) {
        super(showOccupiedAsMesh,
              new PhongMaterial(Color.RED.deriveColor(0, 1, 1, 0.6)),
              new PhongMaterial(Color.RED),
              new PhongMaterial(Color.BLACK.deriveColor(0, 1, 1, 0.3)),
              new ScaledCellViews());
    }
    
    /**
     * Create a ScaledTetreeView with full customization.
     * 
     * @param showOccupiedAsMesh If true, occupied cells shown as mesh; otherwise as wireframe
     * @param occupiedMeshMaterial Material for occupied cell meshes
     * @param occupiedWireframeMaterial Material for occupied cell wireframes
     * @param parentWireframeMaterial Material for parent cell wireframes
     */
    public ScaledTetreeView(boolean showOccupiedAsMesh, 
                           Material occupiedMeshMaterial,
                           Material occupiedWireframeMaterial,
                           Material parentWireframeMaterial) {
        super(showOccupiedAsMesh, occupiedMeshMaterial, occupiedWireframeMaterial, 
              parentWireframeMaterial, new ScaledCellViews());
    }
}