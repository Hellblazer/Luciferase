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
package com.hellblazer.luciferase.esvt.core;

import javax.vecmath.Vector3f;

/**
 * Contour encoding/decoding for ESVT (Efficient Sparse Voxel Tetrahedra).
 *
 * <p>Contours represent sub-tetrahedron surface geometry as oriented planes.
 * This enables accurate surface intersection beyond just tetrahedron bounds.
 *
 * <p><b>Contour value format (32 bits):</b>
 * <pre>
 * Bits 0-17  (18 bits): Encoded normal direction
 * Bits 18-24 (7 bits):  Position offset (signed, relative to tet center)
 * Bits 25-31 (7 bits):  Thickness (unsigned, half-width of the slab)
 * </pre>
 *
 * <p>Based on Laine & Karras 2010 ESVO contour encoding, adapted for tetrahedra.
 *
 * @author hal.hildebrand
 */
public final class ESVTContour {

    // Bit masks
    private static final int NORMAL_MASK = 0x0003FFFF;  // 18 bits

    // Encoding constants (from ESVO reference)
    private static final float NORMAL_SCALE = 31.0f;
    private static final float BOUND_RANGE = 48.0f;

    // exp2(-18) = 1.0f / (1 << 18) = 1.0f / 262144.0f
    private static final float EXP2_NEG18 = 1.0f / 262144.0f;
    // exp2(-25) = 1.0f / (1 << 25) = 1.0f / 33554432.0f
    private static final float EXP2_NEG25 = 1.0f / 33554432.0f;
    // exp2(-26) = 1.0f / (1 << 26) = 1.0f / 67108864.0f
    private static final float EXP2_NEG26 = 1.0f / 67108864.0f;

    /**
     * Encode a surface normal into the lower 18 bits.
     *
     * <p>The normal is encoded as 3 x 6-bit values using a hierarchical
     * encoding scheme that preserves direction with minimal quantization error.
     *
     * @param normal The surface normal (will be normalized)
     * @return Encoded normal in lower 18 bits
     */
    public static int encodeNormal(Vector3f normal) {
        var a = new Vector3f(Math.abs(normal.x), Math.abs(normal.y), Math.abs(normal.z));
        float maxAbs = Math.max(a.x, Math.max(a.y, a.z));
        if (maxAbs < 1e-16f) {
            return 0; // Degenerate normal
        }

        // Scale so largest component is +/- 31
        float scale = NORMAL_SCALE / maxAbs;
        float nx = normal.x * scale;
        float ny = normal.y * scale;
        float nz = normal.z * scale;

        // Determine dominant axis and clamp to exactly +/- 31
        int dominantAxis = (a.x >= a.y && a.x >= a.z) ? 0 : (a.y >= a.x && a.y >= a.z) ? 1 : 2;
        switch (dominantAxis) {
            case 0 -> nx = (nx >= 0) ? NORMAL_SCALE : -NORMAL_SCALE;
            case 1 -> ny = (ny >= 0) ? NORMAL_SCALE : -NORMAL_SCALE;
            case 2 -> nz = (nz >= 0) ? NORMAL_SCALE : -NORMAL_SCALE;
        }

        // Hierarchical encoding with XOR for signed representation
        // First encode z (bits 0-5)
        int value = clamp((int) (nz + 32.5f), 0, 63) ^ 32;
        // Encode y (bits 6-11), subtracting accumulated value effect
        float yAccum = value * EXP2_NEG26 * 64.0f; // = value / 1048576
        value |= (clamp((int) (ny - yAccum + 32.5f), 0, 63) ^ 32) << 6;
        // Encode x (bits 12-17), subtracting accumulated value effect
        float xAccum = value * EXP2_NEG26 * 4096.0f; // = value / 16384
        value |= (clamp((int) (nx - xAccum + 32.5f), 0, 63) ^ 32) << 12;

        return value;
    }

    /**
     * Decode the normal from an encoded contour value.
     * Matches ESVO reference: decodeContourNormal
     *
     * @param value Encoded contour value
     * @return Decoded normal vector
     */
    public static Vector3f decodeNormal(int value) {
        // Sign-extending shifts and scale
        // x: bits 12-17, shift left by 14, then right by 26 total for scale
        float x = (float) (value << 14) * EXP2_NEG26;
        // y: bits 6-11, shift left by 20
        float y = (float) (value << 20) * EXP2_NEG26;
        // z: bits 0-5, shift left by 26
        float z = (float) (value << 26) * EXP2_NEG26;
        return new Vector3f(x, y, z);
    }

