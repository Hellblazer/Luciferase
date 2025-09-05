package com.dyada.examples;

import com.dyada.transformations.*;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import com.dyada.core.coordinates.*;

/**
 * Comprehensive example demonstrating coordinate transformations.
 * Shows linear, rotation, scaling, translation, and composite transformations.
 */
public class TransformationExample {
    
    public static void main(String[] args) throws TransformationException {
        System.out.println("=== DyAda Transformation Example ===");
        
        // Original point to transform
        var originalPoint = new Coordinate(new double[]{3.0, 4.0});
        System.out.printf("Original point: [%.1f, %.1f]%n", 
            originalPoint.values()[0], originalPoint.values()[1]);
        
        demonstrateLinearTransformations();
        demonstrateRotationTransformations();
        demonstrateScalingTransformations();
        demonstrateTranslationTransformations();
        demonstrateCompositeTransformations();
        demonstrateInverseTransformations();
        demonstrateBatchTransformations();
        
        System.out.println("\\n=== All transformations completed successfully ===");
    }
    
    private static void demonstrateLinearTransformations() throws TransformationException {
        System.out.println("\\n--- Linear Transformations ---");
        
        // 2D scaling matrix
        var scalingMatrix = new double[][]{
            {2.0, 0.0},
            {0.0, 3.0}
        };
        var linearTransform = new LinearTransformation(scalingMatrix);
        
        var point = new Coordinate(new double[]{2.0, 3.0});
        var transformed = linearTransform.transform(point);
        
        System.out.printf("Point [%.1f, %.1f] scaled by [2x, 3x] = [%.1f, %.1f]%n",
            point.values()[0], point.values()[1],
            transformed.values()[0], transformed.values()[1]);
        
        // Check if transformation is linear
        System.out.printf("Is linear: %b%n", linearTransform.isLinear());
        System.out.printf("Is invertible: %b%n", linearTransform.isInvertible());
        
        // Compute Jacobian determinant
        double determinant = linearTransform.computeJacobianDeterminant(point);
        System.out.printf("Jacobian determinant: %.1f%n", determinant);
        
        // 2D rotation matrix (45 degrees)
        double angle = Math.PI / 4; // 45 degrees
        var rotationMatrix = new double[][]{
            {Math.cos(angle), -Math.sin(angle)},
            {Math.sin(angle), Math.cos(angle)}
        };
        var rotationTransform = new LinearTransformation(rotationMatrix);
        
        var rotated = rotationTransform.transform(new Coordinate(new double[]{1.0, 0.0}));
        System.out.printf("Point [1.0, 0.0] rotated 45° = [%.3f, %.3f]%n",
            rotated.values()[0], rotated.values()[1]);
    }
    
    private static void demonstrateRotationTransformations() throws TransformationException {
        System.out.println("\\n--- Rotation Transformations ---");
        
        // 2D rotation
        var rotation2D = RotationTransformation.rotation2D(Math.PI / 2); // 90 degrees
        var point2D = new Coordinate(new double[]{1.0, 0.0});
        var rotated2D = rotation2D.transform(point2D);
        
        System.out.printf("2D: [%.1f, %.1f] rotated 90° = [%.3f, %.3f]%n",
            point2D.values()[0], point2D.values()[1],
            rotated2D.values()[0], rotated2D.values()[1]);
        
        // Check transformation properties
        System.out.printf("Rotation is invertible: %b%n", rotation2D.isInvertible());
        double det = rotation2D.computeJacobianDeterminant(point2D);
        System.out.printf("Rotation determinant: %.1f (should be 1.0)%n", det);
    }
    
    private static void demonstrateScalingTransformations() throws TransformationException {
        System.out.println("\\n--- Scaling Transformations ---");
        
        // Uniform scaling
        var uniformScaling = new ScalingTransformation(new double[]{2.0, 2.0});
        var point = new Coordinate(new double[]{3.0, 4.0});
        var uniformScaled = uniformScaling.transform(point);
        
        System.out.printf("Uniform scaling 2x: [%.1f, %.1f] → [%.1f, %.1f]%n",
            point.values()[0], point.values()[1],
            uniformScaled.values()[0], uniformScaled.values()[1]);
        
        // Non-uniform scaling
        var nonUniformScaling = new ScalingTransformation(new double[]{1.5, 0.5});
        var nonUniformScaled = nonUniformScaling.transform(point);
        
        System.out.printf("Non-uniform scaling [1.5x, 0.5x]: [%.1f, %.1f] → [%.1f, %.1f]%n",
            point.values()[0], point.values()[1],
            nonUniformScaled.values()[0], nonUniformScaled.values()[1]);
        
        System.out.printf("Scaling factor product: %.2f%n", 
            uniformScaling.getScaleFactors()[0] * uniformScaling.getScaleFactors()[1]);
    }
    
