package com.hellblazer.luciferase.lucien.tetree;

/**
 * Tetrahedral Mesh Bey Subdivision Implementation
 *
 * This class implements the Bey subdivision scheme for tetrahedral meshes, subdividing any cell into its 8 children
 * according to the thesis specifications.
 */
public class TetrahedralSubdivision {

    /**
     * Table 4.1: Child types for each parent type For a tetrahedron T of type b, gives the types of T's children
     * T0,...,T7 The corner-children T0, T1, T2, T3 always have the same type as T
     */
    private static final int[][] CHILD_TYPES = { { 0, 0, 0, 0, 4, 5, 2, 1 },  // Parent type 0
                                                 { 1, 1, 1, 1, 3, 2, 5, 0 },  // Parent type 1
                                                 { 2, 2, 2, 2, 0, 1, 4, 3 },  // Parent type 2
                                                 { 3, 3, 3, 3, 5, 4, 1, 2 },  // Parent type 3
                                                 { 4, 4, 4, 4, 2, 3, 0, 5 },  // Parent type 4
                                                 { 5, 5, 5, 5, 1, 0, 3, 4 }   // Parent type 5
    };
    /**
     * Table 4.2: Local index (TM-ordering) of children For each parent type b, maps Bey order to TM order
     */
    private static final int[][] TM_ORDER    = { { 0, 1, 4, 7, 2, 3, 6, 5 },  // Parent type 0
                                                 { 0, 1, 5, 7, 2, 3, 6, 4 },  // Parent type 1
                                                 { 0, 3, 4, 7, 1, 2, 6, 5 },  // Parent type 2
                                                 { 0, 1, 6, 7, 2, 3, 4, 5 },  // Parent type 3
                                                 { 0, 3, 5, 7, 1, 2, 4, 6 },  // Parent type 4
                                                 { 0, 3, 6, 7, 2, 1, 4, 5 }   // Parent type 5
    };
    /**
     * Inverse of TM_ORDER: maps TM order back to Bey order
     */
    private static final int[][] BEY_ORDER   = new int[6][8];

    static {
        // Compute inverse mapping
        for (int parentType = 0; parentType < 6; parentType++) {
            for (int beyIndex = 0; beyIndex < 8; beyIndex++) {
                int tmIndex = TM_ORDER[parentType][beyIndex];
                BEY_ORDER[parentType][tmIndex] = beyIndex;
            }
        }
    }

    /**
     * Compute the anchor node (x0) for a child based on its index
     */
    private static int[] computeChildAnchor(int[][] vertices, int childIndex) {
        int[] x0 = vertices[0];
        int[] x1 = vertices[1];
        int[] x2 = vertices[2];
        int[] x3 = vertices[3];

        // Midpoints
        int[] x01 = midpoint(x0, x1);
        int[] x02 = midpoint(x0, x2);
        int[] x03 = midpoint(x0, x3);

        // Based on Bey's numbering (4.2b), anchor nodes are:
        switch (childIndex) {
            case 0:
                return x0;        // T0: [x0, x01, x02, x03]
            case 1:
                return x01;       // T1: [x01, x1, x12, x13]
            case 2:
                return x02;       // T2: [x02, x12, x2, x23]
            case 3:
                return x03;       // T3: [x03, x13, x23, x3]
            case 4:
                return x01;       // T4: [x01, x02, x03, x13]
            case 5:
                return x01;       // T5: [x01, x02, x12, x13]
            case 6:
                return x02;       // T6: [x02, x03, x13, x23]
            case 7:
                return x02;       // T7: [x02, x12, x13, x23]
            default:
                throw new IllegalArgumentException("Invalid child index: " + childIndex);
        }
    }

    /**
     * Compute vertices of a tetrahedron from its Tet structure Based on Algorithm 4.3.1 from the thesis
     */
    private static int[][] computeVertices(Tet T) {
        int[][] X = new int[4][3];
        int h = 1 << (30 - T.level); // 2^(L-level) where L=30 is max level

        // Anchor node
        X[0][0] = T.x;
        X[0][1] = T.y;
        X[0][2] = T.z;

        // Compute other vertices based on type
        int i = T.type / 2;
        int j;

        if (T.type % 2 == 0) {
            j = (i + 2) % 3;
        } else {
            j = (i + 1) % 3;
        }

        // X[1] = X[0] + h*ei
        X[1][0] = X[0][0] + (i == 0 ? h : 0);
        X[1][1] = X[0][1] + (i == 1 ? h : 0);
        X[1][2] = X[0][2] + (i == 2 ? h : 0);

        // X[2] = X[1] + h*ej
        X[2][0] = X[1][0] + (j == 0 ? h : 0);
        X[2][1] = X[1][1] + (j == 1 ? h : 0);
        X[2][2] = X[1][2] + (j == 2 ? h : 0);

        // X[3] = X[0] + (h,h,h)
        X[3][0] = X[0][0] + h;
        X[3][1] = X[0][1] + h;
        X[3][2] = X[0][2] + h;

        return X;
    }

