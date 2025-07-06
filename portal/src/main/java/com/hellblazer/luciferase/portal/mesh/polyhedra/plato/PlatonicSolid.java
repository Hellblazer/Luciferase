package com.hellblazer.luciferase.portal.mesh.polyhedra.plato;

import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.util.VectorMath;

import javax.vecmath.Vector3d;

/**
 * Abstract class for a Platonic solid mesh centered at the origin.
 *
 * @author Brian Yao
 */
public abstract class PlatonicSolid extends Polyhedron {

    private final double edgeLength;

    /**
     * @param edgeLength The length of each edge in this platonic solid.
     */
    public PlatonicSolid(double edgeLength) {
        this.edgeLength = edgeLength;
    }

    public static double edgeLength(Vector3d[] vs) {
        Vector3d diff = VectorMath.diff(vs[1], vs[0]);
        return diff.length();
    }

    /**
     * @return The edge length of each edge in this platonic solid.
     */
    public double getEdgeLength() {
        return edgeLength;
    }

}
