package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance gate for Luciferase-4pd: Luciferase {@link Tet} subdivision must be geometrically and
 * topologically identical to t8code's dtet — type k IS t8code type k everywhere (coordinates, child,
 * parent). This test embeds an INDEPENDENT port of the t8code dtet algorithm (with its own verbatim
 * copy of the connectivity tables, transcribed from origin/main {@code t8_dtet_connectivity.c} and
 * {@code t8_dtet_bits.c}) and asserts that {@code Tet} matches it over a DFS of a refined tree.
 *
 * <p>Validation method follows t8code's own ({@code t8_gtest_face_neigh.cxx}): reciprocity / DFS,
 * never a "shares >= N vertices" geometric assertion (Tet face neighbors are non-conforming and
 * share 0-3 vertices even for pure tets).
 */
class T8codeDtetOracleTest {

    private static final byte MAX = (byte) com.hellblazer.luciferase.lucien.Constants.getMaxRefinementLevel();

    // ---- t8code dtet ground-truth tables (origin/main, verbatim) ----

    // t8_dtet_index_to_bey_number[type][morton] -> bey id
    private static final int[][] INDEX_TO_BEY = {
        { 0, 1, 4, 5, 2, 7, 6, 3 }, { 0, 1, 5, 4, 7, 2, 6, 3 }, { 0, 4, 5, 1, 2, 7, 6, 3 },
        { 0, 1, 5, 4, 6, 7, 2, 3 }, { 0, 4, 5, 1, 6, 2, 7, 3 }, { 0, 5, 4, 1, 6, 7, 2, 3 } };

    // t8_dtet_type_of_child[type][bey] -> child type
    private static final int[][] TYPE_OF_CHILD = {
        { 0, 0, 0, 0, 4, 5, 2, 1 }, { 1, 1, 1, 1, 3, 2, 5, 0 }, { 2, 2, 2, 2, 0, 1, 4, 3 },
        { 3, 3, 3, 3, 5, 4, 1, 2 }, { 4, 4, 4, 4, 2, 3, 0, 5 }, { 5, 5, 5, 5, 1, 0, 3, 4 } };

    // t8_dtet_beyid_to_vertex[bey] -> parent vertex
    private static final int[] BEYID_TO_VERTEX = { 0, 1, 2, 3, 1, 1, 2, 2 };

    // t8_dtet_cid_type_to_parenttype[cid][type] -> parent type
    private static final int[][] CID_TYPE_TO_PARENTTYPE = {
        { 0, 1, 2, 3, 4, 5 }, { 0, 1, 1, 1, 0, 0 }, { 2, 2, 2, 3, 3, 3 }, { 1, 1, 2, 2, 2, 1 },
        { 5, 5, 4, 4, 4, 5 }, { 0, 0, 0, 5, 5, 5 }, { 4, 3, 3, 3, 4, 4 }, { 0, 1, 2, 3, 4, 5 } };

    private record OracleTet(int x, int y, int z, byte level, byte type) {}

    /** t8code t8_dtet_compute_coords: vertex coordinates of a tet. v0=anchor, v3=opposite cube corner. */
    private static int[] computeCoords(OracleTet t, int vertex) {
        int h = 1 << (MAX - t.level);
        int ei = t.type / 2;
        int ej = (ei + ((t.type % 2 == 0) ? 2 : 1)) % 3;
        int[] c = { t.x, t.y, t.z };
        if (vertex == 0) {
            return c;
        }
        c[ei] += h;
        if (vertex == 1) {
            return c;
        }
        if (vertex == 2) {
            c[ej] += h;
            return c;
        }
        // vertex == 3: opposite cube corner (all three dims)
        c[0] = t.x + h;
        c[1] = t.y + h;
        c[2] = t.z + h;
        return c;
    }

