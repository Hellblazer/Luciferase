/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified test for spatial query methods in Prism class.
 * 
 * @author hal.hildebrand
 */
public class PrismSpatialQueriesSimpleTest {
    
    private Prism<LongEntityID, String> prism;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    public void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        prism = new Prism<>(idGenerator, 10.0f, 10);
    }
    
    // Fixtures live in the S0 half (y < x, away from the diagonal) so they are unambiguously inside the level-0
    // root S0 search triangle. worldSize is 10, so a world Z of 2 normalizes to 0.2. (Luciferase-8sufr: these tests
    // were vacuous — they called the no-op Triangle.setBounds and asserted only assertNotNull. setBounds is now
    // removed and these assert real entity membership.)
    //
    // Semantics note: findInTriangularRegion filters at NODE granularity (trianglesIntersect on the prism node's
    // triangle, then Z overlap) — it is a conservative pre-filter, not a strict per-entity point-in-triangle test.
    // The S1-half fixtures are placed well away from the y=x diagonal so their level-5 nodes do not touch the S0
    // root; near-diagonal entities could appear in a cross-half query because adjacent nodes share the diagonal edge.

    @Test
    public void testFindInTriangularRegion() {
        var inLow = prism.insert(new Point3f(5f, 1f, 2f), (byte) 5, "inLow");  // S0 half, Z=2 (in range)
        var inMid = prism.insert(new Point3f(6f, 2f, 3f), (byte) 5, "inMid");  // S0 half, Z=3 (in range)
        var outZ  = prism.insert(new Point3f(5f, 1f, 8f), (byte) 5, "outZ");   // S0 half, Z=8 (out of Z range)
        var s1    = prism.insert(new Point3f(1f, 5f, 2f), (byte) 5, "s1");     // S1 half (y>x), excluded by triangle

        // Level-0 root triangle of the S0 half covers the entire lower-right (y<=x) region.
        Triangle searchTriangle = new Triangle(0, 0, 0, 0);
        Set<LongEntityID> results = prism.findInTriangularRegion(searchTriangle, 1, 4);

        assertTrue(results.contains(inLow), "S0 entity with Z in range must be found");
        assertTrue(results.contains(inMid), "S0 entity with Z in range must be found");
        assertFalse(results.contains(outZ), "entity with Z outside the layer must be excluded");
        assertFalse(results.contains(s1), "entity in the S1 half must be excluded by the S0 search triangle");
    }

    @Test
    public void testFindInVerticalLayer() {
        var bottom = prism.insert(new Point3f(5f, 1f, 1f), (byte) 5, "Bottom");  // Z=1
        var mid1   = prism.insert(new Point3f(6f, 2f, 3f), (byte) 5, "Middle1"); // Z=3
        var mid2   = prism.insert(new Point3f(7f, 3f, 3.5f), (byte) 5, "Middle2");// Z=3.5
        var top    = prism.insert(new Point3f(8f, 4f, 8f), (byte) 5, "Top");     // Z=8

        // Layer Z in [2,4]: excludes the Z=1 bottom and the Z=8 top; the vertical layer is a pure-Z filter.
        Set<LongEntityID> results = prism.findInVerticalLayer(2, 4);

        assertTrue(results.contains(mid1), "Z=3 entity is within the [2,4] layer");
        assertTrue(results.contains(mid2), "Z=3.5 entity is within the [2,4] layer");
        assertFalse(results.contains(bottom), "Z=1 entity is below the layer");
        assertFalse(results.contains(top), "Z=8 entity is above the layer");
    }

    @Test
    public void testFindInTriangularPrism() {
        var inside     = prism.insert(new Point3f(5f, 1f, 2f), (byte) 5, "Inside");     // S0, Z in range
        var outsideXY  = prism.insert(new Point3f(1f, 5f, 2f), (byte) 5, "OutsideXY");  // S1 half
        var outsideZ   = prism.insert(new Point3f(5f, 1f, 8f), (byte) 5, "OutsideZ");   // S0 but Z out
        var alsoInside = prism.insert(new Point3f(6f, 2f, 3f), (byte) 5, "AlsoInside"); // S0, Z in range

        Triangle searchTriangle = new Triangle(0, 0, 0, 0);
        Set<LongEntityID> results = prism.findInTriangularPrism(searchTriangle, 1, 4);

        assertTrue(results.contains(inside), "S0 entity with Z in range must be found");
        assertTrue(results.contains(alsoInside), "S0 entity with Z in range must be found");
        assertFalse(results.contains(outsideXY), "S1-half entity must be excluded by the S0 search triangle");
        assertFalse(results.contains(outsideZ), "entity with Z outside the range must be excluded");
    }

    @Test
    public void testEmptyResults() {
        // No entities inserted.
        Triangle searchTriangle = new Triangle(0, 0, 0, 0);

        Set<LongEntityID> results1 = prism.findInTriangularRegion(searchTriangle, 0, 10);
        Set<LongEntityID> results2 = prism.findInVerticalLayer(0, 10);
        Set<LongEntityID> results3 = prism.findInTriangularPrism(searchTriangle, 0, 10);

        assertTrue(results1.isEmpty());
        assertTrue(results2.isEmpty());
        assertTrue(results3.isEmpty());
    }
}