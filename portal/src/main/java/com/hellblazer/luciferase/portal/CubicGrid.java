/**
 * Copyright (c) 2016 Chiral Behaviors, LLC, all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hellblazer.luciferase.portal;

import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.blueMaterial;
import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.greenMaterial;
import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.redMaterial;

import java.util.function.BiFunction;
import java.util.function.Function;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3d;

import com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Pair;

/**
 * @author halhildebrand
 *
 */
public class CubicGrid {

    public static enum Neighborhood {
        EIGHT, SIX

    }

    public static Affine affine(Matrix4f m) {
        var t = new Affine();
        t.setToTransform(m.getM00(), m.getM10(), m.getM20(), m.getM30(), m.getM01(), m.getM11(), m.getM21(), m.getM31(),
                         m.getM02(), m.getM12(), m.getM22(), m.getM32());
        return t;
    }

    public static TriangleMesh cone(double radius, double height, int divisions) {
        var mesh = new TriangleMesh();
        // Start with the top of the cone, later we will build our faces from these
        mesh.getPoints().addAll(0, 0, 0); // Point 0: Top of the Cone
        // Generate the segments of the bottom circle (Cone Base)
        double segment_angle = 2.0 * Math.PI / divisions;
        float x, z;
        double angle;
        double halfCount = (Math.PI / 2 - Math.PI / (divisions / 2));
        // Reverse loop for speed!! der
        for (int i = divisions + 1; --i >= 0;) {
            angle = segment_angle * i;
            x = (float) (radius * Math.cos(angle - halfCount));
            z = (float) (radius * Math.sin(angle - halfCount));
            mesh.getPoints().addAll(x, (float) height, z);
        }
        mesh.getPoints().addAll(0, (float) height, 0); // Point N: Center of the Cone Base

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

    public static Point3D xAxis(Cube cube) {
        Vector3d vector = cube.getFaces().get(1).centroid();
        return new Point3D(vector.x, vector.y, vector.z);
    }

    public static Point3D yAxis(Cube cube) {
        Vector3d vector = cube.getFaces().get(2).centroid();
        return new Point3D(vector.x, vector.y, vector.z);
    }

    public static Point3D zAxis(Cube cube) {
        Vector3d vector = cube.getFaces().get(0).centroid();
        return new Point3D(vector.x, vector.y, vector.z);
    }

    private final double               intervalX;
    private final double               intervalY;
    private final double               intervalZ;
    private final Neighborhood         neighborhood;
    private final Point3D              origin;
    private final Point3D              xAxis;
    private final Pair<Double, Double> xExtent;
    private final Point3D              yAxis;
    private final Pair<Double, Double> yExtent;
    private final Point3D              zAxis;

    private final Pair<Double, Double> zExtent;

    public CubicGrid(Neighborhood neighborhood) {
        this(neighborhood, new Point3D(0d, 0d, 0d), new Pair<>(5d, 5d), new Point3D(1d, 0d, 0d), 1d, new Pair<>(5d, 5d),
             new Point3D(0d, 1d, 0d), 1d, new Pair<>(5d, 5d), new Point3D(0d, 0d, 1d), 1d);
    }

    public CubicGrid(Neighborhood neighborhood, Cube cube, double extent) {
        this(neighborhood, cube, new Pair<>(extent, extent), new Pair<>(extent, extent), new Pair<>(extent, extent));
    }

    public CubicGrid(Neighborhood neighborhood, Cube cube, Pair<Double, Double> xExtent, Pair<Double, Double> yExtent,
                     Pair<Double, Double> zExtent) {
        this(neighborhood, new Point3D(0, 0, 0), xExtent, xAxis(cube), cube.getEdgeLength(), yExtent, yAxis(cube),
             cube.getEdgeLength(), zExtent, zAxis(cube), cube.getEdgeLength());
    }

    public CubicGrid(Neighborhood neighborhood, Point3D origin, Pair<Double, Double> xExtent, Point3D xAxis,
                     double intervalX, Pair<Double, Double> yExtent, Point3D yAxis, double intervalY,
                     Pair<Double, Double> zExtent, Point3D zAxis, double intervalZ) {
        this.origin = origin;
        this.neighborhood = neighborhood;
        this.xExtent = xExtent;
        this.xAxis = xAxis.subtract(origin).normalize();
        this.intervalX = intervalX;
        this.yExtent = yExtent;
        this.yAxis = yAxis.subtract(origin).normalize();
        this.intervalY = intervalY;
        this.zExtent = zExtent;
        this.zAxis = zAxis.subtract(origin).normalize();
        this.intervalZ = intervalZ;
    }

    public void addAxes(Group grid, double radius, double height, double lineRadius, int divisions) {
        Point3D xPositive = xAxis.multiply(intervalX * xExtent.getKey());
        Line axis = new Line(lineRadius, xAxis.multiply(-intervalX * xExtent.getKey()), xPositive);
        axis.setMaterial(redMaterial);
        grid.getChildren().addAll(axis);
        var cone = new MeshView(cone(radius / 2, height, divisions));
        cone.setMaterial(redMaterial);
        cone.setTranslateX(xPositive.getX() - height);
        cone.setTranslateY(xPositive.getY());
        cone.setTranslateZ(xPositive.getZ());
        cone.getTransforms().add(affine(PrincipalAxis.Z.rotation(-1f).toMatrix()));
        grid.getChildren().add(cone);

        Point3D yPositive = yAxis.multiply(intervalY * yExtent.getKey());
        axis = new Line(lineRadius, yAxis.multiply(-intervalY * yExtent.getKey()), yPositive);
        axis.setMaterial(blueMaterial);
        grid.getChildren().addAll(axis);
        cone = new MeshView(cone(radius / 2, height, divisions));
        cone.setMaterial(blueMaterial);
        cone.setTranslateX(yPositive.getX());
        cone.setTranslateY(yPositive.getY() - height);
        cone.setTranslateZ(yPositive.getZ());
        grid.getChildren().add(cone);

        Point3D zPositive = zAxis.multiply(intervalZ * zExtent.getKey());
        axis = new Line(lineRadius, zAxis.multiply(-intervalZ * zExtent.getKey()), zPositive);
        axis.setMaterial(greenMaterial);
        grid.getChildren().addAll(axis);
        cone = new MeshView(cone(radius / 2, height, divisions));
        cone.setMaterial(greenMaterial);
        cone.setTranslateX(zPositive.getX());
        cone.setTranslateY(zPositive.getY());
        cone.setTranslateZ(zPositive.getZ() - height);
        cone.getTransforms().add(affine(PrincipalAxis.X.rotation(1f).toMatrix()));
        grid.getChildren().add(cone);
    }

    public Group construct(Material xaxis, Material yaxis, Material zaxis) {
        Group grid = new Group();
        Point3D pos;
        Point3D neg;
        double bodyOffset = neighborhood == Neighborhood.SIX ? 0.5 : 0;

        final Point3D deltaX = xAxis.multiply(intervalX);
        final Point3D deltaY = yAxis.multiply(intervalY);
        final Point3D deltaZ = zAxis.multiply(intervalZ);

        Point3D corner;
        corner = deltaY.multiply(yExtent.getKey() + bodyOffset).add(deltaZ.multiply(zExtent.getKey() + bodyOffset));
        neg = xAxis.multiply(-intervalX * (xExtent.getKey() + bodyOffset)).subtract(corner);
        pos = xAxis.multiply(intervalX * (xExtent.getValue() + bodyOffset)).subtract(corner);

        construct(grid, neg, pos, yExtent.getKey() + yExtent.getValue(), zExtent.getKey() + zExtent.getValue(), xaxis,
                  (i, p) -> p.add(deltaY.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey() + bodyOffset).add(deltaZ.multiply(zExtent.getKey() + bodyOffset));
        neg = yAxis.multiply(-intervalY * (yExtent.getKey() + bodyOffset)).subtract(corner);
        pos = yAxis.multiply(intervalY * (yExtent.getValue() + bodyOffset)).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), zExtent.getKey() + zExtent.getValue(), yaxis,
                  (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey() + bodyOffset).add(deltaY.multiply(yExtent.getKey() + bodyOffset));
        neg = zAxis.multiply(-intervalZ * (zExtent.getKey() + bodyOffset)).subtract(corner);
        pos = zAxis.multiply(intervalZ * (zExtent.getValue() + bodyOffset)).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), yExtent.getKey() + yExtent.getValue(), zaxis,
                  (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaY));
        return grid;
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

