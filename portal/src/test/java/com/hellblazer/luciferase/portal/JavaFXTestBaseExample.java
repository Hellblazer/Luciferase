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
package com.hellblazer.luciferase.portal;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Sphere;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example test demonstrating usage of JavaFXTestBase for UI testing.
 * 
 * <p>This test verifies that:</p>
 * <ul>
 *   <li>JavaFX toolkit initializes correctly</li>
 *   <li>JavaFX nodes can be created in tests</li>
 *   <li>Scene graph operations work properly</li>
 *   <li>Materials and 3D shapes can be instantiated</li>
 * </ul>
 * 
 * @author hal.hildebrand
 */
public class JavaFXTestBaseExample extends JavaFXTestBase {

    @Test
    public void testJavaFXInitialization() {
        assertTrue(isJavaFXInitialized(), "JavaFX should be initialized");
    }

    @Test
    public void testCreateSimpleNode() throws Exception {
        runOnFxThreadAndWait(() -> {
            var box = new Box(1.0, 1.0, 1.0);
            assertNotNull(box);
            assertEquals(1.0, box.getWidth());
            assertEquals(1.0, box.getHeight());
            assertEquals(1.0, box.getDepth());
        });
    }

    @Test
    public void testCreateSceneGraph() throws Exception {
        runOnFxThreadAndWait(() -> {
            var root = new Group();
            var box = new Box(1.0, 1.0, 1.0);
            var sphere = new Sphere(0.5);

            root.getChildren().addAll(box, sphere);

            assertEquals(2, root.getChildren().size());
            assertTrue(root.getChildren().contains(box));
            assertTrue(root.getChildren().contains(sphere));
        });
    }

    @Test
    public void testMaterialCreation() throws Exception {
        runOnFxThreadAndWait(() -> {
            var material = new PhongMaterial(Color.BLUE);
            assertNotNull(material);
            assertEquals(Color.BLUE, material.getDiffuseColor());

            var box = new Box(1.0, 1.0, 1.0);
            box.setMaterial(material);
            assertEquals(material, box.getMaterial());
        });
    }

    @Test
    public void testNodeTransforms() throws Exception {
        runOnFxThreadAndWait(() -> {
            var box = new Box(1.0, 1.0, 1.0);
            
            // Set translation
            box.setTranslateX(10.0);
            box.setTranslateY(20.0);
            box.setTranslateZ(30.0);

            assertEquals(10.0, box.getTranslateX());
            assertEquals(20.0, box.getTranslateY());
            assertEquals(30.0, box.getTranslateZ());

            // Set scale
            box.setScaleX(2.0);
            box.setScaleY(3.0);
            box.setScaleZ(4.0);

            assertEquals(2.0, box.getScaleX());
            assertEquals(3.0, box.getScaleY());
            assertEquals(4.0, box.getScaleZ());
        });
    }

    @Test
    public void testGroupHierarchy() throws Exception {
        runOnFxThreadAndWait(() -> {
            var root = new Group();
            var childGroup = new Group();
            var box = new Box(1.0, 1.0, 1.0);

            childGroup.getChildren().add(box);
            root.getChildren().add(childGroup);

            assertEquals(1, root.getChildren().size());
            assertEquals(childGroup, root.getChildren().get(0));
            assertEquals(1, childGroup.getChildren().size());
            assertEquals(box, childGroup.getChildren().get(0));
        });
    }

    @Test
    public void testMultipleNodesCreation() throws Exception {
        runOnFxThreadAndWait(() -> {
            var group = new Group();

            // Create 10 boxes
            for (int i = 0; i < 10; i++) {
                var box = new Box(1.0, 1.0, 1.0);
                box.setTranslateX(i * 2.0);
                group.getChildren().add(box);
            }

            assertEquals(10, group.getChildren().size());

            // Verify positions
            for (int i = 0; i < 10; i++) {
                var node = group.getChildren().get(i);
                assertTrue(node instanceof Box);
                assertEquals(i * 2.0, node.getTranslateX());
            }
        });
    }
}
