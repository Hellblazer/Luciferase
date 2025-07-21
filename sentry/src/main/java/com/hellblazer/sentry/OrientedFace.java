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

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

import static com.hellblazer.sentry.V.*;

/**
 * An oriented face of a tetrahedron.
 * <p>
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public abstract class OrientedFace implements Iterable<Vertex> {
    
    // Cache for adjacent vertex
    private Vertex cachedAdjacentVertex;
    private boolean adjacentVertexCached = false;
    
    /**
     * Invalidate the cached adjacent vertex. Should be called when topology changes.
     */
    protected void invalidateAdjacentVertexCache() {
        adjacentVertexCached = false;
        cachedAdjacentVertex = null;
    }

    /**
     * Perform a flip for deletion of the vertex from the tetrahedralization. The incident and adjacent tetrahedra form
     * an ear of the star set of tetrahedra adjacent to v.
     * <p>
     *
     * @param index - the index of the receiver in the list of ears
     * @param ears  - the list of ears of the vertex
     * @param n     - the vertex to be deleted
     * @return true if the receiver is to be deleted from the list of ears
     */
    public boolean flip(int index, ArrayList<OrientedFace> ears, Vertex n) {
        if (!isValid()) {
            return true;
        }

        // Early exit optimization - check common cases first
        if (isRegular()) {
            return false;
        }

        int reflexEdge = 0;
        int reflexEdges = 0;
        // Determine how many faces are visible from the tetrahedron
        for (int i = 0; reflexEdges < 2 && i < 3; i++) {
            if (isReflex(i)) {
                reflexEdge = i;
                reflexEdges++;
            }
        }

        if (reflexEdges == 0 && isConvex(indexOf(n)) && isLocallyDelaunay(index, n, ears)) {
            // Only one face of the opposing tetrahedron is visible
            var created = flip2to3();
            if (created[0].includes(n)) {
                if (created[1].includes(n)) {
                    ears.add(created[0].getFace(created[0].ordinalOf(created[1])));
                } else {
                    ears.add(created[0].getFace(created[0].ordinalOf(created[2])));
                }
            } else {
                ears.add(created[1].getFace(created[1].ordinalOf(created[2])));
            }
            return true;
        } else if (reflexEdges == 1) {
            // Two faces of the opposing tetrahedron are visible
            Vertex opposingVertex = getVertex(reflexEdge);
            var t1 = getIncident().getNeighbor(opposingVertex);
            var t2 = getAdjacent().getNeighbor(opposingVertex);
            if (t1 != null && t1 == t2 && isFlippable3ear(n) && isLocallyDelaunay(index, n, ears)) {
                flip3to2(reflexEdge);
                return true;
            }
        }
        // all three faces are visible, no action taken
        return false;
    }

    /**
     * Perform the flip which incrementally restores the delaunay condition after the vertex has been inserted into the
     * tetrahedralization.
     * <p>
     *
     * @param n    - the inserted vertex
     * @param ears - the stack of oriented faces left to process
     * @return - the last valid tetrahedron noted, or null if no flip was performed.
     */
    public Tetrahedron flip(Vertex n, List<OrientedFace> ears) {
        if (!isValid()) {
            return null;
        }
        int reflexEdge = 0;
        int reflexEdges = 0;
        // Determine how many faces are visible from the tetrahedron formed
        // by the inserted point and the popped facet
        for (int i = 0; reflexEdges < 2 && i < 3; i++) {
            if (isReflex(i)) {
                reflexEdge = i;
                reflexEdges++;
            }
        }

        Tetrahedron returned = null;
        var regular = isRegular();
        if (reflexEdges == 0 && !regular) {
            // Only one face of the opposing tetrahedron is visible
            for (var t : flip2to3()) {
                var f = t.getFace(n);
                if (f.hasAdjacent()) {
                    ears.add(f);
                }
                returned = t;
            }
        } else if (reflexEdges == 1 && !regular) {
            // Two faces of the opposing tetrahedron are visible
            var opposingVertex = getVertex(reflexEdge);
            var t1 = getIncident().getNeighbor(opposingVertex);
            var t2 = getAdjacent().getNeighbor(opposingVertex);
            if (t1 != null && t1 == t2) {
                for (var t : flip3to2(reflexEdge)) {
                    OrientedFace f = t.getFace(n);
                    if (f.hasAdjacent()) {
                        ears.add(f);
                    }
                    returned = t;
                }
            }
        }
        // all three faces are visible, no action taken
        return returned;
    }

    /**
     * Perform the bistellar flip 2 -> 3. This produces three new tetrahedra from the receiver and a tetrahdron that
     * shares the receiver face
     *
     * @return the three created tetrahedron
     */
    public Tetrahedron[] flip2to3() {
        var incident = getIncident();

        var opposingVertex = getAdjacentVertex();
        var incidentVertex = getIncidentVertex();

        var vertex0 = getVertex(0);
        var vertex1 = getVertex(1);
        var vertex2 = getVertex(2);

        var allocator = TetrahedronPoolContext.getAllocator();
        var t0 = allocator.acquire(vertex0, incidentVertex, vertex1, opposingVertex);
        var t1 = allocator.acquire(vertex1, incidentVertex, vertex2, opposingVertex);
        var t2 = allocator.acquire(vertex0, vertex2, incidentVertex, opposingVertex);

        t0.setNeighborA(t1);
        t0.setNeighborC(t2);

        t1.setNeighborA(t2);
        t1.setNeighborC(t0);

        t2.setNeighborA(t1);
        t2.setNeighborB(t0);

        incident.patch(vertex2, t0, D);
        incident.patch(vertex0, t1, D);
        incident.patch(vertex1, t2, D);

        var adjacent = getAdjacent();

        adjacent.patch(vertex0, t1, B);
        adjacent.patch(vertex1, t2, C);
        adjacent.patch(vertex2, t0, B);

        incident.delete();
        adjacent.delete();
        
        // Defer release until after all operations complete
        TetrahedronPoolContext.deferRelease(incident);
        TetrahedronPoolContext.deferRelease(adjacent);

        t0.removeAnyDegenerateTetrahedronPair();
        t1.removeAnyDegenerateTetrahedronPair();
        t2.removeAnyDegenerateTetrahedronPair();

        if (t0.isDeleted()) {
            if (t1.isDeleted()) {
                if (t2.isDeleted()) {
                    return new Tetrahedron[] {};
                } else {
                    return new Tetrahedron[] { t2 };
                }
            } else if (t2.isDeleted()) {
                return new Tetrahedron[] { t1 };
            } else {
                return new Tetrahedron[] { t1, t2 };
            }
        } else if (t1.isDeleted()) {
            if (t2.isDeleted()) {
                return new Tetrahedron[] { t0 };
            } else {
                return new Tetrahedron[] { t0, t2 };
            }
        } else if (t2.isDeleted()) {
            return new Tetrahedron[] { t0, t1 };
        } else {
            return new Tetrahedron[] { t0, t1, t2 };
        }
    }

    /**
     * Perform the bistellar 3->2 flip. This flip constructs two new tetrahedra from the two tetraheda determined by the
     * incident and adjacent neighbor of the face, along with the tetrahedron on the reflexive edge of the face.
     * <p>
     *
     * @param reflexEdge - the vertex opposite the reflex edge of the face
     * @return the two created tetrahedron
     */
    public Tetrahedron[] flip3to2(int reflexEdge) {
        var incident = getIncident();
        var o2 = getIncident().getNeighbor(getVertex(reflexEdge));

        Vertex top0 = null;
        Vertex top1 = null;

        switch (reflexEdge) {
            case 0:
                top0 = getVertex(1);
                top1 = getVertex(2);
                break;
            case 1:
                top0 = getVertex(0);
                top1 = getVertex(2);
                break;
            case 2:
                top0 = getVertex(0);
                top1 = getVertex(1);
                break;
            default:
                throw new IllegalArgumentException("Invalid reflex edge index: " + reflexEdge);
        }

        var x = getVertex(reflexEdge);
        var y = getIncidentVertex();
        var z = getAdjacentVertex();

        var allocator = TetrahedronPoolContext.getAllocator();
        Tetrahedron t0;
        Tetrahedron t1;
        if (top0.orientation(x, y, z) > 0) {
            t0 = allocator.acquire(x, y, z, top0);
            t1 = allocator.acquire(y, x, z, top1);
        } else {
            t0 = allocator.acquire(x, y, z, top1);
            t1 = allocator.acquire(y, x, z, top0);
        }

        t0.setNeighborD(t1);
        t1.setNeighborD(t0);

        incident.patch(t0.getD(), t1, t1.ordinalOf(getAdjacentVertex()));
        incident.patch(t1.getD(), t0, t0.ordinalOf(getAdjacentVertex()));

        Tetrahedron adjacent = getAdjacent();

        adjacent.patch(t0.getD(), t1, t1.ordinalOf(getIncidentVertex()));
        adjacent.patch(t1.getD(), t0, t0.ordinalOf(getIncidentVertex()));

        o2.patch(t0.getD(), t1, t1.ordinalOf(getVertex(reflexEdge)));
        o2.patch(t1.getD(), t0, t0.ordinalOf(getVertex(reflexEdge)));

        incident.delete();
        adjacent.delete();
        
        // Defer release until after all operations complete
        TetrahedronPoolContext.deferRelease(incident);
        TetrahedronPoolContext.deferRelease(adjacent);
        o2.delete();
        
        // Defer release until after all operations complete
        TetrahedronPoolContext.deferRelease(o2);

        return new Tetrahedron[] { t0, t1 };
    }

    /**
     * Answer the adjacent tetrahedron to the face
     *
     * @return
     */
    abstract public Tetrahedron getAdjacent();

    /**
     * Answer the vertex in the adjacent tetrahedron which is opposite of this face.
     *
     * @return
     */
    public Vertex getAdjacentVertex() {
        if (!adjacentVertexCached) {
            Tetrahedron adjacent = getAdjacent();
            var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
            if (current == null) {
                cachedAdjacentVertex = null;
            } else {
                cachedAdjacentVertex = adjacent.getVertex(current);
            }
            adjacentVertexCached = true;
        }
        return cachedAdjacentVertex;
    }

    /**
     * Answer the canonical ordinal of the vertex in the adjacent tetrahedron which is opposite of this face.
     *
     * @return
     */
    public V getAdjacentVertexOrdinal() {
        Tetrahedron adjacent = getAdjacent();
        return adjacent == null ? null : adjacent.ordinalOf(getIncident());
    }

    /**
     * Answer the two vertices defining the edge opposite of the vertex v
     *
     * @param v - the vertex defining the edge
     * @return the array of two vertices defining the edge
     */
    abstract public Vertex[] getEdge(Vertex v);

    /**
     * Answer the tetrahedron which is incident with this face
     *
     * @return
     */
    abstract public Tetrahedron getIncident();

    /**
     * Answer the vertex in the tetrahedron which is opposite of this face
     *
     * @return
     */
    abstract public Vertex getIncidentVertex();

    /**
     * Answer the canonical vertex for this face
     *
     * @param anIndex
     * @return
     */
    abstract public Vertex getVertex(int anIndex);

    public boolean hasAdjacent() {
        return getAdjacent() != null;
    }

    abstract public boolean includes(Vertex v);

    /**
     * Answer the edge index corresponding to the vertex
     *
     * @param v - the vertex
     * @return the index of the edge
     */
    abstract public int indexOf(Vertex v);

    /**
     * Answer true if the faces joined by the edge are concave when viewed from the originating tetrahedron.
     * <p>
     *
     * @param vertex - the vertex of the face that is opposite of the edge
     * @return true if the faces joined by the edge are convex, false if these faces are not convex
     */
    abstract public boolean isConvex(int vertex);

    /**
     * Answer true if the faces joined by the edge are not concave when viewed from the originating tetrahedron.
     * <p>
     *
     * @param vertex - the vertex of the face that is opposite of the edge
     * @return true if the faces joined by the edge are reflex, false if these faces are not reflex
     */
    abstract public boolean isReflex(int vertex);

    /**
     * Answer true if the vertex in the adjacent tetrahedron is not contained in the circumsphere of the incident
     * tetrahedron
     *
     * @return
     */
    public boolean isRegular() {
        return !getIncident().inSphere(getAdjacentVertex());
    }

    @Override
    public Iterator<Vertex> iterator() {
        return new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                return i < 3;
            }

            @Override
            public Vertex next() {
                if (i == 3) {
                    throw new NoSuchElementException("No vertices left on this face");
                }
                return getVertex(i++);
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Deletion of vertices from face not supported");
            }
        };
    }

    /**
     * Answer +1 if the orientation of the query point is positive with respect to this face, -1 if negative and 0 if
     * the test point is coplanar with the face
     *
     * @param query - the point to be tested
     * @return +1 if the orientation of the query point is positive with respect to the face, -1 if negative and 0 if
     * the query point is coplanar
     */
    abstract public double orientationOf(Vertex query);

    private boolean inSphere(Vertex query, Vertex b, Vertex c, Vertex d) {
        var a = getIncidentVertex();
        if (d.orientation(a, b, c) < 0) {
            Vertex tmp = b;
            b = a;
            a = tmp;
        }
        return query.inSphere(a, b, c, d) > 0.0d;
    }

    private boolean isFlippable3ear(Vertex n) {
        var opposingFace = getIncident().getFace(n);
        opposingFace.getAdjacent().getFace(opposingFace.getAdjacentVertex());
        return opposingFace.orientationOf(n) > 0.0d;

    }

    private boolean isLocallyDelaunay(int index, Vertex v, ArrayList<OrientedFace> ears) {
        // Early exit if ears list is small
        if (ears.size() <= 1) {
            return true;
        }
        
        // Pre-compute vertices for circumsphere test
        int vIndex = indexOf(v);
        Vertex v0, v1, v2;
        switch (vIndex) {
            case 0:
                v0 = getVertex(1);
                v1 = getVertex(2);
                v2 = getVertex(0);
                break;
            case 1:
                v0 = getVertex(0);
                v1 = getVertex(2);
                v2 = getVertex(1);
                break;
            default:
                v0 = getVertex(0);
                v1 = getVertex(1);
                v2 = getVertex(2);
                break;
        }
        
        // Check ears for Delaunay condition
        for (int i = 0; i < ears.size(); i++) {
            if (index != i) {
                OrientedFace ear = ears.get(i);
                if (ear != this && ear.isValid()) {
                    // Check vertices of the ear
                    for (Vertex e : ear) {
                        if (e != v && inSphere(e, v0, v1, v2)) {
                            return false;  // Early exit on first violation
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean isValid() {
        return !getIncident().isDeleted() && hasAdjacent() && !getAdjacent().isDeleted();
    }
}
