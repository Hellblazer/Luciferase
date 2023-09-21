package com.hellblazer.luciferase.portal;

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
import javafx.util.Pair;

import javax.vecmath.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.*;
import static java.lang.Math.*;

/**
 * A Rhombic Dodecahedron Grid Coordinate System (RDGCS). This is the Face
 * Center Cubic Grid. This grid encapsulates how to convert from cubic to and
 * from a rhombic dodecahedron isometric lattice. All integer coordinates in this
 * lattice correspond to a center of a cell and are thus valid coordinates.
 *
 * @author hal.hildebrand
 **/
abstract public class RDGCS {
    protected static final Point3D X_AXIS = new Point3D(1, 0, 0);
    protected static final Point3D Y_AXIS = new Point3D(0, 1, 0);
    protected static final Point3D Z_AXIS = new Point3D(0, 0, 1);
    protected static final float MULR2 = (float) pow(2, -0.5);
    protected static final float DIVR2 = (float) (1 / sqrt(2));
    protected final double intervalX;
    protected final double intervalY;
    protected final double intervalZ;

    protected final Point3D origin;
    protected final Point3D xAxis;
    protected final Point3D yAxis;
    protected final Point3D zAxis;
    protected final Pair<Integer, Integer> xExtent;
    protected final Pair<Integer, Integer> yExtent;
    protected final Pair<Integer, Integer> zExtent;

    public RDGCS() {
        this(new Point3D(0d, 0d, 0d), new Pair<Integer, Integer>(-5, 5), 1d,
                new Pair<Integer, Integer>(-5, 5), 1d, new Pair<Integer, Integer>(-5, 5), 1d);
    }


    public RDGCS(Point3D origin, Pair<Integer, Integer> xExtent, double intervalX, Pair<Integer, Integer> yExtent,
                 double intervalY, Pair<Integer, Integer> zExtent, double intervalZ) {
        this(intervalX, intervalY, intervalZ, origin, xExtent, yExtent, zExtent);
    }

    public RDGCS(double edgeLength, int extent) {
        this(edgeLength, new Pair<>(-extent, extent), new Pair<>(-extent, extent), new Pair<>(-extent, extent));
    }

    public RDGCS(double edgeLength, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent,
                 Pair<Integer, Integer> zExtent) {
        this(new Point3D(0, 0, 0), xExtent, edgeLength, yExtent, edgeLength, zExtent, edgeLength);
    }

    public RDGCS(double intervalX, double intervalY, double intervalZ, Point3D origin, Pair<Integer, Integer> xExtent, Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        this.intervalX = intervalX;
        this.intervalY = intervalY;
        this.intervalZ = intervalZ;
        this.origin = origin;
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
        this.xAxis = toCartesian(X_AXIS.subtract(origin).normalize());
        this.yAxis = toCartesian(Y_AXIS.subtract(origin).normalize());
        this.zAxis = toCartesian(Z_AXIS.subtract(origin).normalize());
    }

    public static void addConeBaseSegments(Point3D centerBase, int divisions, Point3D top, float radius, ObservableFloatArray mesh) {
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
        var p = new double[]{v3x, v3y, v3z};

        // Store vector elements in second array, this time with absolute value
        var p_abs = new double[]{abs(v3x), abs(v3y), abs(v3z)};

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
            if (p_abs[i] == maxval) maxindex = i;
            else if (p_abs[i] == minval) minindex = i;
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

        double segment_angle = 2.0 * PI / divisions;

        // Reverse loop for speed!! der
        for (int i = divisions + 1; --i >= 0; ) {
            var angle = segment_angle * i;
            var circlepointx = (float) (top.getX() + radius * (v1x * cos(angle) + v2x * sin(angle)));
            var circlepointy = (float) (top.getY() + radius * (v1y * cos(angle) + v2y * sin(angle)));
            var circlepointz = (float) (top.getZ() + radius * (v1z * cos(angle) + v2z * sin(angle)));
            mesh.addAll(circlepointx, circlepointy, circlepointz);
        }
    }

    public static Point3D extend(Point3D from, Point3D to, float additional) {
        var oldLength = to.distance(from);
        var diff = to.subtract(from);
        float lengthFraction = oldLength != 0.0f ? (float) ((additional + oldLength) / oldLength) : 0.0f;
        return diff.multiply(lengthFraction);
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
            mesh.getFaces().addAll( // use dummy texCoords, @TODO Upgrade face code to be real
                    0, 0, i + 1, 0, i, 0, // Vertical Faces "wind" counter clockwise
                    divisions + 2, 0, i, 0, i + 1, 0 // Base Faces "wind" clockwise
            );
        }
        return mesh;
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

    /**
     * Answer the 12 face connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public abstract Point3i[] faceConnectedNeighbors(Point3i cell);

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


    public void position(int i, int j, int k, Node node) {
        node.getTransforms().add(positionTransform(i, j, k));
    }

    public Transform positionTransform(int i, int j, int k) {
        Point3D vector = toCartesian(new Point3i(i, j, k));
        return new Translate(vector.getX(), vector.getY(), vector.getZ());
    }

    /**
     * Answer the 6 vertex connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public abstract Point3i[] vertexConnectedNeighbors(Point3i cell);

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
