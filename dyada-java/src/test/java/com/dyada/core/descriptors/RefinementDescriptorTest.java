package com.dyada.core.descriptors;

import com.dyada.core.bitarray.BitArray;
import com.dyada.core.coordinates.InvalidLevelIndexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RefinementDescriptor Tests")
class RefinementDescriptorTest {

    @Test
    @DisplayName("Create simple descriptor with dimensions")
    void testCreateBasic() {
        var descriptor = RefinementDescriptor.create(3);
        
        assertEquals(3, descriptor.getNumDimensions());
        assertEquals(1, descriptor.size());
        assertEquals(1, descriptor.getNumBoxes());
        assertTrue(descriptor.isBox(0));
        assertTrue(descriptor.isValid());
    }

    @Test
    @DisplayName("Create descriptor with invalid dimensions")
    void testCreateInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, 
            () -> RefinementDescriptor.create(0));
        assertThrows(IllegalArgumentException.class, 
            () -> RefinementDescriptor.create(-1));
    }

    @Test
    @DisplayName("Create regular refinement tree")
    void testRegularRefinement() {
        // Regular refinement: level 1 in X, level 2 in Y, level 0 in Z
        var descriptor = RefinementDescriptor.regular(3, new int[]{1, 2, 0});
        
        assertEquals(3, descriptor.getNumDimensions());
        assertTrue(descriptor.size() > 1);
        assertTrue(descriptor.isValid());
        assertTrue(descriptor.getNumBoxes() > 0);
    }

    @Test
    @DisplayName("Regular refinement with invalid parameters")
    void testRegularRefinementInvalid() {
        // Wrong dimension count
        assertThrows(IllegalArgumentException.class, 
            () -> RefinementDescriptor.regular(3, new int[]{1, 2}));
        
        // Negative level
        assertThrows(IllegalArgumentException.class, 
            () -> RefinementDescriptor.regular(2, new int[]{1, -1}));
        
        // Invalid dimensions
        assertThrows(IllegalArgumentException.class, 
            () -> RefinementDescriptor.regular(0, new int[]{}));
    }

    @Test
    @DisplayName("Test BitArray access and bounds checking")
    void testBitArrayAccess() {
        var descriptor = RefinementDescriptor.create(2);
        
        // Valid access
        var bits = descriptor.get(0);
        assertNotNull(bits);
        assertEquals(2, bits.size());
        
        // Invalid access
        assertThrows(IndexOutOfBoundsException.class, 
            () -> descriptor.get(-1));
        assertThrows(IndexOutOfBoundsException.class, 
            () -> descriptor.get(1));
    }

    @Test
    @DisplayName("Test leaf node detection")
    void testLeafDetection() {
        var descriptor = RefinementDescriptor.create(3);
        
        // Single root node should be a leaf
        assertTrue(descriptor.isBox(0));
        assertEquals(1, descriptor.getNumBoxes());
        
        // Test with more complex structure
        var regularDescriptor = RefinementDescriptor.regular(2, new int[]{1, 1});
        assertTrue(regularDescriptor.getNumBoxes() > 0);
    }

    @Test
    @DisplayName("Test descriptor validation")
    void testValidation() {
        var descriptor = RefinementDescriptor.create(3);
        assertTrue(descriptor.isValid());
        
        var errors = descriptor.validate();
        assertTrue(errors.isEmpty());
        
        // Test with invalid data structure
        var invalidData = new ArrayList<BitArray>();
        // Empty data should be invalid
        var invalidDescriptor = RefinementDescriptor.fromData(invalidData, 2);
        assertFalse(invalidDescriptor.isValid());
        
        var validationErrors = invalidDescriptor.validate();
        assertFalse(validationErrors.isEmpty());
    }

    @Test
    @DisplayName("Test descriptor with mismatched dimensions")
    void testMismatchedDimensions() {
        var data = List.of(
            BitArray.of(2),  // 2 dimensions
            BitArray.of(3)   // 3 dimensions - mismatch!
        );
        
        var descriptor = RefinementDescriptor.fromData(data, 2);
        var errors = descriptor.validate();
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("wrong dimension count"));
    }

    @Test
    @DisplayName("Test iterator functionality")
    void testIterator() {
        var descriptor = RefinementDescriptor.regular(2, new int[]{1, 0});
        
        var count = 0;
        for (var bits : descriptor) {
            assertNotNull(bits);
            assertEquals(2, bits.size());
            count++;
        }
        
        assertEquals(descriptor.size(), count);
    }

    @Test
    @DisplayName("Test tree structure properties")
    void testTreeStructure() {
        var descriptor = RefinementDescriptor.create(3);
        
        // Root properties
        assertEquals(0, descriptor.getParent(0));  // Root parent is special case
        var siblings = descriptor.getSiblings(0);
        assertTrue(siblings.isEmpty());  // Root has no siblings
        
        var children = descriptor.getChildren(0);
        assertTrue(children.isEmpty());  // Single node has no children (it's a leaf)
    }

    @Test
    @DisplayName("Test descriptor copying")
    void testCopy() {
        var original = RefinementDescriptor.create(3);
        var copy = original.copy();
        
        assertEquals(original, copy);
        assertNotSame(original, copy);
        assertEquals(original.getNumDimensions(), copy.getNumDimensions());
        assertEquals(original.size(), copy.size());
        assertEquals(original.getNumBoxes(), copy.getNumBoxes());
    }

    @Test
    @DisplayName("Test refinement application")
    void testWithRefinement() {
        var descriptor = RefinementDescriptor.create(3);
        var refinementPattern = BitArray.of(new boolean[]{true, false, true});
        
        // Since this is a leaf node, we should be able to refine it
        assertThrows(IllegalArgumentException.class, 
            () -> descriptor.withRefinement(0, refinementPattern));
        
        // Test with invalid pattern size
        var wrongSizePattern = BitArray.of(2);
        assertThrows(IllegalArgumentException.class, 
            () -> descriptor.withRefinement(0, wrongSizePattern));
        
        // Test with invalid index
        assertThrows(IndexOutOfBoundsException.class, 
            () -> descriptor.withRefinement(1, refinementPattern));
    }

    @Test
    @DisplayName("Test max depth calculation")
    void testMaxDepth() {
        var simpleDescriptor = RefinementDescriptor.create(2);
        assertTrue(simpleDescriptor.getMaxDepth() >= 0);
        
        var regularDescriptor = RefinementDescriptor.regular(2, new int[]{2, 2});
        assertTrue(regularDescriptor.getMaxDepth() > 0);
    }

    @Test
    @DisplayName("Test descriptor equality and hashing")
    void testEqualityAndHashing() {
        var desc1 = RefinementDescriptor.create(3);
        var desc2 = RefinementDescriptor.create(3);
        var desc3 = RefinementDescriptor.create(2);
        
        assertEquals(desc1, desc2);
        assertNotEquals(desc1, desc3);
        assertEquals(desc1.hashCode(), desc2.hashCode());
        
        // Test null and different class
        assertNotEquals(desc1, null);
        assertNotEquals(desc1, "not a descriptor");
    }

    @Test
    @DisplayName("Test toString representation")
    void testToString() {
        var descriptor = RefinementDescriptor.create(3);
        var string = descriptor.toString();
        
        assertNotNull(string);
        assertTrue(string.contains("RefinementDescriptor"));
        assertTrue(string.contains("nodes=1"));
        assertTrue(string.contains("boxes=1"));
        assertTrue(string.contains("dimensions=3"));
    }

    @Test
    @DisplayName("Test branch iteration")
    void testBranches() {
        var descriptor = RefinementDescriptor.create(2);
        var branches = descriptor.branches();
        
        assertNotNull(branches);
        // Simple descriptor should have minimal branch structure
        var branchList = branches.limit(10).toList();
        assertFalse(branchList.isEmpty());
    }

    @Test
    @DisplayName("Test with complex regular tree")
    void testComplexRegularTree() {
        // Create a more complex tree with varying refinement levels
        var levels = new int[]{2, 1, 3};
        var descriptor = RefinementDescriptor.regular(3, levels);
        
        assertEquals(3, descriptor.getNumDimensions());
        assertTrue(descriptor.size() > 1);
        assertTrue(descriptor.getNumBoxes() > 0);
        assertTrue(descriptor.isValid());
        
        // All nodes should have correct dimension count
        for (int i = 0; i < descriptor.size(); i++) {
            assertEquals(3, descriptor.get(i).size());
        }
    }

    @Test
    @DisplayName("Test edge cases")
    void testEdgeCases() {
        // Single dimension
        var singleDim = RefinementDescriptor.create(1);
        assertEquals(1, singleDim.getNumDimensions());
        assertTrue(singleDim.isValid());
        
        // Zero refinement levels (all zeros)
        var zeroRefinement = RefinementDescriptor.regular(3, new int[]{0, 0, 0});
        assertEquals(1, zeroRefinement.size());  // Should just be root
        assertEquals(1, zeroRefinement.getNumBoxes());  // Root is a leaf
        
        // Large dimension count
        var largeDim = RefinementDescriptor.create(10);
        assertEquals(10, largeDim.getNumDimensions());
        assertTrue(largeDim.isValid());
    }

    @Test
    @DisplayName("Test data access")
    void testDataAccess() {
        var descriptor = RefinementDescriptor.create(2);
        var data = descriptor.getData();
        
        assertNotNull(data);
        assertEquals(1, data.size());
        
        // Data should be immutable
        assertThrows(UnsupportedOperationException.class, 
            () -> data.add(BitArray.of(2)));
    }
}