package com.hellblazer.luciferase.render.webgpu;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Camera controller for WebGPU rendering.
 * Provides orbital, FPS, and free-fly camera modes.
 */
public class CameraController {
    
    public enum CameraMode {
        ORBITAL,    // Orbit around target
        FPS,        // First-person shooter style
        FREE_FLY    // Free flight with roll
    }
    
    // Camera state
    private final Vector3f position = new Vector3f(0, 0, 10);
    private final Vector3f target = new Vector3f(0, 0, 0);
    private final Vector3f up = new Vector3f(0, 1, 0);
    private final Vector3f forward = new Vector3f(0, 0, -1);
    private final Vector3f right = new Vector3f(1, 0, 0);
    
    // Matrices
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    
    // Camera parameters
    private CameraMode mode = CameraMode.ORBITAL;
    private float fov = (float) Math.toRadians(60.0);
    private float aspectRatio = 16.0f / 9.0f;
    private float nearPlane = 0.1f;
    private float farPlane = 1000.0f;
    
    // Orbital camera parameters
    private float orbitDistance = 50.0f;
    private float orbitYaw = 0.0f;
    private float orbitPitch = 0.3f;
    private float minOrbitDistance = 1.0f;
    private float maxOrbitDistance = 500.0f;
    
    // FPS camera parameters
    private float yaw = 0.0f;
    private float pitch = 0.0f;
    private float roll = 0.0f;
    private float moveSpeed = 50.0f;
    private float mouseSensitivity = 0.002f;
    
    // Input state
    private boolean[] keys = new boolean[GLFW_KEY_LAST];
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean mousePressed = false;
    private int mouseButton = GLFW_MOUSE_BUTTON_LEFT;
    
    // Smoothing
    private boolean enableSmoothing = true;
    private float smoothingFactor = 0.1f;
    private final Vector3f targetPosition = new Vector3f();
    private final Vector3f smoothPosition = new Vector3f();
    
    public CameraController() {
        updateMatrices();
    }
    
    /**
     * Set camera mode.
     */
    public void setMode(CameraMode mode) {
        this.mode = mode;
        
        // Initialize mode-specific parameters
        switch (mode) {
            case ORBITAL:
                // Calculate orbital parameters from current position
                Vector3f toCamera = new Vector3f(position).sub(target);
                orbitDistance = toCamera.length();
                orbitYaw = (float) Math.atan2(toCamera.x, toCamera.z);
                orbitPitch = (float) Math.asin(toCamera.y / orbitDistance);
                break;
                
            case FPS:
            case FREE_FLY:
                // Calculate look direction from current state
                forward.set(target).sub(position).normalize();
                yaw = (float) Math.atan2(forward.x, forward.z);
                pitch = (float) Math.asin(forward.y);
                updateDirectionVectors();
                break;
        }
    }
    
    /**
     * Update camera for the current frame.
     */
    public void update(float deltaTime) {
        switch (mode) {
            case ORBITAL:
                updateOrbital(deltaTime);
                break;
            case FPS:
                updateFPS(deltaTime);
                break;
            case FREE_FLY:
                updateFreeFly(deltaTime);
                break;
        }
        
        // Apply smoothing if enabled
        if (enableSmoothing) {
            smoothPosition.lerp(targetPosition, smoothingFactor);
            position.set(smoothPosition);
        } else {
            position.set(targetPosition);
        }
        
        updateMatrices();
    }
    
    private void updateOrbital(float deltaTime) {
        // Update position based on spherical coordinates
        float x = orbitDistance * (float) Math.cos(orbitPitch) * (float) Math.sin(orbitYaw);
        float y = orbitDistance * (float) Math.sin(orbitPitch);
        float z = orbitDistance * (float) Math.cos(orbitPitch) * (float) Math.cos(orbitYaw);
        
        targetPosition.set(target).add(x, y, z);
        
        // Update up vector for proper orientation
        if (Math.abs(orbitPitch) > Math.PI / 2 - 0.01) {
            up.set(0, orbitPitch > 0 ? 1 : -1, 0);
        } else {
            up.set(0, 1, 0);
        }
    }
    
