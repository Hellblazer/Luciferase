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

import com.hellblazer.luciferase.lucien.collision.BoxShape;
import com.hellblazer.luciferase.lucien.collision.CapsuleShape;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import com.hellblazer.luciferase.lucien.collision.physics.ImpulseResolver;
import com.hellblazer.luciferase.lucien.collision.physics.InertiaTensor;
import com.hellblazer.luciferase.lucien.collision.physics.PhysicsMaterial;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import com.hellblazer.luciferase.portal.mesh.explorer.AutoScalingGroup;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Interactive application for debugging and visualizing collision detection and physics. Demonstrates all collision
 * shapes, contact points, and physics forces in real-time.
 *
 * @author hal.hildebrand
 */
public class CollisionDebugViewer extends Abstract3DApp {

    private final Random               random = new Random(42);
    private       CollisionVisualizer  visualizer;
    private       List<CollisionShape> shapes;
    private       List<RigidBody>      bodies;
    private       AnimationTimer       animationTimer;
    private       Group                rootGroup;
    // UI Controls
    private       CheckBox             showWireframes;
    private       CheckBox             showContactPoints;
    private       CheckBox             showPenetrationVectors;
    private       CheckBox             showVelocityVectors;
    private       CheckBox             showAABBs;
    private       CheckBox             invertMouse;
    private       Slider               vectorScale;
    private       ColorPicker          wireframeColor;
    private       ColorPicker          contactColor;
    private       Button               addSphere;
    private       Button               addBox;
    private       Button               addCapsule;
    private       Button               clearAll;
    private       Button               runPhysics;
    private       Label                statusLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    protected String title() {
        return "Collision Debug Viewer";
    }