    /**
     * Encode position bounds into the contour value.
     * Matches ESVO reference: encodeContourBounds
     *
     * @param value Existing value with encoded normal
     * @param lo    Low bound (distance from tet center along normal)
     * @param hi    High bound (distance from tet center along normal)
     * @return Complete contour value with position and thickness
     */
    public static int encodeBounds(int value, float lo, float hi) {
        // Clamp bounds to valid range
        lo = Math.max(lo, -BOUND_RANGE);
        hi = Math.min(hi, BOUND_RANGE);

        // Encode position: center of the bounds, bits 18-24
        float center = (lo + hi) * (2.0f / 3.0f);
        int posBits = clamp((int) (center - value * EXP2_NEG18 + 64.5f), 0, 127) ^ 64;
        value |= posBits << 18;

        // Decode position to compute thickness
        float pos = (float) (value << 7) * EXP2_NEG25 * (3.0f / 4.0f);
        float thick = Math.max(pos - lo, hi - pos) * 2.0f;

        // Encode thickness: bits 25-31
        int thickBits = clamp((int) (thick * (4.0f / 3.0f) - value * EXP2_NEG25 + 0.99999f), 0, 127);
        value |= thickBits << 25;

        return value;
    }

    /**
     * Decode position and thickness from contour value.
     * Matches ESVO reference: decodeContourPosThick
     *
     * @param value Encoded contour value
     * @return float[2] = {position, thickness}
     */
    public static float[] decodePosThick(int value) {
        // Position: (value << 7) * exp2(-25) * 0.75
        float pos = (float) (value << 7) * EXP2_NEG25 * (3.0f / 4.0f);
        // Thickness: (unsigned)value * 0.75 * exp2(-25)
        float thick = (float) (value & 0xFFFFFFFFL) * (3.0f / 4.0f) * EXP2_NEG25;
        return new float[]{pos, thick};
    }

    /**
     * Create a contour value from normal and bounds.
     *
     * @param normal Surface normal
     * @param lo     Low bound along normal from tet center
     * @param hi     High bound along normal from tet center
     * @return Complete encoded contour value
     */
    public static int encode(Vector3f normal, float lo, float hi) {
        return encodeBounds(encodeNormal(normal), lo, hi);
    }

    /**
     * Transform a parent contour to a child tetrahedron.
     * Matches ESVO reference: xformContourToChild
     *
     * @param value       Parent contour value
     * @param childOffset Offset of child center from parent center (normalized [-0.5, 0.5])
     * @return Transformed contour for child
     */
    public static int transformToChild(int value, Vector3f childOffset) {
        var normal = decodeNormal(value);
        float[] posThick = decodePosThick(value);

        // Transform position: scale by 2 (child is half size) and shift by offset dot normal
        float pos = posThick[0] * 2.0f;
        pos += normal.x * (0.5f - childOffset.x);
        pos += normal.y * (0.5f - childOffset.y);
        pos += normal.z * (0.5f - childOffset.z);

        float thick = posThick[1];
        return encodeBounds(value & NORMAL_MASK,
            clamp(pos - thick, -BOUND_RANGE, BOUND_RANGE),
            clamp(pos + thick, -BOUND_RANGE, BOUND_RANGE));
    }

    /**
     * Compute ray-contour intersection.
     *
     * <p>The contour defines a slab (infinite plane with thickness).
     * This returns the t-parameter range where the ray is inside the slab.
     *
     * @param contour   Encoded contour value
     * @param rayOrigin Ray origin relative to tet center
     * @param rayDir    Ray direction (normalized)
     * @param tetScale  Scale of the tetrahedron
     * @return float[2] = {tEntry, tExit} or null if no intersection
     */
    public static float[] intersectRay(int contour, Vector3f rayOrigin, Vector3f rayDir, float tetScale) {
        var normal = decodeNormal(contour);
        float[] posThick = decodePosThick(contour);

        // Scale position and thickness to world units
        float pos = posThick[0] * tetScale;
        float halfThick = posThick[1] * tetScale * 0.5f;

        // Compute dot products
        float denom = normal.x * rayDir.x + normal.y * rayDir.y + normal.z * rayDir.z;
        float originDot = normal.x * rayOrigin.x + normal.y * rayOrigin.y + normal.z * rayOrigin.z;

        // Handle near-parallel case
        if (Math.abs(denom) < 1e-10f) {
            // Ray parallel to plane - check if inside slab
            float dist = Math.abs(originDot - pos);
            if (dist <= halfThick) {
                return new float[]{Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
            }
            return null; // Outside slab, no intersection
        }

        // Compute intersection with both planes of the slab
        float t1 = (pos - halfThick - originDot) / denom;
        float t2 = (pos + halfThick - originDot) / denom;

        // Ensure t1 <= t2
        if (t1 > t2) {
            float tmp = t1;
            t1 = t2;
            t2 = tmp;
        }

        return new float[]{t1, t2};
    }

    /**
     * Check if a contour value has valid data (non-zero normal).
     */
    public static boolean isValid(int value) {
        return (value & NORMAL_MASK) != 0;
    }

    /**
     * Get the normal part of a contour value.
     */
    public static int getNormalPart(int value) {
        return value & NORMAL_MASK;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private ESVTContour() {
        // Utility class
    }
}
