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

package com.hellblazer.sentry;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */
@FunctionalInterface
public interface StarVisitor {
    /**
     * Visit the tetrahedron t in the start set. The central vertex of the star set
     * is vertex v in tetrahedron t. The other 3 vertices in tetrahedron t are given
     * by {a, b, c} and are in counterclockwise order, where the central vertex of
     * the star is V ordinal D in t, following the right hand rule.
     *
     * @param v - the V Ordinal of the central vertex Vc of the star in tetrahedron
     *          t
     * @param t - a tetrahedron in the star set, where the central vertex of the
     *          start is the vertex ordinal in t
     * @param a - vertex A relative to Vc
     * @param b - vertex B relative to Vc
     * @param c - vertex C relative to Vc
     */
    void visit(V v, Tetrahedron t, Vertex a, Vertex b, Vertex c);
}