    /** t8code t8_dtet_child in Morton child order. */
    private static OracleTet child(OracleTet parent, int morton) {
        int bey = INDEX_TO_BEY[parent.type][morton];
        byte childLevel = (byte) (parent.level + 1);
        int ax, ay, az;
        if (bey == 0) {
            ax = parent.x;
            ay = parent.y;
            az = parent.z;
        } else {
            int v = BEYID_TO_VERTEX[bey];
            int[] vc = computeCoords(parent, v);
            ax = (parent.x + vc[0]) >> 1;
            ay = (parent.y + vc[1]) >> 1;
            az = (parent.z + vc[2]) >> 1;
        }
        byte childType = (byte) TYPE_OF_CHILD[parent.type][bey];
        return new OracleTet(ax, ay, az, childLevel, childType);
    }

    /** t8code t8_dtet_parent. */
    private static OracleTet parent(OracleTet t) {
        int h = 1 << (MAX - t.level);
        int cid = ((t.x & h) != 0 ? 1 : 0) | ((t.y & h) != 0 ? 2 : 0) | ((t.z & h) != 0 ? 4 : 0);
        byte ptype = (byte) CID_TYPE_TO_PARENTTYPE[cid][t.type];
        return new OracleTet(t.x & ~h, t.y & ~h, t.z & ~h, (byte) (t.level - 1), ptype);
    }

    private static Set<String> vertexSet(Point3i[] coords) {
        Set<String> s = new HashSet<>();
        for (Point3i p : coords) {
            s.add(p.x + "," + p.y + "," + p.z);
        }
        return s;
    }

    private static Set<String> vertexSet(OracleTet t) {
        Set<String> s = new HashSet<>();
        for (int v = 0; v < 4; v++) {
            int[] c = computeCoords(t, v);
            s.add(c[0] + "," + c[1] + "," + c[2]);
        }
        return s;
    }

