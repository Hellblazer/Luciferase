package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TumblerRegion state management and entity tracking.
 *
 * @author hal.hildebrand
 */
class TumblerRegionTest {

    @Test
    void testCreate() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertEquals(key, region.key());
        assertEquals(0, region.entityCount());
        assertEquals(0.0f, region.density(), 0.001f);
        assertNotNull(region.entities());
        assertTrue(region.entities().isEmpty());
        assertNull(region.parentKey());
        assertNotNull(region.childKeys());
        assertTrue(region.childKeys().isEmpty());
        assertEquals(TumblerRegion.RegionState.ACTIVE, region.state());
    }

    @Test
    void testCreateChild() {
        var parentKey = new CompactTetreeKey((byte) 4, 0L);
        var childKey = new CompactTetreeKey((byte) 5, 0L);
        var region = TumblerRegion.<LongEntityID>createChild(childKey, parentKey);

        assertEquals(childKey, region.key());
        assertEquals(parentKey, region.parentKey());
        assertEquals(0, region.entityCount());
        assertEquals(TumblerRegion.RegionState.ACTIVE, region.state());
    }

    @Test
    void testIsLeaf() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertTrue(region.isLeaf());

        var withChildren = region.withChildren(List.of(new CompactTetreeKey((byte) 5, 0L)));
        assertFalse(withChildren.isLeaf());
    }

    @Test
    void testIsActive() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertTrue(region.isActive());

        var splitting = region.withState(TumblerRegion.RegionState.SPLITTING);
        assertFalse(splitting.isActive());
    }

    @Test
    void testIsRoot() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertTrue(region.isRoot());

        var parentKey = new CompactTetreeKey((byte) 3, 0L);
        var child = TumblerRegion.<LongEntityID>createChild(key, parentKey);
        assertFalse(child.isRoot());
    }

    @Test
    void testWithEntityAdded() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        var entity1 = new LongEntityID(1L);
        var region1 = region.withEntityAdded(entity1);

        assertEquals(1, region1.entityCount());
        assertTrue(region1.entities().contains(entity1));

        var entity2 = new LongEntityID(2L);
        var region2 = region1.withEntityAdded(entity2);

        assertEquals(2, region2.entityCount());
        assertTrue(region2.entities().contains(entity1));
        assertTrue(region2.entities().contains(entity2));
    }

    @Test
    void testWithEntityRemoved() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        var entity1 = new LongEntityID(1L);
        var entity2 = new LongEntityID(2L);
        var region1 = region.withEntityAdded(entity1).withEntityAdded(entity2);

        assertEquals(2, region1.entityCount());

        var region2 = region1.withEntityRemoved(entity1);

        assertEquals(1, region2.entityCount());
        assertFalse(region2.entities().contains(entity1));
        assertTrue(region2.entities().contains(entity2));

        var region3 = region2.withEntityRemoved(entity2);

        assertEquals(0, region3.entityCount());
        assertFalse(region3.entities().contains(entity2));
    }

    @Test
    void testWithEntityRemovedNonExistent() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        var entity1 = new LongEntityID(1L);
        var region1 = region.withEntityAdded(entity1);

        var entity2 = new LongEntityID(2L);
        var region2 = region1.withEntityRemoved(entity2);  // Remove non-existent

        // Count should still be 1 (entity1 still present)
        assertEquals(1, region2.entityCount());
        assertTrue(region2.entities().contains(entity1));
    }

    @Test
    void testWithDensity() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        var updated = region.withDensity(123.45f);

        assertEquals(123.45f, updated.density(), 0.001f);
        assertEquals(0, updated.entityCount());  // Other fields unchanged
    }

    @Test
    void testWithState() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertEquals(TumblerRegion.RegionState.ACTIVE, region.state());

        var splitting = region.withState(TumblerRegion.RegionState.SPLITTING);
        assertEquals(TumblerRegion.RegionState.SPLITTING, splitting.state());

        var frozen = splitting.withState(TumblerRegion.RegionState.FROZEN);
        assertEquals(TumblerRegion.RegionState.FROZEN, frozen.state());

        var active = frozen.withState(TumblerRegion.RegionState.ACTIVE);
        assertEquals(TumblerRegion.RegionState.ACTIVE, active.state());
    }

    @Test
    void testWithChildren() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        assertTrue(region.childKeys().isEmpty());

        var child1 = new CompactTetreeKey((byte) 5, 0L);
        var child2 = new CompactTetreeKey((byte) 5, 1L);
        var withChildren = region.withChildren(List.of(child1, child2));

        assertEquals(2, withChildren.childKeys().size());
        assertTrue(withChildren.childKeys().contains(child1));
        assertTrue(withChildren.childKeys().contains(child2));
    }

    @Test
    void testNeedsSplit() {
        var config = TumblerConfig.defaults();  // splitThreshold=5000, maxLevel=10
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        // Below threshold - no split
        assertFalse(region.needsSplit(config));

        // Add entities up to threshold
        var updated = region;
        for (int i = 0; i < 5000; i++) {
            updated = updated.withEntityAdded(new LongEntityID(i));
        }
        assertFalse(updated.needsSplit(config));  // Exactly at threshold

        // Above threshold - should split
        updated = updated.withEntityAdded(new LongEntityID(5000));
        assertTrue(updated.needsSplit(config));

        // At max level - cannot split
        var maxLevelKey = new CompactTetreeKey((byte) 10, 0L);
        var maxLevelRegion = TumblerRegion.<LongEntityID>create(maxLevelKey);
        for (int i = 0; i < 5001; i++) {
            maxLevelRegion = maxLevelRegion.withEntityAdded(new LongEntityID(i));
        }
        assertFalse(maxLevelRegion.needsSplit(config));  // At max level

        // Not active - cannot split
        var splitting = updated.withState(TumblerRegion.RegionState.SPLITTING);
        assertFalse(splitting.needsSplit(config));
    }

    @Test
    void testCanJoin() {
        var config = TumblerConfig.defaults();  // joinThreshold=500, minLevel=4
        var key = new CompactTetreeKey((byte) 5, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        // Empty - can join
        assertTrue(region.canJoin(config));

        // Add entities up to threshold
        var updated = region;
        for (int i = 0; i < 499; i++) {
            updated = updated.withEntityAdded(new LongEntityID(i));
        }
        assertTrue(updated.canJoin(config));  // Below threshold (499 < 500)

        // At threshold - cannot join
        updated = updated.withEntityAdded(new LongEntityID(499));
        assertFalse(updated.canJoin(config));  // Exactly at threshold (500 == 500)

        // Above threshold - cannot join
        updated = updated.withEntityAdded(new LongEntityID(500));
        assertFalse(updated.canJoin(config));

        // At min level - cannot join
        var minLevelKey = new CompactTetreeKey((byte) 4, 0L);
        var minLevelRegion = TumblerRegion.<LongEntityID>create(minLevelKey);
        assertFalse(minLevelRegion.canJoin(config));  // At min level

        // Not active - cannot join
        var joining = region.withState(TumblerRegion.RegionState.JOINING);
        assertFalse(joining.canJoin(config));
    }

    @Test
    void testToString() {
        var key = new CompactTetreeKey((byte) 4, 0L);
        var region = TumblerRegion.<LongEntityID>create(key);

        var str = region.toString();
        assertTrue(str.contains("TumblerRegion"));
        assertTrue(str.contains("key="));
        assertTrue(str.contains("count=0"));
        assertTrue(str.contains("density=0"));
        assertTrue(str.contains("state=ACTIVE"));
        assertTrue(str.contains("children=0"));
    }
}
