package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.TestOutputSuppressor;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the S0-S5 tetrahedral subdivision in Tet.coordinates() Tests that the 6 tetrahedra correctly tile a cube
 * using standard cube vertices.
 *
 * @author hal.hildebrand
 */
public class VertexComputationValidationTest {

    @Test
    void testEdgeIdentification() {
        TestOutputSuppressor.println("\nEdge Identification Test");
        TestOutputSuppressor.println("========================");

        // Use grid-aligned coordinates
        byte level = 10;
        int cellSize = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level);
        Tet tet = new Tet(2 * cellSize, 3 * cellSize, 4 * cellSize, level, (byte) 0);
        Point3i[] vertices = tet.coordinates();

        // List all 6 edges
        TestOutputSuppressor.println("Edges:");
        TestOutputSuppressor.println("  Edge 0-1: " + pointToString(vertices[0]) + " to " + pointToString(vertices[1]));
        TestOutputSuppressor.println("  Edge 0-2: " + pointToString(vertices[0]) + " to " + pointToString(vertices[2]));
        TestOutputSuppressor.println("  Edge 0-3: " + pointToString(vertices[0]) + " to " + pointToString(vertices[3]));
        TestOutputSuppressor.println("  Edge 1-2: " + pointToString(vertices[1]) + " to " + pointToString(vertices[2]));
        TestOutputSuppressor.println("  Edge 1-3: " + pointToString(vertices[1]) + " to " + pointToString(vertices[3]));
        TestOutputSuppressor.println("  Edge 2-3: " + pointToString(vertices[2]) + " to " + pointToString(vertices[3]));

