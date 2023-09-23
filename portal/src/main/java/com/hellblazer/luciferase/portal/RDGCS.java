package com.hellblazer.luciferase.portal;

import com.hellblazer.luciferase.portal.mesh.Line;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Material;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Pair;

import javax.vecmath.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A Rhombic Dodecahedron Grid Coordinate System (RDGCS). This is the Face
 * Center Cubic Grid. This grid encapsulates how to convert to and from the cubic and
 * rhombic dodecahedron isometric lattice. All integer coordinates in this
 * grid/lattice correspond to a center of a cell and are valid coordinates.
 *
 * @author hal.hildebrand
 **/
abstract public class RDGCS extends Grid {
    protected final Point3D xAxis;
    protected final Point3D yAxis;
    protected final Point3D zAxis;

    public RDGCS() {
        this(new Point3D(0d, 0d, 0d), new Pair<Integer, Integer>(-5, 5), 1d,
                new Pair<Integer, Integer>(-5, 5), 1d, new Pair<Integer, Integer>(-5, 5), 1d);
    }
    public RDGCS(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent,
                 double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        super(intervalX, intervalY, intervalZ, origin, xExtent, yExtent, zExtent);
        xAxis = toCartesian(X_AXIS.subtract(origin).normalize());
        yAxis = toCartesian(Y_AXIS.subtract(origin).normalize());
        zAxis = toCartesian(Z_AXIS.subtract(origin).normalize());
    }
    public RDGCS(double edgeLength, int extent) {
        this(edgeLength, new Pair<>(-extent, extent), new Pair<>(-extent, extent), new Pair<>(-extent, extent));
    }

    public RDGCS(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent,
                 Pair<Integer, Integer> zExtent) {
        this(new Point3D(0, 0, 0), xExtent, edgeLength, yExtent, edgeLength, zExtent, edgeLength);
    }


    public RDGCS(double intervalX, double intervalY, double intervalZ, Point3D origin, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent, Point3D xAxis, Point3D yAxis, Point3D zAxis) {
        this(origin, xExtent, intervalX, yExtent, intervalY, zExtent, intervalZ);
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

    /**
     * Calculate the cross product of the two vectors, u and v, in rdg
     * coordinates
     *
     * @param u
     * @param v
     * @return the Point3f representing the cross product in rdg coordinates
     */
    abstract public Point3f cross(Tuple3f u, Tuple3f v);

    /**
     * Calculate the dot product of the two vectors, u and v, in rdg
     * coordinates
     *
     * @param u
     * @param v
     * @return the dot product of the two vectors
     */
    public abstract float dot(Vector3f u, Vector3f v);

    public abstract Vector3f rotateVectorCC(Vector3f vec, Vector3f axis, double theta);

    /**
     * convert the rdg point to the equivalent cartesian point
     *
     * @param rdg
     * @return
     */
    public abstract Point3D toCartesian(Point3D rdg);

    /**
     * convert the rdg point to the equivalent cartesian point, preserving
     * edge length
     *
     * @param rdg
     * @return
     */
    public abstract Point3D toCartesian(Tuple3i rdg);

    /**
     * convert the cartesian point to the equivalent rdg point, preserving
     * edge length
     *
     * @param cartesian
     * @return
     */
    public abstract Point3i toRDG(Tuple3f cartesian);


    @Override
    public void position(int i, int j, int k, Node node) {
        node.getTransforms().add(positionTransform(i, j, k));
    }

    @Override
    public Transform positionTransform(int i, int j, int k) {
        Point3D vector = toCartesian(new Point3i(i, j, k));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    @Override
    public Group construct(Material xaxis, Material yaxis, Material zaxis) {
        Group grid = new Group();
        Point3D pos;
        Point3D neg;

        final Point3D deltaX = xAxis.multiply(intervalX);
        final Point3D deltaY = yAxis.multiply(getIntervalY());
        final Point3D deltaZ = zAxis.multiply(intervalZ);

        Point3D corner;
        corner = deltaY.multiply(yExtent.getKey()).add(deltaZ.multiply(zExtent.getKey()));
        neg = xAxis.multiply(-intervalX * (xExtent.getKey())).subtract(corner);
        pos = xAxis.multiply(intervalX * (xExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, yExtent.getKey() + yExtent.getValue(), zExtent.getKey() + zExtent.getValue(), xaxis, (i, p) -> p.add(deltaY.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey()).add(deltaZ.multiply(zExtent.getKey()));
        neg = yAxis.multiply(-intervalY * (yExtent.getKey())).subtract(corner);
        pos = yAxis.multiply(intervalY * (yExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), zExtent.getKey() + zExtent.getValue(), yaxis, (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaZ));

        corner = deltaX.multiply(xExtent.getKey()).add(deltaY.multiply(yExtent.getKey()));
        neg = zAxis.multiply(-intervalZ * (zExtent.getKey())).subtract(corner);
        pos = zAxis.multiply(intervalZ * (zExtent.getValue())).subtract(corner);

        construct(grid, neg, pos, xExtent.getKey() + xExtent.getValue(), yExtent.getKey() + yExtent.getValue(), zaxis, (i, p) -> p.add(deltaX.multiply(i)), p -> p.add(deltaY));
        return grid;
    }

    private void construct(Group grid, Point3D neg, Point3D pos, double a, double b, Material material, BiFunction<Double, Point3D, Point3D> advanceA, Function<Point3D, Point3D> advanceB) {
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
