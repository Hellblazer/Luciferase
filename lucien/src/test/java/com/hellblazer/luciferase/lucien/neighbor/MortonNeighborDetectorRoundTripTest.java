/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip correctness tests for MortonNeighborDetector.
 *
 * Verifies that: (1) findFaceNeighbors produces keys whose decoded coordinates
 * differ by exactly one cellSize on exactly one axis; (2) re-decoding the
 * neighbor key reproduces the expected offset cell (Morton code + level are
 * mutually consistent); (3) isBoundaryElement and findNeighborsWithOffsets use
 * the same raw-grid-unit convention; (4) the public decodeCoordinates method
 * returns the same raw coords as MortonCurve.decode.
 */
class MortonNeighborDetectorRoundTripTest {

    private MortonNeighborDetector detector;

    @BeforeEach
    void setUp() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, Point3f>(idGenerator);
        detector = new MortonNeighborDetector(octree);
    }

    // -----------------------------------------------------------------------
    // 1. Face-neighbor round-trip: coords differ by exactly cellSize on one axis
    // -----------------------------------------------------------------------

    @Test
    void testFaceNeighborCoordsOffsetByOneCellSize() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        // Use a cell well away from all boundaries
        var key = MortonKey.fromCellIndices(10, 10, 10, level);
        int[] originCoords = MortonCurve.decode(key.getMortonCode());

        List<MortonKey> faceNeighbors = detector.findFaceNeighbors(key);
        assertEquals(6, faceNeighbors.size(), "Interior cell should have 6 face neighbors");

        int[][] expectedOffsets = {
            { cellSize, 0, 0 }, { -cellSize, 0, 0 },
            { 0, cellSize, 0 }, { 0, -cellSize, 0 },
            { 0, 0, cellSize }, { 0, 0, -cellSize }
        };

        for (int i = 0; i < 6; i++) {
            var neighbor = faceNeighbors.get(i);
            assertEquals(level, neighbor.getLevel(),
                         "Neighbor must be at same level as source");

            int[] neighborCoords = MortonCurve.decode(neighbor.getMortonCode());
            assertEquals(originCoords[0] + expectedOffsets[i][0], neighborCoords[0],
                         "Face neighbor " + i + " X mismatch");
            assertEquals(originCoords[1] + expectedOffsets[i][1], neighborCoords[1],
                         "Face neighbor " + i + " Y mismatch");
            assertEquals(originCoords[2] + expectedOffsets[i][2], neighborCoords[2],
                         "Face neighbor " + i + " Z mismatch");
        }
    }

    // -----------------------------------------------------------------------
    // 2. Re-decoding neighbor key reproduces the offset cell (Morton consistent)
    // -----------------------------------------------------------------------

    @Test
    void testFaceNeighborKeyRoundTrip() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        var key = MortonKey.fromCellIndices(10, 10, 10, level);

        for (var neighbor : detector.findFaceNeighbors(key)) {
            // Re-encode neighbor coords and verify they reproduce the same Morton key
            int[] coords = MortonCurve.decode(neighbor.getMortonCode());
            long reEncoded = MortonCurve.encode(coords[0], coords[1], coords[2]);
            assertEquals(neighbor.getMortonCode(), reEncoded,
                         "Re-encoding neighbor coords must reproduce the same Morton code");

            // Neighbor coords must be aligned to cellSize grid
            assertEquals(0, coords[0] % cellSize, "Neighbor X must be cell-aligned");
            assertEquals(0, coords[1] % cellSize, "Neighbor Y must be cell-aligned");
            assertEquals(0, coords[2] % cellSize, "Neighbor Z must be cell-aligned");
        }
    }

    // -----------------------------------------------------------------------
    // 3. isBoundaryElement and findNeighborsWithOffsets use the same convention
    // -----------------------------------------------------------------------

    @Test
    void testBoundaryConsistencyWithNeighborCount() {
        // A boundary cell in +X should produce only 5 face neighbors (not 6)
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        int maxCellIndex = (Constants.MAX_COORD + 1) / cellSize - 1;

        // Cell at maximum X boundary
        var keyAtMaxX = MortonKey.fromCellIndices(maxCellIndex, 10, 10, level);

        assertTrue(detector.isBoundaryElement(keyAtMaxX, NeighborDetector.Direction.POSITIVE_X),
                   "Max-X cell must be detected as +X boundary");
        assertFalse(detector.isBoundaryElement(keyAtMaxX, NeighborDetector.Direction.NEGATIVE_X),
                    "Max-X cell must not be detected as -X boundary");

        // findFaceNeighbors must omit the out-of-bounds +X neighbor
        var neighbors = detector.findFaceNeighbors(keyAtMaxX);
        assertEquals(5, neighbors.size(),
                     "Cell at max-X boundary should have 5 face neighbors, not 6");

        // None of the returned neighbors should have X > MAX_COORD
        for (var neighbor : neighbors) {
            int[] coords = MortonCurve.decode(neighbor.getMortonCode());
            assertTrue(coords[0] <= Constants.MAX_COORD,
                       "No neighbor X coordinate may exceed MAX_COORD");
        }
    }

    @Test
    void testBoundaryConsistencyAtOrigin() {
        // Cell at origin: 3 face neighbors missing (negative directions)
        byte level = 10;
        var key = MortonKey.fromCellIndices(0, 0, 0, level);

        assertTrue(detector.isBoundaryElement(key, NeighborDetector.Direction.NEGATIVE_X));
        assertTrue(detector.isBoundaryElement(key, NeighborDetector.Direction.NEGATIVE_Y));
        assertTrue(detector.isBoundaryElement(key, NeighborDetector.Direction.NEGATIVE_Z));
        assertFalse(detector.isBoundaryElement(key, NeighborDetector.Direction.POSITIVE_X));
        assertFalse(detector.isBoundaryElement(key, NeighborDetector.Direction.POSITIVE_Y));
        assertFalse(detector.isBoundaryElement(key, NeighborDetector.Direction.POSITIVE_Z));

        var neighbors = detector.findFaceNeighbors(key);
        assertEquals(3, neighbors.size(),
                     "Origin cell should have exactly 3 face neighbors");
    }

    // -----------------------------------------------------------------------
    // 4. decodeCoordinates returns raw MortonCurve.decode values (no shift)
    // -----------------------------------------------------------------------

    @Test
    void testDecodeCoordinatesReturnsRawCoords() {
        for (byte level = 1; level <= 21; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            int maxCellIndex = Math.max(0, (Constants.MAX_COORD + 1) / cellSize - 1);
            // Pick a middle cell
            int idx = Math.min(10, maxCellIndex);
            var key = MortonKey.fromCellIndices(idx, idx, idx, level);

            int[] expected = MortonCurve.decode(key.getMortonCode());
            int[] actual   = detector.decodeCoordinates(key);

            assertArrayEquals(expected, actual,
                              "decodeCoordinates must equal MortonCurve.decode at level " + level);
            // Raw coord must be a multiple of cellSize (cell-aligned)
            assertEquals(0, actual[0] % cellSize,
                         "Raw X coord must be cell-aligned at level " + level);
            assertEquals(0, actual[1] % cellSize,
                         "Raw Y coord must be cell-aligned at level " + level);
            assertEquals(0, actual[2] % cellSize,
                         "Raw Z coord must be cell-aligned at level " + level);
        }
    }

    // -----------------------------------------------------------------------
    // 5. Edge-neighbor round-trip: each result differs by cellSize on exactly TWO axes
    // -----------------------------------------------------------------------

    /**
     * findEdgeNeighbors returns 18 keys (6 face + 12 edge).
     * For each of the 12 pure-edge entries, exactly TWO axis deltas are non-zero
     * and each equals ±cellSize.  Re-decoding must reproduce the expected coords.
     */
    @Test
    void testEdgeNeighborCoordsOffsetOnExactlyTwoAxes() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        // Interior cell — all 18 neighbors exist
        var key = MortonKey.fromCellIndices(10, 10, 10, level);
        int[] origin = MortonCurve.decode(key.getMortonCode());

        List<MortonKey> edgeNeighbors = detector.findEdgeNeighbors(key);
        assertEquals(18, edgeNeighbors.size(), "Interior cell should have 18 edge+face neighbors");

        // Classify: face (1 non-zero delta) vs edge (2 non-zero deltas); no vertex (3)
        int faceCount = 0, edgeCount = 0;
        for (var neighbor : edgeNeighbors) {
            assertEquals(level, neighbor.getLevel(), "Neighbor level must match source");

            // Involution: re-encode decoded coords reproduces the same Morton code
            int[] nc = MortonCurve.decode(neighbor.getMortonCode());
            long reEncoded = MortonCurve.encode(nc[0], nc[1], nc[2]);
            assertEquals(neighbor.getMortonCode(), reEncoded, "Re-encoding must reproduce Morton code");

            int dx = Math.abs(nc[0] - origin[0]);
            int dy = Math.abs(nc[1] - origin[1]);
            int dz = Math.abs(nc[2] - origin[2]);

            // Each non-zero delta must be exactly cellSize
            if (dx != 0) assertEquals(cellSize, dx, "Non-zero X delta must equal cellSize");
            if (dy != 0) assertEquals(cellSize, dy, "Non-zero Y delta must equal cellSize");
            if (dz != 0) assertEquals(cellSize, dz, "Non-zero Z delta must equal cellSize");

            int nonZeroAxes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
            assertTrue(nonZeroAxes == 1 || nonZeroAxes == 2,
                       "findEdgeNeighbors must return only face (1) or edge (2) offsets; got " + nonZeroAxes);
            if (nonZeroAxes == 1) faceCount++;
            else edgeCount++;
        }
        assertEquals(6, faceCount, "Exactly 6 face neighbors in findEdgeNeighbors result");
        assertEquals(12, edgeCount, "Exactly 12 edge neighbors in findEdgeNeighbors result");
    }

    // -----------------------------------------------------------------------
    // 6. Vertex-neighbor round-trip: each result differs by cellSize on exactly THREE axes
    // -----------------------------------------------------------------------

    /**
     * findVertexNeighbors returns 26 keys (6 face + 12 edge + 8 vertex).
     * For each of the 8 pure-vertex entries, exactly THREE axis deltas are non-zero
     * and each equals ±cellSize.  Re-decoding must reproduce the expected coords.
     */
    @Test
    void testVertexNeighborCoordsOffsetOnExactlyThreeAxes() {
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        // Interior cell — all 26 neighbors exist
        var key = MortonKey.fromCellIndices(10, 10, 10, level);
        int[] origin = MortonCurve.decode(key.getMortonCode());

        List<MortonKey> vertexNeighbors = detector.findVertexNeighbors(key);
        assertEquals(26, vertexNeighbors.size(), "Interior cell should have 26 vertex+edge+face neighbors");

        int faceCount = 0, edgeCount = 0, vertexCount = 0;
        for (var neighbor : vertexNeighbors) {
            assertEquals(level, neighbor.getLevel(), "Neighbor level must match source");

            int[] nc = MortonCurve.decode(neighbor.getMortonCode());
            long reEncoded = MortonCurve.encode(nc[0], nc[1], nc[2]);
            assertEquals(neighbor.getMortonCode(), reEncoded, "Re-encoding must reproduce Morton code");

            int dx = Math.abs(nc[0] - origin[0]);
            int dy = Math.abs(nc[1] - origin[1]);
            int dz = Math.abs(nc[2] - origin[2]);

            if (dx != 0) assertEquals(cellSize, dx, "Non-zero X delta must equal cellSize");
            if (dy != 0) assertEquals(cellSize, dy, "Non-zero Y delta must equal cellSize");
            if (dz != 0) assertEquals(cellSize, dz, "Non-zero Z delta must equal cellSize");

            int nonZeroAxes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
            assertTrue(nonZeroAxes >= 1 && nonZeroAxes <= 3,
                       "findVertexNeighbors must return face/edge/vertex offsets; got " + nonZeroAxes);
            if (nonZeroAxes == 1) faceCount++;
            else if (nonZeroAxes == 2) edgeCount++;
            else vertexCount++;
        }
        assertEquals(6, faceCount, "Exactly 6 face neighbors in findVertexNeighbors result");
        assertEquals(12, edgeCount, "Exactly 12 edge neighbors in findVertexNeighbors result");
        assertEquals(8, vertexCount, "Exactly 8 vertex neighbors in findVertexNeighbors result");
    }

    // -----------------------------------------------------------------------
    // 7. Multi-level face neighbor invariant: coords differ by cellSize at level
    // -----------------------------------------------------------------------

    @Test
    void testFaceNeighborAtMultipleLevels() {
        for (byte level = 3; level <= 15; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            int maxCellIndex = (Constants.MAX_COORD + 1) / cellSize - 1;
            int idx = Math.min(10, maxCellIndex - 1);
            if (idx < 1) continue; // skip degenerate levels

            var key = MortonKey.fromCellIndices(idx, idx, idx, level);
            int[] originCoords = MortonCurve.decode(key.getMortonCode());

            var faceNeighbors = detector.findFaceNeighbors(key);
            assertEquals(6, faceNeighbors.size(),
                         "Interior cell at level " + level + " should have 6 face neighbors");

            for (var neighbor : faceNeighbors) {
                assertEquals(level, neighbor.getLevel(),
                             "Neighbor level must match source at level " + level);
                int[] nc = MortonCurve.decode(neighbor.getMortonCode());
                // The Manhattan distance in grid coords must be exactly cellSize
                int dx = Math.abs(nc[0] - originCoords[0]);
                int dy = Math.abs(nc[1] - originCoords[1]);
                int dz = Math.abs(nc[2] - originCoords[2]);
                int manhattan = dx + dy + dz;
                assertEquals(cellSize, manhattan,
                             "Face neighbor at level " + level + " must be exactly one cellSize away");
            }
        }
    }

    // -----------------------------------------------------------------------
    // 8. findNeighborsWithOwners: fail-loud — no partition resolver wired
    // -----------------------------------------------------------------------

    /**
     * MortonNeighborDetector has no partition/ownership resolver.
     * Returning isLocal=true with rank=0 for every neighbor would silently
     * degrade the distributed ghost layer. The method must throw
     * UnsupportedOperationException (fail-loud) until a real resolver is wired.
     */
    @Test
    void testFindNeighborsWithOwnersFaceThrowsUnsupported() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        assertThrows(UnsupportedOperationException.class,
                     () -> detector.findNeighborsWithOwners(key, GhostType.FACES),
                     "findNeighborsWithOwners(FACES) must throw — no owner-resolver wired");
    }

    @Test
    void testFindNeighborsWithOwnersEdgeThrowsUnsupported() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        assertThrows(UnsupportedOperationException.class,
                     () -> detector.findNeighborsWithOwners(key, GhostType.EDGES),
                     "findNeighborsWithOwners(EDGES) must throw — no owner-resolver wired");
    }

    @Test
    void testFindNeighborsWithOwnersVertexThrowsUnsupported() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        assertThrows(UnsupportedOperationException.class,
                     () -> detector.findNeighborsWithOwners(key, GhostType.VERTICES),
                     "findNeighborsWithOwners(VERTICES) must throw — no owner-resolver wired");
    }

    // -----------------------------------------------------------------------
    // 9. Bounds fix (.146): maximum-coordinate cell IS returned as a neighbor
    // -----------------------------------------------------------------------

    /**
     * Regression test for bead Luciferase-7wzml.146.
     *
     * At the finest level (21), each cell has cellSize=1 and the maximum valid
     * cell origin is exactly Constants.MAX_COORD (2097151).  The cell at index
     * (MAX_COORD-1, 0, 0) has its +X face neighbor at (MAX_COORD, 0, 0), which
     * is a valid in-bounds cell.  A strict {@code nx < maxCoordinate} bound
     * would reject that neighbor (off-by-one).  The correct inclusive bound
     * {@code nx <= Constants.MAX_COORD} must accept it.
     */
    @Test
    void testMaxCoordinateCellReturnedAsNeighborOfAdjacentCell() {
        // Use a level where cellSize > 1 so that mid-range cell indices are available.
        // At level 10: cellSize=2048, max cell index = MAX_COORD / 2048 = 1023.
        // Cell (1022, 10, 10): all six face neighbors are in bounds:
        //   +X -> 1023*2048 = 2095104 <= MAX_COORD  ✓
        //   -X -> 1021*2048             in range      ✓
        //   ±Y, ±Z -> 9*2048 and 11*2048 both in range ✓
        // The +X neighbor world coordinate 2095104 is NOT MAX_COORD itself, but the
        // critical bound is that it is <= MAX_COORD.  We also test at level 21
        // (cellSize=1) where the +X neighbor of cell (MAX_COORD-1, 10, 10) has
        // world-X = MAX_COORD exactly — the case the off-by-one bud excluded.
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        int maxCellIndex = Constants.MAX_COORD / cellSize; // 1023

        // Cell one step below the max in X; interior in Y and Z.
        var penultimateX = MortonKey.fromCellIndices(maxCellIndex - 1, 10, 10, level);
        var faceNeighbors = detector.findFaceNeighbors(penultimateX);

        assertEquals(6, faceNeighbors.size(),
                     "Cell one step from the max-X boundary should have exactly 6 face neighbors");

        // The +X neighbor is at world coord (maxCellIndex * cellSize) — must be present.
        int expectedMaxX = maxCellIndex * cellSize;
        boolean foundMaxX = false;
        for (var neighbor : faceNeighbors) {
            int[] nc = MortonCurve.decode(neighbor.getMortonCode());
            if (nc[0] == expectedMaxX) {
                foundMaxX = true;
                assertEquals(level, neighbor.getLevel(), "Max-boundary neighbor must be at same level");
            }
        }
        assertTrue(foundMaxX,
                   "The cell at the maximum valid X coordinate (" + expectedMaxX + ") must be returned "
                   + "as the +X neighbor — a strict '<' bound incorrectly excludes it (bead Luciferase-7wzml.146)");

        // Also verify at level 21 (cellSize=1) where the +X neighbor's world coord
        // is exactly Constants.MAX_COORD = 2097151, the most direct trigger of .146.
        byte fineLevel = 21;
        int maxFineIdx = Constants.MAX_COORD; // 2097151; world coord = idx * 1
        // Use y=z=10 to keep the cell interior on those axes.
        var penultimateFine = MortonKey.fromCellIndices(maxFineIdx - 1, 10, 10, fineLevel);
        var fineNeighbors = detector.findFaceNeighbors(penultimateFine);

        assertEquals(6, fineNeighbors.size(),
                     "At level 21, cell (MAX_COORD-1, 10, 10) should have 6 face neighbors");

        boolean foundExactMaxCoord = false;
        for (var neighbor : fineNeighbors) {
            int[] nc = MortonCurve.decode(neighbor.getMortonCode());
            if (nc[0] == Constants.MAX_COORD && nc[1] == 10 && nc[2] == 10) {
                foundExactMaxCoord = true;
            }
        }
        assertTrue(foundExactMaxCoord,
                   "At level 21, the cell whose world-X equals exactly MAX_COORD (" + Constants.MAX_COORD + ") "
                   + "must be returned as the +X neighbor (bead Luciferase-7wzml.146)");
    }

    // -----------------------------------------------------------------------
    // 10. Existence contract (.147): geometric neighbors vs octree-occupancy
    // -----------------------------------------------------------------------

    /**
     * Documents and verifies the geometric-neighbor contract (bead Luciferase-7wzml.147).
     *
     * {@code findFaceNeighbors} returns <em>potential</em> geometric neighbors;
     * existence in the octree is NOT checked.  This test uses a sparse octree
     * (only the source cell is inserted) to confirm that neighbor keys for
     * unoccupied cells are still returned, and then demonstrates that callers
     * who need only occupied neighbors must explicitly filter via
     * {@link com.hellblazer.luciferase.lucien.SpatialIndex#hasNode}.
     */
    @Test
    void testGeometricNeighborsReturnedRegardlessOfOctreeOccupancy() {
        var idGenerator = new SequentialLongIDGenerator();
        var sparseOctree = new Octree<LongEntityID, Point3f>(idGenerator);
        var sparseDetector = new MortonNeighborDetector(sparseOctree);

        byte level = 10;
        // Insert only the source cell — all its neighbors are geometrically valid
        // but none will exist in the octree.
        var sourcePoint = new Point3f(10 * Constants.lengthAtLevel(level),
                                       10 * Constants.lengthAtLevel(level),
                                       10 * Constants.lengthAtLevel(level));
        sparseOctree.insert(sourcePoint, level, sourcePoint);

        var sourceKey = MortonKey.fromCellIndices(10, 10, 10, level);

        // All 6 face neighbors are returned (geometric contract).
        var geometricNeighbors = sparseDetector.findFaceNeighbors(sourceKey);
        assertEquals(6, geometricNeighbors.size(),
                     "findFaceNeighbors must return 6 geometric neighbors regardless of octree occupancy");

        // None of those neighbor positions has a node in the sparse octree.
        long existingNeighborCount = geometricNeighbors.stream()
                                                       .filter(sparseOctree::hasNode)
                                                       .count();
        assertEquals(0, existingNeighborCount,
                     "No geometric neighbor should exist in the sparse octree — "
                     + "callers wanting only occupied neighbors must filter via hasNode");

        // Insert one neighbor and verify hasNode correctly identifies it.
        var neighborPoint = new Point3f(11 * Constants.lengthAtLevel(level),
                                         10 * Constants.lengthAtLevel(level),
                                         10 * Constants.lengthAtLevel(level));
        sparseOctree.insert(neighborPoint, level, neighborPoint);

        long existingAfterInsert = geometricNeighbors.stream()
                                                     .filter(sparseOctree::hasNode)
                                                     .count();
        assertEquals(1, existingAfterInsert,
                     "Exactly one geometric neighbor should now exist after inserting it — "
                     + "hasNode is the correct existence filter (bead Luciferase-7wzml.147)");
    }
}
