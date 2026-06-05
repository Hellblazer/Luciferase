package com.hellblazer.luciferase.portal.mesh.util;

import com.hellblazer.luciferase.portal.mesh.Face;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;

import javax.vecmath.Vector3d;
import java.util.ArrayList;
import java.util.List;

/**
 * A Java implementation of precisely the iterative algorithm for computing canonical polyhedra designed by George W.
 * Hart. All the code in this class is based directly off of his work.
 *
 * Further information on this algorithm and what it does is on Hart's website. See this link:
 * http://www.georgehart.com/canonical/canonical-supplement.html
 *
 * @author Brian Yao
 */
public class Canonicalize {

    // Scale-relative divergence guard: stop if any vertex moves more than this
    // multiple of the centroid-relative mean vertex radius in a single iteration.
    //
    // An absolute threshold (the old value of 1.0) false-trips on legitimate
    // unit-scale meshes (edge length ~1.0) whose first-iteration displacements
    // are ~2–3× but are NOT divergent — just the algorithm taking a large
    // corrective step from a non-canonical starting shape.
    //
    // We use the centroid-relative mean radius (bounding-sphere proxy) rather than
    // mean edge length because mean edge length is dominated by massively-distorted
    // edges (the very case we want the guard to catch), making the guard blind to
    // the runaway it is supposed to detect.  The centroid-relative formulation is
    // also TRANSLATION-INVARIANT: a mesh at world coords (100,100,100) yields the
    // same radius as the same mesh centred at the origin.  The old origin-relative
    // formula produced radius ≈173 for a unit mesh at (100,100,100), making the
    // threshold ≈606 and permanently disabling the guard for any off-origin mesh.
    //
    // Factor derivation: for Cube(1.0) (centroid-relative mean radius ≈0.87),
    // iter-1 displacement ≈2–3; for a tet with one vertex at (1000,1000,1000)
    // relative to its centroid (mean radius ≈433), iter-1 displacement ≈1732.
    // A factor in (2.89, 3.99) satisfies both.  We use 3.5.
    private static final double MAX_VERTEX_CHANGE_FACTOR = 3.5;

    /**
     * Canonicalizes a polyhedron by adjusting its vertices iteratively. When no vertex moves more than the given
     * threshold, the algorithm terminates.
     *
     * @param poly      The polyhedron whose vertices to adjust.
     * @param threshold The threshold of vertex movement after an iteration.
     * @return The number of iterations that were executed.
     */
    public static int adjust(Polyhedron poly, double threshold) {
        return canonicalize(poly, threshold, false);
    }

    /**
     * Canonicalizes a polyhedron by adjusting its vertices iteratively.
     *
     * @param poly          The polyhedron whose vertices to adjust.
     * @param numIterations The number of iterations to adjust for.
     */
    public static void adjust(Polyhedron poly, int numIterations) {
        Polyhedron dual = poly.dual();
        for (int i = 0; i < numIterations; i++) {
            List<Vector3d> newDualPositions = reciprocalCenters(poly);
            dual.setVertexPositions(newDualPositions);
            List<Vector3d> newPositions = reciprocalCenters(dual);
            poly.setVertexPositions(newPositions);
        }
        poly.setVertexNormalsToFaceNormals();
    }