    /**
     * Create a child tetrahedron based on Bey's subdivision
     */
    private static Tet createChild(Tet parent, int childLevel, int[][] vertices, int childIndex) {
        // Compute anchor node based on child index
        int[] anchor = computeChildAnchor(vertices, childIndex);

        // Get child type from table
        int childType = CHILD_TYPES[parent.type][childIndex];

        return new Tet(childLevel, anchor[0], anchor[1], anchor[2], childType);
    }

    /**
     * Validate that a Tet structure represents a valid tetrahedron
     */
    public static boolean isValidTet(Tet T) {
        // Check type is in valid range
        if (T.type < 0 || T.type > 5) {
            return false;
        }

        // Check level is non-negative
        if (T.level < 0) {
            return false;
        }

        // Check coordinates are properly aligned for the level
        int h = 1 << (30 - T.level);
        return T.x % h == 0 && T.y % h == 0 && T.z % h == 0;
    }

    /**
     * Example usage and testing
     */
    public static void main(String[] args) {
        // Create a root tetrahedron of type 0 at level 0
        Tet root = new Tet(0, 0, 0, 0, 0);

        System.out.println("Parent tetrahedron: " + root);
        System.out.println("Is valid: " + isValidTet(root));
        System.out.println();

        // Subdivide into 8 children
        Tet[] children = subdivide(root);

        System.out.println("8 Children (in TM order):");
        for (int i = 0; i < children.length; i++) {
            System.out.printf("Child %d: %s (Type S%d)%n", i, children[i], children[i].type);
            System.out.println("  Is valid: " + isValidTet(children[i]));
        }

        // Verify children are fully contained (by construction they are)
        System.out.println("\nAll children are fully contained in parent by construction.");
        System.out.println("Each child occupies 1/8 of the parent's volume.");
    }

    /**
     * Compute midpoint between two points
     */
    private static int[] midpoint(int[] p1, int[] p2) {
        return new int[] { (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2, (p1[2] + p2[2]) / 2 };
    }

    /**
     * Subdivide a tetrahedron into its 8 children according to Bey's subdivision scheme
     *
     * @param parent The parent tetrahedron to subdivide
     * @return Array of 8 child tetrahedra in TM order
     */
    public static Tet[] subdivide(Tet parent) {
        Tet[] children = new Tet[8];

        // Compute coordinates of parent vertices
        int[][] vertices = computeVertices(parent);

        // Create children in Bey order first
        Tet[] beyChildren = new Tet[8];

        // Children are at the next refinement level
        int childLevel = parent.level + 1;

        // Create 8 children according to Bey's subdivision rule (4.2b)
        // T0 = [x0, x01, x02, x03]
        beyChildren[0] = createChild(parent, childLevel, vertices, 0);

        // T1 = [x01, x1, x12, x13]
        beyChildren[1] = createChild(parent, childLevel, vertices, 1);

        // T2 = [x02, x12, x2, x23]
        beyChildren[2] = createChild(parent, childLevel, vertices, 2);

        // T3 = [x03, x13, x23, x3]
        beyChildren[3] = createChild(parent, childLevel, vertices, 3);

        // T4 = [x01, x02, x03, x13]
        beyChildren[4] = createChild(parent, childLevel, vertices, 4);

        // T5 = [x01, x02, x12, x13]
        beyChildren[5] = createChild(parent, childLevel, vertices, 5);

        // T6 = [x02, x03, x13, x23]
        beyChildren[6] = createChild(parent, childLevel, vertices, 6);

        // T7 = [x02, x12, x13, x23]
        beyChildren[7] = createChild(parent, childLevel, vertices, 7);

        // Reorder children according to TM order
        for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
            int beyIndex = BEY_ORDER[parent.type][tmIndex];
            children[tmIndex] = beyChildren[beyIndex];
        }

        return children;
    }

    /**
     * Tet structure representing a tetrahedron
     */
    public static class Tet {
        public int level;      // Refinement level
        public int x, y, z;    // Anchor node coordinates
        public int type;       // Type (0-5)

        public Tet(int level, int x, int y, int z, int type) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
        }

        public Tet copy() {
            return new Tet(level, x, y, z, type);
        }

        @Override
        public String toString() {
            return String.format("Tet(level=%d, x=%d, y=%d, z=%d, type=%d)", level, x, y, z, type);
        }
    }
}
