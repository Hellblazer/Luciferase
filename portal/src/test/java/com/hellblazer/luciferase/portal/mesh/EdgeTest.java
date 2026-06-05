package com.hellblazer.luciferase.portal.mesh;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for Edge.equals / hashCode (unordered endpoint pair).
 */
class EdgeTest {

    @Test
    void equalsNull() {
        Edge e = new Edge(1, 2);
        assertFalse(e.equals(null));
    }

    @Test
    void equalsNonEdge_noCCE() {
        Edge e = new Edge(1, 2);
        assertFalse(e.equals("not an edge"));
        assertFalse(e.equals(42));
    }

    @Test
    void equalsReflexive() {
        Edge e = new Edge(3, 7);
        assertEquals(e, e);
    }

    @Test
    void equalsSymmetricSwappedEndpoints() {
        Edge ab = new Edge(5, 9);
        Edge ba = new Edge(9, 5);
        assertEquals(ab, ba);
        assertEquals(ba, ab);
    }

    @Test
    void equalsDistinctEdgeNotEqual() {
        Edge e1 = new Edge(1, 2);
        Edge e2 = new Edge(1, 3);
        assertNotEquals(e1, e2);
    }

    @Test
    void hashCodeConsistentWithEquals() {
        Edge ab = new Edge(4, 11);
        Edge ba = new Edge(11, 4);
        assertEquals(ab, ba, "equal edges prerequisite");
        assertEquals(ab.hashCode(), ba.hashCode(), "equal edges must have equal hashCode");
    }

    @Test
    void usableAsHashSetKey() {
        HashSet<Edge> set = new HashSet<>();
        set.add(new Edge(2, 6));
        assertTrue(set.contains(new Edge(2, 6)));
        assertTrue(set.contains(new Edge(6, 2)), "unordered: swapped endpoints must hit");
        assertFalse(set.contains(new Edge(2, 7)));
    }

    @Test
    void usableAsHashMapKey() {
        HashMap<Edge, Integer> map = new HashMap<>();
        map.put(new Edge(3, 8), 42);
        assertEquals(42, map.get(new Edge(3, 8)));
        assertEquals(42, map.get(new Edge(8, 3)), "unordered: swapped endpoints must hit");
        assertNull(map.get(new Edge(3, 9)));
    }

    /**
     * Pins the vertex-0 zero-degenerate fix: edges incident on vertex 0 must hash
     * to distinct buckets when their other endpoint differs.
     *
     * The old product formula: 0.hashCode() * k.hashCode() = 0 for ALL k,
     * collapsing every vertex-0 edge into bucket 0.  The additive formula gives
     * 0 + k = k, so Edge(0,1) != Edge(0,2) in hash space.
     */
    @Test
    void vertex0EdgesHaveDistinctHashCodes() {
        Edge e01 = new Edge(0, 1);
        Edge e02 = new Edge(0, 2);
        Edge e03 = new Edge(0, 3);
        assertNotEquals(e01.hashCode(), e02.hashCode(),
                        "Edge(0,1) and Edge(0,2) must not collide in bucket 0");
        assertNotEquals(e01.hashCode(), e03.hashCode(),
                        "Edge(0,1) and Edge(0,3) must not collide in bucket 0");
        assertNotEquals(e02.hashCode(), e03.hashCode(),
                        "Edge(0,2) and Edge(0,3) must not collide in bucket 0");
    }
}