    /**
     * A helper method for threshold-based termination in both planarizing and adjusting. If a vertex moves by an
     * unexpectedly large amount, or if the new vertex position has an NaN component, the algorithm automatically
     * terminates.
     *
     * @param poly      The polyhedron to canonicalize.
     * @param threshold The threshold of vertex movement after an iteration.
     * @param planarize True if we are planarizing, false if we are adjusting.
     * @return The number of iterations that were executed.
     */
    private static int canonicalize(Polyhedron poly, double threshold, boolean planarize) {
        double maxVertexChange = planarize ? meanVertexRadius(poly) * MAX_VERTEX_CHANGE_FACTOR : Double.MAX_VALUE;
        Polyhedron dual = poly.dual();
        List<Vector3d> currentPositions = Struct.copyVectorList(poly.getVertexPositions());

        int iterations = 0;
        while (true) {
            List<Vector3d> newDualPositions = planarize ? reciprocalVertices(poly) : reciprocalCenters(poly);
            dual.setVertexPositions(newDualPositions);
            List<Vector3d> newPositions = planarize ? reciprocalVertices(dual) : reciprocalCenters(dual);

            double maxChange = 0.;
            for (int i = 0; i < currentPositions.size(); i++) {
                Vector3d newPos = poly.getVertexPositions().get(i);
                Vector3d diff = VectorMath.diff(newPos, currentPositions.get(i));
                maxChange = Math.max(maxChange, diff.length());
            }

            // Check if an error occurred in computation. If so, terminate
            // immediately.
            // Check if the position changed by a significant amount so as to
            // be erroneous. If so, terminate immediately.
            // maxVertexChange is scale-relative (fraction of mean edge length).
            if (VectorMath.isNaN(newPositions.get(0)) || (planarize && maxChange > maxVertexChange)) {
                break;
            }

            poly.setVertexPositions(newPositions);

            if (maxChange < threshold) {
                break;
            }

            currentPositions = Struct.copyVectorList(poly.getVertexPositions());
            iterations++;
        }

        poly.setVertexNormalsToFaceNormals();
        return iterations;
    }

    /**
     * Modifies a polyhedron's vertices such that faces are closer to planar. The more iterations, the closer the faces
     * are to planar. If a vertex moves by an unexpectedly large amount, or if the new vertex position has an NaN
     * component, the algorithm automatically terminates.
     *
     * @param poly          The polyhedron whose faces to planarize.
     * @param numIterations The number of iterations to planarize for.
     */
    public static void planarize(Polyhedron poly, int numIterations) {
        double maxVertexChange = meanVertexRadius(poly) * MAX_VERTEX_CHANGE_FACTOR;
        Polyhedron dual = poly.dual();
        for (int i = 0; i < numIterations; i++) {
            List<Vector3d> oldPositions = Struct.copyVectorList(poly.getVertexPositions());

            List<Vector3d> newDualPositions = reciprocalVertices(poly);
            dual.setVertexPositions(newDualPositions);
            List<Vector3d> newPositions = reciprocalVertices(dual);

            double maxChange = 0.;
            for (int j = 0; j < newPositions.size(); j++) {
                Vector3d diff = VectorMath.diff(newPositions.get(j), oldPositions.get(j));
                maxChange = Math.max(maxChange, diff.length());
            }

            // Check if an error occurred in computation. If so, terminate
            // immediately. This likely occurs when faces are already planar.
            // Check if the position changed by a significant amount so as to
            // be erroneous. If so, terminate immediately.
            // maxVertexChange is scale-relative (fraction of mean edge length).
            if (VectorMath.isNaN(newPositions.get(0)) || (maxChange > maxVertexChange)) {
                break;
            }

            poly.setVertexPositions(newPositions);
        }
        poly.setVertexNormalsToFaceNormals();
    }

    /**
     * Modifies a polyhedron's vertices such that faces are closer to planar. When no vertex moves more than the given
     * threshold, the algorithm terminates.
     *
     * @param poly      The polyhedron to canonicalize.
     * @param threshold The threshold of vertex movement after an iteration.
     * @return The number of iterations that were executed.
     */
    public static int planarize(Polyhedron poly, double threshold) {
        return canonicalize(poly, threshold, true);
    }

    /**
     * A port of the "reciprocalC" function written by George Hart. Reflects the centers of faces across the unit
     * sphere.
     *
     * @param poly The polyhedron whose centers to invert.
     * @return The list of inverted face centers.
     */
    private static List<Vector3d> reciprocalCenters(Polyhedron poly) {
        List<Vector3d> faceCenters = new ArrayList<>();
        for (Face face : poly.getFaces()) {
            Vector3d newCenter = new Vector3d(face.vertexAverage());
            newCenter.scale(1.0 / newCenter.lengthSquared());
            faceCenters.add(newCenter);
        }
        return faceCenters;
    }

