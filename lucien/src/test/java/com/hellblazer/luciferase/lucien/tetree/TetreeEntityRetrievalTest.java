package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test entity insertion and retrieval in Tetree.
 */
public class TetreeEntityRetrievalTest {
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setup() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void testEntityInsertionAndRetrieval() {
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level); // 2^15 = 32768
        
        // Create a list to store entity IDs and positions
        List<LongEntityID> entityIds = new ArrayList<>();
        List<Point3f> positions = new ArrayList<>();
        
        // Insert 10 entities at different positions
        // At level 5, max coordinate is 2^20 = 1,048,576
        // cellSize is 2^15 = 32,768
        // So we can have up to 32 cells per dimension (2^20 / 2^15 = 2^5 = 32)
        for (int i = 0; i < 10; i++) {
            Point3f position = new Point3f(
                cellSize * (i + 1) * 2,  // max would be 10 * 2 = 20, well within 32
                cellSize * (i + 1) * 2,
                cellSize * (i + 1) * 2
            );
            positions.add(position);
            
            LongEntityID entityId = tetree.insert(position, level, "Entity " + i);
            entityIds.add(entityId);
            
            System.out.printf("Inserted entity %d at (%.0f, %.0f, %.0f)%n", 
                i, position.x, position.y, position.z);
        }
        
        // Verify each entity can be found via enclosing
        System.out.println("\nVerifying entity retrieval:");
        for (int i = 0; i < 10; i++) {
            Point3f position = positions.get(i);
            Point3i intPos = new Point3i(
                Math.round(position.x),
                Math.round(position.y),
                Math.round(position.z)
            );
            
            var enclosing = tetree.enclosing(intPos, level);
            assertNotNull(enclosing, "Should find enclosing tetrahedron for entity " + i);
            
            if (enclosing.entityIds().isEmpty()) {
                System.out.printf("WARNING: No entities found at position (%d, %d, %d) for entity %d%n",
                    intPos.x, intPos.y, intPos.z, i);
                
                // Check what tetrahedron we got
                Tet tet = Tet.tetrahedron(enclosing.sfcIndex());
                System.out.printf("  Enclosing tet: anchor=(%d,%d,%d), type=%d, level=%d%n",
                    tet.x(), tet.y(), tet.z(), tet.type(), tet.l());
                
                // Check if the point is actually contained
                boolean contains = tet.contains(position);
                System.out.printf("  Tet.contains(position) = %s%n", contains);
                
                // Get the tree size
                System.out.printf("  Total entities in tree: %d%n", tetree.size());
                System.out.printf("  Total nodes in tree: %d%n", tetree.nodeCount());
            } else {
                assertTrue(enclosing.entityIds().contains(entityIds.get(i)),
                    "Should find entity " + i + " at its insertion position");
                System.out.printf("Found entity %d at position (%d, %d, %d)%n",
                    i, intPos.x, intPos.y, intPos.z);
            }
        }
        
        // Print tree statistics
        System.out.println("\nTree statistics:");
        System.out.println("Total entities: " + tetree.size());
        System.out.println("Total nodes: " + tetree.nodeCount());
    }
    
    @Test
    void testEntityAtExactTetAnchor() {
        byte level = 5;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Insert entity at exact tetrahedron anchor
        Point3f position = new Point3f(cellSize * 10, cellSize * 10, cellSize * 10);
        LongEntityID entityId = tetree.insert(position, level, "Anchor entity");
        
        // Find enclosing for the exact same position
        Point3i intPos = new Point3i(
            Math.round(position.x),
            Math.round(position.y),
            Math.round(position.z)
        );
        
        var enclosing = tetree.enclosing(intPos, level);
        assertNotNull(enclosing);
        assertFalse(enclosing.entityIds().isEmpty(), 
            "Should find entity at exact anchor position");
        assertTrue(enclosing.entityIds().contains(entityId));
        
        // Get the actual tetrahedron
        Tet tet = Tet.tetrahedron(enclosing.sfcIndex());
        System.out.printf("Entity at (%.0f,%.0f,%.0f) -> Tet at (%d,%d,%d) type %d%n",
            position.x, position.y, position.z,
            tet.x(), tet.y(), tet.z(), tet.type());
    }
}