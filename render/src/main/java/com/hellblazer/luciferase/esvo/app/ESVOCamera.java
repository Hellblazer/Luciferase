package com.hellblazer.luciferase.esvo.app;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

/**
 * FPS-style camera implementation for ESVO applications.
 * Provides movement, rotation, and projection matrix generation.
 */
public class ESVOCamera {
    private final Vector3f position = new Vector3f(0, 0, 0);
    private final Vector3f forward = new Vector3f(0, 0, -1);
    private final Vector3f up = new Vector3f(0, 1, 0);
    private final Vector3f right = new Vector3f(1, 0, 0);
    
    private float yaw = 0.0f;
    private float pitch = 0.0f;
    private float fov = 45.0f;
    private float nearPlane = 0.1f;
    private float farPlane = 1000.0f;
    
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private boolean viewMatrixDirty = true;
    private boolean projectionMatrixDirty = true;
    
    // Movement parameters
    private float movementSpeed = 5.0f;
    private float mouseSensitivity = 0.1f;
    
    public ESVOCamera() {
        updateVectors();
        updateViewMatrix();
        updateProjectionMatrix(800, 600); // Default aspect ratio
    }
    
    public ESVOCamera(Vector3f initialPosition) {
        position.set(initialPosition);
        updateVectors();
        updateViewMatrix();
        updateProjectionMatrix(800, 600);
    }
    
    /**
     * Move camera forward/backward relative to current orientation
     */
    public void moveForward(float distance) {
        var movement = new Vector3f(forward);
        movement.scale(distance);
        position.add(movement);
        viewMatrixDirty = true;
    }
    
    /**
     * Move camera right/left relative to current orientation
     */
    public void moveRight(float distance) {
        var movement = new Vector3f(right);
        movement.scale(distance);
        position.add(movement);
        viewMatrixDirty = true;
    }
    
    /**
     * Move camera up/down in world space
     */
    public void moveUp(float distance) {
        position.y += distance;
        viewMatrixDirty = true;
    }
    
    /**
     * Rotate camera by yaw and pitch deltas
     */
    public void rotate(float deltaYaw, float deltaPitch) {
        yaw += deltaYaw * mouseSensitivity;
        pitch += deltaPitch * mouseSensitivity;
        
        // Clamp pitch to prevent gimbal lock
        pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
        
        updateVectors();
        viewMatrixDirty = true;
    }
    
    /**
     * Set camera position directly
     */
    public void setPosition(Vector3f newPosition) {
        position.set(newPosition);
        viewMatrixDirty = true;
    }
    
    /**
     * Set camera orientation directly
     */
    public void setOrientation(float newYaw, float newPitch) {
        yaw = newYaw;
        pitch = Math.max(-1.5f, Math.min(1.5f, newPitch));
        updateVectors();
        viewMatrixDirty = true;
    }
    
    /**
     * Set field of view
     */
    public void setFieldOfView(float degrees) {
        fov = Math.max(1.0f, Math.min(179.0f, degrees));
        projectionMatrixDirty = true;
    }
    
    /**
     * Set near and far clipping planes
     */
    public void setClippingPlanes(float near, float far) {
        nearPlane = Math.max(0.001f, near);
        farPlane = Math.max(nearPlane + 0.001f, far);
        projectionMatrixDirty = true;
    }
    
    /**
     * Get current view matrix
     */
    public Matrix4f getViewMatrix() {
        if (viewMatrixDirty) {
            updateViewMatrix();
        }
        return new Matrix4f(viewMatrix);
    }
    
    /**
     * Get current projection matrix
     */
    public Matrix4f getProjectionMatrix() {
        if (projectionMatrixDirty) {
            // Use last known aspect ratio if not specified
            updateProjectionMatrix(800, 600);
        }
        return new Matrix4f(projectionMatrix);
    }
    
    /**
     * Update projection matrix with new viewport dimensions
     */
    public void updateProjectionMatrix(int width, int height) {
        var aspectRatio = (float) width / height;
        updateProjectionMatrix(aspectRatio);
    }
    
