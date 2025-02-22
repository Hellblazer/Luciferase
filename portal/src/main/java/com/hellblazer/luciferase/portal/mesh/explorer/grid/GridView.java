/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi GUI
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.portal.mesh.explorer.grid;

import com.hellblazer.sentry.Grid;
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Tetrahedron;
import com.hellblazer.sentry.Vertex;
import com.hellblazer.luciferase.portal.mesh.explorer.Colors;
import com.hellblazer.luciferase.portal.mesh.explorer.GraphicsView;
import javafx.scene.Group;
import javafx.scene.paint.PhongMaterial;

import javax.vecmath.Tuple3f;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public class GridView extends GraphicsView {

    private static final PhongMaterial COLOR_OF_HIGHLIGHTED_REGION = null;

    private final Group        delaunay           = new Group();
    private final Group        delaunayFaces      = new Group();
    private final Set<Tuple3f> fourCorners        = new HashSet<>();
    private final Grid         grid;
    private final Group        highlightedRegions = new Group();
    private final Group        vertexes           = new Group();
    private final Group        voronoi            = new Group();
    private final Group        voronoiFaces       = new Group();

    public GridView() {
        this(new MutableGrid());
    }

    public GridView(MutableGrid t) {
        super();
        grid = t;
        for (Vertex v : grid.extent()) {
            fourCorners.add(v);
        }
        updateDiagram();
    }

    public Grid getGrid() {
        return grid;
    }

    public void highlightRegions(boolean highlight, List<Vertex> vertices) {
        assert vertices != null;
        highlightedRegions.getChildren().clear();
        if (highlight) {
            for (Vertex v : vertices) {
                render(v.getVoronoiRegion(), COLOR_OF_HIGHLIGHTED_REGION, true, highlightedRegions);
            }
            displaySpheres(vertices.stream(), 0.03d, COLOR_OF_HIGHLIGHTED_REGION, highlightedRegions);
        }
        getChildren().add(highlightedRegions);
    }

    public void update(boolean showVD, boolean showVDFaces, boolean showDT, boolean showDTFaces,
                       boolean showAllPoints) {
        var children = getChildren();
        children.clear();

        if (showVD) {
            children.add(voronoi);
        }
        if (showVDFaces) {
            children.add(voronoiFaces);
        }
        if (showDT) {
            children.add(delaunay);
        }
        if (showDTFaces) {
            children.add(delaunayFaces);
        }
        if (showAllPoints) {
            children.add(vertexes);
        }
    }

    public void updateDiagram() {
        voronoi.getChildren().clear();
        voronoiFaces.getChildren().clear();
        delaunay.getChildren().clear();
        delaunayFaces.getChildren().clear();
        for (Tetrahedron t : grid.tetrahedrons()) {
            for (var face : t.getFaces()) {
                render(face, Colors.yellowMaterial, false, delaunay);
                render(face, Colors.yellowMaterial, true, delaunayFaces);
            }
        }
        for (Vertex v : grid) {
            for (var face : v.getVoronoiRegion()) {
                render(face, Colors.cyanMaterial, false, voronoi);
                render(face, Colors.cyanMaterial, true, voronoiFaces);
            }
        }
        displaySpheres(grid.stream(), 0.07, Colors.yellowMaterial, vertexes);
    }

    @Override
    protected boolean isAuxillary(Tuple3f[] face) {
        if (face.length < 3) {
            return true;
        }
        for (var v : face) {
            if (fourCorners.contains(v)) {
                return true;
            }
        }
        return false;
    }
}
