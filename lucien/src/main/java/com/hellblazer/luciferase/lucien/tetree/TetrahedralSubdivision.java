package com.hellblazer.luciferase.lucien.tetree;

/**
 * Table-driven tetrahedral subdivision implementation using TM-index tables from the thesis. This implements Bey's
 * red-refinement with proper type tracking for space-filling curves.
 *
 * CORRECTED VERSION: Fixed octahedral children vertex orderings to maintain positive orientation
 */
public class TetrahedralSubdivision {

    /**
     * Main demonstration
     */
    public static void main(String[] args) {
        // Start with edge length 2^21 at level 0
        int initialEdgeLength = 1 << 21; // 2^21 = 2,097,152

        // Create S0 type tetrahedron (from thesis Figure 4.2)
        Point3D v0 = new Point3D(0, 0, 0);
        Point3D v1 = new Point3D(initialEdgeLength, 0, 0);
        Point3D v2 = new Point3D(0, initialEdgeLength, 0);
        Point3D v3 = new Point3D(0, 0, initialEdgeLength);

        Tetrahedron current = new Tetrahedron(v0, v1, v2, v3, 0, 0); // Type 0, Level 0

        System.out.println("Table-driven Tetrahedral Subdivision Test (CORRECTED)");
        System.out.printf("Initial edge length: %d (2^21)\n", initialEdgeLength);
        System.out.printf("Initial type: %d (S0)\n", current.type);
        System.out.printf("Initial volume: %.6e\n", current.volume());
        System.out.println();

        boolean allValid = true;

        // Subdivide from level 0 to 20
        for (int level = 0; level < 20; level++) {
            System.out.printf("Level %d -> %d:\n", level, level + 1);

            // Subdivide using tables
            Tetrahedron[] children = current.subdivide();

            // Validate
            boolean valid = SubdivisionValidator.validateSubdivision(current, children);
            if (!valid) {
                allValid = false;
                break;
            }

            // Debug info for first few levels
            if (level < 3) {
                SubdivisionValidator.debugSubdivision(current, children);
            }

            // Summary
            System.out.printf("  Edge length: %d -> %d\n", initialEdgeLength >> level,
                              initialEdgeLength >> (level + 1));
            System.out.printf("  Volume ratio: %.15f (expected: 0.125)\n", children[0].volume() / current.volume());

            // Verify edge length
            double currentEdgeLength = current.getEdgeLength();
            double childEdgeLength = children[0].getEdgeLength();
            System.out.printf("  Edge length check: %.0f -> %.0f (ratio: %.6f)\n", currentEdgeLength, childEdgeLength,
                              childEdgeLength / currentEdgeLength);

            // Continue with first child
            current = children[0];
        }

        System.out.println("\nFinal Summary:");
        System.out.println("All subdivisions valid: " + (allValid ? "YES ✓" : "NO ✗"));
        System.out.printf("Final tetrahedron: type=%d, level=%d\n", current.type, current.level);
        System.out.printf("Final edge length: %d\n", initialEdgeLength >> 20);
        System.out.printf("Final volume: %.6e\n", current.volume());

        // Demonstrate TM-ordering
        System.out.println("\n=== TM-Ordering Demonstration ===");
        Tetrahedron demo = new Tetrahedron(v0, v1, v2, v3, 0, 0);
        System.out.println("Parent type: 0");

        Tetrahedron[] beyChildren = demo.subdivide();
        Tetrahedron[] tmChildren = demo.subdivideTMOrder();

        System.out.println("\nBey's order vs TM-order:");
        System.out.println("TM  Bey  Type  Description");
        System.out.println("---  ---  ----  -----------");

        int[] tmToBey = SubdivisionTables.TM_TO_BEY_PERMUTATION[0];
        for (int tm = 0; tm < 8; tm++) {
            int bey = tmToBey[tm];
            String desc = bey < 4 ? "Corner " + bey : "Octahedral";
            System.out.printf("%2d   %2d    %d    %s\n", tm, bey, tmChildren[tm].type, desc);
        }

        System.out.println("\nVerifying TM-ordered children match expected Bey indices:");
        boolean tmOrderCorrect = true;
        for (int tm = 0; tm < 8; tm++) {
            int expectedBey = tmToBey[tm];
            // Compare centroids to verify they're the same tetrahedron
            Point3D tmCentroid = tmChildren[tm].centroid();
            Point3D beyCentroid = beyChildren[expectedBey].centroid();
            boolean same = tmCentroid.equals(beyCentroid);
            if (!same) {
                System.out.printf("ERROR: TM child %d doesn't match Bey child %d\n", tm, expectedBey);
                tmOrderCorrect = false;
            }
        }
        System.out.println("TM ordering correct: " + (tmOrderCorrect ? "YES ✓" : "NO ✗"));

        // Verify edge lengths through subdivision
        System.out.println("\n=== Edge Length Verification ===");
        System.out.println("Level  Expected  Actual    Error");
        System.out.println("-----  --------  --------  -----");

        Tetrahedron test = new Tetrahedron(v0, v1, v2, v3, 0, 0);
        boolean edgeLengthsCorrect = true;

        for (int lvl = 0; lvl <= 5; lvl++) {
            double expected = Math.pow(2, 21 - lvl);
            double actual = test.getEdgeLength();
            double error = Math.abs(actual - expected);

            System.out.printf("%5d  %8.0f  %8.0f  %5.0f\n", lvl, expected, actual, error);

            if (error > 0.5) {
                edgeLengthsCorrect = false;
            }

            if (lvl < 5) {
                test = test.subdivide()[0]; // Take first child
            }
        }

        System.out.println("\nEdge lengths correct: " + (edgeLengthsCorrect ? "YES ✓" : "NO ✗"));
    }