    /**
     * Update projection matrix with aspect ratio
     */
    public void updateProjectionMatrix(float aspectRatio) {
        var fovRad = (float) Math.toRadians(fov);
        var f = 1.0f / (float) Math.tan(fovRad / 2.0f);
        
        projectionMatrix.setIdentity();
        projectionMatrix.m00 = f / aspectRatio;
        projectionMatrix.m11 = f;
        projectionMatrix.m22 = (farPlane + nearPlane) / (nearPlane - farPlane);
        projectionMatrix.m23 = (2.0f * farPlane * nearPlane) / (nearPlane - farPlane);
        projectionMatrix.m32 = -1.0f;
        projectionMatrix.m33 = 0.0f;
        
        projectionMatrixDirty = false;
    }
    
    /**
     * Get camera position
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }
    
    /**
     * Get camera forward direction
     */
    public Vector3f getForward() {
        return new Vector3f(forward);
    }
    
    /**
     * Get camera up direction
     */
    public Vector3f getUp() {
        return new Vector3f(up);
    }
    
    /**
     * Get camera right direction
     */
    public Vector3f getRight() {
        return new Vector3f(right);
    }
    
    /**
     * Get current yaw angle in radians
     */
    public float getYaw() {
        return yaw;
    }
    
    /**
     * Get current pitch angle in radians
     */
    public float getPitch() {
        return pitch;
    }
    
    /**
     * Get field of view in degrees
     */
    public float getFieldOfView() {
        return fov;
    }
    
    /**
     * Set movement speed
     */
    public void setMovementSpeed(float speed) {
        movementSpeed = Math.max(0.1f, speed);
    }
    
    /**
     * Get movement speed
     */
    public float getMovementSpeed() {
        return movementSpeed;
    }
    
    /**
     * Set mouse sensitivity
     */
    public void setMouseSensitivity(float sensitivity) {
        mouseSensitivity = Math.max(0.001f, sensitivity);
    }
    
    /**
     * Get mouse sensitivity
     */
    public float getMouseSensitivity() {
        return mouseSensitivity;
    }
    
    /**
     * Update forward, up, and right vectors based on yaw and pitch
     */
    private void updateVectors() {
        // Calculate forward vector
        forward.x = (float) (Math.cos(yaw) * Math.cos(pitch));
        forward.y = (float) Math.sin(pitch);
        forward.z = (float) (Math.sin(yaw) * Math.cos(pitch));
        forward.normalize();
        
        // Calculate right vector
        right.cross(forward, new Vector3f(0, 1, 0));
        right.normalize();
        
        // Calculate up vector
        up.cross(right, forward);
        up.normalize();
    }
    
    /**
     * Update view matrix based on current position and orientation
     */
    private void updateViewMatrix() {
        var target = new Vector3f();
        target.add(position, forward);
        
        // Create look-at matrix
        var zAxis = new Vector3f();
        zAxis.sub(position, target);
        zAxis.normalize();
        
        var xAxis = new Vector3f();
        xAxis.cross(up, zAxis);
        xAxis.normalize();
        
        var yAxis = new Vector3f();
        yAxis.cross(zAxis, xAxis);
        
        viewMatrix.setIdentity();
        
        // Set rotation part
        viewMatrix.m00 = xAxis.x;
        viewMatrix.m10 = xAxis.y;
        viewMatrix.m20 = xAxis.z;
        
        viewMatrix.m01 = yAxis.x;
        viewMatrix.m11 = yAxis.y;
        viewMatrix.m21 = yAxis.z;
        
        viewMatrix.m02 = zAxis.x;
        viewMatrix.m12 = zAxis.y;
        viewMatrix.m22 = zAxis.z;
        
        // Set translation part
        viewMatrix.m03 = -xAxis.dot(position);
        viewMatrix.m13 = -yAxis.dot(position);
        viewMatrix.m23 = -zAxis.dot(position);
        
        viewMatrixDirty = false;
    }
}