    private void updateFPS(float deltaTime) {
        Vector3f movement = new Vector3f();
        
        // WASD movement
        if (keys[GLFW_KEY_W]) movement.z -= 1;
        if (keys[GLFW_KEY_S]) movement.z += 1;
        if (keys[GLFW_KEY_A]) movement.x -= 1;
        if (keys[GLFW_KEY_D]) movement.x += 1;
        if (keys[GLFW_KEY_SPACE]) movement.y += 1;
        if (keys[GLFW_KEY_LEFT_SHIFT]) movement.y -= 1;
        
        // Apply movement in camera space
        if (movement.lengthSquared() > 0) {
            movement.normalize().mul(moveSpeed * deltaTime);
            
            Vector3f worldMovement = new Vector3f();
            worldMovement.add(right.mul(movement.x, new Vector3f()));
            worldMovement.add(up.mul(movement.y, new Vector3f()));
            worldMovement.add(forward.mul(-movement.z, new Vector3f()));
            
            targetPosition.add(worldMovement);
        } else {
            targetPosition.set(position);
        }
        
        // Update target based on look direction
        target.set(position).add(forward);
    }
    
    private void updateFreeFly(float deltaTime) {
        // Similar to FPS but with roll support
        updateFPS(deltaTime);
        
        // Q/E for roll
        if (keys[GLFW_KEY_Q]) roll -= deltaTime;
        if (keys[GLFW_KEY_E]) roll += deltaTime;
        
        // Update up vector based on roll
        if (Math.abs(roll) > 0.01) {
            Matrix4f rollMatrix = new Matrix4f().rotateZ(roll);
            up.set(0, 1, 0).mulDirection(rollMatrix);
        }
    }
    
    private void updateDirectionVectors() {
        // Calculate forward vector from yaw and pitch
        forward.x = (float) (Math.sin(yaw) * Math.cos(pitch));
        forward.y = (float) Math.sin(pitch);
        forward.z = (float) (Math.cos(yaw) * Math.cos(pitch));
        forward.normalize();
        
        // Calculate right vector
        right.set(forward).cross(0, 1, 0).normalize();
        
        // Calculate up vector
        up.set(right).cross(forward).normalize();
    }
    
    private void updateMatrices() {
        // Update view matrix
        viewMatrix.lookAt(position, target, up);
        
        // Update projection matrix
        projectionMatrix.setPerspective(fov, aspectRatio, nearPlane, farPlane);
        
        // Combine matrices
        projectionMatrix.mul(viewMatrix, viewProjectionMatrix);
    }
    
    /**
     * Handle mouse movement.
     */
    public void handleMouseMove(double xpos, double ypos) {
        if (!mousePressed) {
            lastMouseX = xpos;
            lastMouseY = ypos;
            return;
        }
        
        double dx = xpos - lastMouseX;
        double dy = ypos - lastMouseY;
        
        switch (mode) {
            case ORBITAL:
                if (mouseButton == GLFW_MOUSE_BUTTON_LEFT) {
                    orbitYaw += (float) (dx * mouseSensitivity);
                    orbitPitch += (float) (dy * mouseSensitivity);
                    orbitPitch = Math.max(-1.5f, Math.min(1.5f, orbitPitch));
                } else if (mouseButton == GLFW_MOUSE_BUTTON_MIDDLE) {
                    // Pan camera
                    Vector3f panRight = new Vector3f(viewMatrix.m00(), viewMatrix.m10(), viewMatrix.m20());
                    Vector3f panUp = new Vector3f(viewMatrix.m01(), viewMatrix.m11(), viewMatrix.m21());
                    
                    float panSpeed = orbitDistance * 0.001f;
                    target.add(panRight.mul((float) -dx * panSpeed));
                    target.add(panUp.mul((float) dy * panSpeed));
                }
                break;
                
            case FPS:
            case FREE_FLY:
                yaw += (float) (dx * mouseSensitivity);
                pitch -= (float) (dy * mouseSensitivity);
                pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
                updateDirectionVectors();
                break;
        }
        
        lastMouseX = xpos;
        lastMouseY = ypos;
    }
    
