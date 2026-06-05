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
package com.hellblazer.luciferase.esvt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ESVTData.resolveChildPtr — verifying the fail-loud behaviour on the far-pointer path.
 *
 * @author hal.hildebrand
 */
public class ESVTDataTest {

    /** Build a minimal ESVTData with the given far-pointer table. */
    private ESVTData dataWithFarPointers(int[] farPointers) {
        var nodes = new ESVTNodeUnified[10];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new ESVTNodeUnified();
        }
        return new ESVTData(nodes, new int[0], farPointers, 0, 1, 0, nodes.length);
    }

    /** Build a far node whose childPtr == ptr. */
    private ESVTNodeUnified farNode(int ptr) {
        var node = new ESVTNodeUnified();
        node.setChildPtr(ptr);
        node.setFar(true);
        return node;
    }

    /** Build a near (non-far) node whose childPtr == ptr. */
    private ESVTNodeUnified nearNode(int ptr) {
        var node = new ESVTNodeUnified();
        node.setChildPtr(ptr);
        // isFar() remains false by default
        return node;
    }

    // --- Test 1: far node with a valid farPointers table → returns resolved pointer ---

    @Test
    void farNode_validTable_returnsResolvedPointer() {
        int[] farPointers = {100, 200, 300};
        ESVTData data = dataWithFarPointers(farPointers);

        // ptr == 1 is in range; table[1] == 200
        ESVTNodeUnified node = farNode(1);
        assertEquals(200, data.resolveChildPtr(node),
            "Far node with in-range ptr must return farPointers[ptr]");
    }

    // --- Test 2a: far node with null farPointers → throws (was silently returning raw ptr) ---

    @Test
    void farNode_nullFarPointers_throws() {
        ESVTData data = dataWithFarPointers(null);
        ESVTNodeUnified node = farNode(0);

        assertThrows(IllegalStateException.class,
            () -> data.resolveChildPtr(node),
            "Far node with null farPointers must throw, not silently return raw far-index");
    }

    // --- Test 2b: far node with empty farPointers (index out of range) → throws ---

    @Test
    void farNode_emptyFarPointers_throws() {
        ESVTData data = dataWithFarPointers(new int[0]);
        ESVTNodeUnified node = farNode(0);

        assertThrows(IllegalStateException.class,
            () -> data.resolveChildPtr(node),
            "Far node with empty farPointers must throw, not silently return raw far-index");
    }

    // --- Test 2c: far node with short farPointers (ptr OOB) → throws ---

    @Test
    void farNode_oobFarPointerIndex_throws() {
        int[] farPointers = {100}; // length 1 — ptr==1 is out of range
        ESVTData data = dataWithFarPointers(farPointers);
        ESVTNodeUnified node = farNode(1);

        assertThrows(IllegalStateException.class,
            () -> data.resolveChildPtr(node),
            "Far node with out-of-range ptr must throw, not silently return raw far-index");
    }

    // --- Test 3: near node (isFar==false) → returns direct pointer unchanged ---

    @Test
    void nearNode_returnsPtrDirectly() {
        ESVTData data = dataWithFarPointers(new int[0]); // far table empty — irrelevant for near nodes
        ESVTNodeUnified node = nearNode(42);

        assertEquals(42, data.resolveChildPtr(node),
            "Near node must return raw childPtr directly (not a far-pointer lookup)");
    }

    // --- Guard: near node returns ptr even when farPointers is null ---

    @Test
    void nearNode_nullFarPointers_returnsPtrDirectly() {
        ESVTData data = dataWithFarPointers(null);
        ESVTNodeUnified node = nearNode(7);

        assertEquals(7, data.resolveChildPtr(node),
            "Near node must return raw childPtr even when farPointers is null");
    }
}
