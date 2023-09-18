/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal;

import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.blueMaterial;
import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.greenMaterial;
import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.redMaterial;
import static java.lang.Math.abs;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.portal.mesh.Line;

import javafx.collections.ObservableFloatArray;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Pair;;

/**
 * Functional grid for a Rhombic Dodecahedron Grid (RDG). This is the Face
 * Center Cubic Grid. This grid encapsulates how to convert from cubic to and
 * from a rhombohedral isometric lattice. All integer coordinates in this
 * lattice correspond to a center of a cell and are thus valid coordinates. This
 * lattice is equivalent to tetrahedral / octahedral packing, but without the
 * headache of having to manage two separate primitives or interlaced grid
 * structures as with the face-centered cubic lattice, which produces an
 * equivalent structure.
 * <p>
 * This implementation is based off the
 * <a href="https://gist.github.com/paniq/3afdb420b5d94bf99e36">python gist by
 * Leonard Ritter</a>
 * <p>
 * There is another good grid mapping that uses a non orthogonal basis described
 * in the paper <a href=
 * "https://www.researchgate.net/publication/347616453_Digital_Objects_in_Rhombic_Dodecahedron_Grid/fulltext/609b5f7a458515d31513fb0a/Digital-Objects-in-Rhombic-Dodecahedron-Grid.pdf">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>. I
 * like the simplicity of the Tetrahedral coordinates, although having 2 basis
 * vectors be orthogonal would be pretty sweet.
 *
 * @author hal.hildebrand
 */
public class RDG {

    private static final float   DIVR2  = (float) (1 / Math.sqrt(2));
    private static final float   MULR2  = (float) Math.pow(2, -0.5);
    private static final Point3D X_AXIS = new Point3D(1, 0, 0);
    private static final Point3D Y_AXIS = new Point3D(0, 1, 0);
    private static final Point3D Z_AXIS = new Point3D(0, 0, 1);

    public static void addConeBaseSegments(Point3D centerBase, int divisions, Point3D top, float radius,
                                           ObservableFloatArray mesh) {
        var diff = top.subtract(centerBase);

        // P1 & P2 represent vectors that form the line.
        var dx = diff.getX();
        var dy = diff.getY();
        var dz = diff.getZ();

        var d = sqrt(dx * dx + dy * dy + dz * dz);

        // Normalized vector
        var v3x = dx / d;
        var v3y = dy / d;
        var v3z = dz / d;

        // Store vector elements in an array
        var p = new double[] { v3x, v3y, v3z };

        // Store vector elements in second array, this time with absolute value
        var p_abs = new double[] { abs(v3x), abs(v3y), abs(v3z) };

        // Find elements with MAX and MIN magnitudes
        var maxval = max(max(p_abs[0], p_abs[1]), p_abs[2]);
        var minval = min(min(p_abs[0], p_abs[1]), p_abs[2]);

        // Initialise 3 variables to store which array indexes contain the (max, medium,
        // min) vector magnitudes.
        var maxindex = 0;
        var medindex = 0;
        var minindex = 0;

        // Loop through p_abs array to find which magnitudes are equal to maxval &
        // minval. Store their indexes for use later.
        for (var i = 0; i < 3; i++) {
            if (p_abs[i] == maxval)
                maxindex = i;
            else if (p_abs[i] == minval)
                minindex = i;
        }

        // Find the remaining index which has the medium magnitude
        for (var i = 0; i < 3; i++) {
            if (i != maxindex && i != minindex) {
                medindex = i;
                break;
            }
        }

        // Store the maximum magnitude for now.
        var storemax = (p[maxindex]);

        // Swap the 2 indexes that contain the maximum & medium magnitudes, negating
        // maximum. Set minimum magnitude to zero.
        p[maxindex] = (p[medindex]);
        p[medindex] = -storemax;
        p[minindex] = 0;

        // Calculate v1. Perpendicular to v3.
        var s = sqrt(v3x * v3x + v3z * v3z + v3y * v3y);
        var v1x = s * p[0];
        var v1y = s * p[1];
        var v1z = s * p[2];

        // Calculate v2 as cross product of v3 and v1.
        var v2x = v3y * v1z - v3z * v1y;
        var v2y = v3z * v1x - v3x * v1z;
        var v2z = v3x * v1y - v3y * v1x;

        double segment_angle = 2.0 * Math.PI / divisions;

        // Reverse loop for speed!! der
        for (int i = divisions + 1; --i >= 0;) {
            var angle = segment_angle * i;
            var circlepointx = (float) (top.getX() + radius * (v1x * cos(angle) + v2x * sin(angle)));
            var circlepointy = (float) (top.getY() + radius * (v1y * cos(angle) + v2y * sin(angle)));
            var circlepointz = (float) (top.getZ() + radius * (v1z * cos(angle) + v2z * sin(angle)));
            mesh.addAll(circlepointx, circlepointy, circlepointz);
        }
    }

