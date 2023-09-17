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

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.portal.mesh.Line;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
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

    public void addAxes(Group grid, double radius, double height, double lineRadius, int divisions) {
        Point3D xPositive = xAxis.multiply(intervalX * xExtent.getKey());
        Line axis = new Line(lineRadius, xAxis.multiply(-intervalX * xExtent.getKey()), xPositive);
        axis.setMaterial(redMaterial);
        grid.getChildren().addAll(axis);
//        var cone = new MeshView(cone(radius / 2, height, divisions));
//        cone.setMaterial(redMaterial);
//        cone.setTranslateX(xPositive.getX() - height);
//        cone.setTranslateY(xPositive.getY());
//        cone.setTranslateZ(xPositive.getZ());
//        cone.getTransforms()
//            .add(affine(PrincipalAxis.Z.angle((90 + 45))
//                                       .combine(new Rotor3f(new Vector3f(0, 1, 0),
//                                                            new Vector3f((float) xAxis.getX(), (float) xAxis.getY(),
//                                                                         -(float) xAxis.getZ())))
//                                       .toMatrix()));
//        grid.getChildren().add(cone);

        Point3D yPositive = yAxis.multiply(intervalY * yExtent.getKey());
        axis = new Line(lineRadius, yAxis.multiply(-intervalY * yExtent.getKey()), yPositive);
        axis.setMaterial(blueMaterial);
        grid.getChildren().addAll(axis);
//        cone = new MeshView(cone(radius / 2, height, divisions));
//        cone.setMaterial(blueMaterial);
//        cone.setTranslateX(yPositive.getX());
//        cone.setTranslateY(yPositive.getY() - height);
//        cone.setTranslateZ(yPositive.getZ());
//        grid.getChildren().add(cone);
//        cone.getTransforms()
//            .add(affine(PrincipalAxis.Z.angle((-180))
//                                       .combine(new Rotor3f(new Vector3f(0, 1, 0),
//                                                            new Vector3f((float) yAxis.getX(), (float) yAxis.getY(),
//                                                                         -(float) yAxis.getZ())))
//                                       .toMatrix()));

        Point3D zPositive = zAxis.multiply(intervalZ * zExtent.getKey());
        axis = new Line(lineRadius, zAxis.multiply(-intervalZ * zExtent.getKey()), zPositive);
        axis.setMaterial(greenMaterial);
        grid.getChildren().addAll(axis);
//        cone = new MeshView(cone(radius / 2, height, divisions));
//        cone.setMaterial(greenMaterial);
//        cone.setTranslateX(zPositive.getX());
//        cone.setTranslateY(zPositive.getY());
//        cone.setTranslateZ(zPositive.getZ() - height);
//        cone.getTransforms().add(affine(PrincipalAxis.X.slerp(1f).toMatrix()));
//        grid.getChildren().add(cone);
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
