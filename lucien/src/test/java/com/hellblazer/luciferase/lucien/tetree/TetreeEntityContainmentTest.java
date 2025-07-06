package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
// Kuhn imports removed - using base Tetree with positive volume correction
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to verify that entities are properly contained in nodes and that the visualization would show the correct
 * containing tetrahedra.
 *
 * This test now uses base Tetree with built-in positive volume correction.
 */
public class TetreeEntityContainmentTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        // Use base Tetree with built-in positive volume correction
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testEntityInGapScenario() {
        System.out.println("\n=== Testing Entity in T8Code Gap Scenario ===");

        // Try to insert an entity at a position that might fall in a t8code gap
        // Based on our analysis, positions like (0.1, 0.2, 0.3) in unit cube fall in gaps

        // Scale to actual coordinates at level 10
        float scale = (float) Math.pow(2, 10); // 1024
        float x = 0.1f * scale; // 102.4
        float y = 0.2f * scale; // 204.8
        float z = 0.3f * scale; // 307.2

        Point3f gapPosition = new Point3f(x, y, z);

        System.out.printf("Testing potential gap position: (%.1f, %.1f, %.1f)%n", x, y, z);

        // Try to locate at different levels
        for (byte level = 5; level <= 10; level++) {
            try {
                var tet = Tet.locatePointBeyRefinementFromRoot(gapPosition.x, gapPosition.y, gapPosition.z, level);
                System.out.printf("  Level %d: Located at Tet(%d,%d,%d), type %d%n", level, tet.x(), tet.y(), tet.z(),
                                  tet.type());

                // Check if the tetrahedron actually contains the point
                boolean contains = tet.contains(gapPosition);
                System.out.printf("    Contains check: %s%n", contains);

                if (!contains) {
                    System.out.println("    WARNING: Located tetrahedron doesn't contain the point!");
                }
            } catch (Exception e) {
                System.out.printf("  Level %d: Failed to locate - %s%n", level, e.getMessage());
            }
        }

        // Try to insert the entity
        var id = tetree.insert(gapPosition, (byte) 10, "Gap entity");
        System.out.println("\nEntity inserted with ID: " + id);

        // Check where it ended up
        var nodeWithEntity = tetree.nodes().filter(node -> node.entityIds().contains(id)).findFirst();

        if (nodeWithEntity.isPresent()) {
            var node = nodeWithEntity.get();
            var key = node.sfcIndex();
            var tet = Tet.tetrahedron(key);
            System.out.printf("Entity stored in node at Tet(%d,%d,%d), type %d, level %d%n", tet.x(), tet.y(), tet.z(),
                              tet.type(), tet.l());

            boolean contains = tet.contains(gapPosition);
            System.out.printf("Node's tetrahedron contains point: %s%n", contains);

            if (!contains) {
                System.out.println("ERROR: Entity stored in non-containing tetrahedron!");
            }
        }
    }

    @Test
    void testEntityInsertionAtLevel5() {
        System.out.println("=== Testing Entity Insertion at Level 5 ===");

        // Use the same parameters as the visualization demo
        byte level = 5;
        float maxCoord = (float) Math.pow(2, 20); // 2^20 = 1048576
        float minRange = maxCoord * 0.2f;  // 209715.2
        float maxRange = maxCoord * 0.8f;  // 838860.8

        // Insert some test entities
        Point3f[] positions = { new Point3f(300000, 400000, 500000), new Point3f(600000, 700000, 800000), new Point3f(
        minRange, minRange, minRange), new Point3f(maxRange, maxRange, maxRange) };

        for (int i = 0; i < positions.length; i++) {
            Point3f pos = positions[i];

            // Use Tet to locate the point - now with positive volume correction
            var tet = Tet.locatePointBeyRefinementFromRoot(pos.x, pos.y, pos.z, level);
            System.out.printf("Position (%.0f, %.0f, %.0f) -> Tet at (%d,%d,%d), type %d, level %d%n", pos.x, pos.y,
                              pos.z, tet.x(), tet.y(), tet.z(), tet.type(), tet.l());

            // Insert the entity
            var id = tetree.insert(pos, level, "Entity " + i);

            // Verify it's in a node
            var foundNode = tetree.nodes().filter(node -> node.entityIds().contains(id)).findFirst();

            assertTrue(foundNode.isPresent(), "Entity should be found in a node");

            if (foundNode.isPresent()) {
                var node = foundNode.get();
                var key = node.sfcIndex();
                var nodeTet = Tet.tetrahedron(key);
                System.out.printf("  Entity %s stored in node at (%d,%d,%d), type %d, level %d%n", id, nodeTet.x(),
                                  nodeTet.y(), nodeTet.z(), nodeTet.type(), nodeTet.l());

                // With positive volume correction, we can verify the entity can be found at its position
                var foundAtPosition = tetree.lookup(pos, level);
                assertTrue(foundAtPosition.contains(id),
                           String.format("Should be able to find entity at its position (%.0f,%.0f,%.0f)", pos.x, pos.y,
                                         pos.z));
            }
        }
    }

    @Test
    void testVisualizationNodeVisibility() {
        System.out.println("\n=== Testing Visualization Node Visibility ===");

        // Insert entities at different levels
        byte[] levels = { 3, 4, 5, 6, 7, 8 };
        Set<Object> entityIds = new HashSet<>();

        for (byte level : levels) {
            float cellSize = (float) Math.pow(2, 20 - level);
            float x = cellSize * 10.5f;
            float y = cellSize * 10.5f;
            float z = cellSize * 10.5f;

            var id = tetree.insert(new Point3f(x, y, z), level, "Entity at level " + level);
            entityIds.add(id);
            System.out.printf("Inserted entity at level %d, position (%.0f,%.0f,%.0f)%n", level, x, y, z);
        }

        // Simulate visualization filtering (levels 3-7 visible by default)
        int minVisibleLevel = 3;
        int maxVisibleLevel = 7;

        System.out.println("\nChecking visibility with range " + minVisibleLevel + "-" + maxVisibleLevel);

        AtomicInteger visibleEntities = new AtomicInteger(0);
        AtomicInteger totalEntities = new AtomicInteger(0);

        tetree.nodes().forEach(node -> {
            if (!node.entityIds().isEmpty()) {
                totalEntities.addAndGet(node.entityIds().size());
                var key = node.sfcIndex();
                int level = key.getLevel();

                if (level >= minVisibleLevel && level <= maxVisibleLevel) {
                    visibleEntities.addAndGet(node.entityIds().size());
                    System.out.printf("Node at level %d with %d entities - VISIBLE%n", level, node.entityIds().size());
                } else {
                    System.out.printf("Node at level %d with %d entities - NOT VISIBLE%n", level,
                                      node.entityIds().size());
                }
            }
        });

        System.out.printf("\nTotal entities: %d, Visible: %d, Hidden: %d%n", totalEntities.get(), visibleEntities.get(),
                          totalEntities.get() - visibleEntities.get());

        // With default settings, level 8 entity should not be visible
        assertTrue(visibleEntities.get() < totalEntities.get(), "Some entities should be outside the visible range");
    }
}