    public static Point3f axisAngle(float radians, Vector3f u, Vector3f w) {
        var sin = (float) Math.sin(radians);
        var cos = (float) Math.cos(radians);
        var cross = cross(w, u);
        var t = (1 - cos) * dot(w, u);

        var vx = cos * u.x + sin * cross.x + t * w.x;
        var vy = cos * u.y + sin * cross.y + t * w.y;
        var vz = cos * u.z + sin * cross.z + t * w.z;
        return new Point3f(vx, vy, vz);
    }

    public static TriangleMesh cone(float radius, Point3D top, Point3D centerBase, int divisions) {
        var mesh = new TriangleMesh();
        // Start with the top of the cone, later we will build our faces from these
        mesh.getPoints().addAll((float) top.getX(), (float) top.getY(), (float) top.getZ()); // Point 0: Top of the Cone
        addConeBaseSegments(centerBase, divisions, top, radius, mesh.getPoints());
        // Point N: Center of the Cone Base
        mesh.getPoints().addAll((float) centerBase.getX(), (float) centerBase.getY(), (float) centerBase.getZ());

        // @TODO Birdasaur for now we'll just make an empty texCoordinate group
        // @DUB HELP ME DUBi Wan Kanobi, you are my only hope!
        // I'm not good at determining Texture Coordinates
        mesh.getTexCoords().addAll(0, 0);
        // Add the faces "winding" the points generally counter clock wise
        // Must loop through each face, not including first and last points
        for (int i = 1; i <= divisions; i++) {
            mesh.getFaces()
                .addAll( // use dummy texCoords, @TODO Upgrade face code to be real
                        0, 0, i + 1, 0, i, 0, // Vertical Faces "wind" counter clockwise
                        divisions + 2, 0, i, 0, i + 1, 0 // Base Faces "wind" clockwise
                );
        }
        return mesh;
    }

    /**
     * Calculate the cross product of the two vectors, u and v, in tetrahedral
     * coordinates
     *
     * @param u
     * @param v
     * @return the Point3f representing the cross product in tetrahedral coordinates
     */
    public static Point3f cross(Tuple3f u, Tuple3f v) {
        return new Point3f((-u.x * (v.y - v.z) + u.y * (3 * v.z + v.x) - u.z * (v.x + 3 * v.y)) * (DIVR2 / 2),
                           (-u.x * (v.y + 3 * v.z) - u.y * (v.z - v.x) + u.z * (3 * v.x + v.y)) * (DIVR2 / 2),
                           (u.x * (3 * v.y + v.z) - u.y * (v.z + 3 * v.x) - u.z * (v.x - v.y)) * (DIVR2 / 2));
    }

    /**
     * 
     * Calculate the dot product of the two vectors, u and v, in tetrahedral
     * coordinates
     *
     * @param u
     * @param v
     * @return the dot product of the two vectors
     */
    public static float dot(Vector3f u, Vector3f v) {
        return (u.x * (v.x + v.y) + u.y * (v.x + v.x) + u.z * (v.y + v.x)) / 2 + u.x * v.x + u.y * v.y + u.z * v.x;
    }

    /**
     * Answer the euclidean length of the tetrahedral vector
     *
     * @param tetrahedral
     * @return the legth of the vector
     */
    public static float euclideanNorm(Vector3f tetrahedral) {
        return (float) Math.sqrt(tetrahedral.x * (tetrahedral.x + tetrahedral.y + tetrahedral.z)
        + tetrahedral.y * (tetrahedral.y + tetrahedral.z) + tetrahedral.z * tetrahedral.z);
    }

    public static Point3D extend(Point3D from, Point3D to, float additional) {
        var oldLength = to.distance(from);
        var diff = to.subtract(from);
        float lengthFraction = oldLength != 0.0f ? (float) ((additional + oldLength) / oldLength) : 0.0f;
        return diff.multiply(lengthFraction);
    }

    /**
     * Answer the 12 face connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public static Point3i[] faceConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[12];
        neighbors[0] = new Point3i(x + 1, y, z);
        neighbors[1] = new Point3i(x - 1, y, z);
        neighbors[2] = new Point3i(x, y + 1, z);
        neighbors[3] = new Point3i(x, y - 1, z);
        neighbors[4] = new Point3i(x, y, z + 1);
        neighbors[5] = new Point3i(x, y, z - 1);

        neighbors[6] = new Point3i(x, y + 1, z - 1);
        neighbors[7] = new Point3i(x, y - 1, z + 1);
        neighbors[8] = new Point3i(x - 1, y, z + 1);
        neighbors[9] = new Point3i(x + 1, y, z - 1);
        neighbors[10] = new Point3i(x + 1, y - 1, z);
        neighbors[11] = new Point3i(x - 1, y + 1, z);
        return neighbors;
    }

    /**
     * Answer the manhattan distance
     *
     * @param tetrahedral
     * @return the manhatten distance to the vector
     */
    public static float l1(Vector3f tetrahedral) {
        return Math.abs(tetrahedral.x) + Math.abs(tetrahedral.y) + Math.abs(tetrahedral.z);
    }

