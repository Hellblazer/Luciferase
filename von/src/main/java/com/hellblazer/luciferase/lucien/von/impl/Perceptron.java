/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Thoth Interest Management and Load Balancing Framework.
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

package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.grid.Vertex;
import com.hellblazer.luciferase.lucien.von.Node;
import com.hellblazer.luciferase.lucien.von.Perceiving;
import com.hellblazer.luciferase.lucien.von.SphereOfInteraction;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collection;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public class Perceptron<E extends Perceiving> extends AbstractNode<E> {
    protected final SphereOfInteraction soi;
    protected       boolean             active = true;

    public Perceptron(E entity, Vertex location, float aoiRadius, float maximumVelocity, SphereOfInteraction soi) {
        super(entity, location, aoiRadius, maximumVelocity);
        this.aoiRadius = aoiRadius;
        entity.setCursor(this);
        this.soi = soi;
    }

    @Override
    public void fadeFrom(Node neighbor) {
        remove(neighbor);
    }

    public void join(Node gateway) {
        if (!gateway.equals(this)) {
            gateway.query(this, this);
        }
    }

    public void leave() {
        active = false;
        for (var peer : soi.getPeers()) {
            if (!peer.equals(this)) {
                peer.fadeFrom(this);
            }
        }
    }

    @Override
    public void leave(Node leaving) {
        for (var enclosing : soi.getEnclosingNeighbors(leaving)) {
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

        if (!soi.includes(neighbor)) {
            if (soi.overlaps(this, neighbor.getLocation(), maxRadiusSquared)) {
                soi.insert(neighbor, neighbor.getLocation());
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
        removeNonOverlapped();
        for (var peer : soi.getPeers()) {
            if (!peer.equals(this)) {
                if (soi.isBoundary(peer, this, maxRadiusSquared)) {
                    peer.moveBoundary(this);
                } else {
                    peer.move(this);
                }
            }
        }
    }

    @Override
    public void noticePeers(Collection<Node> peers) {
        if (!active) {
            return;
        }

        for (var peer : peers) {
            if (!soi.includes(peer)) {
                soi.insert(peer, peer.getLocation());
                if (soi.overlaps(this, peer.getLocation(), peer.getMaximumRadiusSquared())) {
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

        var closest = soi.closestTo(joiner.getLocation());
        if (closest != null && !closest.equals(this) && !closest.equals(from)) {
            closest.query(this, joiner);
        } else {
            add(joiner);
            joiner.perceive(this);
            handshakeWith(joiner);
        }
    }

    protected void add(Node node) {
        soi.insert(node, node.getLocation());
    }

    protected void handshakeWith(Node node) {
        if (node.equals(this)) {
            return;
        }
        var peers = soi.getEnclosingNeighbors(node);
        if (peers.size() > 0) {
            node.noticePeers(peers);
        }
    }

    protected void notifySimMove(Node neighbor, Point3f oldLocation) {
        if (oldLocation == null || !soi.includes(neighbor)) {
            notifySimNotice(neighbor);
            return;
        }
        var distance = new Vector3f(x, y, z);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            var velocity = new Vector3f();
            velocity.sub(neighbor.getLocation(), oldLocation);
            var nLocation = neighbor.getLocation();
            sim.move(neighbor.getSim(), nLocation, velocity);
        } else {
            sim.fade(neighbor.getSim());
        }
    }

    protected void notifySimNotice(Node neighbor) {
        var distance = new Vector3f(x, y, z);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            sim.notice(neighbor.getSim(), neighbor.getLocation());
        }
    }

    protected void remove(Node neighbor) {
        if (soi.remove(neighbor)) {
            sim.fade(neighbor.getSim());
        }
    }

    /**
     * disconnect neighbors no longer relevant (within AOI or is an enclosing neighbor)
     */
    protected void removeNonOverlapped() {
        var removed = new ArrayList<Node>();
        for (var neighbor : soi.getPeers()) {
            if (!this.equals(neighbor)) {
                if (!soi.overlaps((Node) this, neighbor.getLocation(),
                                  Math.max(maxRadiusSquared, neighbor.getMaximumRadiusSquared()))) {
                    if (!soi.isEnclosing(neighbor, this)) {
                        removed.add(neighbor);
                    }
                }
            }
        }
        for (var neighbor : removed) {
            remove(neighbor);
            neighbor.fadeFrom(this);
        }
    }

    protected Point3f update(Node node) {
        if (!soi.includes(node)) {
            soi.insert(node, node.getLocation());
            return null;
        }
        var oldLocation = new Point3f(node.getLocation());
        soi.update(node, node.getLocation());
        return oldLocation;
    }
}
