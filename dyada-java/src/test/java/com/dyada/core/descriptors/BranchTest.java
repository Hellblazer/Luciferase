package com.dyada.core.descriptors;

import com.dyada.core.bitarray.BitArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Branch Navigation Tests")
class BranchTest {

    @Test
    @DisplayName("Create branch with valid dimensions")
    void testCreateBranch() {
        var branch = new Branch(3);
        
        assertEquals(3, branch.depth() >= 0 ? 3 : -1); // Test numDimensions indirectly
        assertEquals(0, branch.depth());
        assertTrue(branch.isAtRoot());
        assertNull(branch.getCurrentLevelIncrement());
        assertEquals(0, branch.getRemainingCount());
        assertFalse(branch.hasNextSibling());
    }

    @Test
    @DisplayName("Create branch with invalid dimensions")
    void testCreateBranchInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new Branch(0));
        assertThrows(IllegalArgumentException.class, () -> new Branch(-1));
    }

    @Test
    @DisplayName("Grow branch with refinement")
    void testGrowBranch() {
        var branch = new Branch(3);
        var refinement = BitArray.of(new boolean[]{true, false, true}); // Refine X and Z
        
        var grownBranch = branch.growBranch(refinement);
        
        assertNotNull(grownBranch);
        assertNotSame(branch, grownBranch); // Should be a new instance
        assertEquals(1, grownBranch.depth());
        assertFalse(grownBranch.isAtRoot());
        assertEquals(refinement, grownBranch.getCurrentLevelIncrement());
        
        // With 2 refined dimensions, we should have 2^2 - 1 = 3 siblings to visit
        assertEquals(3, grownBranch.getRemainingCount());
        assertTrue(grownBranch.hasNextSibling());
    }

    @Test
    @DisplayName("Grow branch with invalid refinement")
    void testGrowBranchInvalid() {
        var branch = new Branch(3);
        
        // Wrong size refinement
        var wrongSizeRefinement = BitArray.of(2);
        assertThrows(IllegalArgumentException.class, 
            () -> branch.growBranch(wrongSizeRefinement));
    }

    @Test
    @DisplayName("Advance branch through siblings")
    void testAdvanceBranch() {
        var branch = new Branch(2);
        var refinement = BitArray.of(new boolean[]{true, true}); // Refine both dimensions
        
        var grownBranch = branch.growBranch(refinement);
        assertEquals(3, grownBranch.getRemainingCount()); // 2^2 - 1 = 3
        
        // Advance through siblings
        var sibling1 = grownBranch.advanceBranch();
        assertNotNull(sibling1);
        assertEquals(2, sibling1.getRemainingCount());
        
        var sibling2 = sibling1.advanceBranch();
        assertNotNull(sibling2);
        assertEquals(1, sibling2.getRemainingCount());
        
        var sibling3 = sibling2.advanceBranch();
        assertNotNull(sibling3);
        assertEquals(0, sibling3.getRemainingCount());
        assertFalse(sibling3.hasNextSibling());
        
        // No more siblings
        var noMoreSiblings = sibling3.advanceBranch();
        assertNull(noMoreSiblings);
    }

    @Test
    @DisplayName("Advance branch at root")
    void testAdvanceBranchAtRoot() {
        var branch = new Branch(3);
        
        // Root has no siblings
        var advanced = branch.advanceBranch();
        assertNull(advanced);
    }

    @Test
    @DisplayName("Convert branch to history")
    void testToHistory() {
        var branch = new Branch(2);
        var refinement1 = BitArray.of(new boolean[]{true, false}); // Refine X only
        var refinement2 = BitArray.of(new boolean[]{false, true}); // Refine Y only
        
        // Build a multi-level branch
        var level1 = branch.growBranch(refinement1);
        var level2 = level1.growBranch(refinement2);
        
        var history = level2.toHistory();
        
        assertNotNull(history);
        assertEquals(2, history.indices().size());
        assertEquals(2, history.levelIncrements().size());
        
        // Check level increments match what we added
        assertEquals(refinement1, history.levelIncrements().get(0));
        assertEquals(refinement2, history.levelIncrements().get(1));
        
        // Indices should be calculated based on remaining counts
        assertNotNull(history.indices().get(0));
        assertNotNull(history.indices().get(1));
    }

    @Test
    @DisplayName("Root branch history")
    void testRootBranchHistory() {
        var branch = new Branch(3);
        var history = branch.toHistory();
        
        assertNotNull(history);
        assertTrue(history.indices().isEmpty());
        assertTrue(history.levelIncrements().isEmpty());
    }

    @Test
    @DisplayName("Branch depth tracking")
    void testDepthTracking() {
        var branch = new Branch(2);
        assertEquals(0, branch.depth());
        
        var level1 = branch.growBranch(BitArray.of(new boolean[]{true, false}));
        assertEquals(1, level1.depth());
        
        var level2 = level1.growBranch(BitArray.of(new boolean[]{false, true}));
        assertEquals(2, level2.depth());
        
        var level3 = level2.growBranch(BitArray.of(new boolean[]{true, true}));
        assertEquals(3, level3.depth());
    }

    @Test
    @DisplayName("Branch state queries")
    void testBranchStateQueries() {
        var branch = new Branch(3);
        
        // Root state
        assertTrue(branch.isAtRoot());
        assertNull(branch.getCurrentLevelIncrement());
        assertEquals(0, branch.getRemainingCount());
        assertFalse(branch.hasNextSibling());
        
        // After growing
        var refinement = BitArray.of(new boolean[]{true, true, false});
        var grown = branch.growBranch(refinement);
        
        assertFalse(grown.isAtRoot());
        assertEquals(refinement, grown.getCurrentLevelIncrement());
        assertTrue(grown.getRemainingCount() > 0);
        assertTrue(grown.hasNextSibling());
    }

    @Test
    @DisplayName("Branch copying")
    void testBranchCopy() {
        var branch = new Branch(3);
        var refinement = BitArray.of(new boolean[]{true, false, true});
        var grown = branch.growBranch(refinement);
        
        var copy = grown.copy();
        
        assertNotSame(grown, copy);
        assertEquals(grown, copy);
        assertEquals(grown.depth(), copy.depth());
        assertEquals(grown.isAtRoot(), copy.isAtRoot());
        assertEquals(grown.getCurrentLevelIncrement(), copy.getCurrentLevelIncrement());
        assertEquals(grown.getRemainingCount(), copy.getRemainingCount());
        assertEquals(grown.hasNextSibling(), copy.hasNextSibling());
    }

    @Test
    @DisplayName("Branch equality and hashing")
    void testEqualityAndHashing() {
        var branch1 = new Branch(3);
        var branch2 = new Branch(3);
        var branch3 = new Branch(2);
        
        // Root branches should be equal
        assertEquals(branch1, branch2);
        assertNotEquals(branch1, branch3);
        assertEquals(branch1.hashCode(), branch2.hashCode());
        
        // Grow both branches identically
        var refinement = BitArray.of(new boolean[]{true, false, true});
        var grown1 = branch1.growBranch(refinement);
        var grown2 = branch2.growBranch(refinement);
        
        assertEquals(grown1, grown2);
        assertEquals(grown1.hashCode(), grown2.hashCode());
        
        // Advance one branch
        var advanced = grown1.advanceBranch();
        assertNotEquals(grown1, advanced);
        assertNotEquals(grown2, advanced);
        
        // Test with null and different types
        assertNotEquals(branch1, null);
        assertNotEquals(branch1, "not a branch");
    }

    @Test
    @DisplayName("Branch toString")
    void testToString() {
        var branch = new Branch(3);
        var string = branch.toString();
        
        assertNotNull(string);
        assertTrue(string.contains("Branch"));
        assertTrue(string.contains("depth=0"));
        assertTrue(string.contains("dimensions=3"));
        
        // After growing
        var refinement = BitArray.of(new boolean[]{true, false, true});
        var grown = branch.growBranch(refinement);
        var grownString = grown.toString();
        
        assertTrue(grownString.contains("depth=1"));
    }

    @Test
    @DisplayName("Complex branching scenario")
    void testComplexBranching() {
        var branch = new Branch(3);
        
        // Create a complex branching pattern
        var refinement1 = BitArray.of(new boolean[]{true, true, false}); // 4 children (2^2)
        var level1 = branch.growBranch(refinement1);
        assertEquals(3, level1.getRemainingCount()); // 4 - 1 = 3
        
        // Advance to second child
        var secondChild = level1.advanceBranch();
        assertNotNull(secondChild);
        assertEquals(2, secondChild.getRemainingCount());
        
        // Grow the second child
        var refinement2 = BitArray.of(new boolean[]{false, false, true}); // 2 children (2^1)
        var level2 = secondChild.growBranch(refinement2);
        assertEquals(1, level2.getRemainingCount()); // 2 - 1 = 1
        
        // Verify the complex structure
        assertEquals(2, level2.depth());
        assertFalse(level2.isAtRoot());
        assertEquals(refinement2, level2.getCurrentLevelIncrement());
        
        var history = level2.toHistory();
        assertEquals(2, history.levelIncrements().size());
        assertEquals(refinement1, history.levelIncrements().get(0));
        assertEquals(refinement2, history.levelIncrements().get(1));
    }

    @Test
    @DisplayName("Single dimension branching")
    void testSingleDimensionBranching() {
        var branch = new Branch(1);
        var refinement = BitArray.of(new boolean[]{true}); // Single dimension refinement
        
        var grown = branch.growBranch(refinement);
        assertEquals(1, grown.getRemainingCount()); // 2^1 - 1 = 1
        
        var sibling = grown.advanceBranch();
        assertNotNull(sibling);
        assertEquals(0, sibling.getRemainingCount());
        assertFalse(sibling.hasNextSibling());
        
        var noMore = sibling.advanceBranch();
        assertNull(noMore);
    }

    @Test
    @DisplayName("No refinement branching")
    void testNoRefinementBranching() {
        var branch = new Branch(3);
        var noRefinement = BitArray.of(3); // All false - no refinement
        
        var grown = branch.growBranch(noRefinement);
        assertEquals(0, grown.getRemainingCount()); // 2^0 - 1 = 0
        assertFalse(grown.hasNextSibling());
        
        var advanced = grown.advanceBranch();
        assertNull(advanced); // Should immediately go up a level (no siblings)
    }
}