    private static void demonstrateTranslationTransformations() throws TransformationException {
        System.out.println("\\n--- Translation Transformations ---");
        
        var translation = new TranslationTransformation(new double[]{5.0, -2.0});
        var point = new Coordinate(new double[]{1.0, 3.0});
        var translated = translation.transform(point);
        
        System.out.printf("Translation by [5.0, -2.0]: [%.1f, %.1f] → [%.1f, %.1f]%n",
            point.values()[0], point.values()[1],
            translated.values()[0], translated.values()[1]);
        
        // Translation vector
        var translationVector = translation.getTranslationVector();
        System.out.printf("Translation vector: [%.1f, %.1f]%n",
            translationVector[0], translationVector[1]);
        
        System.out.printf("Translation is affine: %b%n", translation.isAffine());
    }
    
    private static void demonstrateCompositeTransformations() throws TransformationException {
        System.out.println("\\n--- Composite Transformations ---");
        
        // Create individual transformations
        var scaling = new ScalingTransformation(new double[]{2.0, 2.0});
        var rotation = RotationTransformation.rotation2D(Math.PI / 4); // 45 degrees  
        var translation = new TranslationTransformation(new double[]{1.0, 1.0});
        
        // Compose transformations: translate, then rotate, then scale
        var composite = new CompositeTransformation(translation, rotation, scaling);
        
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = composite.transform(point);
        
        System.out.printf("Composite transformation: [%.1f, %.1f] → [%.3f, %.3f]%n",
            point.values()[0], point.values()[1],
            result.values()[0], result.values()[1]);
        
        // Apply transformations step by step for comparison
        var step1 = translation.transform(point);
        var step2 = rotation.transform(step1);
        var step3 = scaling.transform(step2);
        
        System.out.printf("Step-by-step verification: [%.3f, %.3f]%n",
            step3.values()[0], step3.values()[1]);
        
        System.out.printf("Results match: %b%n",
            Math.abs(result.values()[0] - step3.values()[0]) < 1e-10 &&
            Math.abs(result.values()[1] - step3.values()[1]) < 1e-10);
    }
    
    private static void demonstrateInverseTransformations() throws TransformationException {
        System.out.println("\\n--- Inverse Transformations ---");
        
        // Linear transformation with inverse
        var matrix = new double[][]{
            {2.0, 1.0},
            {0.0, 1.0}
        };
        var linearTransform = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{3.0, 2.0});
        
        // Forward transformation
        var transformed = linearTransform.transform(point);
        System.out.printf("Forward: [%.1f, %.1f] → [%.1f, %.1f]%n",
            point.values()[0], point.values()[1],
            transformed.values()[0], transformed.values()[1]);
        
        // Inverse transformation
        var inverse = linearTransform.inverse();
        if (inverse.isPresent()) {
            var restored = inverse.get().transform(transformed);
            System.out.printf("Inverse: [%.1f, %.1f] → [%.3f, %.3f]%n",
                transformed.values()[0], transformed.values()[1],
                restored.values()[0], restored.values()[1]);
            
            // Check if we got back the original point
            boolean isIdentity = Math.abs(restored.values()[0] - point.values()[0]) < 1e-10 &&
                               Math.abs(restored.values()[1] - point.values()[1]) < 1e-10;
            System.out.printf("Round-trip successful: %b%n", isIdentity);
        } else {
            System.out.println("Transformation is not invertible");
        }
        
        // Rotation transformation (always invertible)
        var rotation = RotationTransformation.rotation2D(Math.PI / 3); // 60 degrees
        var rotationInverse = rotation.inverse();
        
        var rotated = rotation.transform(point);
        var rotatedBack = rotationInverse.get().transform(rotated);
        
        System.out.printf("Rotation round-trip: [%.1f, %.1f] → [%.3f, %.3f]%n",
            point.values()[0], point.values()[1],
            rotatedBack.values()[0], rotatedBack.values()[1]);
    }
    
    private static void demonstrateBatchTransformations() throws TransformationException {
        System.out.println("\\n--- Batch Transformations ---");
        
        // Create a set of points
        var points = java.util.List.of(
            new Coordinate(new double[]{0.0, 0.0}),
            new Coordinate(new double[]{1.0, 0.0}),
            new Coordinate(new double[]{1.0, 1.0}),
            new Coordinate(new double[]{0.0, 1.0})
        );
        
        System.out.println("Original points (unit square):");
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            System.out.printf("  Point %d: [%.1f, %.1f]%n", i, p.values()[0], p.values()[1]);
        }
        
        // Apply scaling transformation to all points
        var scaling = new ScalingTransformation(new double[]{2.0, 3.0});
        var transformedPoints = scaling.transformBatch(points);
        
        System.out.println("After scaling [2x, 3x]:");
        for (int i = 0; i < transformedPoints.size(); i++) {
            var p = transformedPoints.get(i);
            System.out.printf("  Point %d: [%.1f, %.1f]%n", i, p.values()[0], p.values()[1]);
        }
        
        // Calculate area change
        double originalArea = 1.0; // Unit square
        double scaledArea = originalArea * scaling.getScaleFactors()[0] * scaling.getScaleFactors()[1];
        System.out.printf("Area scaling factor: %.1f (%.1f → %.1f)%n",
            scaledArea / originalArea, originalArea, scaledArea);
    }
}