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
import javafx.util.Pair;

import javax.vecmath.Point3i;
import java.util.function.Consumer;

import static com.hellblazer.luciferase.portal.mesh.explorer.Colors.*;
import static java.lang.Math.*;

/**
 * An abstract Grid.  The reference grid presented in standard X, Y, Z orthogonal axis points.  Each grid has an origin,
 * and each axis has an extent, which is the interval of points along that axis
 *
 * @author hal.hildebrand
 **/
public abstract class Grid {
    protected static final Point3D X_AXIS = new Point3D(1, 0, 0);
    protected static final Point3D Y_AXIS = new Point3D(0, 1, 0);
    protected static final Point3D Z_AXIS = new Point3D(0, 0, 1);
    protected static final float MULTIPLICATIVE_ROOT_2 = (float) pow(2, -0.5);
    protected static final float DIVIDE_ROOT_2 = (float) (1 / sqrt(2));
    protected final double intervalX;
    protected final double intervalY;
    protected final double intervalZ;
    protected final Point3D origin;
    protected final Pair<Integer, Integer> xExtent;
    protected final Pair<Integer, Integer> yExtent;
    protected final Pair<Integer, Integer> zExtent;

    public Grid(double intervalX, double intervalY, double intervalZ, Point3D origin, Pair<Integer, Integer> xExtent,
                Pair<Integer, Integer> yExtent, Pair<Integer, Integer> zExtent) {
        this.intervalX = intervalX;
        this.intervalY = intervalY;
        this.intervalZ = intervalZ;
        this.origin = origin;
        this.xExtent = xExtent;
        this.yExtent = yExtent;
        this.zExtent = zExtent;
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
        Grid.addConeBaseSegments(centerBase, divisions, top, radius, mesh.getPoints());
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

    /**
     * Add the axis to the group.
     * @param grid
     * @param radius
     * @param height
     * @param lineRadius
     * @param divisions
     */
    public void addAxes(Group grid, float radius, float height, float lineRadius, int divisions) {
        // X Axis
        Point3D xPositive = xAxis().multiply(intervalX * xExtent.getKey());
        Line axis = new Line(lineRadius, xAxis().multiply(-intervalX * xExtent.getKey()), xPositive);
        axis.setMaterial(redMaterial);
        grid.getChildren().addAll(axis);

        var cone = new MeshView(Grid.cone(radius / 2f, xPositive, Grid.extend(origin, xPositive, height), divisions));
        cone.setMaterial(redMaterial);
        grid.getChildren().add(cone);

        // Y Axis
        Point3D yPositive = yAxis().multiply(intervalY * yExtent.getKey());
        axis = new Line(lineRadius, yAxis().multiply(-intervalY * yExtent.getKey()), yPositive);
        axis.setMaterial(blueMaterial);
        grid.getChildren().addAll(axis);

        cone = new MeshView(Grid.cone(radius / 2f, yPositive, Grid.extend(origin, yPositive, height), divisions));
        cone.setMaterial(blueMaterial);
        grid.getChildren().add(cone);

        // Z Axis
        Point3D zPositive = zAxis().multiply(intervalZ * zExtent.getKey());
        axis = new Line(lineRadius, zAxis().multiply(-intervalZ * zExtent.getKey()), zPositive);
        axis.setMaterial(greenMaterial);
        grid.getChildren().addAll(axis);

        cone = new MeshView(Grid.cone(radius / 2f, zPositive, Grid.extend(origin, zPositive, height), divisions));
        cone.setMaterial(greenMaterial);
        grid.getChildren().add(cone);
    }

    abstract public Point3D xAxis();

    abstract public Point3D yAxis();

    abstract public Point3D zAxis();

    /**
     * Answer the face connected neighbors in the Grid
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public abstract Point3i[] faceConnectedNeighbors(Point3i cell);

    /**
     * Transform the supplied node to the X,Y,Z position in the native grid coordinates I, J, K
     * @param i
     * @param j
     * @param k
     * @param node
     */
    public abstract void position(int i, int j, int k, Node node);

    /**
     * Answer the Transform to the X,Y,Z position in the native grid coordinates I, J, K
     * @param i
     * @param j
     * @param k
     * @return
     */
    public abstract Transform positionTransform(int i, int j, int k);

    /**
     * Answer the 6 vertex connected neighbors in the RDB
     *
     * @param cell - the target cell
     * @return the array of Point3i vertex neighbor coordinates of the cell
     */
    public abstract Point3i[] vertexConnectedNeighbors(Point3i cell);

    /**
     * Answer the Group containing the 3 axis representation of the grid's axes
     * @param xaxis
     * @param yaxis
     * @param zaxis
     * @return
     */
    public abstract Group construct(Material xaxis, Material yaxis, Material zaxis);

    /**
     * Iterate over all cell coordinates in the grid
     * @param action
     */
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


    public Pair<Integer, Integer> getxExtent() {
        return xExtent;
    }

    public Pair<Integer, Integer> getyExtent() {
        return yExtent;
    }

    public Pair<Integer, Integer> getzExtent() {
        return zExtent;
    }
}
