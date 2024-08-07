/**
 * Copyright (C) 2010 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.common.OaHashSet;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */
public class EarSet implements StarVisitor {
    private List<OrientedFace> ears    = new LinkedList<>();
    private Set<Ear>           visited = new OaHashSet<>();

    public List<OrientedFace> getEars() {
        return ears;
    }

    @Override
    public void visit(V vertex, Tetrahedron t, Vertex x, Vertex y, Vertex z) {
        OrientedFace face = t.getFace(z);
        if (visited.add(new Ear(face))) {
            ears.add(face);
        }
        face = t.getFace(x);
        if (visited.add(new Ear(face))) {
            ears.add(face);
        }
        face = t.getFace(y);
        if (visited.add(new Ear(face))) {
            ears.add(face);
        }
    }

    private static class Ear {
        final OrientedFace face;
        final int          hashcode;

        Ear(OrientedFace face) {
            this.face = face;
            int hash = 0;
            for (Vertex v : face) {
                hash ^= v.hashCode();
            }
            hashcode = hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Ear) {
                Ear ear = (Ear) obj;
                if ((face.getIncident() == ear.face.getIncident() && face.getAdjacent() == ear.face.getAdjacent()) || (
                face.getAdjacent() == ear.face.getIncident() && face.getIncident() == ear.face.getAdjacent())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hashcode;
        }
    }
}
