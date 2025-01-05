package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.grid.V;
import com.hellblazer.luciferase.lucien.grid.Vertex;
import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.von.SphereOfInteraction;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Random;

/**
 * @author hal.hildebrand
 **/
public class GridSoI implements SphereOfInteraction {
    private final Random     entropy;
    private final Perceptron foci;

    public GridSoI(Random entropy, Perceptron foci) {
        this.entropy = entropy;
        this.foci = foci;
    }

    @Override
    public Node closestTo(Point3f coord) {
        record dist(Vertex v, double distSquared) {
        }
        var tet = foci.locate(coord, entropy);
        var min = new dist(tet.getVertex(V.A), Double.MAX_VALUE);
        var vertex = tet.getVertex(V.B);
        var distanceSquared = vertex.distanceSquared(coord);
        if (distanceSquared < min.distSquared) {
            min = new dist(vertex, distanceSquared);
        }
        vertex = tet.getVertex(V.C);
        distanceSquared = vertex.distanceSquared(coord);
        if (distanceSquared < min.distSquared) {
            min = new dist(vertex, distanceSquared);
        }
        vertex = tet.getVertex(V.D);
        distanceSquared = vertex.distanceSquared(coord);
        if (distanceSquared < min.distSquared) {
            min = new dist(vertex, distanceSquared);
        }
        return (Node) vertex;
    }

    @Override
    public List<Node> getEnclosingNeighbors(Node id) {
        return foci.getNeighbors().stream().map(v -> (Node) v).toList();
    }

    @Override
    public Iterable<Node> getPeers() {
        return null;
    }

    @Override
    public boolean includes(Node peer) {
        return false;
    }

    @Override
    public void insert(Node id, Point3f coord) {

    }

    @Override
    public boolean isBoundary(Node peer, Vertex center, float radiusSquared) {
        return false;
    }

    @Override
    public boolean isEnclosing(Node peer, Node center_node_id) {
        return false;
    }

    @Override
    public boolean overlaps(Node peer, Point3f center, float radiusSquared) {
        return false;
    }

    @Override
    public boolean remove(Node peer) {
        return false;
    }

    @Override
    public void update(Node peer, Point3f coord) {

    }
}
