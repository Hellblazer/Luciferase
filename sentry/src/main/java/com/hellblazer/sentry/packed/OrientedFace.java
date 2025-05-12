package com.hellblazer.sentry.packed;

import com.hellblazer.sentry.packed.PackedGrid.PackedTetrahedron;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

public abstract class OrientedFace implements Iterable<Integer> {

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
    public boolean flip(int index, LinkedList<OrientedFace> ears, int n) {
        if (!isValid()) {
            return true;
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
            var opposingVertex = getVertex(reflexEdge);
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
    public PackedTetrahedron flip(int n, List<OrientedFace> ears) {
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

        PackedTetrahedron returned = null;
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
    public PackedTetrahedron[] flip2to3() {
        var incident = getIncident();

        var opposingVertex = getAdjacentVertex();
        var incidentVertex = getIncidentVertex();

        var vertex0 = getVertex(0);
        var vertex1 = getVertex(1);
        var vertex2 = getVertex(2);

        var t0 = newTetrahedron(vertex0, incidentVertex, vertex1, opposingVertex);
        var t1 = newTetrahedron(vertex1, incidentVertex, vertex2, opposingVertex);
        var t2 = newTetrahedron(vertex0, vertex2, incidentVertex, opposingVertex);

        t0.setNeighborA(t1);
        t0.setNeighborC(t2);

        t1.setNeighborA(t2);
        t1.setNeighborC(t0);

        t2.setNeighborA(t1);
        t2.setNeighborB(t0);

        incident.patch(vertex2, t0, 3);
        incident.patch(vertex0, t1, 3);
        incident.patch(vertex1, t2, 3);

        var adjacent = getAdjacent();

        adjacent.patch(vertex0, t1, 1);
        adjacent.patch(vertex1, t2, 2);
        adjacent.patch(vertex2, t0, 1);

        incident.delete();
        adjacent.delete();

        t0.removeAnyDegenerateTetrahedronPair();
        t1.removeAnyDegenerateTetrahedronPair();
        t2.removeAnyDegenerateTetrahedronPair();

        if (t0.isDeleted()) {
            if (t1.isDeleted()) {
                if (t2.isDeleted()) {
                    return new PackedTetrahedron[] {};
                } else {
                    return new PackedTetrahedron[] { t2 };
                }
            } else if (t2.isDeleted()) {
                return new PackedTetrahedron[] { t1 };
            } else {
                return new PackedTetrahedron[] { t1, t2 };
            }
        } else if (t1.isDeleted()) {
            if (t2.isDeleted()) {
                return new PackedTetrahedron[] { t0 };
            } else {
                return new PackedTetrahedron[] { t0, t2 };
            }
        } else if (t2.isDeleted()) {
            return new PackedTetrahedron[] { t0, t1 };
        } else {
            return new PackedTetrahedron[] { t0, t1, t2 };
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
    public PackedTetrahedron[] flip3to2(int reflexEdge) {
        var incident = getIncident();
        var o2 = getIncident().getNeighbor(getVertex(reflexEdge));

        int top0;
        int top1 = switch (reflexEdge) {
            case 0 -> {
                top0 = getVertex(1);
                yield getVertex(2);
            }
            case 1 -> {
                top0 = getVertex(0);
                yield getVertex(2);
            }
            case 2 -> {
                top0 = getVertex(0);
                yield getVertex(1);
            }
            default -> throw new IllegalArgumentException("Invalid reflex edge index: " + reflexEdge);
        };

        var x = getVertex(reflexEdge);
        var y = getIncidentVertex();
        var z = getAdjacentVertex();

        PackedTetrahedron t0;
        PackedTetrahedron t1;
        if (orientation(top0, x, y, z) > 0) {
            t0 = newTetrahedron(x, y, z, top0);
            t1 = newTetrahedron(y, x, z, top1);
        } else {
            t0 = newTetrahedron(x, y, z, top1);
            t1 = newTetrahedron(y, x, z, top0);
        }

        t0.setNeighborD(t1);
        t1.setNeighborD(t0);

        incident.patch(t0.getD(), t1, t1.ordinalOf(getAdjacentVertex()));
        incident.patch(t1.getD(), t0, t0.ordinalOf(getAdjacentVertex()));

        PackedTetrahedron adjacent = getAdjacent();

        adjacent.patch(t0.getD(), t1, t1.ordinalOf(getIncidentVertex()));
        adjacent.patch(t1.getD(), t0, t0.ordinalOf(getIncidentVertex()));

        o2.patch(t0.getD(), t1, t1.ordinalOf(getVertex(reflexEdge)));
        o2.patch(t1.getD(), t0, t0.ordinalOf(getVertex(reflexEdge)));

        incident.delete();
        adjacent.delete();
        o2.delete();

        return new PackedTetrahedron[] { t0, t1 };
    }

    /**
     * Answer the adjacent tetrahedron to the face
     */
    abstract public PackedTetrahedron getAdjacent();

    /**
     * Answer the vertex in the adjacent tetrahedron which is opposite of this face.
     */
    public int getAdjacentVertex() {
        PackedTetrahedron adjacent = getAdjacent();
        var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
        if (current == null) {
            return -1;
        }
        return adjacent.getVertex(current);
    }

    /**
     * Answer the canonical ordinal of the vertex in the adjacent tetrahedron which is opposite of this face.
     */
    public int getAdjacentVertexOrdinal() {
        PackedTetrahedron adjacent = getAdjacent();
        return adjacent == null ? -1 : adjacent.ordinalOf(getIncident());
    }

    /**
     * Answer the tetrahedron which is incident with this face
     */
    abstract public PackedTetrahedron getIncident();

    /**
     * Answer the vertex in the tetrahedron which is opposite of this face
     */
    abstract public int getIncidentVertex();

    /**
     * Answer the canonical vertex for this face
     */
    abstract public int getVertex(int anIndex);

    public boolean hasAdjacent() {
        return getAdjacent() != null;
    }

    abstract public boolean includes(int v);

    /**
     * Answer the edge index corresponding to the vertex
     *
     * @param v - the vertex
     * @return the index of the edge
     */
    abstract public int indexOf(int v);

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
     */
    public boolean isRegular() {
        return !getIncident().inSphere(getAdjacentVertex());
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                return i < 3;
            }

            @Override
            public Integer next() {
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
    abstract public double orientationOf(int query);

    abstract double inSphere(int query, int a, int b, int c, int d);

    abstract PackedTetrahedron newTetrahedron(int a, int b, int c, int d);

    abstract double orientation(int d, int a, int b, int c);

    private boolean inSphere(int query, int b, int c, int d) {
        var a = getIncidentVertex();
        if (orientation(d, a, b, c) < 0.0f) {
            var tmp = b;
            b = a;
            a = tmp;
        }
        return inSphere(query, a, b, c, d) > 0.0d;
    }

    private boolean isFlippable3ear(int n) {
        var opposingFace = getIncident().getFace(n);
        opposingFace.getAdjacent().getFace(opposingFace.getAdjacentVertex());
        return opposingFace.orientationOf(n) > 0.0d;

    }

    private boolean isLocallyDelaunay(int index, int v, LinkedList<OrientedFace> ears) {
        Function<Integer, Boolean> circumsphere = query -> switch (indexOf(v)) {
            case 0 -> inSphere(query, getVertex(1), getVertex(2), getVertex(0));
            case 1 -> inSphere(query, getVertex(0), getVertex(2), getVertex(1));
            default -> inSphere(query, getVertex(0), getVertex(1), getVertex(2));
        };
        for (int i = 0; i < ears.size(); i++) {
            if (index != i) {
                OrientedFace ear = ears.get(i);
                if (ear != this && ear.isValid()) {
                    for (var e : ear) {
                        if (e != v && circumsphere.apply(e)) {
                            return false;
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
