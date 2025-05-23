package com.hellblazer.luciferase.geometry;

import javax.vecmath.Tuple3d;
import javax.vecmath.Tuple3f;

/**
 * @author hal.hildebrand
 **/
public class MortonCurve {
    public static final long LOOPS          = (long) Math.floor(64.0f / 9.0f);
    public static final long EIGHT_BIT_MASK = 0x000000ff;
    public static final long MAX_BITS       = (long) Math.pow(2, 48);

    //
    private final static int[] D3256 = { 0x00000000, 0x00000001, 0x00000008, 0x00000009, 0x00000040, 0x00000041,
                                         0x00000048, 0x00000049, 0x00000200, 0x00000201, 0x00000208, 0x00000209,
                                         0x00000240, 0x00000241, 0x00000248, 0x00000249, 0x00001000, 0x00001001,
                                         0x00001008, 0x00001009, 0x00001040, 0x00001041, 0x00001048, 0x00001049,
                                         0x00001200, 0x00001201, 0x00001208, 0x00001209, 0x00001240, 0x00001241,
                                         0x00001248, 0x00001249, 0x00008000, 0x00008001, 0x00008008, 0x00008009,
                                         0x00008040, 0x00008041, 0x00008048, 0x00008049, 0x00008200, 0x00008201,
                                         0x00008208, 0x00008209, 0x00008240, 0x00008241, 0x00008248, 0x00008249,
                                         0x00009000, 0x00009001, 0x00009008, 0x00009009, 0x00009040, 0x00009041,
                                         0x00009048, 0x00009049, 0x00009200, 0x00009201, 0x00009208, 0x00009209,
                                         0x00009240, 0x00009241, 0x00009248, 0x00009249, 0x00040000, 0x00040001,
                                         0x00040008, 0x00040009, 0x00040040, 0x00040041, 0x00040048, 0x00040049,
                                         0x00040200, 0x00040201, 0x00040208, 0x00040209, 0x00040240, 0x00040241,
                                         0x00040248, 0x00040249, 0x00041000, 0x00041001, 0x00041008, 0x00041009,
                                         0x00041040, 0x00041041, 0x00041048, 0x00041049, 0x00041200, 0x00041201,
                                         0x00041208, 0x00041209, 0x00041240, 0x00041241, 0x00041248, 0x00041249,
                                         0x00048000, 0x00048001, 0x00048008, 0x00048009, 0x00048040, 0x00048041,
                                         0x00048048, 0x00048049, 0x00048200, 0x00048201, 0x00048208, 0x00048209,
                                         0x00048240, 0x00048241, 0x00048248, 0x00048249, 0x00049000, 0x00049001,
                                         0x00049008, 0x00049009, 0x00049040, 0x00049041, 0x00049048, 0x00049049,
                                         0x00049200, 0x00049201, 0x00049208, 0x00049209, 0x00049240, 0x00049241,
                                         0x00049248, 0x00049249, 0x00200000, 0x00200001, 0x00200008, 0x00200009,
                                         0x00200040, 0x00200041, 0x00200048, 0x00200049, 0x00200200, 0x00200201,
                                         0x00200208, 0x00200209, 0x00200240, 0x00200241, 0x00200248, 0x00200249,
                                         0x00201000, 0x00201001, 0x00201008, 0x00201009, 0x00201040, 0x00201041,
                                         0x00201048, 0x00201049, 0x00201200, 0x00201201, 0x00201208, 0x00201209,
                                         0x00201240, 0x00201241, 0x00201248, 0x00201249, 0x00208000, 0x00208001,
                                         0x00208008, 0x00208009, 0x00208040, 0x00208041, 0x00208048, 0x00208049,
                                         0x00208200, 0x00208201, 0x00208208, 0x00208209, 0x00208240, 0x00208241,
                                         0x00208248, 0x00208249, 0x00209000, 0x00209001, 0x00209008, 0x00209009,
                                         0x00209040, 0x00209041, 0x00209048, 0x00209049, 0x00209200, 0x00209201,
                                         0x00209208, 0x00209209, 0x00209240, 0x00209241, 0x00209248, 0x00209249,
                                         0x00240000, 0x00240001, 0x00240008, 0x00240009, 0x00240040, 0x00240041,
                                         0x00240048, 0x00240049, 0x00240200, 0x00240201, 0x00240208, 0x00240209,
                                         0x00240240, 0x00240241, 0x00240248, 0x00240249, 0x00241000, 0x00241001,
                                         0x00241008, 0x00241009, 0x00241040, 0x00241041, 0x00241048, 0x00241049,
                                         0x00241200, 0x00241201, 0x00241208, 0x00241209, 0x00241240, 0x00241241,
                                         0x00241248, 0x00241249, 0x00248000, 0x00248001, 0x00248008, 0x00248009,
                                         0x00248040, 0x00248041, 0x00248048, 0x00248049, 0x00248200, 0x00248201,
                                         0x00248208, 0x00248209, 0x00248240, 0x00248241, 0x00248248, 0x00248249,
                                         0x00249000, 0x00249001, 0x00249008, 0x00249009, 0x00249040, 0x00249041,
                                         0x00249048, 0x00249049, 0x00249200, 0x00249201, 0x00249208, 0x00249209,
                                         0x00249240, 0x00249241, 0x00249248, 0x00249249 };

