package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3i;

/**
 * Implements Bey subdivision for Tet objects, adapted from TetrahedralSubdivision.
 * This produces 8 children that are 100% geometrically contained within the parent.
 * 
 * @author hal.hildebrand
 */
public class BeySubdivision {
    
    /**
     * Child types for each parent type (from TetrahedralSubdivision Table 4.1)
     * For a tetrahedron T of type b, gives the types of T's children T0,...,T7
     * The corner-children T0, T1, T2, T3 always have the same type as T
     */
    private static final byte[][] CHILD_TYPES = {
        { 0, 0, 0, 0, 4, 5, 2, 1 },  // Parent type 0
        { 1, 1, 1, 1, 3, 2, 5, 0 },  // Parent type 1
        { 2, 2, 2, 2, 0, 1, 4, 3 },  // Parent type 2
        { 3, 3, 3, 3, 5, 4, 1, 2 },  // Parent type 3
        { 4, 4, 4, 4, 2, 3, 0, 5 },  // Parent type 4
        { 5, 5, 5, 5, 1, 0, 3, 4 }   // Parent type 5
    };
    
    /**
     * Local index (TM-ordering) of children (from TetrahedralSubdivision Table 4.2)
     * For each parent type b, maps Bey order to TM order
     */
    private static final byte[][] TM_ORDER = {
        { 0, 1, 4, 7, 2, 3, 6, 5 },  // Parent type 0
        { 0, 1, 5, 7, 2, 3, 6, 4 },  // Parent type 1
        { 0, 3, 4, 7, 1, 2, 6, 5 },  // Parent type 2
        { 0, 1, 6, 7, 2, 3, 4, 5 },  // Parent type 3
        { 0, 3, 5, 7, 1, 2, 4, 6 },  // Parent type 4
        { 0, 3, 6, 7, 2, 1, 4, 5 }   // Parent type 5
    };
    
    /**
     * Inverse of TM_ORDER: maps TM order back to Bey order
     */
    private static final byte[][] BEY_ORDER = new byte[6][8];
    
    static {
        // Compute inverse mapping
        for (int parentType = 0; parentType < 6; parentType++) {
            for (int beyIndex = 0; beyIndex < 8; beyIndex++) {
                int tmIndex = TM_ORDER[parentType][beyIndex];
                BEY_ORDER[parentType][tmIndex] = (byte)beyIndex;
            }
        }
    }
    
    /**
     * Subdivide a Tet into its 8 children using Bey's subdivision scheme.
     * All children are guaranteed to be contained within the parent.
     *
     * @param parent The parent Tet to subdivide
     * @return Array of 8 child Tet objects in TM order
     */
    public static Tet[] subdivide(Tet parent) {
        // Get parent vertices using subdivision-compatible coordinates
        Point3i[] vertices = parent.subdivisionCoordinates();
        Point3i x0 = vertices[0];
        Point3i x1 = vertices[1];
        Point3i x2 = vertices[2];
        Point3i x3 = vertices[3];
        
        // Compute midpoints
        Point3i x01 = midpoint(x0, x1);
        Point3i x02 = midpoint(x0, x2);
        Point3i x03 = midpoint(x0, x3);
        Point3i x12 = midpoint(x1, x2);
        Point3i x13 = midpoint(x1, x3);
        Point3i x23 = midpoint(x2, x3);
        
        // Create children in Bey order
        Tet[] beyChildren = new Tet[8];
        byte childLevel = (byte)(parent.l() + 1);
        
        // The 8 children have these anchor points (Bey's numbering from section 4.2b):
        // T0 = [x0, x01, x02, x03]
        beyChildren[0] = createChild(parent, childLevel, x0, 0);
        
        // T1 = [x01, x1, x12, x13]
        beyChildren[1] = createChild(parent, childLevel, x01, 1);
        
        // T2 = [x02, x12, x2, x23]
        beyChildren[2] = createChild(parent, childLevel, x02, 2);
        
        // T3 = [x03, x13, x23, x3]
        beyChildren[3] = createChild(parent, childLevel, x03, 3);
        
        // T4 = [x01, x02, x03, x13]
        beyChildren[4] = createChild(parent, childLevel, x01, 4);
        
        // T5 = [x01, x02, x12, x13]
        beyChildren[5] = createChild(parent, childLevel, x01, 5);
        
        // T6 = [x02, x03, x13, x23]
        beyChildren[6] = createChild(parent, childLevel, x02, 6);
        
        // T7 = [x02, x12, x13, x23]
        beyChildren[7] = createChild(parent, childLevel, x02, 7);
        
        // Reorder children according to TM order
        Tet[] tmChildren = new Tet[8];
        for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
            int beyIndex = BEY_ORDER[parent.type()][tmIndex];
            tmChildren[tmIndex] = beyChildren[beyIndex];
        }
        
        return tmChildren;
    }
    
    /**
     * Create a child tetrahedron
     */
    private static Tet createChild(Tet parent, byte childLevel, Point3i anchor, int beyIndex) {
        // Get child type from table
        byte childType = CHILD_TYPES[parent.type()][beyIndex];
        
        // Create the child Tet
        return new Tet(anchor.x, anchor.y, anchor.z, childLevel, childType);
    }
    
    /**
     * Compute midpoint between two points
     */
    private static Point3i midpoint(Point3i p1, Point3i p2) {
        // Use bit shift for division by 2 to ensure exact integer arithmetic
        return new Point3i(
            (p1.x + p2.x) >> 1,
            (p1.y + p2.y) >> 1,
            (p1.z + p2.z) >> 1
        );
    }
}