/**
 * Copyright (c) 2016 Hal Hildebrand, all rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Pair;

import javax.vecmath.Point3i;
import javax.vecmath.Vector3d;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The trad cubic grid
 *
 * @author hal.hildebrand
 */
public class CubicGrid extends Grid {
    private final Neighborhood neighborhood;
    private final Point3D      xAxis;
    private final Point3D      yAxis;
    private final Point3D      zAxis;

    public CubicGrid(Neighborhood neighborhood) {
        this(neighborhood, new Cube(1), 5);
    }

    public CubicGrid(Neighborhood neighborhood, Cube cube, Integer extent) {
        this(neighborhood, cube, new Pair<>(extent, extent), new Pair<>(extent, extent), new Pair<>(extent, extent));
    }

    public CubicGrid(Neighborhood neighborhood, Cube cube, Pair<Integer, Integer> xExtent,
                     Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        this(new Point3D(0, 0, 0), neighborhood, new Cube(1), xExtent, yExtent, zExtent);
    }

    public CubicGrid(Point3D origin, Neighborhood neighborhood, Cube cube, Pair<Integer, Integer> xExtent,
                     Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        this(origin, xExtent, xAxis(cube), cube.getEdgeLength(), yExtent, yAxis(cube), cube.getEdgeLength(), zExtent,
             zAxis(cube), cube.getEdgeLength(), neighborhood);
    }

    public CubicGrid(Point3D origin, Pair<Integer, Integer> xExtent, Point3D xAxis, double intervalX,
                     Pair<Integer, Integer> yExtent, Point3D yAxis, double intervalY, Pair<Integer, Integer> zExtent,
                     Point3D zAxis, double intervalZ, Neighborhood neighborhood) {

        super(intervalX, intervalY, intervalZ, origin, xExtent, yExtent, zExtent);
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.zAxis = zAxis;
        this.neighborhood = neighborhood;
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

    @Override
    public Point3i[] faceConnectedNeighbors(Point3i cell) {
        return new Point3i[0];
    }

    @Override
    public void position(int i, int j, int k, Node node) {

    }

    @Override
    public Transform positionTransform(int i, int j, int k) {
        Point3D vector = xAxis.multiply(i * intervalX).add(yAxis.multiply(j * intervalY)).add(
        zAxis.multiply(k * intervalZ));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    @Override
    public Point3i[] vertexConnectedNeighbors(Point3i cell) {
        return new Point3i[0];
    }

    @Override
    public Point3D xAxis() {
        return xAxis;
    }

    @Override
    public Point3D yAxis() {
        return yAxis;
    }

    @Override
    public Point3D zAxis() {
        return zAxis;
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

    public enum Neighborhood {
        EIGHT, SIX

    }
}