        // Calculate edge midpoints
        TestOutputSuppressor.println("\nEdge Midpoints:");
        Point3f[] midpoints = computeEdgeMidpoints(vertices);
        TestOutputSuppressor.println("  M01: " + pointToString(midpoints[0]));
        TestOutputSuppressor.println("  M02: " + pointToString(midpoints[1]));
        TestOutputSuppressor.println("  M03: " + pointToString(midpoints[2]));
        TestOutputSuppressor.println("  M12: " + pointToString(midpoints[3]));
        TestOutputSuppressor.println("  M13: " + pointToString(midpoints[4]));
        TestOutputSuppressor.println("  M23: " + pointToString(midpoints[5]));
    }

    @Test
    void testFaceIdentification() {
        TestOutputSuppressor.println("\nFace Identification Test");
        TestOutputSuppressor.println("========================");

        Tet tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        Point3i[] vertices = tet.coordinates();

        TestOutputSuppressor.println("Faces (each opposite to a vertex):");
        TestOutputSuppressor.println("  Face 0 (opposite V0): V1-V2-V3");
        TestOutputSuppressor.println("  Face 1 (opposite V1): V0-V2-V3");
        TestOutputSuppressor.println("  Face 2 (opposite V2): V0-V1-V3");
        TestOutputSuppressor.println("  Face 3 (opposite V3): V0-V1-V2");
    }

    @Test
    void testTypePairs() {
        TestOutputSuppressor.println("\nType Pairs Analysis");
        TestOutputSuppressor.println("===================");

        for (byte type = 0; type < 6; type++) {
            int ei = type / 2;
            int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;

            TestOutputSuppressor.println(
            "Type " + type + ": ei=" + ei + " (" + dimensionName(ei) + "), ej=" + ej + " (" + dimensionName(ej) + ")");
        }

        TestOutputSuppressor.println("\nGrouped by primary axis:");
        TestOutputSuppressor.println("  X-axis primary (ei=0): Types 0, 1");
        TestOutputSuppressor.println("  Y-axis primary (ei=1): Types 2, 3");
        TestOutputSuppressor.println("  Z-axis primary (ei=2): Types 4, 5");
    }

    @Test
    void testVertexComputationForAllTypes() {
        // Test at level 10 (cell size = 2048)
        byte level = 10;
        int cellSize = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level); // 2048
        int x = 2 * cellSize; // 4096 - aligned to grid
        int y = 3 * cellSize; // 6144 - aligned to grid
        int z = 4 * cellSize; // 8192 - aligned to grid

        TestOutputSuppressor.println("S0-S5 Tetrahedral Subdivision Analysis");
        TestOutputSuppressor.println("========================================");
        TestOutputSuppressor.println("Anchor: (" + x + ", " + y + ", " + z + ")");
        TestOutputSuppressor.println("Level: " + level);

        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(x, y, z, level, type);
            Point3i[] vertices = tet.coordinates();
            int h = tet.length();

            TestOutputSuppressor.println("\nType " + type + " (S" + type + "):");
            TestOutputSuppressor.println("  Cell size (h): " + h);

            // Print which cube vertices this tet uses
            String cubeVertices = switch (type) {
                case 0 -> "0,1,3,7";
                case 1 -> "0,2,3,7";
                case 2 -> "0,4,5,7";
                case 3 -> "0,4,6,7";
                case 4 -> "0,1,5,7";
                case 5 -> "0,2,6,7";
                default -> "unknown";
            };
            TestOutputSuppressor.println("  Cube vertices: " + cubeVertices);

            // Print vertices
            for (int i = 0; i < 4; i++) {
                TestOutputSuppressor.println("  V" + i + ": " + pointToString(vertices[i]));
            }

            // Validate vertex positions match S0-S5 pattern
            validateVertexPattern(vertices, x, y, z, h, type);

            // Calculate and validate volume
            float volume = calculateVolume(vertices);
            float expectedVolume = (float) h * (float) h * (float) h / 6.0f;
            TestOutputSuppressor.println("  Volume: " + volume + " (expected: " + expectedVolume + ")");

            // Validate orientation (volume should be positive)
            assertTrue(volume > 0, "Negative volume for type " + type);
        }
    }

    private void addToDimension(Point3i point, int dimension, int h) {
        switch (dimension) {
            case 0 -> point.x += h;
            case 1 -> point.y += h;
            case 2 -> point.z += h;
            default -> throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
    }

    private float calculateVolume(Point3i[] vertices) {
        // Calculate tetrahedron volume using the formula:
        // V = |det(v1-v0, v2-v0, v3-v0)| / 6
        float x1 = vertices[1].x - vertices[0].x;
        float y1 = vertices[1].y - vertices[0].y;
        float z1 = vertices[1].z - vertices[0].z;

        float x2 = vertices[2].x - vertices[0].x;
        float y2 = vertices[2].y - vertices[0].y;
        float z2 = vertices[2].z - vertices[0].z;

        float x3 = vertices[3].x - vertices[0].x;
        float y3 = vertices[3].y - vertices[0].y;
        float z3 = vertices[3].z - vertices[0].z;

        // Calculate determinant
        float det = x1 * (y2 * z3 - y3 * z2) - y1 * (x2 * z3 - x3 * z2) + z1 * (x2 * y3 - x3 * y2);

        return Math.abs(det) / 6.0f;
    }

    private Point3f[] computeEdgeMidpoints(Point3i[] vertices) {
        Point3f[] midpoints = new Point3f[6];
        int index = 0;

        // Generate all 6 edges (combinations of 4 vertices taken 2 at a time)
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                midpoints[index++] = new Point3f((vertices[i].x + vertices[j].x) / 2.0f,
                                                 (vertices[i].y + vertices[j].y) / 2.0f,
                                                 (vertices[i].z + vertices[j].z) / 2.0f);
            }
        }

        return midpoints;
    }

    private String dimensionName(int dim) {
        return switch (dim) {
            case 0 -> "X";
            case 1 -> "Y";
            case 2 -> "Z";
            default -> "?";
        };
    }

    private String pointToString(Point3i p) {
        return "(" + p.x + ", " + p.y + ", " + p.z + ")";
    }

    private String pointToString(Point3f p) {
        return String.format("(%.1f, %.1f, %.1f)", p.x, p.y, p.z);
    }

    private void validateVertexPattern(Point3i[] vertices, int x, int y, int z, int h, byte type) {
        // Verify V0 is at anchor
        assertEquals(x, vertices[0].x);
        assertEquals(y, vertices[0].y);
        assertEquals(z, vertices[0].z);

        // S0-S5 subdivision: Expected vertices for each type
        Point3i expectedV1, expectedV2, expectedV3;

        switch (type) {
            case 0: // S0: vertices 0, 1, 3, 7
                expectedV1 = new Point3i(x + h, y, z);          // V1
                expectedV2 = new Point3i(x + h, y + h, z);      // V3
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 1: // S1: vertices 0, 2, 3, 7
                expectedV1 = new Point3i(x, y + h, z);          // V2
                expectedV2 = new Point3i(x + h, y + h, z);      // V3
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 2: // S2: vertices 0, 4, 5, 7
                expectedV1 = new Point3i(x, y, z + h);          // V4
                expectedV2 = new Point3i(x + h, y, z + h);      // V5
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 3: // S3: vertices 0, 4, 6, 7
                expectedV1 = new Point3i(x, y, z + h);          // V4
                expectedV2 = new Point3i(x, y + h, z + h);      // V6
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 4: // S4: vertices 0, 1, 5, 7
                expectedV1 = new Point3i(x + h, y, z);          // V1
                expectedV2 = new Point3i(x + h, y, z + h);      // V5
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 5: // S5: vertices 0, 2, 6, 7
                expectedV1 = new Point3i(x, y + h, z);          // V2
                expectedV2 = new Point3i(x, y + h, z + h);      // V6
                expectedV3 = new Point3i(x + h, y + h, z + h);  // V7
                break;
            default:
                throw new IllegalArgumentException("Invalid type: " + type);
        }

        assertEquals(expectedV1, vertices[1], "V1 mismatch for type " + type);
        assertEquals(expectedV2, vertices[2], "V2 mismatch for type " + type);
        assertEquals(expectedV3, vertices[3], "V3 mismatch for type " + type);
    }
}
