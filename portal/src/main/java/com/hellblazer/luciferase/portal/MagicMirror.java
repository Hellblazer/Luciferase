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

import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.X;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Y;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Z;
import static com.hellblazer.luciferase.geometry.Rotor3f.RotationOrder.ZYX;

import com.hellblazer.luciferase.geometry.Rotor3f;
import com.hellblazer.luciferase.portal.CubicGrid.Neighborhood;
import com.hellblazer.luciferase.portal.mesh.explorer.Xform;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;

import javafx.application.Application;
import javafx.event.EventHandler;
import javafx.geometry.Point3D;
import javafx.scene.Camera;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;

/**
 * @author hal.hildebrand
 */
public abstract class MagicMirror extends Application {
    public static class MouseHandler {
        protected double mouseDeltaX;
        protected double mouseDeltaY;
        protected double mouseOldX;
        protected double mouseOldY;
        protected double mousePosX;
        protected double mousePosY;
        protected float  rx;
        protected float  ry;
    }

    public static final float CUBE_EDGE_LENGTH = (float) (Math.sqrt(2) / 2);
    public static final float TET_EDGE_LENGTH  = 1;

    protected static final float AXIS_LENGTH             = 250.0f;
    protected static final float CAMERA_FAR_CLIP         = 10000.0f;
    protected static final float CAMERA_INITIAL_DISTANCE = -450f;
    protected static final float CAMERA_INITIAL_X_ANGLE  = 70.0f;
    protected static final float CAMERA_INITIAL_Y_ANGLE  = 320.0f;
    protected static final float CAMERA_NEAR_CLIP        = 0.1f;
    protected static final float CONTROL_MULTIPLIER      = 0.1f;
    protected static final float MOUSE_SPEED             = 0.1f;
    protected static final float ROTATION_SPEED          = 2.0f;
    protected static final float SHIFT_MULTIPLIER        = 10.0f;
    protected static final float TRACK_SPEED             = 0.3f;

    public static void lookAt(Point3D cameraPosition, Point3D lookAtPos, Camera cam) {
        // Create direction vector
        Point3D camDirection = lookAtPos.subtract(cameraPosition.getX(), cameraPosition.getY(), cameraPosition.getZ());
        camDirection = camDirection.normalize();
        double xRotation = Math.toDegrees(Math.asin(-camDirection.getY()));
        double yRotation = Math.toDegrees(Math.atan2(camDirection.getX(), camDirection.getZ()));
        Rotate rx = new Rotate(xRotation, cameraPosition.getX(), cameraPosition.getY(), cameraPosition.getZ(),
                               Rotate.X_AXIS);
        Rotate ry = new Rotate(yRotation, cameraPosition.getX(), cameraPosition.getY(), cameraPosition.getZ(),
                               Rotate.Y_AXIS);
        cam.getTransforms()
           .addAll(ry, rx, new Translate(cameraPosition.getX(), cameraPosition.getY(), cameraPosition.getZ()));
    }

    public static void main(String[] args) {
        launch(args);
    }

    protected final Group             axisGroup         = new Group();
    protected final PerspectiveCamera camera;
    protected final OrientedGroup     cameraTransform;
    protected final Group             root              = new Group();
    protected final Xform             transformingGroup = new Xform();
    protected final Xform             world             = new Xform();

    public MagicMirror() {
        super();

        var t = new OrientedTxfm();
        t.next(new OrientedTxfm()).next(new OrientedTxfm()).rotate(ZYX, 180, 0, 0);
        t.rotate(ZYX, CAMERA_INITIAL_X_ANGLE, CAMERA_INITIAL_Y_ANGLE, 0);

        cameraTransform = new OrientedGroup(t);
        camera = new PerspectiveCamera(true);
        cameraTransform.getChildren().add(camera);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        root.getChildren().add(world);
        root.setDepthTest(DepthTest.ENABLE);
        root.getChildren().add(cameraTransform);

        camera.setNearClip(CAMERA_NEAR_CLIP);
        camera.setFarClip(CAMERA_FAR_CLIP);
        camera.setTranslateZ(CAMERA_INITIAL_DISTANCE / 4);
        buildAxes();

        Scene scene = new Scene(root, 1024, 768, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.LIGHTGRAY);
        handleKeyboard(scene);
        handleMouse(scene);

        primaryStage.setTitle(title());
        primaryStage.setScene(scene);
        primaryStage.show();

        scene.setCamera(camera);

        // Attach a scroll listener
        primaryStage.addEventHandler(ScrollEvent.SCROLL, event -> {
            float modifier = 50.0f;
            float modifierFactor = 0.01f;
            if (event.isControlDown()) {
                modifier = 1;
            }
            if (event.isShiftDown()) {
                modifier = 100.0f;
            }
            double z = camera.getTranslateZ();
            double newZ = z + event.getDeltaY() * modifierFactor * modifier;
            camera.setTranslateZ(newZ);
        });
    }

