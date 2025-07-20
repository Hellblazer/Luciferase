/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.sentry;

import com.hellblazer.luciferase.common.IdentitySet;
import com.hellblazer.luciferase.geometry.Geometry;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;
import java.util.*;

import static com.hellblazer.sentry.V.*;

/**
 * An oriented, delaunay tetrahedral cell. The vertices of the tetrahedron are A, B, C and D. The vertices {A, B, C} are
 * positively oriented with respect to the fourth vertex D.
 * <p>
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class Tetrahedron implements Iterable<OrientedFace> {
    /**
     * Matrix used to determine the next neighbor in a voronoi face traversal
     */
    private static final V[][][]     VORONOI_FACE_NEXT   = {
    { null, { null, null, D, C }, { null, D, null, B }, { null, C, B, null } },
    { { null, null, D, C }, null, { D, null, null, A }, { C, null, A, null } },
    { { null, D, null, B }, { D, null, null, A }, null, { B, A, null, null } },
    { { null, C, B, null }, { C, null, A, null }, { B, A, null, null }, null } };
    /**
     * Matrix used to determine the origin neighbor in a vororoni face traversal
     */
    private static final V[][]       VORONOI_FACE_ORIGIN = { { null, C, D, B }, { C, null, D, A }, { D, A, null, B },
                                                             { B, C, A, null } };
    /**
     * Vertex A
     */
    private              Vertex      a;
    /**
     * Vertx B
     */
    private              Vertex      b;
    /**
     * Vertex C
     */
    private              Vertex      c;
    /**
     * Vertex D
     */
    private              Vertex      d;
    /**
     * The neighboring tetrahedron opposite of vertex A
     */
    private              Tetrahedron nA;
    /**
     * The neighboring tetrahedron opposite of vertex B
     */
    private              Tetrahedron nB;
    /**
     * The neighboring tetrahedron opposite of vertex C
     */
    private              Tetrahedron nC;
    /**
     * The neighboring tetrahedron opposite of vertex D
     */
    private              Tetrahedron nD;
    
    /**
     * Flag indicating if this tetrahedron is degenerate (volume < threshold)
     */
    private              boolean     isDegenerate = false;
    
    /**
     * Flag indicating if this tetrahedron is near-degenerate
     */
    private              boolean     isNearDegenerate = false;
    
    /**
     * Thresholds for degenerate detection
     */
    private static final float DEGENERATE_THRESHOLD = 1e-10f;
    private static final float NEAR_DEGENERATE_THRESHOLD = 1e-6f;

    /**
     * Construct a tetrahedron from the four vertices
     *
     * @param x
     * @param y
     * @param z
     * @param w
     */
    public Tetrahedron(Vertex x, Vertex y, Vertex z, Vertex w) {
        a = x;
        b = y;
        c = z;
        d = w;

        if (a != null) a.setAdjacent(this);
        if (b != null) b.setAdjacent(this);
        if (c != null) c.setAdjacent(this);
        if (d != null) d.setAdjacent(this);
        
        // Check degeneracy after construction
        updateDegeneracy();
    }

    /**
     * Construct a tetrahedron from the array of four vertices
     *
     * @param vertices
     */
    public Tetrahedron(Vertex[] vertices) {
        this(vertices != null ? vertices[0] : null, 
             vertices != null ? vertices[1] : null, 
             vertices != null ? vertices[2] : null, 
             vertices != null ? vertices[3] : null);
    }

    /**
     * Answer +1 if the orientation of the query is positive with respect to the plane defined by {a, b, c}, -1 if
     * negative, or 0 if the test point is coplanar
     * <p>
     *
     * @param query - the point to query
     * @param a     , b, c - the points defining the plane
     * @return +1 if the orientation of the query point is positive with respect to the plane, -1 if negative and 0 if
     * the test point is coplanar
     */
    public static double orientation(Tuple3f query, Tuple3f a, Tuple3f b, Tuple3f c) {
        var result = Geometry.leftOfPlaneFast(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y, query.z);
        return Math.signum(result);
    }

    /**
     * Add the four faces defined by the tetrahedron to the list of faces
     *
     * @param faces
     */
    public void addFaces(List<Vertex[]> faces) {
        faces.add(new Vertex[] { a, d, b });
        faces.add(new Vertex[] { b, c, a });
        faces.add(new Vertex[] { c, b, d });
        faces.add(new Vertex[] { d, a, c });
    }

    public Point3f center() {
        float[] center = new float[3];
        Geometry.centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        return new Point3f(center[0], center[1], center[2]);
    }

    /**
     * Perform the 1 -> 4 bistellar flip. This produces 4 new tetrahedron from the original tetrahdron, by inserting the
     * new point in the interior of the receiver tetrahedron. The star set of the newly inserted point is pushed onto
     * the supplied stack.
     * <p>
     *
     * @param n    - the inserted point
     * @param ears - the stack of oriented faces that make up the ears of the inserted point
     * @return one of the four new tetrahedra
     */
    public Tetrahedron flip1to4(Vertex n, List<OrientedFace> ears) {
        var pool = TetrahedronPool.getInstance();
        var t0 = pool.acquire(a, b, c, n);
        var t1 = pool.acquire(a, d, b, n);
        var t2 = pool.acquire(a, c, d, n);
        var t3 = pool.acquire(b, d, c, n);

        t0.setNeighborA(t3);
        t0.setNeighborB(t2);
        t0.setNeighborC(t1);

        t1.setNeighborA(t3);
        t1.setNeighborB(t0);
        t1.setNeighborC(t2);

        t2.setNeighborA(t3);
        t2.setNeighborB(t1);
        t2.setNeighborC(t0);

        t3.setNeighborA(t2);
        t3.setNeighborB(t0);
        t3.setNeighborC(t1);

        patch(D, t0, D);
        patch(C, t1, D);
        patch(B, t2, D);
        patch(A, t3, D);

        delete();
        
        // Release deleted tetrahedron to pool
        TetrahedronPool.getInstance().release(this);

        var newFace = t0.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t1.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t2.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t3.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        return t1;
    }

    /**
     * Answer the oriented face of the tetrahedron
     * <p>
     *
     * @param v - the vertex opposite the face
     * @return the OrientedFace
     */
    public OrientedFace getFace(V v) {
        if (v == A) {
            return new FaceCBD();
        }
        if (v == B) {
            return new FaceDAC();
        }
        if (v == C) {
            return new FaceADB();
        }
        if (v == D) {
            return new FaceBCA();
        }
        throw new IllegalArgumentException("Invalid vertex: " + v);
    }

    /**
     * Answer the oriented face opposite the vertex
     *
     * @param v
     * @return
     */
    public OrientedFace getFace(Vertex v) {
        return getFace(ordinalOf(v));
    }

    public List<Vertex[]> getFaces() {
        var faces = new ArrayList<Vertex[]>();
        faces.add(new Vertex[] { a, d, b });
        faces.add(new Vertex[] { b, c, a });
        faces.add(new Vertex[] { c, b, d });
        faces.add(new Vertex[] { d, a, c });
        return faces;
    }

    /**
     * Answer the neighbor that is adjacent to the face opposite of the vertex
     * <p>
     *
     * @param v - the opposing vertex defining the face
     * @return the neighboring tetrahedron, or null if none.
     */
    public Tetrahedron getNeighbor(V v) {
        // Use ordinal for array-like access
        return switch (v) {
            case A -> nA;
            case B -> nB;
            case C -> nC;
            case D -> nD;
        };
    }

    /**
     * Answer the neighbor that is adjacent to the face opposite of the vertex
     * <p>
     *
     * @param vertex
     * @return
     */
    public Tetrahedron getNeighbor(Vertex vertex) {
        return getNeighbor(ordinalOf(vertex));
    }

    /**
     * Answer the vertex of the tetrahedron
     *
     * @param v the vertex
     * @return the vertex
     */
    public Vertex getVertex(V v) {
        if (v == A) {
            return a;
        }
        if (v == B) {
            return b;
        }
        if (v == C) {
            return c;
        }
        if (v == D) {
            return d;
        }
        throw new IllegalStateException("No such point");
    }

    /**
     * Answer the four vertices that define the tetrahedron
     *
     * @return
     */
    public Vertex[] getVertices() {
        return new Vertex[] { a, b, c, d };
    }

    /**
     * Answer true if the query point is contained in the circumsphere of the tetrahedron
     *
     * @param query
     * @return
     */
    public boolean inSphere(Vertex query) {
        return query.inSphere(a, b, c, d) > 0.0d;
    }

    public boolean includes(Vertex query) {
        return a == query || b == query || c == query || d == query;
    }

    /**
     * Answer the iterator over the faces of the tetrahedron
     * <p>
     *
     * @return the iterator of the faces, in the order of the index their opposite vertex
     */
    @Override
    public Iterator<OrientedFace> iterator() {
        return new Iterator<>() {
            final OrientedFace[] faces = { getFace(A), getFace(B), getFace(C), getFace(D) };
            int i = 0;

            @Override
            public boolean hasNext() {
                return i < 4;
            }

            @Override
            public OrientedFace next() {
                return faces[i++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public Tetrahedron locate(Tuple3f query, Random entropy) {
        V o = null;
        for (V face : Grid.VERTICES) {
            if (orientationWrt(face, query) < 0.0d) {
                o = face;
                break;
            }
        }
        if (o == null) {
            // The query point is contained in the receiver
            return this;
        }

        var current = this;
        while (true) {
            // get the tetrahedron on the other side of the face
            var tetrahedron = current.getNeighbor(o);
            if (tetrahedron == null) {
                return null; // not contained in this tetrahedron
            }
            int i = 0;
            for (V v : Grid.ORDER[tetrahedron.ordinalOf(current).ordinal()][entropy.nextInt(6)]) {
                o = v;
                current = tetrahedron;
                if (tetrahedron.orientationWrt(v, query) < 0.0d) {
                    // we have found a face which the query point is on the other side
                    break;
                }
                if (i++ == 2) {
                    return tetrahedron;
                }
            }
        }

    }

    /**
     * Answer the vertex indicator of the the point
     *
     * @param v - the vertex
     * @return the indicator of this vertex or null if not a vertex of this tetrahedron or the supplied vertex is null
     */
    public V ordinalOf(Vertex v) {
        if (v == null) {
            return null;
        }
        if (v == a) {
            return A;
        }
        if (v == b) {
            return B;
        }
        if (v == c) {
            return C;
        }
        return D;
    }

    /**
     * Answer > 0 if the query point is positively oriented with respect to the face opposite the vertex, < 0 if
     * negatively oriented, 0 if the query point is coplanar to the face
     *
     * @param face
     * @param query
     * @return
     */
    public double orientationWrt(V face, Tuple3f query) {
        if (face == A) {
            return orientationWrtCBD(query);
        }
        if (face == B) {
            return orientationWrtDAC(query);
        }
        if (face == C) {
            return orientationWrtADB(query);
        }
        if (face == D) {
            return orientationWrtBCA(query);
        }
        throw new IllegalArgumentException("Invalid face: " + face);
    }

    /**
     * Answer > 0 if the query point is positively oriented with respect to the face ADB, < 0 if negatively oriented, 0
     * if the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtADB(Tuple3f query) {
        return orientation(query, a, d, b);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face BCA, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtBCA(Tuple3f query) {
        return orientation(query, b, c, a);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face CBD, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtCBD(Tuple3f query) {
        return orientation(query, c, b, d);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face DAC, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtDAC(Tuple3f query) {
        return orientation(query, d, a, c);
    }

    @Override
    public String toString() {
        var buf = new StringBuffer();
        buf.append("Tetrahedron [");
        if (isDeleted()) {
            buf.append("DELETED]");
            return buf.toString();
        }
        for (var v : getVertices()) {
            buf.append(v);
            buf.append(", ");
        }
        buf.append(']');
        return buf.toString();
    }

    void children(Stack<Tetrahedron> stack, Set<Tetrahedron> processed) {
        if (nA != null && !processed.contains(nA)) {
            stack.push(nA);
        }
        if (nB != null && !processed.contains(nB)) {
            stack.push(nB);
        }
        if (nC != null && !processed.contains(nC)) {
            stack.push(nC);
        }
        if (nD != null && !processed.contains(nD)) {
            stack.push(nD);
        }
    }

    /**
     * Clean up the pointers
     */
    void delete() {
        nA = nB = nC = nD = null;
        a = b = c = d = null;
    }
    
    /**
     * Reset this tetrahedron with new vertices (for object pooling)
     */
    void reset(Vertex x, Vertex y, Vertex z, Vertex w) {
        // Clear old adjacencies if needed
        if (a != null) a.removeAdjacent(this);
        if (b != null) b.removeAdjacent(this);
        if (c != null) c.removeAdjacent(this);
        if (d != null) d.removeAdjacent(this);
        
        // Set new vertices
        a = x;
        b = y;
        c = z;
        d = w;
        
        // Set adjacencies
        if (a != null) a.setAdjacent(this);
        if (b != null) b.setAdjacent(this);
        if (c != null) c.setAdjacent(this);
        if (d != null) d.setAdjacent(this);
        
        // Clear neighbors
        nA = nB = nC = nD = null;
        
        // Update degeneracy after reset
        updateDegeneracy();
    }
    
    /**
     * Clear this tetrahedron for reuse in the pool
     */
    void clearForReuse() {
        // Clear adjacencies
        if (a != null) a.removeAdjacent(this);
        if (b != null) b.removeAdjacent(this);
        if (c != null) c.removeAdjacent(this);
        if (d != null) d.removeAdjacent(this);
        
        // Clear all fields
        nA = nB = nC = nD = null;
        a = b = c = d = null;
    }

    Vertex getA() {
        return a;
    }

    Vertex getB() {
        return b;
    }

    Vertex getC() {
        return c;
    }

    Vertex getD() {
        return d;
    }

    boolean isDeleted() {
        return a == null;
    }

    /**
     * Calculate the centroid (center point) of this tetrahedron.
     *
     * @return the centroid point
     */
    public Point3f centroid() {
        return new Point3f(
            (a.x + b.x + c.x + d.x) / 4.0f,
            (a.y + b.y + c.y + d.y) / 4.0f,
            (a.z + b.z + c.z + d.z) / 4.0f
        );
    }
    
    /**
     * Calculate the circumsphere radius of this tetrahedron.
     * Useful for validating Delaunay constraints.
     *
     * @return the circumsphere radius
     */
    public float circumsphereRadius() {
        float[] center = new float[3];
        Geometry.centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        // Calculate radius as distance from center to any vertex
        float dx = a.x - center[0];
        float dy = a.y - center[1];
        float dz = a.z - center[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Calculate the volume of this tetrahedron.
     * Volume = |det(a-d, b-d, c-d)| / 6
     *
     * @return the volume
     */
    public float volume() {
        // Vectors from d to other vertices
        float ax = a.x - d.x, ay = a.y - d.y, az = a.z - d.z;
        float bx = b.x - d.x, by = b.y - d.y, bz = b.z - d.z;
        float cx = c.x - d.x, cy = c.y - d.y, cz = c.z - d.z;
        
        // Determinant = a·(b×c)
        float det = ax * (by * cz - bz * cy) - 
                   ay * (bx * cz - bz * cx) + 
                   az * (bx * cy - by * cx);
        
        return Math.abs(det) / 6.0f;
    }
    
    /**
     * Check if this tetrahedron is degenerate (zero or near-zero volume).
     *
     * @return true if degenerate
     */
    public boolean isDegenerate() {
        return isDegenerate;
    }
    
    /**
     * Check if this tetrahedron is near-degenerate.
     *
     * @return true if near-degenerate
     */
    public boolean isNearDegenerate() {
        return isNearDegenerate;
    }
    
    /**
     * Update degeneracy flags based on current volume.
     * Should be called after construction or modification.
     */
    public void updateDegeneracy() {
        // Skip if any vertex is null (e.g., during pool initialization)
        if (a == null || b == null || c == null || d == null) {
            isDegenerate = false;
            isNearDegenerate = false;
            return;
        }
        float vol = Math.abs(volume());
        isDegenerate = vol < DEGENERATE_THRESHOLD;
        isNearDegenerate = vol < NEAR_DEGENERATE_THRESHOLD;
    }
    
    /**
     * Calculate the minimum dihedral angle in this tetrahedron.
     * Useful for assessing tetrahedron quality.
     *
     * @return the minimum dihedral angle in radians
     */
    public float minDihedralAngle() {
        float minAngle = Float.MAX_VALUE;
        
        // Calculate all 6 dihedral angles (between pairs of faces)
        // Face normals
        Vector3f n1 = faceNormal(b, c, a); // Face opposite D
        Vector3f n2 = faceNormal(c, b, d); // Face opposite A
        Vector3f n3 = faceNormal(d, a, c); // Face opposite B
        Vector3f n4 = faceNormal(a, d, b); // Face opposite C
        
        // Angles between faces
        minAngle = Math.min(minAngle, angleBetween(n1, n2)); // Edge BC
        minAngle = Math.min(minAngle, angleBetween(n1, n3)); // Edge AC
        minAngle = Math.min(minAngle, angleBetween(n1, n4)); // Edge AB
        minAngle = Math.min(minAngle, angleBetween(n2, n3)); // Edge CD
        minAngle = Math.min(minAngle, angleBetween(n2, n4)); // Edge BD
        minAngle = Math.min(minAngle, angleBetween(n3, n4)); // Edge AD
        
        return minAngle;
    }
    
    /**
     * Calculate face normal vector.
     */
    private Vector3f faceNormal(Vertex v1, Vertex v2, Vertex v3) {
        Vector3f e1 = new Vector3f(v2.x - v1.x, v2.y - v1.y, v2.z - v1.z);
        Vector3f e2 = new Vector3f(v3.x - v1.x, v3.y - v1.y, v3.z - v1.z);
        Vector3f normal = new Vector3f();
        normal.cross(e1, e2);
        normal.normalize();
        return normal;
    }
    
    /**
     * Calculate angle between two vectors.
     */
    private float angleBetween(Vector3f v1, Vector3f v2) {
        float dot = v1.dot(v2);
        // Clamp to avoid numerical issues with acos
        dot = Math.max(-1.0f, Math.min(1.0f, dot));
        return (float) Math.acos(dot);
    }
    
    /**
     * Check if this tetrahedron satisfies the Delaunay property
     * with respect to a given set of vertices.
     *
     * @param vertices the vertices to check against
     * @return true if Delaunay property is satisfied
     */
    public boolean isDelaunay(Iterable<Vertex> vertices) {
        for (Vertex v : vertices) {
            if (v == a || v == b || v == c || v == d) {
                continue; // Skip vertices of this tetrahedron
            }
            if (inSphere(v)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get detailed validation information for this tetrahedron.
     *
     * @return a map of validation metrics
     */
    public Map<String, Object> getValidationMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("center", center());
        metrics.put("centroid", centroid());
        metrics.put("circumsphereRadius", circumsphereRadius());
        metrics.put("volume", volume());
        metrics.put("isDegenerate", isDegenerate());
        metrics.put("minDihedralAngle", Math.toDegrees(minDihedralAngle()));
        metrics.put("hasAllNeighbors", nA != null && nB != null && nC != null && nD != null);
        return metrics;
    }
    
    /**
     * Answer the canonical ordinal of the opposite vertex of the neighboring tetrahedron
     *
     * @param neighbor
     * @return
     */
    V ordinalOf(Tetrahedron neighbor) {
        // Quick null check first
        if (neighbor == null) {
            return null;
        }
        // Order comparisons by expected frequency
        if (nA == neighbor) {
            return A;
        }
        if (nB == neighbor) {
            return B;
        }
        if (nC == neighbor) {
            return C;
        }
        if (nD == neighbor) {
            return D;
        }
        throw new IllegalArgumentException("Not a neighbor: " + neighbor);
    }

    /**
     * Patch the new tetrahedron created by a flip of the receiver by seting the neighbor to the value in the receiver
     * <p>
     *
     * @param vOld - the opposing vertex the neighboring tetrahedron in the receiver
     * @param n    - the new tetrahedron to patch
     * @param vNew - the opposing vertex of the neighbor to assign in the new tetrahedron
     */
    void patch(V vOld, Tetrahedron n, V vNew) {
        var neighbor = getNeighbor(vOld);
        if (neighbor != null) {
            neighbor.setNeighbor(neighbor.ordinalOf(this), n);
            n.setNeighbor(vNew, neighbor);
        }
    }

    /**
     * Patch the new tetrahedron created by a flip of the receiver by seting the neighbor to the value in the receiver
     * <p>
     *
     * @param old
     * @param n
     * @param vNew
     */
    void patch(Vertex old, Tetrahedron n, V vNew) {
        // Inline ordinalOf to avoid method call overhead
        V vOld = null;
        if (old == a) {
            vOld = A;
        } else if (old == b) {
            vOld = B;
        } else if (old == c) {
            vOld = C;
        } else if (old == d) {
            vOld = D;
        }
        
        if (vOld != null) {
            var neighbor = getNeighbor(vOld);
            if (neighbor != null) {
                neighbor.setNeighbor(neighbor.ordinalOf(this), n);
                n.setNeighbor(vNew, neighbor);
            }
        }
    }

    void removeAnyDegenerateTetrahedronPair() {
        if (nA != null) {
            if (nA == nB) {
                removeDegenerateTetrahedronPair(A, B, C, D);
                return;
            }
            if (nA == nC) {
                removeDegenerateTetrahedronPair(A, C, B, D);
                return;
            }
            if (nA == nD) {
                removeDegenerateTetrahedronPair(A, D, B, C);
                return;
            }
        }

        if (nB != null) {
            if (nB == nC) {
                removeDegenerateTetrahedronPair(B, C, A, D);
                return;
            }
            if (nB == nD) {
                removeDegenerateTetrahedronPair(B, D, A, C);
                return;
            }
        }

        if (nC != null) {
            if (nC == nD) {
                removeDegenerateTetrahedronPair(C, D, A, B);
            }
        }
    }

    void setNeighbor(V v, Tetrahedron n) {
        switch (v) {
            case A -> nA = n;
            case B -> nB = n;
            case C -> nC = n;
            case D -> nD = n;
        }
    }

    void setNeighborA(Tetrahedron t) {
        nA = t;
    }

    void setNeighborB(Tetrahedron t) {
        nB = t;
    }

    void setNeighborC(Tetrahedron t) {
        nC = t;
    }

    void setNeighborD(Tetrahedron t) {
        nD = t;
    }

    /**
     * Traverse the points which define the voronoi face defined by the dual of the line segement defined by the center
     * point and the axis. Terminate the traversal if we have returned to the originating tetrahedron.
     * <p>
     *
     * @param origin
     * @param from
     * @param vC
     * @param axis
     * @param face
     */
    void traverseVoronoiFace(Tetrahedron origin, Tetrahedron from, Vertex vC, Vertex axis, List<Point3f> face) {
        if (origin == this) {
            return;
        }
        var center = new float[3];
        Geometry.centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        face.add(new Point3f(center[0], center[1], center[2]));
        V next = VORONOI_FACE_NEXT[ordinalOf(from).ordinal()][ordinalOf(vC).ordinal()][ordinalOf(axis).ordinal()];
        var t = getNeighbor(next);
        if (t != null) {
            t.traverseVoronoiFace(origin, this, vC, axis, face);
        }

    }

    /**
     * Traverse the points which define the voronoi face defined by the dual of the line segement defined by the center
     * point and the axis.
     * <p>
     *
     * @param vC
     * @param axis
     * @param faces
     */
    void traverseVoronoiFace(Vertex vC, Vertex axis, List<Tuple3f[]> faces) {
        var face = new ArrayList<Point3f>();
        var center = new float[3];
        Geometry.centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        face.add(new Point3f(center[0], center[1], center[2]));
        V v = VORONOI_FACE_ORIGIN[ordinalOf(vC).ordinal()][ordinalOf(axis).ordinal()];
        var next = getNeighbor(v);
        if (next != null) {
            next.traverseVoronoiFace(this, this, vC, axis, face);
        }
        faces.add(face.toArray(new Point3f[face.size()]));
    }

    /**
     * visit the receiver and push unvisited tetrahedrons around the supplied vertex
     *
     * @param vC      - the center vertex
     * @param visitor - the star visitor
     * @param stack   - the stack of visitations
     */
    void visit(Vertex vC, StarVisitor visitor, Stack<Tetrahedron> stack, Set<Tetrahedron> visited) {
        switch (ordinalOf(vC)) {
            case A:
                visitor.visit(A, this, c, b, d);
                if (nC != null) {
                    stack.push(nC);
                }
                if (nB != null) {
                    stack.push(nC);
                }
                if (nD != null) {
                    stack.push(nD);
                }
                break;
            case B:
                visitor.visit(B, this, d, a, c);
                if (nD != null) {
                    stack.push(nD);
                }
                if (nA != null) {
                    stack.push(nA);
                }
                if (nC != null) {
                    stack.push(nC);
                }
                break;
            case C:
                visitor.visit(C, this, a, d, b);
                if (nA != null) {
                    stack.push(nA);
                }
                if (nD != null) {
                    stack.push(nD);
                }
                if (nB != null) {
                    stack.push(nB);
                }
                break;
            case D:
                visitor.visit(D, this, b, c, a);
                if (nB != null) {
                    stack.push(nB);
                }
                if (nA != null) {
                    stack.push(nB);
                }
                if (nC != null) {
                    stack.push(nC);
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid center vertex: " + vC);
        }
    }

    /**
     * Visit the star tetrahedra set of the of the center vertex
     *
     * @param vC      - the center vertex
     * @param visitor - the visitor to invoke for each tetrahedron in the star
     */
    void visitStar(Vertex vC, StarVisitor visitor) {
        var tetrahedrons = new IdentitySet<Tetrahedron>(10);
        var stack = new Stack<Tetrahedron>();
        stack.push(this);
        while (!stack.isEmpty()) {
            var t = stack.pop();
            if (tetrahedrons.add(t)) {
                t.visit(vC, visitor, stack, tetrahedrons);
            }
        }
    }

    private void removeDegenerateTetrahedronPair(V ve1, V ve2, V vf1, V vf2) {
        var nE = getNeighbor(ve1);
        var nF1_that = nE.getNeighbor(getVertex(vf1));
        var nF2_that = nE.getNeighbor(getVertex(vf2));

        patch(vf1, nF1_that, nF1_that.ordinalOf(nE));
        patch(vf2, nF2_that, nF2_that.ordinalOf(nE));

        var e1 = getVertex(ve1);
        var e2 = getVertex(ve2);
        var f1 = getVertex(vf1);
        var f2 = getVertex(vf2);

        delete();
        nE.delete();
        
        // Release deleted tetrahedra to pool
        TetrahedronPool pool = TetrahedronPool.getInstance();
        pool.release(this);
        pool.release(nE);

        e1.freshenAdjacent(nF1_that);
        f2.freshenAdjacent(nF1_that);
        e2.freshenAdjacent(nF2_that);
        f1.freshenAdjacent(nF2_that);
    }

    /**
     * Represents the oriented face opposite vertex C
     *
     * @author hhildebrand
     */
    private class FaceADB extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nC;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == A) {
                return new Vertex[] { d, b };
            }
            if (ordinal == D) {
                return new Vertex[] { b, a };
            }
            if (ordinal == B) {
                return new Vertex[] { a, d };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return c;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return a;
            }
            if (v == 1) {
                return d;
            }
            if (v == 2) {
                return b;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            return (a == v) || (d == v) || (b == v);
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == a) {
                return 0;
            }
            if (v == d) {
                return 1;
            }
            if (v == b) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(c, d, b) < 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(a, c, b) < 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(a, d, c) < 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(c, d, b) > 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(a, c, b) > 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(a, d, c) > 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtADB(query);
        }

        @Override
        public String toString() {
            return "Face ADB";
        }

    }

    /**
     * Represents the oriented face opposite of vertex D
     *
     * @author hhildebrand
     */
    private class FaceBCA extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nD;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == B) {
                return new Vertex[] { c, a };
            }
            if (ordinal == C) {
                return new Vertex[] { a, b };
            }
            if (ordinal == A) {
                return new Vertex[] { b, c };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return d;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return b;
            }
            if (v == 1) {
                return c;
            }
            if (v == 2) {
                return a;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            return (b == v) || (c == v) || (a == v);
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == b) {
                return 0;
            }
            if (v == c) {
                return 1;
            }
            if (v == a) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(d, c, a) < 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(b, d, a) < 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(b, c, d) < 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(d, c, a) > 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(b, d, a) > 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(b, c, d) > 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtBCA(query);
        }

        @Override
        public String toString() {
            return "Face BCA";
        }

    }

    /**
     * Represents the oriented face opposite of vertex A
     *
     * @author hhildebrand
     */
    private class FaceCBD extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nA;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == C) {
                return new Vertex[] { b, d };
            }
            if (ordinal == B) {
                return new Vertex[] { d, c };
            }
            if (ordinal == D) {
                return new Vertex[] { c, b };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return a;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return c;
            }
            if (v == 1) {
                return b;
            }
            if (v == 2) {
                return d;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            return (c == v) || (b == v) || (d == v);
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == c) {
                return 0;
            }
            if (v == b) {
                return 1;
            }
            if (v == d) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(a, b, d) < 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(c, a, d) < 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(c, b, a) < 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Tetrahedron adjacent = getAdjacent();
            var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
            if (current == null) {
                return false;
            }

            var adjacentVertex = adjacent.getVertex(current);
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(a, b, d) > 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(c, a, d) > 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(c, b, a) > 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtCBD(query);
        }

        @Override
        public String toString() {
            return "Face CBD";
        }

    }

    /**
     * Represents the oriented face opposite of vertex B
     *
     * @author hhildebrand
     */
    private class FaceDAC extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nB;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == D) {
                return new Vertex[] { a, c };
            }
            if (ordinal == A) {
                return new Vertex[] { c, d };
            }
            if (ordinal == C) {
                return new Vertex[] { d, a };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return b;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return d;
            }
            if (v == 1) {
                return a;
            }
            if (v == 2) {
                return c;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            return (d == v) || (a == v) || (c == v);
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == d) {
                return 0;
            }
            if (v == a) {
                return 1;
            }
            if (v == c) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            var adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(b, a, c) < 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(d, b, c) < 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(d, a, b) < 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Tetrahedron adjacent = getAdjacent();
            var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
            if (current == null) {
                return false;
            }
            var adjacentVertex = adjacent.getVertex(current);
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(b, a, c) > 0.0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(d, b, c) > 0.0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(d, a, b) > 0.0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtDAC(query);
        }

        @Override
        public String toString() {
            return "Face DAC";
        }
    }
}