    @Test
    void coordinatesMatchT8codeForAllTypesAtRoot() {
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, (byte) 1, type);
            var oracle = new OracleTet(0, 0, 0, (byte) 1, type);
            assertEquals(vertexSet(oracle), vertexSet(tet.coordinates()),
                         "coordinates() vertex set mismatch for type " + type);
        }
    }

    @Test
    void dfsChildParentCoordinatesMatchT8code() {
        int maxDepth = 5;
        Deque<OracleTet> work = new ArrayDeque<>();
        var root = new OracleTet(0, 0, 0, (byte) 0, (byte) 0);
        work.push(root);
        int visited = 0;
        while (!work.isEmpty()) {
            var o = work.pop();
            visited++;
            var tet = new Tet(o.x, o.y, o.z, o.level, o.type);

            // geometry: order-independent vertex-set equality
            assertEquals(vertexSet(o), vertexSet(tet.coordinates()),
                         "coordinates mismatch at " + o);

            if (o.level >= maxDepth) {
                continue;
            }
            for (int m = 0; m < 8; m++) {
                var oChild = child(o, m);
                var tChild = tet.child(m);
                assertEquals(oChild.type, tChild.type(), "child type mismatch parent=" + o + " morton=" + m);
                assertEquals(oChild.x, tChild.x(), "child x mismatch parent=" + o + " morton=" + m);
                assertEquals(oChild.y, tChild.y(), "child y mismatch parent=" + o + " morton=" + m);
                assertEquals(oChild.z, tChild.z(), "child z mismatch parent=" + o + " morton=" + m);
                assertEquals(oChild.level, tChild.l(), "child level mismatch parent=" + o + " morton=" + m);

                // parent round-trip: parent(child) == self (both oracle and Tet)
                var oParent = parent(oChild);
                assertEquals(o.type, oParent.type, "oracle parent type round-trip failed at " + oChild);
                var tParent = tChild.parent();
                assertEquals(o.type, tParent.type(), "Tet.parent type round-trip failed at " + oChild);
                assertEquals(o.x, tParent.x(), "Tet.parent x round-trip failed at " + oChild);
                assertEquals(o.y, tParent.y(), "Tet.parent y round-trip failed at " + oChild);
                assertEquals(o.z, tParent.z(), "Tet.parent z round-trip failed at " + oChild);

                work.push(oChild);
            }
        }
        assertEquals((int) ((Math.pow(8, maxDepth + 1) - 1) / 7), visited, "DFS visited count");
    }

    /** Generic point-in-tetrahedron via same-side-of-face orientation (closed convention, >= 0). */
    private static boolean pointInTet(Point3i[] v, double px, double py, double pz) {
        // For each face (opposite vertex i), p must be on the same closed half-space as vertex i.
        int[][] faces = { { 1, 2, 3, 0 }, { 0, 2, 3, 1 }, { 0, 1, 3, 2 }, { 0, 1, 2, 3 } };
        for (int[] f : faces) {
            Point3i a = v[f[0]], b = v[f[1]], c = v[f[2]], opp = v[f[3]];
            double nx = (double) (b.y - a.y) * (c.z - a.z) - (double) (b.z - a.z) * (c.y - a.y);
            double ny = (double) (b.z - a.z) * (c.x - a.x) - (double) (b.x - a.x) * (c.z - a.z);
            double nz = (double) (b.x - a.x) * (c.y - a.y) - (double) (b.y - a.y) * (c.x - a.x);
            double sOpp = nx * (opp.x - a.x) + ny * (opp.y - a.y) + nz * (opp.z - a.z);
            double sP = nx * (px - a.x) + ny * (py - a.y) + nz * (pz - a.z);
            if (sOpp == 0) {
                continue; // degenerate face normal; skip (axis-aligned Kuhn faces are non-degenerate)
            }
            // p must not be strictly on the far side from opp
            if (Math.signum(sP) != Math.signum(sOpp) && sP != 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    void contains12DOPConsistentWithCoordinates() {
        // contains12DOP must agree with a generic point-in-tet over the actual coordinates() geometry,
        // independently verifying the per-type ordering permutation (RDR-010 Luciferase-4pd).
        java.util.Random rnd = new java.util.Random(123456789L);
        int level = 3;
        int cell = 1 << (MAX - level);
        for (int iter = 0; iter < 4000; iter++) {
            byte type = (byte) rnd.nextInt(6);
            // anchor on the grid at this level
            int ax = (rnd.nextInt(8)) * cell;
            int ay = (rnd.nextInt(8)) * cell;
            int az = (rnd.nextInt(8)) * cell;
            var tet = new Tet(ax, ay, az, (byte) level, type);
            var coords = tet.coordinates();
            // sample a point within the cell AABB
            double px = ax + rnd.nextDouble() * cell;
            double py = ay + rnd.nextDouble() * cell;
            double pz = az + rnd.nextDouble() * cell;
            boolean expected = pointInTet(coords, px, py, pz);
            boolean actual = tet.contains12DOP((float) px, (float) py, (float) pz);
            // Allow boundary disagreement only when point is on a face (tolerance), else must match.
            if (expected != actual) {
                // re-test slightly inside toward centroid to avoid float boundary noise
                double cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0;
                double cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0;
                double cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0;
                double t = 1e-3;
                double ipx = px + (cx - px) * t, ipy = py + (cy - py) * t, ipz = pz + (cz - pz) * t;
                boolean e2 = pointInTet(coords, ipx, ipy, ipz);
                boolean a2 = tet.contains12DOP((float) ipx, (float) ipy, (float) ipz);
                assertEquals(e2, a2, "contains12DOP mismatch type=" + type + " p=(" + px + "," + py + "," + pz
                                    + ") nudged-to-interior");
            }
        }
    }

    @Test
    void tmIndexRoundTripAndComputeTypeMatchOracleAtDepth() {
        // Regold-review finding: tmIndex() and computeType() must use the same t8code upward type walk
        // as parent(). Validate at depth >= 2 against the independent oracle parent chain, and that
        // tmIndex() round-trips through the decoder.
        int maxDepth = 5;
        Deque<OracleTet> work = new ArrayDeque<>();
        work.push(new OracleTet(0, 0, 0, (byte) 0, (byte) 0));
        while (!work.isEmpty()) {
            var o = work.pop();
            var tet = new Tet(o.x, o.y, o.z, o.level, o.type);

            // computeType at every ancestor level must equal the oracle parent-chain type.
            var anc = o;
            for (byte lvl = o.level; lvl >= 0; lvl--) {
                assertEquals(anc.type, tet.computeType(lvl),
                             "computeType(" + lvl + ") mismatch for " + o);
                if (lvl > 0) {
                    anc = parent(anc);
                }
            }

            // tmIndex round-trip: decode(tmIndex) == this tet (encodes the per-level type path).
            var key = tet.tmIndex();
            var decoded = Tet.tetrahedron(key);
            assertEquals(o.x, decoded.x(), "tmIndex round-trip x at " + o);
            assertEquals(o.y, decoded.y(), "tmIndex round-trip y at " + o);
            assertEquals(o.z, decoded.z(), "tmIndex round-trip z at " + o);
            assertEquals(o.level, decoded.l(), "tmIndex round-trip level at " + o);
            assertEquals(o.type, decoded.type(), "tmIndex round-trip type at " + o);

            if (o.level >= maxDepth) {
                continue;
            }
            for (int m = 0; m < 8; m++) {
                work.push(child(o, m));
            }
        }
    }

    @Test
    void locateMethodsAgreeAndContainPoint() {
        java.util.Random rnd = new java.util.Random(987654321L);
        int maxCoord = 1 << MAX;
        for (int iter = 0; iter < 3000; iter++) {
            byte level = (byte) (1 + rnd.nextInt(8));
            float px = rnd.nextInt(maxCoord);
            float py = rnd.nextInt(maxCoord);
            float pz = rnd.nextInt(maxCoord);
            var s0 = Tet.locatePointS0Tree(px, py, pz, level);
            var bey = Tet.locatePointBeyRefinementFromRoot(px, py, pz, level);
            if (bey == null) {
                continue;
            }
            // Both methods must return a tet that geometrically contains the point — the core invariant.
            assertTrue(s0.contains12DOP(px, py, pz), "locatePointS0Tree result must contain the point");
            assertTrue(bey.contains12DOP(px, py, pz), "locatePointBey result must contain the point");
            // Type equality only required for strict-interior points: on a shared face (two equal local
            // coords) several types validly contain the point under the closed-simplex convention, and
            // the two methods may legitimately pick different ones.
            int h = 1 << (MAX - level);
            float ux = px - ((int) (px / h)) * h, uy = py - ((int) (py / h)) * h, uz = pz - ((int) (pz / h)) * h;
            boolean strictInterior = ux != uy && uy != uz && ux != uz;
            if (strictInterior) {
                assertEquals(s0.x(), bey.x(), "locate anchor x disagreement");
                assertEquals(s0.y(), bey.y(), "locate anchor y disagreement");
                assertEquals(s0.z(), bey.z(), "locate anchor z disagreement");
                assertEquals(s0.type(), bey.type(),
                             "locate type disagreement at (" + px + "," + py + "," + pz + ") level " + level);
            }
        }
    }

    @Test
    void parentTypeMatchesT8codeDirectTable() {
        // Independent of the round-trip: every node's computed parent type must equal the t8code
        // cid_type_to_parenttype direct lookup.
        int maxDepth = 4;
        Deque<OracleTet> work = new ArrayDeque<>();
        work.push(new OracleTet(0, 0, 0, (byte) 0, (byte) 0));
        while (!work.isEmpty()) {
            var o = work.pop();
            if (o.level >= maxDepth) {
                continue;
            }
            for (int m = 0; m < 8; m++) {
                var oChild = child(o, m);
                var tChild = new Tet(oChild.x, oChild.y, oChild.z, oChild.level, oChild.type);
                int h = 1 << (MAX - oChild.level);
                int cid = ((oChild.x & h) != 0 ? 1 : 0) | ((oChild.y & h) != 0 ? 2 : 0)
                          | ((oChild.z & h) != 0 ? 4 : 0);
                byte expected = (byte) CID_TYPE_TO_PARENTTYPE[cid][oChild.type];
                assertEquals(expected, tChild.parent().type(),
                             "parent type vs t8code direct table mismatch at " + oChild);
                work.push(oChild);
            }
        }
    }
}
