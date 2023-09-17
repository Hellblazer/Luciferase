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

import com.hellblazer.luciferase.portal.mesh.Line;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Pair;;

/**
 * Functional interface for a Rhombic Dodecahedron Grid (RDG). This grid is
 * based on a a non-orthogonal coordinate system to represent the RDG where each
 * grid point represents the centroid of a rhombic dodecahedron cell. The
 * novelty of this system is that every single one of the integer coordinate
 * points is the centroid of a dodecahedron. The RDG uses integer points,
 * Point3i, to represent coordinates of cells of the grid.
 * <p>
 * The image below shows the transformation of the original cubic basis vectors
 * in (a) to the basis vectors used for the RDG in (c).
 * <p>
 * <img src=
 * "https://media.springernature.com/lw685/springer-static/image/chp%3A10.1007%2F978-3-030-14085-4_3/MediaObjects/481128_1_En_3_Fig2_HTML.png?as=webp"
 * />
 * 
 * <p>
 * These grid functions are implementations from the paper <a href=
 * "https://www.researchgate.net/publication/347616453_Digital_Objects_in_Rhombic_Dodecahedron_Grid/fulltext/609b5f7a458515d31513fb0a/Digital-Objects-in-Rhombic-Dodecahedron-Grid.pdf">Rhombic
 * Dodecahedron Grid—Coordinate System and 3D Digital Object Definitions</a>
 * <p>
 *
 * @author hal.hildebrand
 */
public class RDG {

    static float                 DIVR2  = (float) (1 / Math.sqrt(2));
    static float                 MULR2  = (float) Math.pow(2, -0.5);
    private static final Point3D X_AXIS = new Point3D(1, 0, 0);

    private static final Point3D Y_AXIS = new Point3D(0, 1, 0);

    private static final Point3D Z_AXIS = new Point3D(0, 0, 1);

    public static Point3D toCartesian(Point3D rdg) {
        var x = rdg.getX();
        var y = rdg.getY();
        var z = rdg.getZ();
        return new Point3D(x + y - z, -x + y, z);
    }

    public static Point3i toCartesian(Point3i rdg) {
        var x = rdg.x;
        var y = rdg.y;
        var z = rdg.z;
        return new Point3i(x + y - z, -x + y, z);
    }

    public static Point3D toRDG(Point3D cartesian) {
        var x = cartesian.getX();
        var y = cartesian.getY();
        var z = cartesian.getZ();
        return new Point3D((x - y + z) / 2, (x + y + z) / 2, z);
    }

