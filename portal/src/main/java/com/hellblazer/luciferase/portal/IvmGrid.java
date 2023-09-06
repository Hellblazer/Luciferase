/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal;

import java.util.function.Consumer;

import javax.vecmath.Point3i;

import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Transform;

/**
 * @author hal.hildebrand
 */
public class IvmGrid {
    private final Point3i extent;
    private final boolean inverse;

    public IvmGrid(Point3i extent, boolean inverse) {
        this.extent = extent;
        this.inverse = inverse;
    }

    public void forEach(Consumer<? super Point3i> action) {
        for (int i = 0; i < extent.x; i++) {
            for (int j = 0; j < extent.y; j++) {
                for (int k = 0; k < extent.z; k++) {
                    if (inverse) {
                        if ((i + j + k) % 2 != 0) {
                            action.accept(new Point3i(i, j, k));
                        }
                    } else {
                        if ((i + j + k) % 2 == 0) {
                            action.accept(new Point3i(i, j, k));
                        }
                    }
                }
            }
        }
    }

    public Group populate(Material material, double radius, CubicGrid grid) {
        var group = new Group();
        forEach(location -> {

            Transform position = grid.postitionTransform(location.x - Math.ceil(extent.x / 2),
                                                         location.y - Math.ceil(extent.y / 2),
                                                         location.z - Math.ceil(extent.z / 2));
            var sphere = new Sphere(radius);
            sphere.setMaterial(material);
            sphere.getTransforms().clear();
            sphere.getTransforms().addAll(position);
            group.getChildren().add(sphere);
        });
        return group;
    }
}