    protected void buildAxes() {
        final var cubic = new CubicGrid(Neighborhood.EIGHT, new Cube(CUBE_EDGE_LENGTH), 1);
        cubic.addAxes(axisGroup, 0.1f, 0.2f, 0.008f, 20);
        axisGroup.setVisible(false);
        world.getChildren().addAll(axisGroup);
    }

    protected void handleKeyboard(Scene scene) {
        scene.setOnKeyPressed(new EventHandler<KeyEvent>() {

            @Override
            public void handle(KeyEvent event) {
                switch (event.getCode()) {
                case Z:
                    cameraTransform.getTransform().reset();
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

    protected MouseHandler handleMouse(Scene scene) {
        var h = new MouseHandler();

        scene.setOnMousePressed(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent me) {
                h.mousePosX = me.getSceneX();
                h.mousePosY = me.getSceneY();
                h.mouseOldX = me.getSceneX();
                h.mouseOldY = me.getSceneY();
            }
        });
        scene.setOnMouseDragged(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent me) {
                h.mouseOldX = h.mousePosX;
                h.mouseOldY = h.mousePosY;
                h.mousePosX = me.getSceneX();
                h.mousePosY = me.getSceneY();
                h.mouseDeltaX = (h.mousePosX - h.mouseOldX);
                h.mouseDeltaY = (h.mousePosY - h.mouseOldY);

                double modifier = 1.0;

                if (me.isControlDown()) {
                    modifier = CONTROL_MULTIPLIER;
                }
                if (me.isShiftDown()) {
                    modifier = SHIFT_MULTIPLIER;
                }
                var t = cameraTransform.getTransform();
                if (me.isMiddleButtonDown() || (me.isPrimaryButtonDown() && me.isSecondaryButtonDown())) {
                    t.next()
                     .translation()
                     .setX(t.next().translation().getX() + h.mouseDeltaX * MOUSE_SPEED * modifier * TRACK_SPEED);
                    t.next()
                     .translation()
                     .setY(t.next().translation().getY() + h.mouseDeltaY * MOUSE_SPEED * modifier * TRACK_SPEED);
                } else if (me.isPrimaryButtonDown()) {
                    h.ry = (float) (h.ry - h.mouseDeltaX * MOUSE_SPEED * modifier * ROTATION_SPEED);
                    h.rx = (float) (h.rx + h.mouseDeltaY * MOUSE_SPEED * modifier * ROTATION_SPEED);
                    t.rotate(ZYX, h.rx, h.ry, 0);
                } else if (me.isSecondaryButtonDown()) {
                    float z = (float) t.translation().getZ();
                    float newZ = (float) (z + h.mouseDeltaX * MOUSE_SPEED * modifier);
                    t.translate(0, 0, newZ);
                }
            }
        });
        return h;
    }

    protected Rotor3f rotation(KeyEvent event, float t) {
        return switch (event.getCode()) {
        case A -> X.slerp(t);
        case D -> X.slerp(-t);
        case W -> Y.slerp(t);
        case S -> Y.slerp(-t);
        case Q -> Z.slerp(t);
        case E -> Z.slerp(-t);
        default -> throw new IllegalArgumentException("Unhandled rotation key: %s".formatted(event.getCode()));
        };
    }

    protected String title() {
        return "Magic Mirror";
    }

}
