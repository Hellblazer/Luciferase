package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Arrays;
import java.util.Optional;

/**
 * A specialized transformation for rotations in 2D and 3D space.
 * Uses rotation matrices for efficient computation.
 */
public final class RotationTransformation implements CoordinateTransformation {
    
    private final LinearTransformation linearTransformation;
    private final int dimension;
    
    /**
     * Creates a rotation transformation from a linear transformation.
     * 
     * @param linearTransformation the underlying linear transformation
     */
    private RotationTransformation(LinearTransformation linearTransformation) {
        this.linearTransformation = linearTransformation;
        this.dimension = linearTransformation.getSourceDimension();
    }
    
    /**
     * Creates a 2D rotation.
     * 
     * @param angle the rotation angle in radians
     * @return the rotation transformation
     */
    public static RotationTransformation rotation2D(double angle) {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        
        var matrix = new double[][]{
            {cos, -sin},
            {sin, cos}
        };
        
        return new RotationTransformation(new LinearTransformation(matrix));
    }
    
    /**
     * Creates a 3D rotation around the X-axis.
     * 
     * @param angle the rotation angle in radians
     * @return the rotation transformation
     */
    public static RotationTransformation rotationX(double angle) {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        
        var matrix = new double[][]{
            {1, 0, 0},
            {0, cos, -sin},
            {0, sin, cos}
        };
        
        return new RotationTransformation(new LinearTransformation(matrix));
    }
    
    /**
     * Creates a 3D rotation around the Y-axis.
     * 
     * @param angle the rotation angle in radians
     * @return the rotation transformation
     */
    public static RotationTransformation rotationY(double angle) {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        
        var matrix = new double[][]{
            {cos, 0, sin},
            {0, 1, 0},
            {-sin, 0, cos}
        };
        
        return new RotationTransformation(new LinearTransformation(matrix));
    }
    
    /**
     * Creates a 3D rotation around the Z-axis.
     * 
     * @param angle the rotation angle in radians
     * @return the rotation transformation
     */
    public static RotationTransformation rotationZ(double angle) {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        
        var matrix = new double[][]{
            {cos, -sin, 0},
            {sin, cos, 0},
            {0, 0, 1}
        };
        
        return new RotationTransformation(new LinearTransformation(matrix));
    }
    
    /**
     * Creates a 3D rotation around an arbitrary axis.
     * 
     * @param axis the rotation axis (unit vector)
     * @param angle the rotation angle in radians
     * @return the rotation transformation
     */
    public static RotationTransformation rotation3D(double[] axis, double angle) {
        // Normalize axis
        var length = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (length < 1e-10) {
            throw new IllegalArgumentException("Cannot rotate around zero-length axis");
        }
        var nx = axis[0] / length;
        var ny = axis[1] / length;
        var nz = axis[2] / length;
        
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);
        var oneMCos = 1 - cos;
        
        // Rodrigues' rotation formula
        var matrix = new double[][]{
            {cos + nx * nx * oneMCos, nx * ny * oneMCos - nz * sin, nx * nz * oneMCos + ny * sin},
            {ny * nx * oneMCos + nz * sin, cos + ny * ny * oneMCos, ny * nz * oneMCos - nx * sin},
            {nz * nx * oneMCos - ny * sin, nz * ny * oneMCos + nx * sin, cos + nz * nz * oneMCos}
        };
        
