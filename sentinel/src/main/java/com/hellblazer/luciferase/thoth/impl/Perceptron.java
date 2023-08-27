/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing
 * Framework.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.thoth.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.sentinel.Vertex;
import com.hellblazer.luciferase.thoth.Movable;
import com.hellblazer.luciferase.thoth.Perceiving;
import com.hellblazer.primeMover.annotations.Entity;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

@Entity({ Node.class, Movable.class })
public class Perceptron<E extends Perceiving> extends Node implements SphereOfInteraction {
    private static final long serialVersionUID = 1L;

    protected boolean         active = true;
    protected final Set<Node> soiSet = new HashSet<>();

    public Perceptron(E entity, Point3f location, int aoiRadius, int maximumVelocity) {
        super(entity, location, aoiRadius, maximumVelocity);
        this.aoiRadius = aoiRadius;
        entity.setCursor(this);
    }

    @Override
    public Node closestTo(Tuple3f point3f) {
        Vertex current = null;
        float dSqur = 0;
        for (var v : locate(point3f, ThreadLocalRandom.current()).getVertices()) {
            var d = new Vector3f(point3f);
            d.sub(v);
            final var dist = d.lengthSquared();
            if (current == null || dist < dSqur) {
                current = v;
                dSqur = dist;
            }
        }
        return (Node) current;
    }

    @Override
    public void fadeFrom(Node neighbor) {
        remove(neighbor);
    }

    @Override
    public Node getAliased(Node node) {
        return null;
    }

    @Override
    public Collection<Node> getEnclosingNeighbors(Node id) {
        return Collections.emptyList();
    }

    @Override
    public Iterable<Node> getNodes() {
        // TODO Auto-generated method stub
        return Collections.emptyList();
    }

    @Override
    public boolean includes(Node node) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void insert(Node id) {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isBoundary(Node node, Tuple3f center, float radiusSquared) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isEnclosing(Node node, Node center_node_id) {
        // TODO Auto-generated method stub
        return false;
    }

    public void join(Node gateway) {
        if (!gateway.equals(this)) {
            gateway.query(this, this);
        }
    }

    public void leave() {
        active = false;
        for (var peer : getNodes()) {
            if (!peer.equals(this)) {
                peer.fadeFrom(this);
            }
        }
    }

    @Override
    public void leave(Node leaving) {
        for (var enclosing : getEnclosingNeighbors(leaving)) {
            enclosing.leave(leaving);
        }
        remove(leaving);
    }

    @Override
    public void move(Node neighbor) {
        if (!active) {
            neighbor.leave(this);
            return;
        }

        notifySimMove(neighbor, update(neighbor));
        removeNonOverlapped();
    }

    @Override
    public void moveBoundary(Node neighbor) {
        if (!active) {
            neighbor.leave(this);
            return;
        }

        if (!includes(neighbor)) {
            if (overlaps(this, neighbor.getLocation(), maxRadiusSquared)) {
                insert(neighbor);
            }
            return;
        }
        handshakeWith(neighbor);
        notifySimMove(neighbor, update(neighbor));
        removeNonOverlapped();
    }

    @Override
    public void moveBy(Tuple3f velocity) {
        super.moveBy(velocity);
        this.setLocation(this);
        update(this, this);
        removeNonOverlapped();
        for (var peer : getNodes()) {
            if (!peer.equals(this)) {
                if (isBoundary(peer, this, maxRadiusSquared)) {
                    peer.moveBoundary(this);
                } else {
                    peer.move(this);
                }
            }
        }
    }

    @Override
    public void noticeNodes(Collection<Node> nodes) {
        if (!active) {
            return;
        }

        for (var peer : nodes) {
            if (!includes(peer)) {
                insert(peer.clone());
                if (overlaps(this, peer.getLocation(), peer.getMaximumRadiusSquared())) {
                    peer.perceive(this);
                }
            }
        }
    }

    @Override
    public void perceive(Node neighbor) {
        if (!active) {
            neighbor.leave(this);
            return;
        }

        add(neighbor);
        handshakeWith(neighbor);
        notifySimNotice(neighbor);
    }

    @Override
    public void query(Node from, Node joiner) {
        if (!active) {
            from.leave(this);
            from.query(joiner, joiner);
            return;
        }

        var closest = closestTo(joiner);
        if (closest != null && !closest.equals(this) && !closest.equals(from)) {
            closest.query(this, joiner);
        } else {
            add(joiner);
            joiner.perceive(this);
            handshakeWith(joiner);
        }
    }

    @Override
    public void remove(Node neighbor) {
        if (soiSet.remove(neighbor)) {
            if (neighbor instanceof Perceiving p) {
                sim.fade(p);
            }
        }
    }

    @Override
    public void update(Node node, Tuple3f coord) {
        // TODO Auto-generated method stub

    }

    protected void add(Node node) {
        insert(node.clone());
    }

    protected void handshakeWith(Node node) {
        if (node.equals(this)) {
            return;
        }
        var peers = getEnclosingNeighbors(node);
        if (peers.size() > 0) {
            node.noticeNodes(peers);
        }
    }

    protected void notifySimMove(Node neighbor, Tuple3f oldLocation) {
        if (oldLocation == null || !soiSet.contains(neighbor)) {
            notifySimNotice(neighbor);
            return;
        }
        var distance = new Vector3f(this);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            var velocity = new Vector3f();
            velocity.sub(neighbor.getLocation(), oldLocation);
            Point3f nLocation = neighbor.getLocation();
            sim.move(neighbor.getSim(), nLocation, velocity);
        } else {
            soiSet.remove(neighbor);
            sim.fade(neighbor.getSim());
        }
    }

    protected void notifySimNotice(Node neighbor) {
        var distance = new Vector3f(this);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            soiSet.add(neighbor);
            sim.notice(neighbor.getSim(), neighbor.getLocation());
        }
    }

    /**
     * disconnect neighbors no longer relevant (within AOI or is an enclosing
     * neighbor)
     */
    protected void removeNonOverlapped() {
        ArrayList<Node> removed = new ArrayList<>();
        for (var neighbor : getNodes()) {
            if (!this.equals(neighbor) &&
                !overlaps(this, neighbor.getLocation(),
                          Math.max(maxRadiusSquared, neighbor.getMaximumRadiusSquared())) &&
                !isEnclosing(neighbor, this)) {
                removed.add(neighbor);
            }
        }
        for (var neighbor : removed) {
            remove(neighbor);
            neighbor.fadeFrom(this);
        }
    }

    protected Point3f update(Node node) {
        var neighbor = getAliased(node);
        if (neighbor == null) {
            insert(node.clone());
            return null;
        }
        Point3f oldLocation = new Point3f(neighbor.getLocation());
        neighbor.set(node.getLocation());
        update(neighbor, node.getLocation());
        return oldLocation;
    }

}