    //
    private final static int[] Decode512X = { 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2,
                                              3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1,
                                              0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6,
                                              7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5,
                                              4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6,
                                              7, 6, 7, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1,
                                              0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2,
                                              3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 4, 5, 4, 5, 4, 5, 4, 5,
                                              6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4,
                                              5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7,
                                              6, 7, 6, 7, 6, 7, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0,
                                              1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3,
                                              2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 4, 5, 4, 5, 4,
                                              5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7,
                                              6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4,
                                              5, 6, 7, 6, 7, 6, 7, 6, 7, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3,
                                              0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2,
                                              3, 2, 3, 2, 3, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 2, 3, 2, 3, 2, 3, 2, 3, 4, 5,
                                              4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6,
                                              7, 6, 7, 6, 7, 4, 5, 4, 5, 4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7, 4, 5, 4, 5,
                                              4, 5, 4, 5, 6, 7, 6, 7, 6, 7, 6, 7 };

    //
    private final static int[] Decode512Y = { 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3, 2,
                                              2, 3, 3, 2, 2, 3, 3, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2,
                                              3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1,
                                              1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 0, 0, 1, 1,
                                              0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2,
                                              2, 3, 3, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6,
                                              7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5,
                                              5, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 4, 4, 5, 5, 4, 4, 5, 5,
                                              4, 4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 4,
                                              4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6,
                                              7, 7, 6, 6, 7, 7, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3,
                                              3, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1,
                                              0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 0, 0, 1, 1, 0,
                                              0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2, 2,
                                              3, 3, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3,
                                              3, 2, 2, 3, 3, 2, 2, 3, 3, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5,
                                              6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 4, 4, 5, 5, 4, 4, 5, 5, 4,
                                              4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7, 4, 4,
                                              5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7,
                                              7, 6, 6, 7, 7, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 4, 4, 5, 5, 6, 6, 7, 7,
                                              6, 6, 7, 7, 6, 6, 7, 7, 6, 6, 7, 7 };

    //
    private final static int[] Decode512Z = { 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0,
                                              0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2,
                                              2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0,
                                              0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
                                              3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3,
                                              3, 3, 3, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1,
                                              1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3,
                                              3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 0, 0, 0, 0, 1, 1, 1, 1,
                                              0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 2,
                                              2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2,
                                              2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4,
                                              4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6,
                                              7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 4, 4, 4, 4, 5,
                                              5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5,
                                              5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7,
                                              7, 6, 6, 6, 6, 7, 7, 7, 7, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5,
                                              4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 6,
                                              6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 4, 4,
                                              4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 5, 5, 5, 4, 4, 4,
                                              4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7, 6, 6, 6, 6,
                                              7, 7, 7, 7, 6, 6, 6, 6, 7, 7, 7, 7 };

    //
    public static byte MAX_REFINEMENT_LEVEL = 21;

    //
    public static int MAX_COORDINATE_VALUE = 2097151;

    /**
     * Compare the vertices using their morton order
     *
     * From <a href="Fast Construction of k-Nearest Neighbor Graphs for Point
     * Clouds">https://www.computer.org/csdl/journal/tg/2010/04/ttg2010040599/13rRUNvyate</a>
     *
     * <pre>
     * Procedure COMPARE (point p; point q)
     *   x = 0; dim = 0
     *  for all j = 0 to d do
     *      y = XORMSB(p(j),q(j))
     *      If x < y then
     *          x = y; dim = j
     *      end if
     *  end for
     *  return p(dim) < q (dim)
     *  end procedure
     * </pre>
     *
     * @param b the vertex to be compared.
     * @return 1 if >, 0 if =, -1 if <
     */
    public static boolean compareTo(Tuple3f a, Tuple3f b) {
        var x = 0;
        var dim = 0;
        for (var j = 0; j < 3; j++) {
            var y = xormsb(coordinate(a, j), coordinate(b, j));
            if (x < y) {
                x = y;
                dim = j;
            }
        }
        var ap = coordinate(a, dim);
        var bp = coordinate(b, dim);
        return ap < bp;
    }

