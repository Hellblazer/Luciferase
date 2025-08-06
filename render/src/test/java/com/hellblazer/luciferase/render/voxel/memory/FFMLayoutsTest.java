package com.hellblazer.luciferase.render.voxel.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.lang.foreign.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FFM memory layouts.
 */
public class FFMLayoutsTest {
    
    @Test
    @DisplayName("Test VOXEL_NODE_LAYOUT structure and alignment")
    public void testVoxelNodeLayout() {
        // Verify layout size and alignment
        assertEquals(16, FFMLayouts.VOXEL_NODE_LAYOUT.byteSize(), 
            "Voxel node should be 16 bytes");
        assertEquals(16, FFMLayouts.VOXEL_NODE_LAYOUT.byteAlignment(), 
            "Voxel node should be 16-byte aligned");
        
        try (var arena = Arena.ofConfined()) {
            var node = arena.allocate(FFMLayouts.VOXEL_NODE_LAYOUT);
            
            // Test field access via VarHandles
            byte validMask = (byte) 0xFF;
            byte leafMask = (byte) 0x0F;
            int childPointer = 12345;
            long attachmentData = 0xDEADBEEFL;
            
            FFMLayouts.VOXEL_NODE_VALID_MASK.set(node, 0L, validMask);
            FFMLayouts.VOXEL_NODE_LEAF_MASK.set(node, 0L, leafMask);
            FFMLayouts.VOXEL_NODE_CHILD_POINTER.set(node, 0L, childPointer);
            FFMLayouts.VOXEL_NODE_ATTACHMENT_DATA.set(node, 0L, attachmentData);
            
            // Verify values
            assertEquals(validMask, (byte) FFMLayouts.VOXEL_NODE_VALID_MASK.get(node, 0L));
            assertEquals(leafMask, (byte) FFMLayouts.VOXEL_NODE_LEAF_MASK.get(node, 0L));
            assertEquals(childPointer, (int) FFMLayouts.VOXEL_NODE_CHILD_POINTER.get(node, 0L));
            assertEquals(attachmentData, (long) FFMLayouts.VOXEL_NODE_ATTACHMENT_DATA.get(node, 0L));
        }
    }
    
    @Test
    @DisplayName("Test RAY_LAYOUT structure")
    public void testRayLayout() {
        // Verify layout size and alignment
        assertEquals(32, FFMLayouts.RAY_LAYOUT.byteSize(), 
            "Ray should be 32 bytes");
        assertEquals(16, FFMLayouts.RAY_LAYOUT.byteAlignment(), 
            "Ray should be 16-byte aligned");
        
        try (var arena = Arena.ofConfined()) {
            var ray = arena.allocate(FFMLayouts.RAY_LAYOUT);
            
            // Test ray field access
            float originX = 1.0f, originY = 2.0f, originZ = 3.0f;
            float dirX = 0.577f, dirY = 0.577f, dirZ = 0.577f; // Normalized
            float tMin = 0.001f, tMax = 1000.0f;
            
            FFMLayouts.RAY_ORIGIN_X.set(ray, 0L, originX);
            FFMLayouts.RAY_ORIGIN_Y.set(ray, 0L, originY);
            FFMLayouts.RAY_ORIGIN_Z.set(ray, 0L, originZ);
            FFMLayouts.RAY_T_MIN.set(ray, 0L, tMin);
            FFMLayouts.RAY_DIRECTION_X.set(ray, 0L, dirX);
            FFMLayouts.RAY_DIRECTION_Y.set(ray, 0L, dirY);
            FFMLayouts.RAY_DIRECTION_Z.set(ray, 0L, dirZ);
            FFMLayouts.RAY_T_MAX.set(ray, 0L, tMax);
            
            // Verify values
            assertEquals(originX, (float) FFMLayouts.RAY_ORIGIN_X.get(ray, 0L), 0.0001f);
            assertEquals(originY, (float) FFMLayouts.RAY_ORIGIN_Y.get(ray, 0L), 0.0001f);
            assertEquals(originZ, (float) FFMLayouts.RAY_ORIGIN_Z.get(ray, 0L), 0.0001f);
            assertEquals(tMin, (float) FFMLayouts.RAY_T_MIN.get(ray, 0L), 0.0001f);
            assertEquals(dirX, (float) FFMLayouts.RAY_DIRECTION_X.get(ray, 0L), 0.0001f);
            assertEquals(dirY, (float) FFMLayouts.RAY_DIRECTION_Y.get(ray, 0L), 0.0001f);
            assertEquals(dirZ, (float) FFMLayouts.RAY_DIRECTION_Z.get(ray, 0L), 0.0001f);
            assertEquals(tMax, (float) FFMLayouts.RAY_T_MAX.get(ray, 0L), 0.0001f);
        }
    }
    
    @Test
    @DisplayName("Test HIT_RESULT_LAYOUT structure")
    public void testHitResultLayout() {
        assertEquals(48, FFMLayouts.HIT_RESULT_LAYOUT.byteSize(), 
            "Hit result should be 48 bytes (with padding)");
        assertEquals(16, FFMLayouts.HIT_RESULT_LAYOUT.byteAlignment(), 
            "Hit result should be 16-byte aligned");
    }
    
