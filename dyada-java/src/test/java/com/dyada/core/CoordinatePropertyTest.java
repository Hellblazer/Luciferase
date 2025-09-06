package com.dyada.core;

import com.dyada.core.coordinates.Coordinate;

import java.util.Arrays;

import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.Combinators;

import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Coordinate Property-Based Tests")
class CoordinatePropertyTest {
    
    @Property
    @Label("Addition is commutative")
    void additionIsCommutative(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b
    ) {
        var result1 = a.add(b);
        var result2 = b.add(a);
        
        assertCoordinatesEqual(result1, result2, 1e-10);
    }
    
    @Property
    @Label("Addition is associative")
    void additionIsAssociative(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b,
        @ForAll("coordinates2D") Coordinate c
    ) {
        var result1 = a.add(b).add(c);
        var result2 = a.add(b.add(c));
        
        assertCoordinatesEqual(result1, result2, 1e-10);
    }
    
    @Property
    @Label("Subtraction is inverse of addition")
    void subtractionIsInverseOfAddition(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b
    ) {
        var sum = a.add(b);
        var difference = sum.subtract(b);
        
        assertCoordinatesEqual(a, difference, 1e-10);
    }
    
    @Property
    @Label("Scalar multiplication is distributive")
    void scalarMultiplicationIsDistributive(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b,
        @ForAll @DoubleRange(min = -10.0, max = 10.0) double scalar
    ) {
        var result1 = a.add(b).multiply(scalar);
        var result2 = a.multiply(scalar).add(b.multiply(scalar));
        
        assertCoordinatesEqual(result1, result2, 1e-10);
    }
    
    @Property
    @Label("Scalar multiplication by zero gives zero vector")
    void scalarMultiplicationByZero(
        @ForAll("coordinates2D") Coordinate coord
    ) {
        var result = coord.multiply(0.0);
        
        for (int i = 0; i < result.dimensions(); i++) {
            assertEquals(0.0, result.get(i), 1e-15);
        }
    }
    
    @Property
    @Label("Scalar multiplication by one is identity")
    void scalarMultiplicationByOneIsIdentity(
        @ForAll("coordinates2D") Coordinate coord
    ) {
        var result = coord.multiply(1.0);
        
        assertCoordinatesEqual(coord, result, 1e-15);
    }
    
    @Property
    @Label("Distance is symmetric")
    void distanceIsSymmetric(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b
    ) {
        double distance1 = a.distance(b);
        double distance2 = b.distance(a);
        
        assertEquals(distance1, distance2, 1e-10);
    }
    
    @Property
    @Label("Distance is non-negative")
    void distanceIsNonNegative(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b
    ) {
        double distance = a.distance(b);
        
        assertTrue(distance >= 0.0);
    }
    
    @Property
    @Label("Distance to self is zero")
    void distanceToSelfIsZero(
        @ForAll("coordinates2D") Coordinate coord
    ) {
        double distance = coord.distance(coord);
        
        assertEquals(0.0, distance, 1e-15);
    }
    
    @Property
    @Label("Triangle inequality holds")
    void triangleInequality(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b,
        @ForAll("coordinates2D") Coordinate c
    ) {
        double ab = a.distance(b);
        double bc = b.distance(c);
        double ac = a.distance(c);
        
        assertTrue(ac <= ab + bc + 1e-10); // Add small epsilon for floating point errors
    }
    
    @Property
    @Label("Dot product is commutative")
    void dotProductIsCommutative(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b
    ) {
        double result1 = a.dotProduct(b);
        double result2 = b.dotProduct(a);
        
        assertEquals(result1, result2, 1e-10);
    }
    
    @Property
    @Label("Dot product is distributive over addition")
    void dotProductIsDistributive(
        @ForAll("coordinates2D") Coordinate a,
        @ForAll("coordinates2D") Coordinate b,
        @ForAll("coordinates2D") Coordinate c
    ) {
        double result1 = a.dotProduct(b.add(c));
        double result2 = a.dotProduct(b) + a.dotProduct(c);
        
        assertEquals(result1, result2, 1e-10);
    }
    
    @Property
    @Label("Normalized vector has magnitude 1")
    void normalizedVectorHasMagnitudeOne(
        @ForAll("nonZeroCoordinates2D") Coordinate coord
    ) {
        var normalized = coord.normalize();
        
        assertEquals(1.0, normalized.magnitude(), 1e-10);
    }
    