    public static Point3i toRDG(Point3i cartesian) {
        var x = cartesian.x;
        var y = cartesian.y;
        var z = cartesian.z;
        return new Point3i((x - y + z) / 2, (x + y + z) / 2, z);
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

    /**
     * Answer the norm distance between two RDG points u and v
     *
     * @param u - point in RDG coordinates
     * @param v - point in RDG coordinates
     * @return the norm distance between u and v
     */
    public double distance(Point3f u, Point3f v) {
        var dx = u.x - v.x;
        var dy = u.y - v.y;
        var dz = u.z - v.z;
        var dx2 = dx * dx;
        var dy2 = dy * dy;
        var dz2 = dz * dz;
        var squared = dx2 + dy2 + dz2;
        var dxDz = dx * dz;
        var dyDz = dy * dz;

        return Math.sqrt(squared - dxDz - dyDz);
    }

    /**
     * Answer the euclidaean distance betwwen two RDG points u and v
     *
     * @param u - point in RDG coordinates
     * @param v - point in RDG coordinates
     * @return the euclidian distance between u and v
     */
    public double euclideanDistance(Point3f u, Point3f v) {
        var dx = u.x - v.x;
        var dy = u.y - v.y;
        var dz = u.z - v.z;
        var dx2 = dx * dx;
        var dy2 = dy * dy;
        var dz2 = dz * dz;
        var squared = dx2 + dy2 + dz2;
        var dxDz = dx * dz;
        var dyDz = dy * dz;

        return Math.sqrt(2 * (squared - dxDz - dyDz));
    }

    /**
     * Answer the 12 face connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public Point3i[] faceConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[12];
        neighbors[0] = new Point3i(x + 1, y, z);
        neighbors[1] = new Point3i(x - 1, y, z);
        neighbors[2] = new Point3i(x, y + 1, 1);
        neighbors[3] = new Point3i(x, y - 1, z);
        neighbors[4] = new Point3i(x, y, z + 1);
        neighbors[5] = new Point3i(x, y, z - 1);
        neighbors[6] = new Point3i(x + 1, y + 1, z + 1);
        neighbors[7] = new Point3i(x - 1, y - 1, z - 1);
        neighbors[8] = new Point3i(x, y + 1, z + 1);
        neighbors[9] = new Point3i(x + 1, y, z + 1);
        neighbors[10] = new Point3i(x - 1, y, z - 1);
        neighbors[11] = new Point3i(x, y - 1, z - 1);
        return neighbors;
    }

    public void forEach(Consumer<? super Point3i> action) {
        for (int i = xExtent.getKey(); i <= xExtent.getValue(); i++) {
            for (int j = yExtent.getKey(); j <= yExtent.getValue(); j++) {
                for (int k = zExtent.getKey(); k <= zExtent.getValue(); k++) {
                    if ((i + j + k) % 2 == 0) {
                        action.accept(new Point3i(i, j, k));
                    }
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

    public Group populate(Material material, double radius) {
        var group = new Group();
        forEach(location -> {

            Transform position = postitionTransform(location.x - Math.ceil(intervalX / 2),
                                                    location.y - Math.ceil(intervalY / 2),
                                                    location.z - Math.ceil(intervalZ / 2));
            var sphere = new Sphere(radius);
            sphere.setMaterial(material);
            sphere.getTransforms().clear();
            sphere.getTransforms().addAll(position);
            group.getChildren().add(sphere);
        });
        return group;
    }

    public void postition(double i, double j, double k, Node node) {
        Point3D vector = toCartesian(xAxis.multiply(i * intervalX)
                                          .add(yAxis.multiply(j * intervalY))
                                          .add(zAxis.multiply(k * intervalZ)));
        node.getTransforms().add(new Translate(vector.getX(), vector.getY(), vector.getZ()));
    }

    public Transform postitionTransform(double i, double j, double k) {
        Point3D vector = toCartesian(xAxis.multiply(i * intervalX)
                                          .add(yAxis.multiply(j * intervalY))
                                          .add(zAxis.multiply(k * intervalZ)));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    /**
     * Answer the symmetric point v corresponding to point u in the symmetry group
     *
     * @param group - the symmetry group
     * @param u     - the point in rdg coordinates
     * @return the symmetric Point3i of u in the symmetry group in rdg coordinates
     */
    public Point3i symmetry(int group, Point3i u) {
        var x = u.x;
        var y = u.y;
        var z = u.z;
        var mx = -x;
        var my = -y;
        var mz = -z;

        return switch (group) {
        case 0 -> u;
        case 1 -> new Point3i(mx + z, y, mx + y);
        case 2 -> new Point3i(y, mx + z, z);
        case 3 -> new Point3i(y, x, z);
        case 4 -> new Point3i(my + z, x, x + my);
        case 5 -> new Point3i(mx, y + mz, mz);
        case 6 -> new Point3i(mx + z, my + z, z);
        case 7 -> new Point3i(x, my + z, x + my);
        case 8 -> new Point3i(my, x + mz, mz);
        case 9 -> new Point3i(my, mx, mz);
        case 10 -> new Point3i(y + mz, mx, mx + y);
        case 11 -> new Point3i(x + mz, my, mz);

        case 12 -> new Point3i(y + mz, y, mx + y);
        case 13 -> new Point3i(x, y, x + y + mz);
        case 14 -> new Point3i(mx, mx + z, mx - y + z);
        case 15 -> new Point3i(x + mz, x, x + my);
        case 16 -> new Point3i(y, x, x + y + mz);
        case 17 -> new Point3i(y, y + mz, x + y + mz);
        case 18 -> new Point3i(my, my + z, x + my);
        case 19 -> new Point3i(mx + z, my + z, mx + my + z);
        case 20 -> new Point3i(x, x + mz, x + y + mz);
        case 21 -> new Point3i(mx + z, mx, mx + y);
        case 22 -> new Point3i(my, mx, mx + my + z);
        case 23 -> new Point3i(my + z, my, mx + my + z);

        case 24 -> new Point3i(mx + z, y, z);
        case 25 -> new Point3i(my + z, mx + z, z);
        case 26 -> new Point3i(y, mx + z, mx + y);
        case 27 -> new Point3i(my + z, x, z);
        case 28 -> new Point3i(x + mz, y + mz, mz);
        case 29 -> new Point3i(mx, y + mz, mx + y);
        case 30 -> new Point3i(x, my + z, z);
        case 31 -> new Point3i(y + mz, x + mz, mz);
        case 32 -> new Point3i(my, x + mz, x + my);
        case 33 -> new Point3i(y + mz, mx, mz);
        case 34 -> new Point3i(mx, my, mz);
        case 35 -> new Point3i(x + mz, my, x + my);

        case 36 -> new Point3i(y + mz, y, x + y + mz);
        case 37 -> new Point3i(mx, mx + z, mx + y);
        case 38 -> new Point3i(my + z, mx + z, mx + my + z);
        case 39 -> new Point3i(x + mz, x, x + y + mz);
        case 40 -> new Point3i(y, y + mz, mx + y);
        case 41 -> new Point3i(x + mz, y + mz, x + y + mz);
        case 42 -> new Point3i(my, my + z, mx + my + z);
        case 43 -> new Point3i(x, x + mz, x + my);
        case 44 -> new Point3i(y + mz, x + mz, x + y + mz);
        case 45 -> new Point3i(mx + z, mx, mx + my + z);
        case 46 -> new Point3i(my + z, my, x + my);
        case 47 -> new Point3i(mx, my, mx + my + z);

        default -> throw new IllegalArgumentException("Invalid symmetry group: " + group);
        };
    }

    /**
     * Answer the symmetric point v corresponding to u in the symmetry group
     *
     * @param group - the symmetry group
     * @param u     - the point in orthogonal coordinates
     * @return the symmetric Point3i of u in the symmetry group in orthogonal
     *         coordinates
     */
    public Point3i symmetryOrtho(int group, Point3i u) {
        var x = u.x;
        var y = u.y;
        var z = u.z;
        var mx = -x;
        var my = -y;
        var mz = -z;

        return switch (group) {
        case 0 -> u;
        case 1 -> new Point3i(z, x, y);
        case 2 -> new Point3i(y, mx, z);
        case 3 -> new Point3i(x, my, z);
        case 4 -> new Point3i(z, x, my);
        case 5 -> new Point3i(y, x, mz);
        case 6 -> new Point3i(mx, my, z);
        case 7 -> new Point3i(z, mx, my);
        case 8 -> new Point3i(my, x, mz);
        case 9 -> new Point3i(mx, y, mz);
        case 10 -> new Point3i(mz, mx, y);
        case 11 -> new Point3i(my, mx, mz);

        case 12 -> new Point3i(x, z, y);
        case 13 -> new Point3i(z, y, x);
        case 14 -> new Point3i(y, z, mx);
        case 15 -> new Point3i(x, z, my);
        case 16 -> new Point3i(z, my, x);
        case 17 -> new Point3i(y, mz, x);
        case 18 -> new Point3i(mx, z, my);
        case 19 -> new Point3i(z, my, mx);
        case 20 -> new Point3i(my, mz, x);
        case 21 -> new Point3i(mx, mz, y);
        case 22 -> new Point3i(mz, y, mx);
        case 23 -> new Point3i(my, mz, mx);

        case 24 -> new Point3i(y, x, z);
        case 25 -> new Point3i(mx, y, z);
        case 26 -> new Point3i(z, mx, y);
        case 27 -> new Point3i(my, x, z);
        case 28 -> new Point3i(x, y, mz);
        case 29 -> new Point3i(mz, x, y);
        case 30 -> new Point3i(my, mx, z);
        case 31 -> new Point3i(x, my, mz);
        case 32 -> new Point3i(mz, x, my);
        case 33 -> new Point3i(y, mx, mz);
        case 34 -> new Point3i(mx, my, mz);
        case 35 -> new Point3i(mz, mx, my);

        case 36 -> new Point3i(y, z, x);
        case 37 -> new Point3i(mx, z, y);
        case 38 -> new Point3i(z, y, mx);
        case 39 -> new Point3i(my, z, x);
        case 40 -> new Point3i(x, mz, y);
        case 41 -> new Point3i(mz, y, x);
        case 42 -> new Point3i(my, z, mx);
        case 43 -> new Point3i(x, mz, my);
        case 44 -> new Point3i(mz, my, x);
        case 45 -> new Point3i(y, mz, mx);
        case 46 -> new Point3i(mx, mz, my);
        case 47 -> new Point3i(mz, my, mx);

        default -> throw new IllegalArgumentException("Invalid symmetry group: " + group);
        };
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point
     *
     * @param tetrahedral
     * @return
     */
    public Point3f toCartesian(Tuple3f tetrahedral) {
        return new Point3f(tetrahedral.y + tetrahedral.z, tetrahedral.z + tetrahedral.x, tetrahedral.x + tetrahedral.y);
    }

    /**
     * convert the tetrahedral point to the equivalent cartesian point, preserving
     * edge length
     *
     * @param tetrahedral
     * @return
     */
    public Point3f toCartesianUnit(Tuple3f tetrahedral) {
        return new Point3f((tetrahedral.y + tetrahedral.z) * DIVR2, (tetrahedral.z + tetrahedral.x) * DIVR2,
                           (tetrahedral.x + tetrahedral.y) * DIVR2);
    }

    /**
     * convert the cartesian point to the equivalent tetrahedral point
     *
     * @param cartesian
     * @return
     */
    public Point3f toTetrahedral(Tuple3f cartesian) {
        return new Point3f((-cartesian.x + cartesian.y + cartesian.z) / 2,
                           (cartesian.x - cartesian.y + cartesian.z) / 2,
                           (cartesian.x + cartesian.y - cartesian.z) / 2);
    }

    /**
     * convert the cartesian point to the equivalent tetrahedral point, preserving
     * edge length
     *
     * @param cartesian
     * @return
     */
    public Point3f toTetrahedralUnit(Tuple3f cartesian) {
        return new Point3f((-cartesian.x + cartesian.y + cartesian.z) * DIVR2,
                           (cartesian.x - cartesian.y + cartesian.z) * DIVR2,
                           (cartesian.x + cartesian.y - cartesian.z) * DIVR2);
    }

    /**
     * 
     * Answer the 6 vertex connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public Point3i[] vertexConnectedNeighbors(Point3i cell) {
        var x = cell.x;
        var y = cell.y;
        var z = cell.z;
        var neighbors = new Point3i[6];
        neighbors[0] = new Point3i(x + 1, y + 1, z);
        neighbors[1] = new Point3i(x + 1, y - 1, z);
        neighbors[2] = new Point3i(x - 1, y + 1, z);
        neighbors[3] = new Point3i(x - 1, y - 1, z);
        neighbors[4] = new Point3i(x + 1, y + 1, z + 2);
        neighbors[5] = new Point3i(x - 1, y - 1, z - 2);
        return neighbors;
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