    /**
     * Handle mouse button.
     */
    public void handleMouseButton(int button, int action, int mods) {
        if (action == GLFW_PRESS) {
            mousePressed = true;
            mouseButton = button;
        } else if (action == GLFW_RELEASE) {
            mousePressed = false;
        }
    }
    
    /**
     * Handle mouse scroll.
     */
    public void handleScroll(double xoffset, double yoffset) {
        switch (mode) {
            case ORBITAL:
                orbitDistance -= (float) yoffset * orbitDistance * 0.1f;
                orbitDistance = Math.max(minOrbitDistance, Math.min(maxOrbitDistance, orbitDistance));
                break;
                
            case FPS:
            case FREE_FLY:
                moveSpeed *= Math.pow(1.1, yoffset);
                moveSpeed = Math.max(1.0f, Math.min(500.0f, moveSpeed));
                break;
        }
    }
    
    /**
     * Handle keyboard input.
     */
    public void handleKey(int key, int scancode, int action, int mods) {
        if (key >= 0 && key < keys.length) {
            keys[key] = action != GLFW_RELEASE;
        }
        
        // Mode switching
        if (action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_1:
                    setMode(CameraMode.ORBITAL);
                    break;
                case GLFW_KEY_2:
                    setMode(CameraMode.FPS);
                    break;
                case GLFW_KEY_3:
                    setMode(CameraMode.FREE_FLY);
                    break;
                case GLFW_KEY_R:
                    reset();
                    break;
            }
        }
    }
    
    /**
     * Reset camera to default position.
     */
    public void reset() {
        position.set(0, 0, 10);
        target.set(0, 0, 0);
        up.set(0, 1, 0);
        
        orbitDistance = 50.0f;
        orbitYaw = 0.0f;
        orbitPitch = 0.3f;
        
        yaw = 0.0f;
        pitch = 0.0f;
        roll = 0.0f;
        
        updateDirectionVectors();
        updateMatrices();
    }
    
    /**
     * Set viewport dimensions.
     */
    public void setViewport(int width, int height) {
        aspectRatio = (float) width / height;
        updateMatrices();
    }
    
    /**
     * Look at a specific point.
     */
    public void lookAt(Vector3f eye, Vector3f center, Vector3f up) {
        position.set(eye);
        target.set(center);
        this.up.set(up);
        
        // Update internal parameters based on new position
        if (mode == CameraMode.ORBITAL) {
            setMode(CameraMode.ORBITAL); // Recalculate orbital parameters
        } else {
            forward.set(target).sub(position).normalize();
            yaw = (float) Math.atan2(forward.x, forward.z);
            pitch = (float) Math.asin(forward.y);
            updateDirectionVectors();
        }
        
        updateMatrices();
    }
    
    // Getters
    public Matrix4f getViewMatrix() { return new Matrix4f(viewMatrix); }
    public Matrix4f getProjectionMatrix() { return new Matrix4f(projectionMatrix); }
    public Matrix4f getViewProjectionMatrix() { return new Matrix4f(viewProjectionMatrix); }
    public Vector3f getPosition() { return new Vector3f(position); }
    public Vector3f getTarget() { return new Vector3f(target); }
    public Vector3f getForward() { return new Vector3f(forward); }
    public Vector3f getRight() { return new Vector3f(right); }
    public Vector3f getUp() { return new Vector3f(up); }
    public float getFov() { return fov; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }
    public CameraMode getMode() { return mode; }
    
    // Setters
    public void setFov(float fov) { 
        this.fov = fov;
        updateMatrices();
    }
    
    public void setClipPlanes(float near, float far) {
        this.nearPlane = near;
        this.farPlane = far;
        updateMatrices();
    }
    
    public void setMoveSpeed(float speed) {
        this.moveSpeed = speed;
    }
    
    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = sensitivity;
    }
    
    public void setSmoothing(boolean enable, float factor) {
        this.enableSmoothing = enable;
        this.smoothingFactor = Math.max(0.01f, Math.min(1.0f, factor));
        if (!enable) {
            smoothPosition.set(position);
        }
    }
}