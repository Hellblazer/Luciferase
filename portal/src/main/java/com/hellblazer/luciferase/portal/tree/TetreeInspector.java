package com.hellblazer.luciferase.portal.tree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Octant;
import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.portal.mesh.Edge;
import com.hellblazer.luciferase.portal.mesh.Line;
import com.hellblazer.luciferase.portal.mesh.explorer.Abstract3DApp;
import com.hellblazer.luciferase.portal.mesh.explorer.Colors;
import com.hellblazer.luciferase.portal.mesh.polyhedra.Polyhedron;
import com.hellblazer.luciferase.portal.mesh.polyhedra.plato.Tetrahedron;
import javafx.scene.Group;
import javafx.scene.effect.Glow;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;

import javax.vecmath.Point3i;
import javax.vecmath.Vector3d;

/**
 * @author hal.hildebrand
 **/
public class TetreeInspector extends Abstract3DApp {
    public static final double OPACITY_PER_LEVEL = 1.0d / Constants.MAX_REFINEMENT_LEVEL;

    private final Group           view   = new Group();
    private final PhongMaterial[] levels = new PhongMaterial[Constants.MAX_REFINEMENT_LEVEL];
    private final Octant          root;

    public TetreeInspector() {
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

    public void add(Tet tet) {
        add(new Tetrahedron(false, toVector3d(tet.vertices())), levels[tet.l() - 1], tet.l());
    }

    public double opacity(byte level) {
        return OPACITY_PER_LEVEL * level;
    }

    protected void add(final Polyhedron polyhedron, Material material, int level) {
        MeshView v = new MeshView(polyhedron.toTriangleMesh().constructMesh());
        v.setEffect(new Glow());
        v.setCullFace(CullFace.NONE);
        v.setMaterial(material);
        view.getChildren().add(v);
        for (Edge edge : polyhedron.getEdges()) {
            var line = new Line(0.01 * (Constants.MAX_REFINEMENT_LEVEL - level), edge.getSegment()[0],
                                edge.getSegment()[1]);
            line.setMaterial(Colors.blackMaterial);
            view.getChildren().add(line);
        }
    }

    @Override
    protected Group build() {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        add(tet);
        var subdivision = tet.split()[0];
        add(subdivision);
        add(subdivision.split()[0]);
        return view;
    }

    @Override
    protected String title() {
        return "Grid Inspector";
    }

    private Vector3d[] toVector3d(Point3i[] vertices) {
        var result = new Vector3d[4];
        for (var i = 0; i < 4; i++) {
            var vertex = vertices[i];
            result[i] = new Vector3d(vertex.getX(), vertex.getY(), vertex.getZ());
        }
        return result;
    }

    /**
     * This is the main() you want to run from your IDE
     */
    public static class Launcher {

        public static void main(String[] argv) {
            TetreeInspector.main(argv);
        }
    }
}