    public static Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta) {
        float x, y, z;
        float u, v, w;
        x = vec.getX();
        y = vec.getY();
        z = vec.getZ();
        u = axis.getX();
        v = axis.getY();
        w = axis.getZ();
        float xPrime = (float) (u * (u * x + v * y + w * z) * (1d - Math.cos(theta)) + x * Math.cos(theta)
        + (-w * y + v * z) * Math.sin(theta));
        float yPrime = (float) (v * (u * x + v * y + w * z) * (1d - Math.cos(theta)) + y * Math.cos(theta)
        + (w * x - u * z) * Math.sin(theta));
        float zPrime = (float) (w * (u * x + v * y + w * z) * (1d - Math.cos(theta)) + z * Math.cos(theta)
        + (-v * x + u * y) * Math.sin(theta));
        return new Vector3f(xPrime, yPrime, zPrime);
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point
     *
     * @param point3d
     * @return
     */
    public static Point3D toCartesian(Point3D tetrahedral) {
        return new Point3D((tetrahedral.getY() + tetrahedral.getZ()) * DIVR2,
                           (tetrahedral.getZ() + tetrahedral.getX()) * DIVR2,
                           (tetrahedral.getX() + tetrahedral.getY()) * DIVR2);
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point, preserving
     * edge length
     *
     * @param tetrahedral
     * @return
     */
    public static Point3D toCartesian(Tuple3i tetrahedral) {
        return new Point3D((tetrahedral.y + tetrahedral.z) * DIVR2, (tetrahedral.z + tetrahedral.x) * DIVR2,
                           (tetrahedral.x + tetrahedral.y) * DIVR2);
    }

    /**
     * convert the cartesian point to the equivalent tetrahedral point, preserving
     * edge length
     *
     * @param cartesian
     * @return
     */
    public static Point3i toTetrahedral(Tuple3f cartesian) {
        return new Point3i((int) ((-cartesian.x + cartesian.y + cartesian.z) * MULR2),
                           (int) ((cartesian.x - cartesian.y + cartesian.z) * MULR2),
                           (int) ((cartesian.x + cartesian.y - cartesian.z) * MULR2));
    }

    /**
     * 
     * Answer the 6 vertex connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public static Point3i[] vertexConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[6];
        neighbors[0] = new Point3i(x + 1, y + 1, z);
        neighbors[1] = new Point3i(x + 1, y - 1, z);
        neighbors[2] = new Point3i(x - 1, y + 1, z);
        neighbors[3] = new Point3i(x - 1, y - 1, z);
        neighbors[4] = new Point3i(x, y - 1, z + 1);
        neighbors[5] = new Point3i(x, y + 1, z - 1);
        return neighbors;
    }

    private final double intervalX, intervalY, intervalZ;

    private final Point3D origin, xAxis, yAxis, zAxis;

    private final Pair<Integer, Integer> xExtent, yExtent, zExtent;

    public RDG() {
        this(new Point3D(0d, 0d, 0d), new Pair<>(-5, 5), 1d, new Pair<>(-5, 5), 1d, new Pair<>(-5, 5), 1d);
    }

    public RDG(double edgeLength, int extent) {
        this(edgeLength, new Pair<>(-extent, extent), new Pair<>(-extent, extent), new Pair<>(-extent, extent));
    }

    public RDG(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent,
               Pair<Integer, Integer> zExtent) {
        this(new Point3D(0, 0, 0), xExtent, edgeLength, yExtent, edgeLength, zExtent, edgeLength);
    }

    public RDG(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent,
               double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        this.origin = origin;
        this.xExtent = xExtent;
        this.xAxis = toCartesian(X_AXIS.subtract(origin).normalize());
        this.intervalX = intervalX;
        this.yExtent = yExtent;
        this.yAxis = toCartesian(Y_AXIS.subtract(origin).normalize());
        this.intervalY = intervalY;
        this.zExtent = zExtent;
        this.zAxis = toCartesian(Z_AXIS.subtract(origin).normalize());
        this.intervalZ = intervalZ;
    }

    public void addAxes(Group grid, float radius, float height, float lineRadius, int divisions) {
        // X Axis
        Point3D xPositive = xAxis.multiply(intervalX * xExtent.getKey());
        Line axis = new Line(lineRadius, xAxis.multiply(-intervalX * xExtent.getKey()), xPositive);
        axis.setMaterial(redMaterial);
        grid.getChildren().addAll(axis);

        var cone = new MeshView(cone(radius / 2f, xPositive, extend(origin, xPositive, height), divisions));
        cone.setMaterial(redMaterial);
        grid.getChildren().add(cone);

        // Y Axis
        Point3D yPositive = yAxis.multiply(intervalY * yExtent.getKey());
        axis = new Line(lineRadius, yAxis.multiply(-intervalY * yExtent.getKey()), yPositive);
        axis.setMaterial(blueMaterial);
        grid.getChildren().addAll(axis);

        cone = new MeshView(cone(radius / 2f, yPositive, extend(origin, yPositive, height), divisions));
        cone.setMaterial(blueMaterial);
        grid.getChildren().add(cone);

        // Z Axis
        Point3D zPositive = zAxis.multiply(intervalZ * zExtent.getKey());
        axis = new Line(lineRadius, zAxis.multiply(-intervalZ * zExtent.getKey()), zPositive);
        axis.setMaterial(greenMaterial);
        grid.getChildren().addAll(axis);

        cone = new MeshView(cone(radius / 2f, zPositive, extend(origin, zPositive, height), divisions));
        cone.setMaterial(greenMaterial);
        grid.getChildren().add(cone);
    }

    public Group construct(Material xaxis, Material yaxis, Material zaxis) {
        Group grid = new Group();
        Point3D pos;
        Point3D neg;

        final Point3D deltaX = xAxis.multiply(intervalX);
        final Point3D deltaY = yAxis.multiply(intervalY);
        final Point3D deltaZ = zAxis.multiply(intervalZ);

        Point3D corner;
        corner = deltaY.multiply(yExtent.getKey()).add(deltaZ.multiply(zExtent.getKey()));
        neg = xAxis.multiply(-intervalX * (xExtent.getKey())).subtract(corner);
        pos = xAxis.multiply(intervalX * (xExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, yExtent.getKey() + yExtent.getValue(), zExtent.getKey() + zExtent.getValue(), xaxis,
                  (i, p) -> p.add(deltaY.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey()).add(deltaZ.multiply(zExtent.getKey()));
        neg = yAxis.multiply(-intervalY * (yExtent.getKey())).subtract(corner);
        pos = yAxis.multiply(intervalY * (yExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), zExtent.getKey() + zExtent.getValue(), yaxis,
                  (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey()).add(deltaY.multiply(yExtent.getKey()));
        neg = zAxis.multiply(-intervalZ * (zExtent.getKey())).subtract(corner);
        pos = zAxis.multiply(intervalZ * (zExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), yExtent.getKey() + yExtent.getValue(), zaxis,
                  (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaY));
        return grid;
    }

    public void forEach(Consumer<? super Point3i> action) {
        for (int i = xExtent.getKey(); i <= xExtent.getValue(); i++) {
            for (int j = yExtent.getKey(); j <= yExtent.getValue(); j++) {
                for (int k = zExtent.getKey(); k <= zExtent.getValue(); k++) {
                    action.accept(new Point3i(i, j, k));
                }
            }
        }
    }

    public double getIntervalX() {
        return intervalX;
    }

    public double getIntervalY() {
        return intervalY;
    }

    public double getIntervalZ() {
        return intervalZ;
    }

    public Point3D getOrigin() {
        return origin;
    }

    public Point3D getxAxis() {
        return xAxis;
    }

    public Pair<Integer, Integer> getxExtent() {
        return xExtent;
    }

    public Point3D getyAxis() {
        return yAxis;
    }

    public Pair<Integer, Integer> getyExtent() {
        return yExtent;
    }

    public Point3D getzAxis() {
        return zAxis;
    }

    public Pair<Integer, Integer> getzExtent() {
        return zExtent;
    }

    public void postition(int i, int j, int k, Node node) {
        node.getTransforms().add(postitionTransform(i, j, k));
    }

    public Transform postitionTransform(int i, int j, int k) {
        Point3D vector = toCartesian(new Point3i(i, j, k));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    private void construct(Group grid, Point3D neg, Point3D pos, double a, double b, Material material,
                           BiFunction<Double, Point3D, Point3D> advanceA, Function<Point3D, Point3D> advanceB) {
        Point3D start = neg;
        Point3D end = pos;
        Line axis;
        axis = new Line(0.015, start, end);
        axis.setMaterial(material);
        grid.getChildren().addAll(axis);
        for (int x = 0; x <= a; x++) {
            start = advanceA.apply((double) x, neg);
            end = advanceA.apply((double) x, pos);
            axis = new Line(0.015, start, end);
            axis.setMaterial(material);
            grid.getChildren().addAll(axis);
            for (int z = 0; z < b; z++) {
                start = advanceB.apply(start);
                end = advanceB.apply(end);
                axis = new Line(0.015, start, end);
                axis.setMaterial(material);
                grid.getChildren().addAll(axis);
            }
        }
    }
}
