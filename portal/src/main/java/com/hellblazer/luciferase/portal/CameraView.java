/**
 * CameraView.java
 *
 * Copyright (c) 2013-2016, F(X)yz All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met: * Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer. * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or other materials provided with the
 * distribution. * Neither the name of F(X)yz, any associated website, nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL F(X)yz BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.portal.mesh.explorer.Xform;
import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * This class is based on "AnotherView.java" provided by:
 *
 * Date: 2013/10/31
 *
 * Author: August Lammersdorf, InteractiveMesh e.K. Hauptstraï¿½e 28d, 85737 Ismaning Germany / Munich Area
 * www.InteractiveMesh.com/org
 *
 * Please create your own implementation. This source code is provided "AS IS", without warranty of any kind. You are
 * allowed to copy and use all lines you like of this source code without any copyright notice, but you may not modify,
 * compile, or distribute this 'AnotherView.java'.
 *
 *
 * Following changes were made: replaced Affine with standard Rotate transforms for Camera rx, ry, rz with first person
 * controls. extended ImageView directly (rather than nested node). changed constructors to accept a SubScene, or Group,
 * and/or specified PerspectiveCamera ***ToDo
 *
 * @author Dub
 */
public final class CameraView extends ImageView {
    private final SnapshotParameters params          = new SnapshotParameters();
    private final PerspectiveCamera  camera;
    private final Xform              cameraTransform = new Xform();
    private       WritableImage     image           = null;
    private       double             mouseDeltaX;
    private       double             mouseDeltaY;
    private       double             mouseOldX;
    private       double             mouseOldY;
    private       double             mousePosX;
    private       double mousePosY;
    private final Rotate rx = new Rotate(0, 0, 0, 0, Rotate.X_AXIS);
    private final Rotate ry = new Rotate(0, 0, 0, 0, Rotate.Y_AXIS);
    private final Rotate rz = new Rotate(0, 0, 0, 0, Rotate.Z_AXIS);
    private final double startX = 0;
    private final double startY = 0;
    private final Translate t      = new Translate(0, 0, 0);
    private       AnimationTimer viewTimer = null;
    private final Group          worldToView;
    
    // Enhanced navigation state
    private boolean isPanning = false;
    private double panSpeed = 1.0;
    private double rotateSpeed = 1.0;
    private double zoomSpeed = 10.0;

