/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.portal.CubicGrid;
import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;
import javafx.application.Application;
import javafx.event.EventHandler;
import javafx.scene.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * @author hal.hildebrand
 */
public abstract class Abstract3DApp extends Application {

    public static final    float CUBE_EDGE_LENGTH        = (float) (Math.sqrt(2) / 2);
    protected static final float AXIS_LENGTH             = 250.0f;
    protected static final float CAMERA_FAR_CLIP         = 10000.0f;
    protected static final float CAMERA_INITIAL_DISTANCE = -450;
    protected static final float CAMERA_INITIAL_X_ANGLE  = 70.0f;
    protected static final float CAMERA_INITIAL_Y_ANGLE  = 320.0f;
    protected static final float CAMERA_NEAR_CLIP        = 0.1f;
    protected static final float CONTROL_MULTIPLIER      = 0.1f;
    protected static final float MOUSE_SPEED             = 0.1f;
    protected static final float ROTATION_SPEED          = 2.0f;
    protected static final float SHIFT_MULTIPLIER        = 10.0f;

    protected static final double            TRACK_SPEED       = 0.3;
    protected final        Xform             axisGroup         = new Xform();
    protected final        PerspectiveCamera camera            = new PerspectiveCamera(true);
    protected final        Xform             cameraXform       = new Xform();
    protected final        Xform             cameraXform2      = new Xform();
    protected final        Xform             cameraXform3      = new Xform();
    protected final        Group             root              = new Group();
    protected final        Xform             transformingGroup = new Xform();
    protected final        Xform             world             = new Xform();
    protected              double            mouseDeltaX;
    protected              double            mouseDeltaY;
    protected              double            mouseOldX;
    protected              double            mouseOldY;
    protected              double            mousePosX;
    protected              double            mousePosY;

    public Abstract3DApp() {
        super();
    }

    @Override
    public void start(Stage primaryStage) {

        // setUserAgentStylesheet(STYLESHEET_MODENA);

        root.getChildren().add(world);
        root.setDepthTest(DepthTest.ENABLE);

        // buildScene();
        buildCamera();
        buildAxes();
        var view = build();
        var auto = new AutoScalingGroup(2);
        auto.getChildren().add(getLight());
        auto.enabledProperty().set(true);
        auto.getChildren().add(view);
        transformingGroup.getChildren().add(auto);
        world.getChildren().addAll(transformingGroup);

        Scene scene = new Scene(root, 1024, 768, true);
        scene.setFill(Color.LIGHTGRAY);
        handleKeyboard(scene, world);
        handleMouse(scene, world);

        primaryStage.setTitle(title());
        primaryStage.setScene(scene);
        primaryStage.show();

        scene.setCamera(camera);
        // Attach a scroll listener
        primaryStage.addEventHandler(ScrollEvent.SCROLL, event -> {
            double modifier = 50.0;
            double modifierFactor = 0.01;
            if (event.isControlDown()) {
                modifier = 1;
            }
            if (event.isShiftDown()) {
                modifier = 100.0;
            }
            double z = camera.getTranslateZ();
            double newZ = z + event.getDeltaY() * modifierFactor * modifier;
            camera.setTranslateZ(newZ);
        });
    }

    protected abstract Group build();

    protected void buildAxes() {
        final var cubic = new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1);
        cubic.addAxes(axisGroup, 0.1f, 0.2f, 0.008f, 20);
        axisGroup.setVisible(false);
        world.getChildren().addAll(axisGroup);
    }

    protected void buildCamera() {
        root.getChildren().add(cameraXform);
        cameraXform.getChildren().add(cameraXform2);
        cameraXform2.getChildren().add(cameraXform3);
        cameraXform3.getChildren().add(camera);
        cameraXform3.setRotateZ(180.0);

        camera.setNearClip(CAMERA_NEAR_CLIP);
        camera.setFarClip(CAMERA_FAR_CLIP);
        camera.setTranslateZ(CAMERA_INITIAL_DISTANCE);
        cameraXform.ry.setAngle(CAMERA_INITIAL_Y_ANGLE);
        cameraXform.rx.setAngle(CAMERA_INITIAL_X_ANGLE);
    }

    protected Node getLight() {
        return new AmbientLight();
    }

    protected void handleKeyboard(Scene scene, final Node root) {
        scene.setOnKeyPressed(new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                switch (event.getCode()) {
                    case Z:
                        cameraXform2.t.setX(0.0);
                        cameraXform2.t.setY(0.0);
                        camera.setTranslateZ(CAMERA_INITIAL_DISTANCE);
                        cameraXform.ry.setAngle(CAMERA_INITIAL_Y_ANGLE);
                        cameraXform.rx.setAngle(CAMERA_INITIAL_X_ANGLE);
                        break;
                    case X:
                        axisGroup.setVisible(!axisGroup.isVisible());
                        break;
                    case V:
                        transformingGroup.setVisible(!transformingGroup.isVisible());
                        break;
                    default:
                        break;
                }
            }
        });
    }

    protected void handleMouse(Scene scene, final Node root) {
        scene.setOnMousePressed(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent me) {
                mousePosX = me.getSceneX();
                mousePosY = me.getSceneY();
                mouseOldX = me.getSceneX();
                mouseOldY = me.getSceneY();
            }
        });
        scene.setOnMouseDragged(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent me) {
                mouseOldX = mousePosX;
                mouseOldY = mousePosY;
                mousePosX = me.getSceneX();
                mousePosY = me.getSceneY();
                mouseDeltaX = (mousePosX - mouseOldX);
                mouseDeltaY = (mousePosY - mouseOldY);

                double modifier = 1.0;

                if (me.isControlDown()) {
                    modifier = CONTROL_MULTIPLIER;
                }
                if (me.isShiftDown()) {
                    modifier = SHIFT_MULTIPLIER;
                }

                if (me.isMiddleButtonDown() || (me.isPrimaryButtonDown() && me.isSecondaryButtonDown())) {
                    cameraXform2.t.setX(cameraXform2.t.getX() + mouseDeltaX * MOUSE_SPEED * modifier * TRACK_SPEED);
                    cameraXform2.t.setY(cameraXform2.t.getY() + mouseDeltaY * MOUSE_SPEED * modifier * TRACK_SPEED);
                } else if (me.isPrimaryButtonDown()) {
                    cameraXform.ry.setAngle(
                    cameraXform.ry.getAngle() - mouseDeltaX * MOUSE_SPEED * modifier * ROTATION_SPEED);
                    cameraXform.rx.setAngle(
                    cameraXform.rx.getAngle() + mouseDeltaY * MOUSE_SPEED * modifier * ROTATION_SPEED);
                } else if (me.isSecondaryButtonDown()) {
                    double z = camera.getTranslateZ();
                    double newZ = z + mouseDeltaX * MOUSE_SPEED * modifier;
                    camera.setTranslateZ(newZ);
                }
            }
        });
    }

    abstract protected String title();

}
