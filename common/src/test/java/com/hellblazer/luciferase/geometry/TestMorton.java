package com.hellblazer.luciferase.geometry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Morton 3D test
 *
 * @author Eren
 */
public class TestMorton {

    // correct morton codes f
    private final int[]       control_3D_Encode = { 0, 4, 32, 36, 256, 260, 288, 292, 2048, 2052, 2080, 2084, 2304,
                                                    2308, 2336, 2340, 2, 6, 34, 38, 258, 262, 290, 294, 2050, 2054,
                                                    2082, 2086, 2306, 2310, 2338, 1753, 1757, 1785, 1789, 2009, 2013,
                                                    2041, 2045, 3801, 3805, 3833, 3837, 4057, 4061, 4089, 4093, 1755,
                                                    1759, 1787, 1791, 2011, 2015, 2043, 2047, 3803, 3807, 3835, 3839,
                                                    4059, 4063, 4091, 4095 };
    // Correct morton codes for decoding test
    private final int[][]     control_3D_Decode = { { 0, 0, 0 }, { 0, 0, 1 }, { 0, 0, 2 }, { 0, 0, 3 }, { 0, 0, 4 },
                                                    { 0, 0, 5 }, { 0, 0, 6 }, { 0, 0, 7 }, { 0, 0, 8 }, { 0, 0, 9 },
                                                    { 0, 0, 10 }, { 0, 0, 11 }, { 0, 0, 12 }, { 0, 0, 13 },
                                                    { 0, 0, 14 }, { 0, 0, 15 }, { 0, 1, 0 }, { 0, 1, 1 }, { 0, 1, 2 },
                                                    { 0, 1, 3 }, { 0, 1, 4 }, { 0, 1, 5 }, { 0, 1, 6 }, { 0, 1, 7 },
                                                    { 0, 1, 8 }, { 0, 1, 9 }, { 0, 1, 10 }, { 0, 1, 11 }, { 0, 1, 12 },
                                                    { 0, 1, 13 }, { 0, 1, 14 }, { 15, 14, 0 }, { 15, 14, 1 },
                                                    { 15, 14, 2 }, { 15, 14, 3 }, { 15, 14, 4 }, { 15, 14, 5 },
                                                    { 15, 14, 6 }, { 15, 14, 7 }, { 15, 14, 8 }, { 15, 14, 9 },
                                                    { 15, 14, 10 }, { 15, 14, 11 }, { 15, 14, 12 }, { 15, 14, 13 },
                                                    { 15, 14, 14 }, { 15, 14, 15 }, { 15, 15, 0 }, { 15, 15, 1 },
                                                    { 15, 15, 2 }, { 15, 15, 3 }, { 15, 15, 4 }, { 15, 15, 5 },
                                                    { 15, 15, 6 }, { 15, 15, 7 }, { 15, 15, 8 }, { 15, 15, 9 },
                                                    { 15, 15, 10 }, { 15, 15, 11 }, { 15, 15, 12 }, { 15, 15, 13 },
                                                    { 15, 15, 14 }, { 15, 15, 15 } };
    protected     MortonCurve mortonTest;
    private       Random      random;

    @Test
    public void comparison() {
        var entropy = new Random(0x1638);
        var points = new ArrayList<Tuple3f>();
        for (int i = 0; i < 1024; i++) {
            long c = (long) (entropy.nextDouble() * Math.pow(2, 64));
            int[] p = MortonCurve.decode(c);
            points.add(new Point3f(p[0], p[1], p[2]));
        }
        var mortonSorted = new ArrayList<>(points);
        mortonSorted.sort((o1, o2) -> {
            var a = MortonCurve.encode((int) o1.x, (int) o1.y, (int) o1.z);
            var b = MortonCurve.encode((int) o2.x, (int) o2.y, (int) o2.z);
            return Long.compareUnsigned(a, b);
        });

        var mortonCompared = new ArrayList<>(points);
        mortonCompared.sort(MortonCurve.floatComparator());

        assertEquals(mortonSorted, mortonCompared);
    }

    @BeforeEach
    public void setUp() {
        mortonTest = new MortonCurve();
        random = new Random(0x1638);
    }

    @Test
    public void testDecode() {
        try {
            for (int i = 0; i < 1024; i++) {
                long c = (long) (random.nextDouble() * Math.pow(2, 64));
                MortonCurve.decode(c);
            }
            Assertions.fail("My method didn't throw when I expected it to");
        } catch (Throwable ex) {
            System.out.printf("Caught %s%n", ex);
        }
        for (int i = 0; i < 63; i++) {
            Assertions.assertArrayEquals(MortonCurve.decode(control_3D_Encode[i]),
                                         new int[] { control_3D_Decode[i][0], control_3D_Decode[i][1],
                                                     control_3D_Decode[i][2] });
        }
    }

    @Test
    public void testEncode() {
        for (int i = 0; i < 63; i++) {
            assertEquals(MortonCurve.encode(control_3D_Decode[i][0], control_3D_Decode[i][1], control_3D_Decode[i][2]),
                         control_3D_Encode[i]);
        }
    }
}