    @Test
    @DisplayName("Test MATERIAL_LAYOUT structure")
    public void testMaterialLayout() {
        assertEquals(32, FFMLayouts.MATERIAL_LAYOUT.byteSize(), 
            "Material should be 32 bytes");
        assertEquals(16, FFMLayouts.MATERIAL_LAYOUT.byteAlignment(), 
            "Material should be 16-byte aligned");
        
        try (var arena = Arena.ofConfined()) {
            var material = arena.allocate(FFMLayouts.MATERIAL_LAYOUT);
            
            // Test albedo array access
            var albedoHandle = FFMLayouts.MATERIAL_LAYOUT.varHandle(
                MemoryLayout.PathElement.groupElement("albedo"),
                MemoryLayout.PathElement.sequenceElement(0)
            );
            
            // Set RGBA values
            float r = 0.8f, g = 0.2f, b = 0.1f, a = 1.0f;
            material.set(ValueLayout.JAVA_FLOAT, 0, r);  // R
            material.set(ValueLayout.JAVA_FLOAT, 4, g);  // G
            material.set(ValueLayout.JAVA_FLOAT, 8, b);  // B
            material.set(ValueLayout.JAVA_FLOAT, 12, a); // A
            
            // Set PBR properties
            material.set(ValueLayout.JAVA_FLOAT, 16, 0.5f);  // metallic
            material.set(ValueLayout.JAVA_FLOAT, 20, 0.3f);  // roughness
            material.set(ValueLayout.JAVA_FLOAT, 24, 0.0f);  // emission
            
            // Verify values
            assertEquals(r, material.get(ValueLayout.JAVA_FLOAT, 0), 0.0001f);
            assertEquals(g, material.get(ValueLayout.JAVA_FLOAT, 4), 0.0001f);
            assertEquals(b, material.get(ValueLayout.JAVA_FLOAT, 8), 0.0001f);
            assertEquals(a, material.get(ValueLayout.JAVA_FLOAT, 12), 0.0001f);
            assertEquals(0.5f, material.get(ValueLayout.JAVA_FLOAT, 16), 0.0001f);
            assertEquals(0.3f, material.get(ValueLayout.JAVA_FLOAT, 20), 0.0001f);
            assertEquals(0.0f, material.get(ValueLayout.JAVA_FLOAT, 24), 0.0001f);
        }
    }
    
    @Test
    @DisplayName("Test array allocation and sizing")
    public void testArrayAllocation() {
        try (var arena = Arena.ofConfined()) {
            int nodeCount = 100;
            
            // Test array size calculation
            long expectedSize = FFMLayouts.VOXEL_NODE_LAYOUT.byteSize() * nodeCount;
            assertEquals(expectedSize, 
                FFMLayouts.calculateArraySize(FFMLayouts.VOXEL_NODE_LAYOUT, nodeCount));
            
            // Test array allocation
            var array = FFMLayouts.allocateArray(arena, FFMLayouts.VOXEL_NODE_LAYOUT, nodeCount);
            assertNotNull(array);
            assertEquals(expectedSize, array.byteSize());
            
            // Test individual element access in array
            for (int i = 0; i < nodeCount; i++) {
                long offset = i * FFMLayouts.VOXEL_NODE_LAYOUT.byteSize();
                var element = array.asSlice(offset, FFMLayouts.VOXEL_NODE_LAYOUT.byteSize());
                
                // Write unique value to each element
                FFMLayouts.VOXEL_NODE_CHILD_POINTER.set(element, 0L, i * 100);
            }
            
            // Verify values
            for (int i = 0; i < nodeCount; i++) {
                long offset = i * FFMLayouts.VOXEL_NODE_LAYOUT.byteSize();
                var element = array.asSlice(offset, FFMLayouts.VOXEL_NODE_LAYOUT.byteSize());
                
                int value = (int) FFMLayouts.VOXEL_NODE_CHILD_POINTER.get(element, 0L);
                assertEquals(i * 100, value);
            }
        }
    }
    
    @Test
    @DisplayName("Test memory alignment")
    public void testMemoryAlignment() {
        try (var arena = Arena.ofConfined()) {
            // Allocate with specific alignment
            var aligned = arena.allocate(FFMLayouts.VOXEL_NODE_LAYOUT.byteSize(), 256);
            
            // Check that address is aligned to 256 bytes
            long address = aligned.address();
            assertEquals(0, address % 256, 
                "Memory should be aligned to 256 bytes for GPU compatibility");
        }
    }
    
    @Test
    @DisplayName("Test sequence layout creation")
    public void testSequenceLayout() {
        int elementCount = 64;
        var sequenceLayout = FFMLayouts.createArrayLayout(FFMLayouts.VOXEL_NODE_LAYOUT, elementCount);
        
        assertEquals(elementCount * FFMLayouts.VOXEL_NODE_LAYOUT.byteSize(), 
            sequenceLayout.byteSize());
        assertEquals(FFMLayouts.VOXEL_NODE_LAYOUT.byteAlignment(), 
            sequenceLayout.byteAlignment());
    }
}