    public CameraView(SubScene scene) {
        // Make sure "world" is a group
        assert scene.getRoot().getClass().equals(Group.class);

        worldToView = (Group) scene.getRoot();

        camera = new PerspectiveCamera(true);
        //        cameraTransform.setTranslate(0, 0, -500);
        cameraTransform.getChildren().add(camera);
        camera.setNearClip(0.1);
        camera.setFarClip(15000.0);
        camera.setTranslateZ(-1500);
        cameraTransform.ry.setAngle(-45.0);
        cameraTransform.rx.setAngle(-10.0);

        params.setCamera(camera);

        params.setDepthBuffer(true);
        params.setFill(Color.rgb(0, 0, 0, 0.5));

        viewTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                redraw();
            }
        };
    }

    public PerspectiveCamera getCamera() {
        return camera;
    }

    public Rotate getRx() {
        return rx;
    }

    public Rotate getRy() {
        return ry;
    }

    public Rotate getRz() {
        return rz;
    }

    public Translate getT() {
        return t;
    }

    public void pause() {
        viewTimer.stop();
    }

    public void setFirstPersonNavigationEabled(boolean b) {
        if (b) {
            // Navigation
            setMouseTransparent(false);

            // Enhanced keyboard movement with panning
            setOnKeyPressed(event -> {
                double moveSpeed = 10.0;
                double verticalSpeed = 10.0;
                
                // Add shift modifier to simulate "Running Speed"
                if (event.isShiftDown()) {
                    moveSpeed = 50.0;
                    verticalSpeed = 50.0;
                }
                
                // Add ctrl modifier for fine control
                if (event.isControlDown()) {
                    moveSpeed = 2.0;
                    verticalSpeed = 2.0;
                }
                
                // What key did the user press?
                KeyCode keycode = event.getCode();
                
                // Forward/Backward movement (Z-axis)
                if (keycode == KeyCode.W) {
                    camera.setTranslateZ(camera.getTranslateZ() + moveSpeed);
                }
                if (keycode == KeyCode.S) {
                    camera.setTranslateZ(camera.getTranslateZ() - moveSpeed);
                }
                
                // Left/Right strafe (X-axis)
                if (keycode == KeyCode.A) {
                    camera.setTranslateX(camera.getTranslateX() - moveSpeed);
                }
                if (keycode == KeyCode.D) {
                    camera.setTranslateX(camera.getTranslateX() + moveSpeed);
                }
                
                // Up/Down movement (Y-axis)
                if (keycode == KeyCode.Q) {
                    camera.setTranslateY(camera.getTranslateY() - verticalSpeed);
                }
                if (keycode == KeyCode.E) {
                    camera.setTranslateY(camera.getTranslateY() + verticalSpeed);
                }
                
                // Reset camera position
                if (keycode == KeyCode.R) {
                    resetCamera();
                }
                
                // Pan mode toggle
                if (keycode == KeyCode.SPACE) {
                    isPanning = !isPanning;
                }
            });

            setOnMousePressed((MouseEvent me) -> {
                mousePosX = me.getSceneX();
                mousePosY = me.getSceneY();
                mouseOldX = me.getSceneX();
                mouseOldY = me.getSceneY();

            });
            setOnMouseDragged((MouseEvent me) -> {
                mouseOldX = mousePosX;
                mouseOldY = mousePosY;
                mousePosX = me.getSceneX();
                mousePosY = me.getSceneY();
                mouseDeltaX = (mousePosX - mouseOldX);
                mouseDeltaY = (mousePosY - mouseOldY);

                double modifier = 10.0;
                double modifierFactor = 0.1;

                if (me.isControlDown()) {
                    modifier = 0.1;
                }
                if (me.isShiftDown()) {
                    modifier = 50.0;
                }
                if (me.isPrimaryButtonDown()) {
                    if (isPanning) {
                        // Pan mode - move camera in XY plane
                        camera.setTranslateX(camera.getTranslateX() - mouseDeltaX * modifierFactor * modifier * panSpeed);
                        camera.setTranslateY(camera.getTranslateY() - mouseDeltaY * modifierFactor * modifier * panSpeed);
                    } else {
                        // Rotate mode - rotate camera view
                        cameraTransform.ry.setAngle(
                        ((cameraTransform.ry.getAngle() + mouseDeltaX * modifierFactor * modifier * rotateSpeed * 2.0) % 360 + 540) % 360
                        - 180);

                        cameraTransform.rx.setAngle(
                        ((cameraTransform.rx.getAngle() - mouseDeltaY * modifierFactor * modifier * rotateSpeed * 2.0) % 360 + 540) % 360
                        - 180);
                    }
                } else if (me.isSecondaryButtonDown()) {
                    // Right button - zoom
                    double z = camera.getTranslateZ();
                    double newZ = z + mouseDeltaY * modifierFactor * modifier * zoomSpeed;
                    camera.setTranslateZ(newZ);
                } else if (me.isMiddleButtonDown()) {
                    // Middle button - pan in XY plane (alternative to space+drag)
                    camera.setTranslateX(camera.getTranslateX() - mouseDeltaX * modifierFactor * modifier * panSpeed);
                    camera.setTranslateY(camera.getTranslateY() - mouseDeltaY * modifierFactor * modifier * panSpeed);
                }
            });
            
            // Mouse wheel for zoom
            setOnScroll(event -> {
                double zoomFactor = 1.05;
                double deltaY = event.getDeltaY();
                
                if (deltaY < 0) {
                    camera.setTranslateZ(camera.getTranslateZ() / zoomFactor);
                } else {
                    camera.setTranslateZ(camera.getTranslateZ() * zoomFactor);
                }
            });

        } else {
            setOnMouseDragged(null);
            setOnScroll(null);
            setOnMousePressed(null);
            setOnKeyPressed(null);
            setMouseTransparent(true);
        }
    }

    public void startViewing() {
        viewTimer.start();
    }

    private void redraw() {
        params.setViewport(new Rectangle2D(0, 0, getFitWidth(), getFitHeight()));
        if (image == null || image.getWidth() != getFitWidth() || image.getHeight() != getFitHeight()) {
            image = worldToView.snapshot(params, null);
        } else {
            worldToView.snapshot(params, image);
        }
        setImage(image);
    }
    
    /**
     * Reset camera to default position and orientation
     */
    public void resetCamera() {
        camera.setTranslateX(0);
        camera.setTranslateY(0);
        camera.setTranslateZ(-1500);
        cameraTransform.rx.setAngle(-10.0);
        cameraTransform.ry.setAngle(-45.0);
        cameraTransform.rz.setAngle(0);
        cameraTransform.t.setX(0);
        cameraTransform.t.setY(0);
        cameraTransform.t.setZ(0);
    }
    
    /**
     * Set camera navigation speeds
     */
    public void setNavigationSpeeds(double pan, double rotate, double zoom) {
        this.panSpeed = pan;
        this.rotateSpeed = rotate;
        this.zoomSpeed = zoom;
    }
    
    /**
     * Get current pan mode state
     */
    public boolean isPanning() {
        return isPanning;
    }
    
    /**
     * Set pan mode
     */
    public void setPanning(boolean panning) {
        this.isPanning = panning;
    }
    
    /**
     * Camera state snapshot for preservation across scene changes.
     * Captures position, rotation, and transform state.
     */
    public static class CameraState {
        public final double translateX;
        public final double translateY;
        public final double translateZ;
        public final double rxAngle;
        public final double ryAngle;
        public final double rzAngle;
        public final double tX;
        public final double tY;
        public final double tZ;
        
        public CameraState(double translateX, double translateY, double translateZ,
                          double rxAngle, double ryAngle, double rzAngle,
                          double tX, double tY, double tZ) {
            this.translateX = translateX;
            this.translateY = translateY;
            this.translateZ = translateZ;
            this.rxAngle = rxAngle;
            this.ryAngle = ryAngle;
            this.rzAngle = rzAngle;
            this.tX = tX;
            this.tY = tY;
            this.tZ = tZ;
        }
    }
    
    /**
     * Save current camera state for later restoration.
     * Captures all position, rotation, and transform values.
     * 
     * @return CameraState snapshot of current camera configuration
     */
    public CameraState saveCameraState() {
        return new CameraState(
            camera.getTranslateX(),
            camera.getTranslateY(),
            camera.getTranslateZ(),
            cameraTransform.rx.getAngle(),
            cameraTransform.ry.getAngle(),
            cameraTransform.rz.getAngle(),
            cameraTransform.t.getX(),
            cameraTransform.t.getY(),
            cameraTransform.t.getZ()
        );
    }
    
    /**
     * Restore camera to a previously saved state.
     * Applies all position, rotation, and transform values.
     * 
     * @param state Previously saved camera state
     */
    public void restoreCameraState(CameraState state) {
        if (state == null) {
            return;
        }
        
        camera.setTranslateX(state.translateX);
        camera.setTranslateY(state.translateY);
        camera.setTranslateZ(state.translateZ);
        cameraTransform.rx.setAngle(state.rxAngle);
        cameraTransform.ry.setAngle(state.ryAngle);
        cameraTransform.rz.setAngle(state.rzAngle);
        cameraTransform.t.setX(state.tX);
        cameraTransform.t.setY(state.tY);
        cameraTransform.t.setZ(state.tZ);
    }

}