    @Override
    public void start(Stage primaryStage) {
        // Initialize visualization system first
        visualizer = new CollisionVisualizer();
        shapes = new ArrayList<>();
        bodies = new ArrayList<>();
        
        // Initialize status label
        statusLabel = new Label("Collision Debug Viewer - Ready");
        statusLabel.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 5px; -fx-border-color: #cccccc; -fx-border-width: 1px 0 0 0;");
        
        // Set up the 3D scene content
        root.getChildren().add(world);
        root.setDepthTest(javafx.scene.DepthTest.ENABLE);
        
        buildCamera();
        buildAxes();
        var view = build();
        var auto = new AutoScalingGroup(2);
        auto.getChildren().add(getLight());
        auto.enabledProperty().set(true);
        auto.getChildren().add(view);
        transformingGroup.getChildren().add(auto);
        world.getChildren().addAll(transformingGroup);
        
        // Create SubScene for 3D content - this is the key change!
        // The SubScene will contain only the 3D content and have its own camera
        SubScene scene3D = new SubScene(root, 960, 768, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKGRAY);
        
        // Set the PerspectiveCamera ONLY on the SubScene, not the main Scene
        scene3D.setCamera(camera);
        
        // Apply mouse and keyboard handlers to the SubScene
        scene3D.setOnMousePressed(event -> {
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
            mouseOldX = event.getSceneX();
            mouseOldY = event.getSceneY();
        });
        
        scene3D.setOnMouseDragged(event -> {
            mouseOldX = mousePosX;
            mouseOldY = mousePosY;
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
            mouseDeltaX = (mousePosX - mouseOldX);
            mouseDeltaY = (mousePosY - mouseOldY);

            double modifier = 1.0;
            if (event.isShiftDown()) {
                modifier = SHIFT_MULTIPLIER;
            }
            
            // Check invert setting
            double invertFactor = (invertMouse != null && invertMouse.isSelected()) ? -1.0 : 1.0;
            
            // Check Ctrl key state explicitly
            boolean ctrlPressed = event.isControlDown();

            if (event.isMiddleButtonDown() || (event.isPrimaryButtonDown() && event.isSecondaryButtonDown())) {
                // Pan with middle mouse or both buttons
                cameraXform2.t.setX(cameraXform2.t.getX() + mouseDeltaX * MOUSE_SPEED * modifier * TRACK_SPEED * 10.0 * invertFactor);
                cameraXform2.t.setY(cameraXform2.t.getY() + mouseDeltaY * MOUSE_SPEED * modifier * TRACK_SPEED * 10.0 * invertFactor);
            } else if (event.isPrimaryButtonDown() && ctrlPressed) {
                // Pan with Ctrl+Left mouse - increase speed by 10x
                cameraXform2.t.setX(cameraXform2.t.getX() + mouseDeltaX * MOUSE_SPEED * modifier * TRACK_SPEED * 10.0 * invertFactor);
                cameraXform2.t.setY(cameraXform2.t.getY() + mouseDeltaY * MOUSE_SPEED * modifier * TRACK_SPEED * 10.0 * invertFactor);
            } else if (event.isPrimaryButtonDown() && !ctrlPressed) {
                // Rotate with left mouse only (when Ctrl is NOT pressed)
                cameraXform.ry.setAngle(cameraXform.ry.getAngle() - mouseDeltaX * MOUSE_SPEED * modifier * ROTATION_SPEED * invertFactor);
                cameraXform.rx.setAngle(cameraXform.rx.getAngle() + mouseDeltaY * MOUSE_SPEED * modifier * ROTATION_SPEED * invertFactor);
            } else if (event.isSecondaryButtonDown()) {
                // Zoom with right mouse
                double z = camera.getTranslateZ();
                double newZ = z + mouseDeltaX * MOUSE_SPEED * modifier * invertFactor;
                camera.setTranslateZ(newZ);
            }
        });
        
        // Attach scroll listener to the SubScene
        scene3D.setOnScroll(event -> {
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
        
        // Create the main layout with SubScene in center and UI as fixed panels
        var borderPane = new BorderPane();
        
        // The 3D SubScene goes in the center - only this will be manipulable in 3D
        borderPane.setCenter(scene3D);
        
        // Control panel on the right - this stays fixed in 2D
        borderPane.setRight(createControlPanel());
        
        // Status bar at bottom - this stays fixed in 2D
        borderPane.setBottom(statusLabel);
        
        // Create main scene with the border pane as the root
        // Note: NO camera is set on the main scene - it uses default 2D rendering
        Scene scene = new Scene(borderPane, 1280, 768);
        scene.setFill(Color.LIGHTGRAY);
        
        // Keyboard handlers on the main scene
        scene.setOnKeyPressed(event -> {
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
        });
        
        primaryStage.setTitle(title());
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Setup property bindings for UI controls
        setupUIBindings();
    }

    /**
     * Setup bindings between UI controls and visualizer properties.
     */
    private void setupUIBindings() {
        if (showWireframes != null) {
            visualizer.showWireframesProperty().bind(showWireframes.selectedProperty());
        }
        if (showContactPoints != null) {
            visualizer.showContactPointsProperty().bind(showContactPoints.selectedProperty());
        }
        if (showPenetrationVectors != null) {
            visualizer.showPenetrationVectorsProperty().bind(showPenetrationVectors.selectedProperty());
        }
        if (showVelocityVectors != null) {
            visualizer.showVelocityVectorsProperty().bind(showVelocityVectors.selectedProperty());
        }
        if (showAABBs != null) {
            visualizer.showAABBsProperty().bind(showAABBs.selectedProperty());
        }
        if (vectorScale != null) {
            visualizer.vectorScaleProperty().bind(vectorScale.valueProperty());
        }
        if (wireframeColor != null) {
            visualizer.wireframeColorProperty().bind(wireframeColor.valueProperty());
        }
        if (contactColor != null) {
            visualizer.contactPointColorProperty().bind(contactColor.valueProperty());
        }
    }

    @Override
    protected Group build() {
        // Create root group for 3D content
        rootGroup = new Group();
        rootGroup.getChildren().add(visualizer.getRootGroup());

        // Create initial demo shapes
        createDemoScene();

        // Setup animation loop
        setupAnimation();
        
        return rootGroup;
    }

    @Override
    public void stop() {
        if (animationTimer != null) {
            animationTimer.stop();
        }
    }

    /**
     * Add a box at a specific location.
     */
    private void addBoxAt(Point3f position, Vector3f size) {
        var box = new BoxShape(position, size);
        shapes.add(box);
        visualizer.addShape(box);

        // Create corresponding rigid body
        var inertia = InertiaTensor.box(1.0f, size);
        var body = new RigidBody(1.0f, inertia);
        body.setPosition(position);
        body.setMaterial(PhysicsMaterial.STEEL);
        bodies.add(body);
        visualizer.addRigidBody(body);
    }

    /**
     * Add a capsule at a specific location.
     */
    private void addCapsuleAt(Point3f position, float radius, float height) {
        var capsule = new CapsuleShape(position, height, radius);
        shapes.add(capsule);
        visualizer.addShape(capsule);

        // Create corresponding rigid body
        var inertia = InertiaTensor.cylinder(1.0f, radius, height);
        var body = new RigidBody(1.0f, inertia);
        body.setPosition(position);
        body.setMaterial(PhysicsMaterial.WOOD);
        bodies.add(body);
        visualizer.addRigidBody(body);
    }

    /**
     * Add a random box.
     */
    private void addRandomBox() {
        var position = new Point3f((random.nextFloat() - 0.5f) * 300, (random.nextFloat() - 0.5f) * 300,
                                   (random.nextFloat() - 0.5f) * 300);
        var size = new Vector3f(25f + random.nextFloat() * 75f, 25f + random.nextFloat() * 75f,
                                25f + random.nextFloat() * 75f);
        addBoxAt(position, size);
        
        // Give new box a small random velocity
        if (!bodies.isEmpty()) {
            var body = bodies.get(bodies.size() - 1);
            body.setLinearVelocity(new Vector3f(
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20
            ));
        }
        
        updateStatus();
    }

    /**
     * Add a random capsule.
     */
    private void addRandomCapsule() {
        var position = new Point3f((random.nextFloat() - 0.5f) * 300, (random.nextFloat() - 0.5f) * 300,
                                   (random.nextFloat() - 0.5f) * 300);
        var radius = 15f + random.nextFloat() * 20f;  // Reduced from 35f
        var height = 40f + random.nextFloat() * 60f;  // Reduced from 100f
        addCapsuleAt(position, radius, height);
        
        // Give new capsule a small random velocity
        if (!bodies.isEmpty()) {
            var body = bodies.get(bodies.size() - 1);
            body.setLinearVelocity(new Vector3f(
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20
            ));
        }
        
        updateStatus();
    }

    /**
     * Add a random sphere.
     */
    private void addRandomSphere() {
        var position = new Point3f((random.nextFloat() - 0.5f) * 300, (random.nextFloat() - 0.5f) * 300,
                                   (random.nextFloat() - 0.5f) * 300);
        var radius = 25f + random.nextFloat() * 50f;
        addSphereAt(position, radius);
        
        // Give new sphere a small random velocity
        if (!bodies.isEmpty()) {
            var body = bodies.get(bodies.size() - 1);
            body.setLinearVelocity(new Vector3f(
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20,
                (random.nextFloat() - 0.5f) * 20
            ));
        }
        
        updateStatus();
    }

    /**
     * Add a sphere at a specific location.
     */
    private void addSphereAt(Point3f position, float radius) {
        var sphere = new SphereShape(position, radius);
        shapes.add(sphere);
        visualizer.addShape(sphere);
        

        // Create corresponding rigid body
        var inertia = InertiaTensor.sphere(1.0f, radius);
        var body = new RigidBody(1.0f, inertia);
        body.setPosition(position);
        body.setMaterial(PhysicsMaterial.RUBBER);
        bodies.add(body);
        visualizer.addRigidBody(body);
    }

    /**
     * Clear all shapes from the scene.
     */
    private void clearAllShapes() {
        for (var shape : shapes) {
            visualizer.removeShape(shape);
        }
        for (var body : bodies) {
            visualizer.removeRigidBody(body);
        }
        shapes.clear();
        bodies.clear();
        visualizer.clearContacts();
        updateStatus();
    }

    /**
     * Create the control panel for adjusting visualization settings.
     */
    public ScrollPane createControlPanel() {
        var vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setPrefWidth(250);
        vbox.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1px;");

        // Visibility controls
        vbox.getChildren().add(new Label("Visibility:"));

        showWireframes = new CheckBox("Show Wireframes");
        showWireframes.setSelected(true);

        showContactPoints = new CheckBox("Show Contact Points");
        showContactPoints.setSelected(true);

        showPenetrationVectors = new CheckBox("Show Penetration Vectors");
        showPenetrationVectors.setSelected(true);

        showVelocityVectors = new CheckBox("Show Velocity Vectors");

        showAABBs = new CheckBox("Show AABBs");

        vbox.getChildren().addAll(showWireframes, showContactPoints, showPenetrationVectors, showVelocityVectors,
                                  showAABBs);

        vbox.getChildren().add(new Separator());

        // Scale controls
        vbox.getChildren().add(new Label("Vector Scale:"));
        vectorScale = new Slider(0.1, 5.0, 1.0);
        vectorScale.setShowTickLabels(true);
        vectorScale.setShowTickMarks(true);
        vbox.getChildren().add(vectorScale);

        vbox.getChildren().add(new Separator());

        // Color controls
        vbox.getChildren().add(new Label("Colors:"));

        wireframeColor = new ColorPicker(Color.CYAN);
        vbox.getChildren().addAll(new Label("Wireframe:"), wireframeColor);

        contactColor = new ColorPicker(Color.YELLOW);
        vbox.getChildren().addAll(new Label("Contact Points:"), contactColor);

        vbox.getChildren().add(new Separator());

        // Shape creation controls
        vbox.getChildren().add(new Label("Add Shapes:"));

        addSphere = new Button("Add Sphere");
        addSphere.setOnAction(e -> addRandomSphere());
        addSphere.setMaxWidth(Double.MAX_VALUE);

        addBox = new Button("Add Box");
        addBox.setOnAction(e -> addRandomBox());
        addBox.setMaxWidth(Double.MAX_VALUE);

        addCapsule = new Button("Add Capsule");
        addCapsule.setOnAction(e -> addRandomCapsule());
        addCapsule.setMaxWidth(Double.MAX_VALUE);

        vbox.getChildren().addAll(addSphere, addBox, addCapsule);

        vbox.getChildren().add(new Separator());

        // Physics controls
        vbox.getChildren().add(new Label("Physics:"));

        runPhysics = new Button("Run Physics Step");
        runPhysics.setOnAction(e -> runPhysicsStep());
        runPhysics.setMaxWidth(Double.MAX_VALUE);

        clearAll = new Button("Clear All");
        clearAll.setOnAction(e -> clearAllShapes());
        clearAll.setMaxWidth(Double.MAX_VALUE);

        vbox.getChildren().addAll(runPhysics, clearAll);

        // Camera controls
        vbox.getChildren().add(new Separator());
        vbox.getChildren().add(new Label("Camera:"));
        
        invertMouse = new CheckBox("Invert Mouse");
        invertMouse.setSelected(true); // Default to inverted
        vbox.getChildren().add(invertMouse);

        // Info section
        vbox.getChildren().add(new Separator());
        vbox.getChildren().add(new Label("Info:"));

        var infoLabel = new Label(
        "Controls:\n" +
        "• Left mouse: Rotate view\n" +
        "• Ctrl+Left mouse: Pan view\n" +
        "• Right mouse: Zoom\n" + 
        "• Middle mouse: Pan view\n" +
        "• Scroll: Zoom in/out\n" +
        "• Z key: Reset camera");
        infoLabel.setWrapText(true);
        vbox.getChildren().add(infoLabel);

        var scrollPane = new ScrollPane(vbox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(270);
        return scrollPane;
    }

    /**
     * Create the initial demo scene with various collision shapes.
     */
    private void createDemoScene() {
        // Create a few demo shapes to start with - scale up for visibility
        addSphereAt(new Point3f(0, 100, 0), 50.0f);
        if (!bodies.isEmpty()) {
            // Give the first sphere some initial velocity
            bodies.get(bodies.size() - 1).setLinearVelocity(new Vector3f(20, 0, 0));
        }
        
        addBoxAt(new Point3f(200, 0, 0), new Vector3f(50, 50, 50));
        if (!bodies.isEmpty()) {
            // Give the box some initial velocity
            bodies.get(bodies.size() - 1).setLinearVelocity(new Vector3f(-10, 5, 0));
        }
        
        addCapsuleAt(new Point3f(-200, 50, 0), 20.0f, 60.0f);
        if (!bodies.isEmpty()) {
            // Give the capsule some initial velocity
            bodies.get(bodies.size() - 1).setLinearVelocity(new Vector3f(10, 5, 5));
        }

        // Add another sphere with velocity
        addSphereAt(new Point3f(100, 200, 0), 40.0f);
        if (!bodies.isEmpty()) {
            // Give it velocity toward the first sphere
            bodies.get(bodies.size() - 1).setLinearVelocity(new Vector3f(-15, -10, 0));
        }

        updateStatus();
    }

    /**
     * Run a physics simulation step.
     */
    private void runPhysicsStep() {
        // Apply different random impulses to each body for variety
        for (int i = 0; i < bodies.size(); i++) {
            var body = bodies.get(i);
            if (!body.isKinematic()) {
                // Apply a random impulse instead of force for more immediate effect
                var impulse = new Vector3f(
                    (random.nextFloat() - 0.5f) * 10.0f,
                    (random.nextFloat() - 0.5f) * 10.0f,
                    (random.nextFloat() - 0.5f) * 10.0f
                );
                body.applyImpulse(impulse, new Point3f(0, 0, 0));
                
                // Also apply some gravity
                body.applyForce(new Vector3f(0, -9.81f * body.getMass(), 0));
                
                // Integrate physics
                body.integrate(0.016f); // 60 FPS

                // Update shape position to match body
                if (i < shapes.size()) {
                    var shape = shapes.get(i);
                    if (shape != null) {
                        var newPos = body.getPosition();
                        var delta = new Vector3f();
                        delta.sub(newPos, shape.getPosition());
                        shape.translate(delta);
                    }
                }
            }
        }
        
        // Check for collisions after movement
        updateCollisionDetection();
        updateStatus();
    }

    /**
     * Setup animation loop for dynamic updates.
     */
    private void setupAnimation() {
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Update physics simulation (if any)
                updatePhysics();

                // Update visualizations
                visualizer.update();

                // Check for collisions and update contact points
                updateCollisionDetection();
            }
        };
        animationTimer.start();
    }

    /**
     * Update collision detection and contact points.
     */
    private void updateCollisionDetection() {
        visualizer.clearContacts();

        // Check all pairs of shapes for collisions
        for (int i = 0; i < shapes.size(); i++) {
            for (int j = i + 1; j < shapes.size(); j++) {
                var shapeA = shapes.get(i);
                var shapeB = shapes.get(j);

                var collision = shapeA.collidesWith(shapeB);
                if (collision.collides) {
                    visualizer.addContact(collision.contactPoint, collision.contactNormal, collision.penetrationDepth);

                    // Resolve collision if both have rigid bodies
                    if (i < bodies.size() && j < bodies.size()) {
                        var bodyA = bodies.get(i);
                        var bodyB = bodies.get(j);
                        ImpulseResolver.resolveCollision(bodyA, bodyB, collision);
                    }
                }
            }
        }
    }

    /**
     * Update physics simulation.
     */
    private void updatePhysics() {
        // Update physics for all bodies
        for (int i = 0; i < bodies.size(); i++) {
            var body = bodies.get(i);
            if (!body.isKinematic()) {
                // Apply gravity
                body.applyForce(new Vector3f(0, -9.81f * body.getMass(), 0));
                
                // Apply stronger damping to prevent infinite acceleration
                var velocity = body.getLinearVelocity();
                var damping = new Vector3f(velocity);
                damping.scale(-0.1f); // 10% damping (increased from 5%)
                body.applyForce(damping);
                
                // Clamp velocity to reasonable limits
                var maxSpeed = 500.0f;
                if (velocity.length() > maxSpeed) {
                    velocity.normalize();
                    velocity.scale(maxSpeed);
                    body.setLinearVelocity(velocity);
                }
                
                // Integrate physics
                body.integrate(0.016f); // 60 FPS
                
                // Update shape position to match body
                if (i < shapes.size()) {
                    var shape = shapes.get(i);
                    if (shape != null) {
                        var newPos = body.getPosition();
                        var delta = new Vector3f();
                        delta.sub(newPos, shape.getPosition());
                        shape.translate(delta);
                    }
                }
            }
        }
    }

    /**
     * Update status display.
     */
    private void updateStatus() {
        if (statusLabel != null && shapes != null && visualizer != null) {
            var shapeCount = shapes.size();
            var contactCount = visualizer.getContacts().size();
            statusLabel.setText(
            String.format("Shapes: %d | Contacts: %d | Use controls to adjust visualization", shapeCount, contactCount));
        }
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            CollisionDebugViewer.main(argv);
        }
    }
}