    public static boolean compareTo(Tuple3d a, Tuple3d b) {
        var x = 0;
        var dim = 0;
        for (var j = 0; j < 3; j++) {
            var y = xormsb(coordinate(a, j), coordinate(b, j));
            if (x < y) {
                x = y;
                dim = j;
            }
        }
        var ap = coordinate(a, dim);
        var bp = coordinate(b, dim);
        return ap < bp;
    }

    public static float coordinate(Tuple3f t, int d) {
        return switch (d) {
            case 0 -> t.x;
            case 1 -> t.y;
            case 2 -> t.z;
            default -> throw new IllegalArgumentException();
        };
    }

    public static double coordinate(Tuple3d t, int d) {
        return switch (d) {
            case 0 -> t.x;
            case 1 -> t.y;
            case 2 -> t.z;
            default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Decode Morton (z-ordering)
     *
     * @param c morton code up to 64 bits
     * @return array [x,y,z] .
     */
    public static int[] decode(long c) {
        var result = new int[3];
        // Morton codes up to 64 bits
        if (c < MAX_BITS) {
            result[0] = decodeHelper(c, Decode512X);
            result[1] = decodeHelper(c, Decode512Y);
            result[2] = decodeHelper(c, Decode512Z);
        }
        return result;
    }

    /**
     * Helper Method for LUT decoding
     *
     * @param c     morton code up to 64 bits
     * @param coord morton decode LUT
     * @return decoded value
     */
    private static int decodeHelper(long c, int[] coord) {
        var a = 0L;
        var nineBitMask = 0x000001ff;
        for (var i = 0; i < LOOPS; ++i) {
            a |= ((long) coord[(int) ((c >> (i * 9)) & nineBitMask)] << (3 * i));
        }
        return (int) a;
    }

    /**
     * Morton (z-ordering) encoding with Lookup Table method
     *
     * @param x range is from 0 to 2097151.
     * @param y range is from 0 to 2097151.
     * @param z range is from 0 to 2097151.
     * @return return Morton Code as long .
     */
    public static long encode(int x, int y, int z) {
        var result = 0;
        for (var i = 256; i > 0; i--) {
            var shift = (i - 1) * 8;
            result = result << 24 | (D3256[(int) ((z >> shift) & EIGHT_BIT_MASK)] << 2) | (
            D3256[(int) ((y >> shift) & EIGHT_BIT_MASK)] << 1) | D3256[(int) ((x >> shift) & EIGHT_BIT_MASK)];
        }

        return result;
    }

    public static long getMantissa(double num) {
        long bits = Double.doubleToLongBits(num);
        long mantissa = bits & 0x000FFFFFFFFFFFFFL;
        return mantissa;
    }

    public static int getMantissa(float value) {
        int floatBits = Float.floatToIntBits(value);
        int mantissa = floatBits & 0x007FFFFF;
        return mantissa;
    }

    public static int mostSignificantDifferingBit(long a, long b) {
        var xorResult = a ^ b;
        if (xorResult == 0) {
            return -1;
        }
        int msbIndex = 64 - Long.numberOfLeadingZeros(xorResult);
        return msbIndex;
    }

    public static int mostSignificantDifferingBit(int a, int b) {
        var xorResult = a ^ b;
        if (xorResult == 0) {
            return -1;
        }
        int msbIndex = 31 - Integer.numberOfLeadingZeros(xorResult);
        return msbIndex;
    }

    /**
     * From <a href="Fast Construction of k-Nearest Neighbor Graphs for Point
     * Clouds">https://www.computer.org/csdl/journal/tg/2010/04/ttg2010040599/13rRUNvyate</a>
     * <pre>
     * procedure XORMSB(double a, double b)
     *   x = EXPONENT(a); y = EXPONENT(b)
     *   if x == y then
     *      z = MSDB(MANTISSA(a), MANTISSA(b))
     *      x = x - z
     *      return x
     *   end if
     *   if y < x then return x
     *   else return y
     *  end Procedure
     *  </pre>
     */
    public static final int xormsb(double a, double b) {
        var x = Math.getExponent(a);
        var y = Math.getExponent(b);
        if (x == y) {
            var z = mostSignificantDifferingBit(getMantissa(a), getMantissa(b));
            x = x - z;
            return x;
        }
        if (y < x) {
            return x;
        }
        return y;
    }

    public static final int xormsb(float a, float b) {
        var x = Math.getExponent(a);
        var y = Math.getExponent(b);
        if (x == y) {
            var z = mostSignificantDifferingBit(getMantissa(a), getMantissa(b));
            x = x - z;
            return x;
        }
        if (y < x) {
            return x;
        }
        return y;
    }
}
