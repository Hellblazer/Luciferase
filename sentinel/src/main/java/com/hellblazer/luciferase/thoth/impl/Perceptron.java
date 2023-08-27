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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.thoth.Movable;
import com.hellblazer.luciferase.thoth.Perceiving;
import com.hellblazer.primeMover.annotations.Entity;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

@Entity({ Node.class, Movable.class })
public class Perceptron<E extends Perceiving> extends Node<E> {
    private static final long serialVersionUID = 1L;

    protected boolean                     active = true;
    protected final SphereOfInteraction   soi;
    protected final Map<UUID, Perceiving> soiSet = new HashMap<>();

    public Perceptron(E entity, UUID id, Point3f location, int aoiRadius, int maximumVelocity,
                      SphereOfInteraction soi) {
        super(entity, id, location, aoiRadius, maximumVelocity);
        this.aoiRadius = aoiRadius;
        entity.setCursor(this);
        this.soi = soi;
        soi.insert(this, location);
    }

    @Override
    public void fadeFrom(Node<?> neighbor) {
        remove(neighbor);
    }

    public void join(Node<?> gateway) {
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
    public void leave(Node<?> leaving) {
        for (var enclosing : soi.getEnclosingNeighbors(leaving)) {
            enclosing.leave(leaving);
        }
        remove(leaving);
    }

    @Override
    public void move(Node<?> neighbor) {
        if (!active) {
            neighbor.leave(this);
            return;
        }

        notifySimMove(neighbor, update(neighbor));
        removeNonOverlapped();
    }

    @Override
    public void moveBoundary(Node<?> neighbor) {
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
        this.setLocation(this);
        soi.update(this, this);
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
    public void noticeNodes(Collection<Node<?>> nodes) {
        if (!active) {
            return;
        }

        for (var peer : nodes) {
            if (!soi.includes(peer)) {
                soi.insert(peer.clone(), peer.getLocation());
                if (soi.overlaps(this, peer.getLocation(), peer.getMaximumRadiusSquared())) {
                    peer.perceive(this);
                }
            }
        }
    }

    @Override
    public void perceive(Node<?> neighbor) {
        if (!active) {
            neighbor.leave(this);
            return;
        }

        add(neighbor);
        handshakeWith(neighbor);
        notifySimNotice(neighbor);
    }

    @Override
    public void query(Node<?> from, Node<?> joiner) {
        if (!active) {
            from.leave(this);
            from.query(joiner, joiner);
            return;
        }

        var closest = soi.closestTo(joiner);
        if (closest != null && !closest.equals(this) && !closest.equals(from)) {
            closest.query(this, joiner);
        } else {
            add(joiner);
            joiner.perceive(this);
            handshakeWith(joiner);
        }
    }

    protected void add(Node<?> node) {
        soi.insert(node.clone(), node.getLocation());
    }

    protected void handshakeWith(Node<?> node) {
        if (node.equals(this)) {
            return;
        }
        var peers = soi.getEnclosingNeighbors(node);
        if (peers.size() > 0) {
            node.noticeNodes(peers);
        }
    }

    protected void notifySimMove(Node<?> neighbor, Tuple3f oldLocation) {
        if (oldLocation == null || !soiSet.containsKey(neighbor.id)) {
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
            soiSet.remove(neighbor.getId());
            sim.fade(neighbor.getSim());
        }
    }

    protected void notifySimNotice(Node<?> neighbor) {
        var distance = new Vector3f(this);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            soiSet.put(neighbor.id, neighbor.getSim());
            sim.notice(neighbor.getSim(), neighbor.getLocation());
        }
    }

    protected void remove(Node<?> neighbor) {
        soi.remove(neighbor);
        Perceiving node = soiSet.remove(neighbor.id);
        if (node != null) {
            sim.fade(node);
        }
    }

    /**
     * disconnect neighbors no longer relevant (within AOI or is an enclosing
     * neighbor)
     */
    protected void removeNonOverlapped() {
        ArrayList<Node<?>> removed = new ArrayList<>();
        for (var neighbor : soi.getPeers()) {
            if (!this.equals(neighbor) &&
                !soi.overlaps(this, neighbor.getLocation(),
                              Math.max(maxRadiusSquared, neighbor.getMaximumRadiusSquared())) &&
                !soi.isEnclosing(neighbor, this)) {
                removed.add(neighbor);
            }
        }
        for (var neighbor : removed) {
            remove(neighbor);
            neighbor.fadeFrom(this);
        }
    }

    protected Point3f update(Node<?> node) {
        var neighbor = soi.getAliased(node);
        if (neighbor == null) {
            soi.insert(node.clone(), node.getLocation());
            return null;
        }
        Point3f oldLocation = new Point3f(neighbor.getLocation());
        neighbor.set(node.getLocation());
        soi.update(neighbor, node.getLocation());
        return oldLocation;
    }

}