    @Property
    @Label("Linear interpolation at t=0 gives start point")
    void lerpAtZeroGivesStart(
        @ForAll("coordinates2D") Coordinate start,
        @ForAll("coordinates2D") Coordinate end
    ) {
        var result = start.lerp(end, 0.0);
        
        assertCoordinatesEqual(start, result, 1e-12);
    }
    
    @Property
    @Label("Linear interpolation at t=1 gives end point")
    void lerpAtOneGivesEnd(
        @ForAll("coordinates2D") Coordinate start,
        @ForAll("coordinates2D") Coordinate end
    ) {
        var result = start.lerp(end, 1.0);
        
        assertCoordinatesEqual(end, result, 1e-12);
    }
    
    @Property
    @Label("Linear interpolation at t=0.5 gives midpoint")
    void lerpAtHalfGivesMidpoint(
        @ForAll("coordinates2D") Coordinate start,
        @ForAll("coordinates2D") Coordinate end
    ) {
        var midpoint = start.lerp(end, 0.5);
        var expectedMidpoint = start.add(end).multiply(0.5);
        
        assertCoordinatesEqual(expectedMidpoint, midpoint, 1e-10);
    }
    
    @Property
    @Label("Cross product in 3D is perpendicular to both vectors")
    void crossProductIsPerpendicular(
        @ForAll("coordinates3D") Coordinate a,
        @ForAll("coordinates3D") Coordinate b
    ) {
        Assume.that(a.magnitude() > 1e-6 && b.magnitude() > 1e-6);
        
        var crossProduct = a.crossProduct(b);
        
        // Cross product should be perpendicular to both input vectors
        double dotA = crossProduct.dotProduct(a);
        double dotB = crossProduct.dotProduct(b);
        
        assertEquals(0.0, dotA, 1e-9);
        assertEquals(0.0, dotB, 1e-9);
    }
    
    @Property
    @Label("Magnitude squared equals dot product with self")
    void magnitudeSquaredEqualsDotProductWithSelf(
        @ForAll("coordinates2D") Coordinate coord
    ) {
        double magnitudeSquared = coord.magnitude() * coord.magnitude();
        double dotProductWithSelf = coord.dotProduct(coord);
        
        assertEquals(magnitudeSquared, dotProductWithSelf, 1e-10);
    }
    
    // Generators
    
    @Provide
    Arbitrary<Coordinate> coordinates2D() {
        return Arbitraries.doubles()
            .between(-100.0, 100.0)
            .array(double[].class)
            .ofSize(2)
            .map(Coordinate::new);
    }
    
    @Provide
    Arbitrary<Coordinate> coordinates3D() {
        return Arbitraries.doubles()
            .between(-100.0, 100.0)
            .array(double[].class)
            .ofSize(3)
            .map(Coordinate::new);
    }
    
    @Provide
    Arbitrary<Coordinate> nonZeroCoordinates2D() {
        return coordinates2D()
            .filter(coord -> coord.magnitude() > 1e-6);
    }
    
    @Provide
    Arbitrary<Coordinate> coordinatesInRange() {
        return Arbitraries.integers()
            .between(2, 5)
            .flatMap(dimensions -> 
                Arbitraries.doubles()
                    .between(-10.0, 10.0)
                    .array(double[].class)
                    .ofSize(dimensions)
                    .map(Coordinate::new)
            );
    }
    
    // Helper methods
    
    private void assertCoordinatesEqual(Coordinate expected, Coordinate actual, double delta) {
        assertEquals(expected.dimensions(), actual.dimensions());
        
        for (int i = 0; i < expected.dimensions(); i++) {
            assertEquals(expected.get(i), actual.get(i), delta, 
                "Coordinates differ at dimension " + i);
        }
    }
}

@DisplayName("MultiscaleIndex Property-Based Tests")
class MultiscaleIndexPropertyTest {
    
    @Property
    @Label("Converting to and from LevelIndex preserves uniform indices")
    void levelIndexRoundTrip(
        @ForAll("validLevelIndexPairs") com.dyada.core.coordinates.LevelIndex levelIndex
    ) {
        var multiscaleIndex = MultiscaleIndex.fromLevelIndex(levelIndex);
        var roundTrip = multiscaleIndex.toLevelIndex();
        
        assertEquals(levelIndex.level(), roundTrip.level());
        assertArrayEquals(levelIndex.coordinates(), roundTrip.coordinates());
    }
    
    @Property
    @Label("Max level is always >= min level")
    void maxLevelIsAlwaysGreaterThanOrEqualToMinLevel(
        @ForAll("multiscaleIndices") MultiscaleIndex index
    ) {
        assertTrue(index.getMaxLevel() >= index.getMinLevel());
    }
    
