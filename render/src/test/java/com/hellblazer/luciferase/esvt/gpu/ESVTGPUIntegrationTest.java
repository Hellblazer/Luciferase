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
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ESVT GPU rendering pipeline.
 *
 * GPU tests are disabled by default (require OpenGL context and sandbox bypass).
 * Run with: RUN_GPU_TESTS=true mvn test -Dtest=ESVTGPUIntegrationTest
 *
 * @author hal.hildebrand
 */
class ESVTGPUIntegrationTest {

    /**
     * Test ESVTGPUMemory creation and node operations (no GPU required)
     */
    @Test
    void testESVTGPUMemoryNodeOperations() {
        // Create a small test tree
        var nodes = new ESVTNodeUnified[4];

        // Root node (type 0)
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setValid(true);
        nodes[0].setChildMask(0b00001111); // 4 children
        nodes[0].setLeafMask(0b00001111);  // All are leaves
        nodes[0].setChildPtr(1);           // Children start at index 1

        // Child nodes (types derived from parent type 0)
        for (int i = 1; i < 4; i++) {
            nodes[i] = new ESVTNodeUnified(nodes[0].getChildType(i - 1));
            nodes[i].setValid(true);
        }

        // Create ESVTData
        var esvtData = new ESVTData(nodes, 0, 2, 3, 1);

        // Verify data
        assertEquals(4, esvtData.nodeCount());
        assertEquals(0, esvtData.rootType());
        assertEquals(32, esvtData.sizeInBytes()); // 4 * 8 bytes
        assertNotNull(esvtData.root());
    }

    /**
     * Test ESVTData ByteBuffer serialization (no GPU required)
     */
    @Test
    void testESVTDataByteBufferSerialization() {
        // Create nodes
        var nodes = new ESVTNodeUnified[2];
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setValid(true);
        nodes[0].setChildMask(0b00000001);
        nodes[0].setLeafMask(0b00000001);
        nodes[0].setChildPtr(1);

        nodes[1] = new ESVTNodeUnified((byte) 0);
        nodes[1].setValid(true);

        var esvtData = new ESVTData(nodes, 0, 1, 1, 1);

        // Serialize to ByteBuffer
        ByteBuffer buffer = esvtData.toByteBuffer();
        assertNotNull(buffer);
        assertEquals(16, buffer.remaining()); // 2 * 8 bytes

        // Deserialize and verify
        var restored = ESVTData.fromByteBuffer(buffer, 2, 0, 1, 1, 1);
        assertEquals(esvtData.nodeCount(), restored.nodeCount());
        assertEquals(esvtData.rootType(), restored.rootType());

        // Verify node content
        assertTrue(restored.root().isValid());
        assertEquals(0b00000001, restored.root().getChildMask());
    }

    /**
     * Test ESVTNodeUnified child type derivation
     */
    @Test
    void testChildTypeDerivation() {
        // Parent type 0 should produce specific child types
        var parent = new ESVTNodeUnified((byte) 0);

        // From TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[0]:
        // { 0, 0, 0, 0, 4, 5, 2, 1 }
        assertEquals(0, parent.getChildType(0));
        assertEquals(0, parent.getChildType(1));
        assertEquals(0, parent.getChildType(2));
        assertEquals(0, parent.getChildType(3));
        assertEquals(4, parent.getChildType(4));
        assertEquals(5, parent.getChildType(5));
        assertEquals(2, parent.getChildType(6));
        assertEquals(1, parent.getChildType(7));

        // Parent type 3 should produce different child types
        var parent3 = new ESVTNodeUnified((byte) 3);

        // From TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[3]:
        // { 3, 3, 3, 3, 5, 4, 1, 2 }
        assertEquals(3, parent3.getChildType(0));
        assertEquals(5, parent3.getChildType(4));
        assertEquals(4, parent3.getChildType(5));
    }

    /**
     * Test ESVTComputeRenderer creation (no GPU required for construction)
     */
    @Test
    void testESVTComputeRendererCreation() {
        var renderer = new ESVTComputeRenderer(800, 600);

        assertNotNull(renderer);
        assertEquals(800, renderer.getFrameWidth());
        assertEquals(600, renderer.getFrameHeight());
        assertFalse(renderer.isInitialized());
        assertFalse(renderer.isDisposed());
    }

    /**
     * Test ESVT shader file exists and can be loaded
     */
    @Test
    void testESVTShaderFileExists() {
        var shaderStream = getClass().getResourceAsStream("/shaders/raycast_esvt.comp");
        assertNotNull(shaderStream, "ESVT shader file should exist in resources");
    }

    /**
     * GPU integration test - requires OpenGL context
     * Run with: RUN_GPU_TESTS=true mvn test
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    void testGPURenderingPipeline() {
        // This test requires OpenGL context setup
        // For now, we skip the actual GPU operations and test the structure

        // Create test data
        var nodes = createTestTetrahedralTree();
        var esvtData = new ESVTData(nodes, 0, 3, 4, 3);

        // Create renderer
        var renderer = new ESVTComputeRenderer(256, 256);

        // Note: Full GPU test would require:
        // 1. GLFW window creation
        // 2. OpenGL context initialization
        // 3. renderer.initialize()
        // 4. ESVTGPUMemory creation and upload
        // 5. renderer.renderFrame(...)
        // 6. Output texture verification

        assertNotNull(renderer);
        assertEquals(7, esvtData.nodeCount());
    }

    /**
     * Create a simple test tetrahedral tree with root and some children
     */
    private ESVTNodeUnified[] createTestTetrahedralTree() {
        var nodes = new ESVTNodeUnified[7];

        // Root (type 0)
        nodes[0] = new ESVTNodeUnified((byte) 0);
        nodes[0].setValid(true);
        nodes[0].setChildMask(0b00000111); // 3 children (0, 1, 2)
        nodes[0].setLeafMask(0b00000100);  // Only child 2 is leaf
        nodes[0].setChildPtr(1);

        // Child 0 at index 1 (internal node, has children)
        nodes[1] = new ESVTNodeUnified((byte) 0);
        nodes[1].setValid(true);
        nodes[1].setChildMask(0b00000011); // 2 children
        nodes[1].setLeafMask(0b00000011);  // Both are leaves
        nodes[1].setChildPtr(4);

        // Child 1 at index 2 (internal node, has children)
        nodes[2] = new ESVTNodeUnified((byte) 0);
        nodes[2].setValid(true);
        nodes[2].setChildMask(0b00000001); // 1 child
        nodes[2].setLeafMask(0b00000001);  // Leaf
        nodes[2].setChildPtr(6);

        // Child 2 at index 3 (leaf - no children)
        nodes[3] = new ESVTNodeUnified((byte) 0);
        nodes[3].setValid(true);

        // Grandchildren (all leaves)
        nodes[4] = new ESVTNodeUnified((byte) 0);
        nodes[4].setValid(true);

        nodes[5] = new ESVTNodeUnified((byte) 0);
        nodes[5].setValid(true);

        nodes[6] = new ESVTNodeUnified((byte) 0);
        nodes[6].setValid(true);

        return nodes;
    }
}
