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

import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.X;
import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.Y;
import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.Z;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.lucien.animus.Rotor3f;

import javafx.application.Application;
import javafx.event.EventHandler;
import javafx.scene.Camera;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
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
    }

    protected static final double AXIS_LENGTH        = 250.0;
    protected static final double CONTROL_MULTIPLIER = 0.1;
    protected static final double MOUSE_SPEED        = 0.1;
    protected static final double ROTATION_SPEED     = 2.0;
    protected static final double SHIFT_MULTIPLIER   = 10.0;
    protected static final double TRACK_SPEED        = 0.3;

    public static void main(String[] args) {
        launch(args);
    }

    protected final Group axisGroup         = new Group();
    protected Portal      portal;
    protected final Group root              = new Group();
    protected final Xform transformingGroup = new Xform();
    protected final Xform world             = new Xform();

    public MagicMirror() {
        super();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        root.getChildren().add(world);
        root.setDepthTest(DepthTest.ENABLE);
        portal = portal();

        world.getChildren().addAll(portal.getAvatar().getAnimated(), portal.getCamera().getAnimated());

        Scene scene = new Scene(root, 1024, 768, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.LIGHTGRAY);
        handleKeyboard(scene);
        handleMouse(scene);

        primaryStage.setTitle(title());
        primaryStage.setScene(scene);
        primaryStage.show();

        portal.setCamera(scene);
        resetCameraDefault();

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
            var position = portal.getCamera().getPosition();

            final var p = position.get();
            p.z = (float) (p.z + event.getDeltaY() * modifierFactor * modifier);
            position.set(p);
        });
    }

    abstract protected Animus<Node> animus();

    protected void buildAxes() {
        final PhongMaterial redMaterial = new PhongMaterial();
        redMaterial.setDiffuseColor(Color.DARKRED);
        redMaterial.setSpecularColor(Color.RED);

        final PhongMaterial greenMaterial = new PhongMaterial();
        greenMaterial.setDiffuseColor(Color.DARKGREEN);
        greenMaterial.setSpecularColor(Color.GREEN);

        final PhongMaterial blueMaterial = new PhongMaterial();
        blueMaterial.setDiffuseColor(Color.DARKBLUE);
        blueMaterial.setSpecularColor(Color.BLUE);

        final Box xAxis = new Box(AXIS_LENGTH, 1, 1);
        final Box yAxis = new Box(1, AXIS_LENGTH, 1);
        final Box zAxis = new Box(1, 1, AXIS_LENGTH);

        xAxis.setMaterial(redMaterial);
        yAxis.setMaterial(greenMaterial);
        zAxis.setMaterial(blueMaterial);

        axisGroup.getChildren().addAll(xAxis, yAxis, zAxis);
        axisGroup.setVisible(false);
        world.getChildren().addAll(axisGroup);
    }

    abstract protected Animus<Camera> camera();

    protected void handleKeyboard(Scene scene) {
        scene.setOnKeyPressed(new EventHandler<KeyEvent>() {

            @Override
            public void handle(KeyEvent event) {
                switch (event.getCode()) {
                case Z:
                    resetCameraDefault();
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
        final var position = portal.getCamera().getPosition();
        final var orientation = portal.getCamera().getOrientation();

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

                if (me.isMiddleButtonDown() || (me.isPrimaryButtonDown() && me.isSecondaryButtonDown())) {
                    var p = new Vector3f(position.get());
                    p.add(new Point3f((float) (h.mouseDeltaX * MOUSE_SPEED * modifier * TRACK_SPEED),
                                      (float) (h.mouseDeltaY * MOUSE_SPEED * modifier * TRACK_SPEED), 0f));
                    position.set(p);
                } else if (me.isPrimaryButtonDown()) {
                    var o = new Rotor3f(orientation.get());
                    o.combine(X.slerp((float) (-h.mouseDeltaX * MOUSE_SPEED * modifier * ROTATION_SPEED)))
                     .combine(Y.slerp((float) (h.mouseDeltaY * MOUSE_SPEED * modifier * ROTATION_SPEED)));
                    orientation.set(o);
                } else if (me.isSecondaryButtonDown()) {
                    var p = new Vector3f(position.get());
                    p.z = (float) (p.z + h.mouseDeltaX * MOUSE_SPEED * modifier);
                    position.set(p);
                }
            }
        });
        return h;
    }

    protected Portal portal() {
        return new Portal(animus(), camera());
    }

    abstract protected void resetCameraDefault();

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
