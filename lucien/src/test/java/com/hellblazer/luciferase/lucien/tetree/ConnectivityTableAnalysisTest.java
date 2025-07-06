package com.hellblazer.luciferase.lucien.tetree;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Analyzes the connectivity tables to understand octahedron splitting rules
 * 
 * @author hal.hildebrand
 */
public class ConnectivityTableAnalysisTest {

    @Test
    void analyzeChildVertexMapping() {
        System.out.println("Child Vertex Mapping Analysis");
        System.out.println("=============================\n");
        
        System.out.println("Parent Reference Points:");
        System.out.println("  0-3: Parent vertices (V0, V1, V2, V3)");
        System.out.println("  4: Edge 0-1 midpoint (M01)");
        System.out.println("  5: Edge 0-2 midpoint (M02)");
        System.out.println("  6: Edge 0-3 midpoint (M03)");
        System.out.println("  7: Edge 1-2 midpoint (M12)");
        System.out.println("  8: Edge 1-3 midpoint (M13)");
        System.out.println("  9: Edge 2-3 midpoint (M23)");
        System.out.println("  10: Center point (?)");
        System.out.println();
        
        // Analyze each child
        String[] refPointNames = {"V0", "V1", "V2", "V3", "M01", "M02", "M03", "M12", "M13", "M23", "Center"};
        
        for (int child = 0; child < 8; child++) {
            System.out.println("Child " + child + ":");
            System.out.print("  Vertices: [");
            byte[] vertices = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child];
            for (int i = 0; i < 4; i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(refPointNames[vertices[i]]);
            }
            System.out.println("]");
            
            // Classify child type
            String childType = classifyChild(child, vertices);
            System.out.println("  Type: " + childType);
            System.out.println();
        }
    }
    
    @Test
    void analyzeIndexToBeyMapping() {
        System.out.println("\nIndex to Bey Number Mapping");
        System.out.println("===========================\n");
        
        for (byte parentType = 0; parentType < 6; parentType++) {
            System.out.print("Parent Type " + parentType + ": ");
            byte[] mapping = TetreeConnectivity.INDEX_TO_BEY_NUMBER[parentType];
            System.out.print("[");
            for (int i = 0; i < 8; i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(mapping[i]);
            }
            System.out.println("]");
        }
        
        // Analyze patterns
        System.out.println("\nPattern Analysis:");
        System.out.println("- Bey ID 0 is always at index 0 (interior octahedron)");
        System.out.println("- Bey IDs 1-3 (corner children) appear at different indices");
        System.out.println("- This suggests different octahedron orientations per parent type");
    }
    
    @Test
    void analyzeBeyIdToVertex() {
        System.out.println("\nBey ID to Vertex Mapping");
        System.out.println("========================\n");
        
        byte[] mapping = TetreeConnectivity.BEY_ID_TO_VERTEX;
        for (int beyId = 0; beyId < 8; beyId++) {
            System.out.println("Bey ID " + beyId + " -> Vertex " + mapping[beyId]);
        }
        
        System.out.println("\nInterpretation:");
        System.out.println("- Bey IDs 0-3: Corner children at vertices 0-3");
        System.out.println("- Bey IDs 4-7: Octahedral children (values 1,1,2,2)");
        System.out.println("- The repeated values (1,1,2,2) likely indicate edge associations");
    }
    
    @Test
    void visualizeOctahedronSplitting() {
        System.out.println("\nOctahedron Splitting Visualization");
        System.out.println("==================================\n");
        
        // Group children by type
        System.out.println("Corner Children (at parent vertices):");
        for (int child = 1; child <= 4; child++) {
            byte[] verts = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child];
            System.out.println("  Child " + child + " at V" + (child-1) + ": includes vertex " + 
                             (child-1) + " and nearby midpoints");
        }
        
        System.out.println("\nOctahedral Children (from splitting central octahedron):");
        int[] octaChildren = {0, 5, 6, 7};
        for (int child : octaChildren) {
            byte[] verts = TetreeConnectivity.CHILD_VERTEX_PARENT_VERTEX[child];
            System.out.print("  Child " + child + ": ");
            
            // Count midpoints vs center
            int midpointCount = 0;
            boolean hasCenter = false;
            for (byte v : verts) {
                if (v >= 4 && v <= 9) midpointCount++;
                if (v == 10) hasCenter = true;
            }
            
            System.out.println(midpointCount + " midpoints" + (hasCenter ? " + center" : ""));
        }
        
        System.out.println("\nSplitting Pattern:");
        System.out.println("- The 6 edge midpoints form an octahedron");
        System.out.println("- The center point (10) is used to split the octahedron");
        System.out.println("- This creates 4 tetrahedra from the octahedron");
    }
    
    @Test
    void testChildTypeRetrieval() {
        System.out.println("\nChild Type Retrieval Test");
        System.out.println("=========================\n");
        
        // Test getting child types for parent type 0
        byte parentType = 0;
        System.out.println("Parent Type " + parentType + " child types:");
        for (int childIndex = 0; childIndex < 8; childIndex++) {
            byte beyId = TetreeConnectivity.getBeyChildId(parentType, childIndex);
            byte childType = TetreeConnectivity.getChildType(parentType, beyId);
            System.out.println("  Child " + childIndex + " -> Bey ID " + beyId + 
                             " -> Type " + childType);
        }
    }
    
    private String classifyChild(int childIndex, byte[] vertices) {
        // Check if it's a corner child (has a parent vertex 0-3)
        for (byte v : vertices) {
            if (v >= 0 && v <= 3) {
                return "Corner child at vertex " + v;
            }
        }
        
        // Otherwise it's an octahedral child
        return "Octahedral child (interior)";
    }
}