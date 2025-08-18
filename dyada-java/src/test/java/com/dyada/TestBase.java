package com.dyada;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.MultiscaleIndex;
import com.dyada.core.bitarray.BitArray;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Base class for all DyAda tests providing common utilities and setup.
 */
public abstract class TestBase {
    
    protected static final Logger log = LoggerFactory.getLogger(TestBase.class);
    
    protected Random random;
    protected static final long RANDOM_SEED = 42L;
    
    @BeforeEach
    void setUp(TestInfo testInfo) {
        log.debug("Starting test: {}.{}", 
            testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown"),
            testInfo.getDisplayName());
        
        // Initialize with fixed seed for reproducible tests
        random = new Random(RANDOM_SEED);
    }
    
    // Coordinate creation helpers
    protected Coordinate coordinate2D(double x, double y) {
        return new Coordinate(new double[]{x, y});
    }
    
    protected Coordinate coordinate3D(double x, double y, double z) {
        return new Coordinate(new double[]{x, y, z});
    }
    
    // LevelIndex creation helpers
    protected LevelIndex levelIndex2D(byte level, int x, int y) {
        var levels = new byte[2];
        var indices = new long[]{x, y};
        
        // Calculate appropriate levels for each dimension based on index values
        for (int d = 0; d < 2; d++) {
            var index = (d == 0) ? x : y;
            if (index == 0) {
                levels[d] = 0;
            } else {
                levels[d] = (byte) Math.max(level, 32 - Integer.numberOfLeadingZeros(index));
            }
        }
        
        return new LevelIndex(levels, indices);
    }
    
    protected LevelIndex levelIndex3D(byte level, int x, int y, int z) {
        var levels = new byte[3];
        var indices = new long[]{x, y, z};
        
        // Calculate appropriate levels for each dimension based on index values
        int[] indexValues = {x, y, z};
        for (int d = 0; d < 3; d++) {
            var index = indexValues[d];
            if (index == 0) {
                levels[d] = 0;
            } else {
                levels[d] = (byte) Math.max(level, 32 - Integer.numberOfLeadingZeros(index));
            }
        }
        
        return new LevelIndex(levels, indices);
    }
    
    // MultiscaleIndex creation helpers
    protected MultiscaleIndex multiscaleIndex2D(byte level, int x, int y) {
        return MultiscaleIndex.create2D(level, x, y);
    }
    
    protected MultiscaleIndex multiscaleIndex3D(byte level, int x, int y, int z) {
        return MultiscaleIndex.create3D(level, x, y, z);
    }
    
    // BitArray creation helpers
    protected BitArray bitArray(int size) {
        return BitArray.of(size);
    }
    
    protected BitArray bitArray(boolean... values) {
        var array = BitArray.of(values.length);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                array = array.set(i, true);
            }
        }
        return array;
    }
    
    // Random data generation helpers
    protected double[] randomDoubleArray(int size, double min, double max) {
        var array = new double[size];
        for (int i = 0; i < size; i++) {
            array[i] = min + random.nextDouble() * (max - min);
        }
        return array;
    }
    
    protected int[] randomIntArray(int size, int min, int max) {
        var array = new int[size];
        for (int i = 0; i < size; i++) {
            array[i] = min + random.nextInt(max - min + 1);
        }
        return array;
    }
    
    protected Coordinate randomCoordinate2D(double min, double max) {
        return coordinate2D(
            min + random.nextDouble() * (max - min),
            min + random.nextDouble() * (max - min)
        );
    }
    
    protected Coordinate randomCoordinate3D(double min, double max) {
        return coordinate3D(
            min + random.nextDouble() * (max - min),
            min + random.nextDouble() * (max - min),
            min + random.nextDouble() * (max - min)
        );
    }
    
    // Assertion helpers
    protected void assertArrayEquals(double[] expected, double[] actual, double delta) {
        if (expected.length != actual.length) {
            throw new AssertionError("Array lengths differ: expected " + expected.length + 
                                   " but was " + actual.length);
        }
        
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > delta) {
                throw new AssertionError("Arrays differ at index " + i + 
                                       ": expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }
    
    protected void assertCoordinateEquals(Coordinate expected, Coordinate actual, double delta) {
        assertArrayEquals(expected.values(), actual.values(), delta);
    }
    
    // Performance testing helpers
    protected long timeOperation(Runnable operation) {
        var start = System.nanoTime();
        operation.run();
        return System.nanoTime() - start;
    }
    
    protected void logPerformance(String operation, long nanos) {
        double millis = nanos / 1_000_000.0;
        log.debug("{} took {:.3f} ms", operation, millis);
    }
}