    public Pair<Double, Double> getxExtent() {
        return xExtent;
    }

    public Point3D getyAxis() {
        return yAxis;
    }

    public Pair<Double, Double> getyExtent() {
        return yExtent;
    }

    public Point3D getzAxis() {
        return zAxis;
    }

    public Pair<Double, Double> getzExtent() {
        return zExtent;
    }

    public void postition(double i, double j, double k, Node node) {
        Point3D vector = xAxis.multiply(i * intervalX)
                              .add(yAxis.multiply(j * intervalY))
                              .add(zAxis.multiply(k * intervalZ));
        node.getTransforms().add(new Translate(vector.getX(), vector.getY(), vector.getZ()));
    }

    public Transform postitionTransform(double i, double j, double k) {
        Point3D vector = xAxis.multiply(i * intervalX)
                              .add(yAxis.multiply(j * intervalY))
                              .add(zAxis.multiply(k * intervalZ));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    private void construct(Group grid, Point3D neg, Point3D pos, double a, double b, Material material,
                           BiFunction<Double, Point3D, Point3D> advanceA, Function<Point3D, Point3D> advanceB) {
        a = neighborhood == Neighborhood.SIX ? a + 1 : a;
        b = neighborhood == Neighborhood.SIX ? b + 1 : b;
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
        if (neighborhood == Neighborhood.SIX) {
            start = advanceA.apply(a, neg);
            end = advanceA.apply(a, pos);
            axis = new Line(0.015, start, end);
            axis.setMaterial(material);
            grid.getChildren().addAll(axis);
        }
    }
}