        return new RotationTransformation(new LinearTransformation(matrix));
    }
    
    /**
     * Creates a 3D rotation from Euler angles.
     * 
     * @param yaw rotation around Y-axis in radians
     * @param pitch rotation around X-axis in radians  
     * @param roll rotation around Z-axis in radians
     * @return the rotation transformation
     */
    public static RotationTransformation eulerAngles(double yaw, double pitch, double roll) {
        var rotY = rotationY(yaw);
        var rotX = rotationX(pitch);
        var rotZ = rotationZ(roll);
        
        // Apply in order: yaw, pitch, roll
        var composed = (RotationTransformation) rotY.compose(rotX).compose(rotZ);
        return composed;
    }
    
    /**
     * Creates a rotation that aligns one vector to another.
     * 
     * @param from the source vector
     * @param to the target vector
     * @return the rotation transformation
     */
    public static RotationTransformation alignVectors(double[] from, double[] to) {
        if (from.length != to.length) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }
        
        if (from.length == 2) {
            return alignVectors2D(from, to);
        } else if (from.length == 3) {
            return alignVectors3D(from, to);
        } else {
            throw new IllegalArgumentException("Only 2D and 3D vector alignment supported");
        }
    }
    
    /**
     * Creates a 2D rotation that aligns one vector to another.
     */
    private static RotationTransformation alignVectors2D(double[] from, double[] to) {
        // Normalize vectors
        var fromLen = Math.sqrt(from[0] * from[0] + from[1] * from[1]);
        var toLen = Math.sqrt(to[0] * to[0] + to[1] * to[1]);
        
        var fromN = new double[]{from[0] / fromLen, from[1] / fromLen};
        var toN = new double[]{to[0] / toLen, to[1] / toLen};
        
        // Calculate angle between vectors using atan2
        var fromAngle = Math.atan2(fromN[1], fromN[0]);
        var toAngle = Math.atan2(toN[1], toN[0]);
        var angle = toAngle - fromAngle;
        
        return rotation2D(angle);
    }
    
    /**
     * Creates a 3D rotation that aligns one vector to another.
     */
    private static RotationTransformation alignVectors3D(double[] from, double[] to) {
        // Normalize vectors
        var fromLen = Math.sqrt(from[0] * from[0] + from[1] * from[1] + from[2] * from[2]);
        var toLen = Math.sqrt(to[0] * to[0] + to[1] * to[1] + to[2] * to[2]);
        
        if (fromLen < 1e-10 || toLen < 1e-10) {
            throw new IllegalArgumentException("Cannot align zero-length vectors");
        }
        
        var fromN = new double[]{from[0] / fromLen, from[1] / fromLen, from[2] / fromLen};
        var toN = new double[]{to[0] / toLen, to[1] / toLen, to[2] / toLen};
        
        // Calculate dot product (cosine of angle)
        var dot = fromN[0] * toN[0] + fromN[1] * toN[1] + fromN[2] * toN[2];
        
        // Handle special cases
        if (dot > 0.999999) {
            // Vectors are already aligned, return identity
            return new RotationTransformation(LinearTransformation.identity(3));
        } else if (dot < -0.999999) {
            // Vectors are opposite, need 180-degree rotation
            // Find an orthogonal axis for rotation
            var axis = new double[3];
            
            // Choose the coordinate with smallest absolute component to avoid near-zero cross product
            var absX = Math.abs(fromN[0]);
            var absY = Math.abs(fromN[1]);
            var absZ = Math.abs(fromN[2]);
            
            if (absX <= absY && absX <= absZ) {
                // Use cross product with (1, 0, 0)
                axis[0] = 0;
                axis[1] = fromN[2];
                axis[2] = -fromN[1];
            } else if (absY <= absX && absY <= absZ) {
                // Use cross product with (0, 1, 0)
                axis[0] = -fromN[2];
                axis[1] = 0;
                axis[2] = fromN[0];
            } else {
                // Use cross product with (0, 0, 1)
                axis[0] = fromN[1];
                axis[1] = -fromN[0];
                axis[2] = 0;
            }
            
            return rotation3D(axis, Math.PI);
        } else {
            // General case: use cross product for rotation axis
            var axis = new double[]{
                fromN[1] * toN[2] - fromN[2] * toN[1],
                fromN[2] * toN[0] - fromN[0] * toN[2],
                fromN[0] * toN[1] - fromN[1] * toN[0]
            };
            
            var angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
            return rotation3D(axis, angle);
        }
    }
    
    /**
     * Checks if this transformation is linear.
     * 
     * @return true (rotations are always linear)
     */
    public boolean isLinear() {
        return true;
    }
    
    /**
     * Computes the Jacobian determinant at the given coordinate.
     * 
     * @param coordinate the coordinate (unused for linear transformations)
     * @return the determinant of the transformation matrix
     */
    public double computeJacobianDeterminant(Coordinate coordinate) {
        return determinant();
    }
    
    @Override
    public Coordinate transform(Coordinate source) throws TransformationException {
        return linearTransformation.transform(source);
    }
    
    @Override
    public Bounds transformBounds(Bounds bounds) throws TransformationException {
        return linearTransformation.transformBounds(bounds);
    }
    
    @Override
    public Coordinate inverseTransform(Coordinate target) throws TransformationException {
        return linearTransformation.inverseTransform(target);
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        var linearInverse = linearTransformation.inverse();
        return linearInverse.map(t -> new RotationTransformation((LinearTransformation) t));
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof RotationTransformation rotation) {
            var composedLinear = linearTransformation.compose(rotation.linearTransformation);
            return new RotationTransformation((LinearTransformation) composedLinear);
        } else {
            return new CompositeTransformation(this, other);
        }
    }
    
    @Override
    public double determinant() {
        return linearTransformation.determinant();
    }
    
    @Override
    public boolean isIsometric() {
        return true; // Rotations preserve distances
    }
    
    @Override
    public boolean isConformal() {
        return true; // Rotations preserve angles
    }
    
    @Override
    public boolean isInvertible() {
        return true; // Rotations are always invertible
    }
    
    @Override
    public int getSourceDimension() {
        return dimension;
    }
    
    @Override
    public int getTargetDimension() {
        return dimension;
    }
    
    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("RotationTransformation(").append(dimension).append("D):\\n");
        sb.append("Matrix:\\n").append(linearTransformation.getDescription());
        sb.append("Determinant: ").append(String.format("%.6f", determinant()));
        return sb.toString();
    }
    
    /**
     * Gets the rotation matrix.
     * 
     * @return a copy of the rotation matrix
     */
    public double[][] getMatrix() {
        return linearTransformation.getMatrix();
    }
    
    /**
     * Gets the underlying linear transformation.
     * 
     * @return the linear transformation
     */
    public LinearTransformation getLinearTransformation() {
        return linearTransformation;
    }
    
    @Override
    public String toString() {
        return "RotationTransformation{" +
               "dimension=" + dimension +
               '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RotationTransformation other)) return false;
        
        return dimension == other.dimension && 
               linearTransformation.equals(other.linearTransformation);
    }
    
    @Override
    public int hashCode() {
        return linearTransformation.hashCode() * 31 + dimension;
    }
}