    @Property
    @Label("Uniform indices have same level in all dimensions")
    void uniformIndicesHaveSameLevel(
        @ForAll @IntRange(min = 1, max = 5) int dimensions,
        @ForAll @IntRange(min = 0, max = 10) byte level,
        @ForAll @IntRange(min = 0, max = 100) int baseIndex
    ) {
        Assume.that(level >= 0 && level <= 10); // Extra safety check to override cached samples
        var index = MultiscaleIndex.uniform(dimensions, level, baseIndex);
        
        assertTrue(index.isUniform());
        assertEquals(level, index.getMaxLevel());
        assertEquals(level, index.getMinLevel());
        
        for (int i = 0; i < dimensions; i++) {
            assertEquals(level, index.getLevel(i));
            assertEquals(baseIndex, index.getIndex(i));
        }
    }
    
    @Property
    @Label("WithLevel preserves other dimensions")
    void withLevelPreservesOtherDimensions(
        @ForAll("multiscaleIndices") MultiscaleIndex original,
        @ForAll @IntRange(min = 0, max = 10) byte newLevel
    ) {
        Assume.that(original.dimensions() > 1);
        Assume.that(newLevel >= 0); // Extra safety check
        
        int dimensionToChange = 0;
        var modified = original.withLevel(dimensionToChange, newLevel);
        
        assertEquals(newLevel, modified.getLevel(dimensionToChange));
        
        // Other dimensions should be unchanged
        for (int i = 1; i < original.dimensions(); i++) {
            assertEquals(original.getLevel(i), modified.getLevel(i));
            assertEquals(original.getIndex(i), modified.getIndex(i));
        }
    }
    
    @Property
    @Label("WithIndex preserves other dimensions")
    void withIndexPreservesOtherDimensions(
        @ForAll("multiscaleIndices") MultiscaleIndex original,
        @ForAll @IntRange(min = 0, max = 100) int newIndex
    ) {
        Assume.that(original.dimensions() > 1);
        
        int dimensionToChange = 0;
        var modified = original.withIndex(dimensionToChange, newIndex);
        
        assertEquals(newIndex, modified.getIndex(dimensionToChange));
        
        // Other dimensions should be unchanged
        for (int i = 1; i < original.dimensions(); i++) {
            assertEquals(original.getLevel(i), modified.getLevel(i));
            assertEquals(original.getIndex(i), modified.getIndex(i));
        }
    }
    
    // Generators
    
    @Provide
    Arbitrary<MultiscaleIndex> multiscaleIndices() {
        return Arbitraries.integers()
            .between(2, 4)
            .flatMap(dimensions -> 
                Combinators.combine(
                    levelArrays(dimensions),
                    indexArrays(dimensions)
                ).as(MultiscaleIndex::create)
            );
    }
    
    @Provide
    Arbitrary<int[]> coordinateArrays() {
        return Arbitraries.integers()
            .between(2, 4)
            .flatMap(dimensions ->
                Arbitraries.integers()
                    .between(0, 100)
                    .array(int[].class)
                    .ofSize(dimensions)
            );
    }
    
    private Arbitrary<byte[]> levelArrays(int dimensions) {
        return Arbitraries.integers()
            .between(0, 10)
            .map(i -> (byte) i.intValue())
            .array(byte[].class)
            .ofSize(dimensions);
    }
    
    @Provide
    Arbitrary<com.dyada.core.coordinates.LevelIndex> validLevelIndexPairs() {
        return Arbitraries.integers()
            .between(2, 4)
            .flatMap(dimensions ->
                Arbitraries.integers()
                    .between(0, 10)
                    .map(level -> (byte) level.intValue())
                    .flatMap(level -> {
                        long maxIndex = (1L << level) - 1;
                        return Arbitraries.integers()
                            .between(0, (int) Math.min(maxIndex, 100))
                            .array(int[].class)
                            .ofSize(dimensions)
                            .map(indices -> {
                                byte[] levels = new byte[dimensions];
                                Arrays.fill(levels, level);
                                long[] longIndices = Arrays.stream(indices).asLongStream().toArray();
                                return new com.dyada.core.coordinates.LevelIndex(levels, longIndices);
                            });
                    })
            );
    }
    
    private Arbitrary<int[]> indexArrays(int dimensions) {
        return Arbitraries.integers()
            .between(0, 100)
            .array(int[].class)
            .ofSize(dimensions);
    }
}