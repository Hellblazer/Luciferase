/**
 * Copyright (C) 2009-2023 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.grid;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static com.hellblazer.luciferase.lucien.grid.V.*;

/**
 * The dynamic, mutable version of the Grid
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public class MutableGrid extends Grid {
    protected Vertex      tail;
    private   Tetrahedron last;

    public MutableGrid() {
        this(getFourCorners());
    }

    public MutableGrid(Vertex[] fourCorners) {
        super(fourCorners);
        last = new Tetrahedron(fourCorners);
    }

    public void clear() {
        if (head != null) {
            head.clear();
        }
        for (var v : fourCorners) {
            v.reset();
        }
    }

    public void rebuild(Random entropy) {
        clear();
        last = new Tetrahedron(fourCorners);
        for (var v : head) {
            insert(v, locate(v, last, entropy));
        }
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p - the point to be inserted
     * @return the Vertex in the tetrahedralization
     */
    public Vertex track(Point3f p, Random entropy) {
        assert p != null;
        final var v = new Vertex(p);
        add(v, locate(p, entropy));
        return v;
    }

    public Tetrahedron locate(Point3f p, Random entropy) {
        return locate(p, last, entropy);
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p    - the point to be inserted
     * @param near - the nearby vertex
     * @return the new Vertex in the tetrahedralization
     */
    public Vertex track(Point3f p, Vertex near, Random entropy) {
        assert p != null;
        final var v = new Vertex(p);
        add(v, near.locate(p, entropy));
        return v;
    }

    public void untrack(Vertex v) {
        if (head != null) {
            head.detach(v);
            v.clear();
        }
    }

    /**
     * Perform the 4->1 bistellar flip. This flip is the inverse of the 1->4 flip.
     *
     * @param n - the vertex who's star defines the 4 tetrahedron
     * @return the tetrahedron created from the flip
     */
    protected Tetrahedron flip4to1(Vertex n) {
        Deque<OrientedFace> star = n.getStar();
        ArrayList<Tetrahedron> deleted = new ArrayList<>();
        for (OrientedFace f : star) {
            deleted.add(f.getIncident());
        }
        assert star.size() == 4;
        OrientedFace base = star.pop();
        Vertex a = base.getVertex(2);
        Vertex b = base.getVertex(0);
        Vertex c = base.getVertex(1);
        Vertex d = null;
        OrientedFace face = star.pop();
        for (Vertex v : face) {
            if (!base.includes(v)) {
                d = v;
                break;
            }
        }
        assert d != null;
        Tetrahedron t = new Tetrahedron(a, b, c, d);
        base.getIncident().patch(base.getIncidentVertex(), t, D);
        if (face.includes(a)) {
            if (face.includes(b)) {
                assert !face.includes(c);
                face.getIncident().patch(face.getIncidentVertex(), t, C);
                face = star.pop();
                if (face.includes(a)) {
                    assert !face.includes(b);
                    face.getIncident().patch(face.getIncidentVertex(), t, B);
                    face = star.pop();
                    assert !face.includes(a);
                    face.getIncident().patch(face.getIncidentVertex(), t, A);
                } else {
                    face.getIncident().patch(face.getIncidentVertex(), t, A);
                    face = star.pop();
                    assert !face.includes(b);
                    face.getIncident().patch(face.getIncidentVertex(), t, B);
                }
            } else {
                face.getIncident().patch(face.getIncidentVertex(), t, B);
                face = star.pop();
                if (face.includes(a)) {
                    assert !face.includes(c);
                    face.getIncident().patch(face.getIncidentVertex(), t, C);
                    face = star.pop();
                    assert !face.includes(a);
                    face.getIncident().patch(face.getIncidentVertex(), t, A);
                } else {
                    face.getIncident().patch(face.getIncidentVertex(), t, A);
                    face = star.pop();
                    assert !face.includes(c);
                    face.getIncident().patch(face.getIncidentVertex(), t, C);
                }
            }
        } else {
            face.getIncident().patch(face.getIncidentVertex(), t, A);
            face = star.pop();
            if (face.includes(b)) {
                assert !face.includes(c);
                face.getIncident().patch(face.getIncidentVertex(), t, C);
                face = star.pop();
                assert !face.includes(b);
                face.getIncident().patch(face.getIncidentVertex(), t, B);
            } else {
                face.getIncident().patch(face.getIncidentVertex(), t, B);
                face = star.pop();
                assert !face.includes(c);
                face.getIncident().patch(face.getIncidentVertex(), t, C);
            }
        }

        for (Tetrahedron tet : deleted) {
            tet.delete();
        }
        return t;
    }

    private void add(Vertex v, final Tetrahedron target) {
        insert(v, target);
        if (head == null) {
            head = v;
        } else if (tail != null) {
            tail.append(v);
            tail = v;
        } else {
            head.append(v);
            tail = v;
        }
        size++;
    }

    private void insert(Vertex v, final Tetrahedron target) {
        List<OrientedFace> ears = new ArrayList<>(20);
        last = target.flip1to4(v, ears);
        while (!ears.isEmpty()) {
            Tetrahedron l = ears.remove(ears.size() - 1).flip(v, ears);
            if (l != null) {
                last = l;
            }
        }
    }
}
