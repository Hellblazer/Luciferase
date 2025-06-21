package com.hellblazer.luciferase.portal.tree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.octree.Octant;
import com.hellblazer.luciferase.portal.mesh.Edge;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import com.hellblazer.luciferase.portal.mesh.explorer.Colors;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Cube;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;

import javax.vecmath.Vector3d;

/**
 * @author hal.hildebrand
 **/
public class OctreeInspector extends Abstract3DApp {
    public static final double OPACITY_PER_LEVEL = 1.0d / Constants.getMaxRefinementLevel();

    private final Group           view   = new Group();
    private final PhongMaterial[] levels = new PhongMaterial[Constants.getMaxRefinementLevel()];
    private final Octant          root;

    public OctreeInspector() {
        root = new Octant(0, 0, 0, 20);
        var darkgreen = Color.DARKGREEN;
        for (byte i = 0; i < levels.length; i++) {
            var color = new Color(darkgreen.getRed(), darkgreen.getGreen(), darkgreen.getBlue(), opacity(i));
            levels[i] = new PhongMaterial(color);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    public void add(Octant octant) {
        add(cube(octant), levels[octant.level()]);
    }

    public double opacity(byte level) {
        return OPACITY_PER_LEVEL * level;
    }

    protected void add(final Polyhedron polyhedron, Material material) {
        MeshView v = new MeshView(polyhedron.toTriangleMesh().constructMesh());
        v.setMaterial(material);
        view.getChildren().add(v);
        for (Edge edge : polyhedron.getEdges()) {
            var line = new Line(0.01, edge.getSegment()[0], edge.getSegment()[1]);
            line.setMaterial(Colors.blackMaterial);
            view.getChildren().add(line);
        }
    }

    @Override
    protected Group build() {
        final var children = view.getChildren();
        add(root);
        return view;
    }

    @Override
    protected String title() {
        return "Grid Inspector";
    }

    private Polyhedron cube(Octant octant) {
        var edgeLength = Constants.lengthAtLevel(octant.level());
        return new Cube(edgeLength, vertices(octant, edgeLength));
    }

    private Vector3d[] vertices(Octant octant, int edgeLength) {
        Vector3d[] vs = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            Vector3d current = new Vector3d(octant.x(), octant.y(), octant.z());
            current.x = (i & 1) == 1 ? edgeLength : 0;
            current.y = ((i >> 1) & 1) == 1 ? edgeLength : 0;
            current.z = ((i >> 2) & 1) == 1 ? edgeLength : 0;
            vs[i] = current;
        }
        return vs;
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            OctreeInspector.main(argv);
        }
    }
}
