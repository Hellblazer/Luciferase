/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;

/**
 * Profile where and when lazy keys are being resolved during insertion.
 */
public class LazyEvaluationProfilingTest {

    private Tetree<LongEntityID, String> tetree;

    @Test
    void analyzeResolutionPoints() {
        System.out.println("\n=== ANALYZING RESOLUTION POINTS ===\n");

        tetree.setLazyEvaluation(true);

        // Track resolution through different operations
        var position = new Point3f(5000, 5000, 5000);

        // Test 1: Just create a lazy key directly
        System.out.println("1. Creating lazy key...");
        var tet = Tet.locateStandardRefinement(position.x, position.y, position.z, (byte) 10);
        var key = new LazyTetreeKey(tet);
        System.out.println("   Key type: " + key.getClass().getSimpleName());
        System.out.println("   Is resolved: " + key.isResolved());

        // Test 2: HashMap operations
        System.out.println("\n2. Testing HashMap operations...");
        var map = new java.util.HashMap<BaseTetreeKey<?>, String>();
        map.put(key, "test");
        if (key instanceof LazyTetreeKey lazy) {
            System.out.println("   After put() - Is resolved: " + lazy.isResolved());
        }

        var value = map.get(key);
        if (key instanceof LazyTetreeKey lazy) {
            System.out.println("   After get() - Is resolved: " + lazy.isResolved());
        }

        // Test 3: TreeSet operations (sorted)
        System.out.println("\n3. Testing TreeSet operations...");
        var set = new java.util.TreeSet<BaseTetreeKey<?>>();
        if (key instanceof LazyTetreeKey lazy) {
            System.out.println("   Before add() - Is resolved: " + lazy.isResolved());
        }
        set.add(key);
        if (key instanceof LazyTetreeKey lazy) {
            System.out.println("   After add() - Is resolved: " + lazy.isResolved() + " (TreeSet forces comparison)");
        }
    }

    @Test
    void measureOverhead() {
        System.out.println("\n=== MEASURING LAZY KEY OVERHEAD ===\n");

        var positions = new ArrayList<Point3f>();
        for (int i = 0; i < 10000; i++) {
            positions.add(new Point3f(i * 10, i * 10, i * 10));
        }

        // Test 1: Direct TetreeKey creation
        long directStart = System.nanoTime();
        var directKeys = new ArrayList<BaseTetreeKey<?>>();
        for (var pos : positions) {
            var tet = Tet.locateStandardRefinement(pos.x, pos.y, pos.z, (byte) 10);
            directKeys.add(tet.tmIndex());
        }
        long directTime = System.nanoTime() - directStart;

        // Test 2: LazyTetreeKey creation (no resolution)
        long lazyStart = System.nanoTime();
        var lazyKeys = new ArrayList<LazyTetreeKey>();
        for (var pos : positions) {
            var tet = Tet.locateStandardRefinement(pos.x, pos.y, pos.z, (byte) 10);
            lazyKeys.add(new LazyTetreeKey(tet));
        }
        long lazyTime = System.nanoTime() - lazyStart;

        // Test 3: LazyTetreeKey with immediate resolution
        long resolvedStart = System.nanoTime();
        var resolvedKeys = new ArrayList<BaseTetreeKey<?>>();
        for (var pos : positions) {
            var tet = Tet.locateStandardRefinement(pos.x, pos.y, pos.z, (byte) 10);
            var lazy = new LazyTetreeKey(tet);
            lazy.resolve();
            resolvedKeys.add(lazy);
        }
        long resolvedTime = System.nanoTime() - resolvedStart;

        System.out.printf("Direct TetreeKey creation: %.2f ms (%.2f ns/key)%n", directTime / 1_000_000.0,
                          directTime / (double) positions.size());
        System.out.printf("Lazy key creation (unresolved): %.2f ms (%.2f ns/key)%n", lazyTime / 1_000_000.0,
                          lazyTime / (double) positions.size());
        System.out.printf("Lazy key + resolution: %.2f ms (%.2f ns/key)%n", resolvedTime / 1_000_000.0,
                          resolvedTime / (double) positions.size());

        double creationOverhead = (lazyTime - directTime) / (double) positions.size();
        double resolutionOverhead = (resolvedTime - directTime) / (double) positions.size();

        System.out.printf("\nOverhead per key:%n");
        System.out.printf("  Lazy creation: %.2f ns%n", creationOverhead);
        System.out.printf("  Total with resolution: %.2f ns%n", resolutionOverhead);
    }

    @Test
    void profileSingleInsertionResolution() {
        System.out.println("\n=== PROFILING LAZY KEY RESOLUTION ===\n");

        // Create a custom LazyTetreeKey that tracks resolution
        class InstrumentedLazyTetreeKey extends LazyTetreeKey {
            private final String location;

            public InstrumentedLazyTetreeKey(Tet tet, String location) {
                super(tet);
                this.location = location;
            }

            @Override
            public void resolve() {
                System.out.println("Key resolved at: " + location);
                new Exception().printStackTrace(System.out);
                super.resolve();
            }
        }

        // Override Tetree to use instrumented keys
        var instrumentedTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator()) {
            @Override
            protected BaseTetreeKey<?> calculateSpatialIndex(Point3f position, byte level) {
                var tet = Tet.locateStandardRefinement(position.x, position.y, position.z, level);
                return new InstrumentedLazyTetreeKey(tet, "calculateSpatialIndex");
            }
        };

        System.out.println("Inserting single entity with instrumented lazy key...\n");
        instrumentedTetree.insert(new Point3f(1000, 2000, 3000), (byte) 10, "Test");
    }

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testSelectiveLazyEvaluation() {
        System.out.println("\n=== TESTING SELECTIVE LAZY EVALUATION ===\n");

        // Compare different strategies
        var positions = new ArrayList<Point3f>();
        var contents = new ArrayList<String>();
        for (int i = 0; i < 1000; i++) {
            positions.add(new Point3f(i * 100, i * 100, i * 100));
            contents.add("Entity_" + i);
        }

        // Strategy 1: Always lazy
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        tetree.setLazyEvaluation(true);

        long alwaysLazyStart = System.nanoTime();
        for (int i = 0; i < positions.size(); i++) {
            tetree.insert(positions.get(i), (byte) 10, contents.get(i));
        }
        long alwaysLazyTime = System.nanoTime() - alwaysLazyStart;

        // Strategy 2: Never lazy
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        tetree.setLazyEvaluation(false);

        long neverLazyStart = System.nanoTime();
        for (int i = 0; i < positions.size(); i++) {
            tetree.insert(positions.get(i), (byte) 10, contents.get(i));
        }
        long neverLazyTime = System.nanoTime() - neverLazyStart;

        // Strategy 3: Lazy only for bulk
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        tetree.setLazyEvaluation(true);

        long bulkLazyStart = System.nanoTime();
        tetree.insertBatch(positions, contents, (byte) 10);
        long bulkLazyTime = System.nanoTime() - bulkLazyStart;

        System.out.printf("Always lazy (single inserts): %.2f ms%n", alwaysLazyTime / 1_000_000.0);
        System.out.printf("Never lazy (single inserts): %.2f ms%n", neverLazyTime / 1_000_000.0);
        System.out.printf("Lazy bulk insert: %.2f ms%n", bulkLazyTime / 1_000_000.0);
        System.out.printf("\nSingle insert speedup (never/always): %.2fx%n", (double) alwaysLazyTime / neverLazyTime);
        System.out.printf("Bulk speedup (never single/lazy bulk): %.2fx%n", (double) neverLazyTime / bulkLazyTime);
    }
}