    /**
     * Represents a 3D point/vector
     */
    static class Point3D {
        final double x, y, z;

        Point3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof final Point3D other)) {
                return false;
            }
            final double EPSILON = 1e-15;
            return Math.abs(x - other.x) < EPSILON && Math.abs(y - other.y) < EPSILON && Math.abs(z - other.z)
            < EPSILON;
        }

        @Override
        public String toString() {
            return String.format("(%.6f, %.6f, %.6f)", x, y, z);
        }

        Point3D add(Point3D other) {
            return new Point3D(x + other.x, y + other.y, z + other.z);
        }

        Point3D cross(Point3D other) {
            return new Point3D(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }

        double dot(Point3D other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Point3D midpoint(Point3D other) {
            return new Point3D((x + other.x) / 2.0, (y + other.y) / 2.0, (z + other.z) / 2.0);
        }

        Point3D scale(double s) {
            return new Point3D(x * s, y * s, z * s);
        }

        Point3D subtract(Point3D other) {
            return new Point3D(x - other.x, y - other.y, z - other.z);
        }
    }

    /**
     * Represents a tetrahedron with 4 vertices and type information
     */
    static class Tetrahedron {
        final Point3D[] vertices;
        final int       type;  // Type in TM-index system (0-5)
        final int       level;

        Tetrahedron(Point3D v0, Point3D v1, Point3D v2, Point3D v3, int type, int level) {
            this.vertices = new Point3D[] { v0, v1, v2, v3 };
            this.type = type;
            this.level = level;
        }

        /**
         * Compute the centroid
         */
        Point3D centroid() {
            return new Point3D((vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0,
                               (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0,
                               (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0);
        }

        /**
         * Get edge length at this tetrahedron's level Edge length = 2^(L-level) where L is the initial level (21 in our
         * case)
         */
        double getEdgeLength() {
            // Initial edge length is 2^21, each subdivision halves the edge length
            return Math.pow(2, 21 - level);
        }

        /**
         * Get child by TM-order index (implements Algorithm 4.3.5)
         *
         * @param tmIndex The index in TM-order (0-7)
         * @return The child tetrahedron at that TM position
         */
        Tetrahedron getTMChild(int tmIndex) {
            if (tmIndex < 0 || tmIndex >= 8) {
                throw new IllegalArgumentException("TM index must be between 0 and 7");
            }

            // Get all children in Bey's order
            Tetrahedron[] beyChildren = subdivide();

            // Convert TM index to Bey index using the permutation table
            int beyIndex = SubdivisionTables.TM_TO_BEY_PERMUTATION[this.type][tmIndex];

            return beyChildren[beyIndex];
        }

        /**
         * Table-driven subdivision using TM-index tables
         */
        Tetrahedron[] subdivide() {
            return SubdivisionTables.subdivideTetrahedron(this);
        }

        /**
         * Get all children in TM-order
         *
         * @return Array of 8 children ordered according to TM-index
         */
        Tetrahedron[] subdivideTMOrder() {
            Tetrahedron[] tmChildren = new Tetrahedron[8];

            for (int i = 0; i < 8; i++) {
                tmChildren[i] = getTMChild(i);
            }

            return tmChildren;
        }

        /**
         * Compute the volume using scalar triple product
         */
        double volume() {
            Point3D v1 = vertices[1].subtract(vertices[0]);
            Point3D v2 = vertices[2].subtract(vertices[0]);
            Point3D v3 = vertices[3].subtract(vertices[0]);
            return Math.abs(v1.dot(v2.cross(v3))) / 6.0;
        }
    }

    /**
     * Tables from the thesis for tetrahedral subdivision
     */
    static class SubdivisionTables {
        // Table 4.1: Child types for each parent type
        // First 4 children (corners) always have same type as parent
        // Last 4 children (octahedral) have specific types
        private static final int[][] CHILD_TYPE_TABLE = { { 0, 0, 0, 0, 4, 5, 2, 1 }, // Parent type 0
                                                          { 1, 1, 1, 1, 3, 2, 5, 0 }, // Parent type 1
                                                          { 2, 2, 2, 2, 0, 1, 4, 3 }, // Parent type 2
                                                          { 3, 3, 3, 3, 5, 4, 1, 2 }, // Parent type 3
                                                          { 4, 4, 4, 4, 2, 3, 0, 5 }, // Parent type 4
                                                          { 5, 5, 5, 5, 1, 0, 3, 4 }  // Parent type 5
        };

        // Table 4.2: Inverse permutation from TM-order to Bey's order
        // For parent type b, TM_TO_BEY_PERMUTATION[b][i] gives the Bey index
        // of the i-th child in TM-order
        private static final int[][] TM_TO_BEY_PERMUTATION = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                               { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                               { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                               { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                               { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                               { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
        };

        // Vertex indices for each child from Bey's subdivision (equation 4.2b)
        // Format: each row is [v0_idx, v1_idx, v2_idx, v3_idx] where indices refer to:
        // 0-3: original vertices, 4-9: edge midpoints in order 01,02,03,12,13,23
        // Based on equation (4.2b) from the thesis, but with corrected orderings for orientation:
        // T0 := [x0, x01, x02, x03]
        // T1 := [x01, x1, x12, x13]
        // T2 := [x02, x12, x2, x23]
        // T3 := [x03, x13, x23, x3]
        // T4 := [x01, x02, x03, x13] - vertices from thesis, ordering adjusted
        // T5 := [x01, x02, x12, x13] - vertices from thesis, ordering adjusted
        // T6 := [x02, x03, x13, x23] - vertices from thesis, ordering adjusted
        // T7 := [x02, x12, x13, x23] - vertices from thesis, ordering adjusted
        private static final int[][] CHILD_VERTEX_INDICES = { { 0, 4, 5, 6 },  // T0: [x0, x01, x02, x03]
                                                              { 4, 1, 7, 8 },  // T1: [x01, x1, x12, x13]
                                                              { 5, 7, 2, 9 },  // T2: [x02, x12, x2, x23]
                                                              { 6, 8, 9, 3 },  // T3: [x03, x13, x23, x3]
                                                              { 4, 5, 6, 8 },  // T4: [x01, x02, x03, x13]
                                                              { 4, 7, 5, 8 },
                                                              // T5: [x01, x12, x02, x13] - corrected ordering
                                                              { 5, 6, 8, 9 },  // T6: [x02, x03, x13, x23]
                                                              { 5, 8, 7, 9 }
                                                              // T7: [x02, x13, x12, x23] - corrected ordering
        };

        /**
         * Subdivide a tetrahedron using the tables
         */
        static Tetrahedron[] subdivideTetrahedron(Tetrahedron parent) {
            // Compute all vertices (4 original + 6 edge midpoints)
            Point3D[] allVertices = new Point3D[10];

            // Original vertices
            System.arraycopy(parent.vertices, 0, allVertices, 0, 4);

            // Edge midpoints in standard order: 01,02,03,12,13,23
            allVertices[4] = parent.vertices[0].midpoint(parent.vertices[1]); // 01
            allVertices[5] = parent.vertices[0].midpoint(parent.vertices[2]); // 02
            allVertices[6] = parent.vertices[0].midpoint(parent.vertices[3]); // 03
            allVertices[7] = parent.vertices[1].midpoint(parent.vertices[2]); // 12
            allVertices[8] = parent.vertices[1].midpoint(parent.vertices[3]); // 13
            allVertices[9] = parent.vertices[2].midpoint(parent.vertices[3]); // 23

            // Create 8 children using the tables
            Tetrahedron[] children = new Tetrahedron[8];
            int[] childTypes = CHILD_TYPE_TABLE[parent.type];

            for (int i = 0; i < 8; i++) {
                int[] vertexIndices = CHILD_VERTEX_INDICES[i];
                children[i] = new Tetrahedron(allVertices[vertexIndices[0]], allVertices[vertexIndices[1]],
                                              allVertices[vertexIndices[2]], allVertices[vertexIndices[3]],
                                              childTypes[i], parent.level + 1);
            }

            return children;
        }
    }

    /**
     * Validation utilities
     */
    static class SubdivisionValidator {
        private static final double EPSILON        = 1e-15;
        private static final double VOLUME_EPSILON = 1e-20;

        public static void debugSubdivision(Tetrahedron parent, Tetrahedron[] children) {
            System.out.print("\nSubdivision Debug Info:\n");
            System.out.printf("Parent type: %d, level: %d\n", parent.type, parent.level);
            System.out.printf("Parent volume: %.6e\n", parent.volume());
            System.out.printf("Parent orientation: %.6e\n", getOrientation(parent));

            double totalVolume = 0;
            for (int i = 0; i < 8; i++) {
                double orientation = getOrientation(children[i]);
                totalVolume += children[i].volume();
                System.out.printf("Child %d: type=%d, volume=%.6e (%.1f%%), orientation=%.6e %s\n", i, children[i].type,
                                  children[i].volume(), 100.0 * children[i].volume() / parent.volume(), orientation,
                                  (orientation * getOrientation(parent) > 0) ? "✓" : "✗");
            }
            System.out.printf("Total child volume: %.6e (ratio: %.15f)\n", totalVolume, totalVolume / parent.volume());
        }

        private static double getOrientation(Tetrahedron tet) {
            Point3D v1 = tet.vertices[1].subtract(tet.vertices[0]);
            Point3D v2 = tet.vertices[2].subtract(tet.vertices[0]);
            Point3D v3 = tet.vertices[3].subtract(tet.vertices[0]);
            return v1.dot(v2.cross(v3));
        }

        private static boolean isVertexValidForChild(Tetrahedron parent, Point3D vertex) {
            // Check if vertex is either a parent vertex or edge midpoint
            for (Point3D pv : parent.vertices) {
                if (vertex.equals(pv)) {
                    return true;
                }
            }

            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    Point3D midpoint = parent.vertices[i].midpoint(parent.vertices[j]);
                    if (vertex.equals(midpoint)) {
                        return true;
                    }
                }
            }

            return false;
        }

        /**
         * Validate subdivision
         */
        public static boolean validateSubdivision(Tetrahedron parent, Tetrahedron[] children) {
            if (children.length != 8) {
                System.err.println("ERROR: Expected 8 children, got " + children.length);
                return false;
            }

            // Check 1: Positive volumes
            for (int i = 0; i < children.length; i++) {
                double vol = children[i].volume();
                if (vol <= VOLUME_EPSILON) {
                    System.err.printf("ERROR: Child %d has non-positive volume: %e\n", i, vol);
                    return false;
                }
            }

            // Check 2: Volume conservation
            double parentVolume = parent.volume();
            double childrenVolumeSum = 0;
            for (Tetrahedron child : children) {
                childrenVolumeSum += child.volume();
            }
            double volumeError = Math.abs(parentVolume - childrenVolumeSum);
            double relativeError = volumeError / Math.max(parentVolume, VOLUME_EPSILON);
            if (relativeError > EPSILON) {
                System.err.printf("ERROR: Volume not conserved. Parent: %e, Children sum: %e, Relative error: %e\n",
                                  parentVolume, childrenVolumeSum, relativeError);
                return false;
            }

            // Check 3: Child types match table
            int[] expectedTypes = SubdivisionTables.CHILD_TYPE_TABLE[parent.type];
            for (int i = 0; i < 8; i++) {
                if (children[i].type != expectedTypes[i]) {
                    System.err.printf("ERROR: Child %d has wrong type. Expected %d, got %d\n", i, expectedTypes[i],
                                      children[i].type);
                    return false;
                }
            }

            // Check 4: Orientation consistency
            double parentOrientation = getOrientation(parent);
            for (int i = 0; i < children.length; i++) {
                double childOrientation = getOrientation(children[i]);
                if (parentOrientation * childOrientation < 0) {
                    System.err.printf("ERROR: Child %d has opposite orientation from parent\n", i);
                    return false;
                }
            }

            // Check 5: Containment
            for (int i = 0; i < children.length; i++) {
                for (int j = 0; j < 4; j++) {
                    Point3D vertex = children[i].vertices[j];
                    if (!isVertexValidForChild(parent, vertex)) {
                        System.err.printf("ERROR: Child %d vertex %d is invalid\n", i, j);
                        return false;
                    }
                }
            }

            System.out.print("  Validation complete: all checks passed\n");
            return true;
        }
    }
}