    /**
     * A port of the "reciprocalN" function written by George Hart.
     *
     * @param poly The polyhedron to apply this canonicalization to.
     * @return A list of the new vertices of the dual polyhedron.
     */
    private static List<Vector3d> reciprocalVertices(Polyhedron poly) {
        List<Vector3d> newVertices = new ArrayList<>();

        List<Vector3d> vertexPositions = poly.getVertexPositions();
        for (Face face : poly.getFaces()) {
            // Initialize values which will be updated in the loop below
            Vector3d centroid = face.vertexAverage();
            Vector3d normalSum = new Vector3d();
            double avgEdgeDistance = 0.;

            // Retrieve the indices of the vertices defining this face
            int[] faceVertexIndices = face.getVertexIndices();

            // Keep track of the "previous" two vertices in CCW order
            int lastLastVertexIndex = faceVertexIndices[faceVertexIndices.length - 2];
            int lastVertexIndex = faceVertexIndices[faceVertexIndices.length - 1];
            for (int vertexIndex : faceVertexIndices) {
                Vector3d vertex = vertexPositions.get(vertexIndex);

                // Compute the normal of the plane defined by this vertex and
                // the previous two
                Vector3d lastlastVertex = vertexPositions.get(lastLastVertexIndex);
                Vector3d lastVertex = vertexPositions.get(lastVertexIndex);
                Vector3d v1 = new Vector3d(lastlastVertex);
                v1.sub(lastVertex);
                Vector3d v2 = new Vector3d(vertex);
                v2.sub(lastVertex);
                Vector3d normal = new Vector3d();
                normal.cross(v1, v2);
                normalSum.add(normal);

                // Compute distance from edge to origin
                avgEdgeDistance += Geometry.pointLineDist(new Vector3d(), lastlastVertex, lastVertex);

                // Update the previous vertices for the next iteration
                lastLastVertexIndex = lastVertexIndex;
                lastVertexIndex = vertexIndex;
            }

            normalSum.normalize();
            avgEdgeDistance /= faceVertexIndices.length;

            Vector3d resultingVector = new Vector3d();
            resultingVector.scale(centroid.dot(normalSum), normalSum);
            resultingVector.scale(1.0 / resultingVector.lengthSquared());
            resultingVector.scale((1.0 + avgEdgeDistance) / 2.0);
            newVertices.add(resultingVector);
        }

        return newVertices;
    }

    /**
     * Compute the mean distance of the polyhedron's vertices from their centroid.  This is used as a
     * bounding-sphere proxy for the scale-relative divergence guard in planarize().
     *
     * <p>The centroid-relative mean radius is <em>translation-invariant</em>: a mesh translated to world
     * coordinates (e.g., centroid at (100, 100, 100)) yields the same radius as the same mesh centred at
     * the origin.  The old origin-relative formula produced a radius of ~173 for a unit mesh at (100,100,100),
     * making the divergence threshold ~606 and permanently disabling the guard for any off-origin mesh.
     *
     * <p>Unlike mean edge length, the centroid-relative mean radius is not dominated by massively-distorted
     * edges — exactly the case the divergence guard must detect.  A single outlier vertex raises this mean
     * proportionally, keeping the threshold scaled to the actual distortion magnitude.
     *
     * <p>A floor of {@code 1e-6} is applied to prevent a degenerate all-coincident mesh (zero radius) from
     * producing a threshold of 0, which would cause the guard to fire immediately on any movement.
     *
     * @param poly The polyhedron.
     * @return The centroid-relative mean vertex radius, floored at 1e-6, or 1.0 if the polyhedron has no vertices.
     */
    private static double meanVertexRadius(Polyhedron poly) {
        List<Vector3d> positions = poly.getVertexPositions();
        if (positions.isEmpty()) {
            return 1.0;
        }

        // Compute centroid (translation-invariant anchor)
        Vector3d centroid = new Vector3d();
        for (Vector3d v : positions) {
            centroid.add(v);
        }
        centroid.scale(1.0 / positions.size());

        // Accumulate centroid-relative distances
        double total = 0.0;
        for (Vector3d v : positions) {
            Vector3d delta = new Vector3d(v);
            delta.sub(centroid);
            total += delta.length();
        }
        double meanRadius = total / positions.size();

        // Floor: prevent zero-radius (all-coincident) from making the threshold 0,
        // which would cause the guard to trip on ANY non-zero movement.
        return Math.max(meanRadius, 1e-6);
    }

}
