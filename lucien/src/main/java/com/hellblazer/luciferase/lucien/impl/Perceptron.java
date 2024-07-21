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

package com.hellblazer.luciferase.lucien.impl;

import com.hellblazer.luciferase.lucien.Perceiving;
import com.hellblazer.luciferase.lucien.grid.Vertex;

import javax.vecmath.Point2d;
import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public class Perceptron<E extends Perceiving> extends AbstractNode<E> {
    protected final SphereOfInteraction   soi;
    protected final Map<UUID, Perceiving> soiSet = new HashMap<>();
    protected final Peer                  thisAsPeer;
    protected       boolean               active = true;

    public Perceptron(E entity, UUID id, Vertex location, float aoiRadius, float maximumVelocity,
                      SphereOfInteraction soi) {
        super(entity, id, location, aoiRadius, maximumVelocity);
        this.aoiRadius = aoiRadius;
        entity.setCursor(this);
        this.soi = soi;
        thisAsPeer = new Peer(this, sim, id, location, aoiRadius, maximumVelocity);
    }

    @Override
    public void fadeFrom(Peer neighbor) {
        remove(neighbor);
    }

    public Collection<Peer> getNeighbors() {
        final ArrayList<Peer> neighbors = new ArrayList<>();
        for (Peer peer : soi.getPeers()) {
            if (!peer.equals(thisAsPeer)) {
                neighbors.add(peer);
            }
        }
        return neighbors;
    }

    public Peer getThisAsPeer() {
        return thisAsPeer;
    }

    public List<Point2d[]> getVoronoiDomainEdges() {
        return soi.getVoronoiDomainEdges();
    }

    public void join(Node gateway) {
        if (!gateway.equals(this)) {
            gateway.query(thisAsPeer, thisAsPeer);
        }
    }

    public void leave() {
        active = false;
        for (Peer peer : soi.getPeers()) {
            if (!peer.equals(thisAsPeer)) {
                peer.fadeFrom(thisAsPeer);
            }
        }
    }

    @Override
    public void leave(Peer leaving) {
        for (Node enclosing : soi.getEnclosingNeighbors(leaving)) {
            enclosing.leave(leaving);
        }
        remove(leaving);
    }

    @Override
    public void move(Peer neighbor) {
        if (!active) {
            neighbor.leave(thisAsPeer);
            return;
        }

        notifySimMove(neighbor, update(neighbor));
        removeNonOverlapped();
    }

    @Override
    public void moveBoundary(Peer neighbor) {
        if (!active) {
            neighbor.leave(thisAsPeer);
            return;
        }

        if (!soi.includes(neighbor)) {
            if (soi.overlaps(thisAsPeer, neighbor.getLocation(), maxRadiusSquared)) {
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
        for (Peer peer : soi.getPeers()) {
            if (!peer.equals(thisAsPeer)) {
                if (soi.isBoundary(peer, location, maxRadiusSquared)) {
                    peer.moveBoundary(thisAsPeer);
                } else {
                    peer.move(thisAsPeer);
                }
            }
        }
    }

    @Override
    public void noticePeers(Collection<Peer> peers) {
        if (!active) {
            return;
        }

        for (Peer peer : peers) {
            if (!soi.includes(peer)) {
                soi.insert(peer.clone(), peer.getLocation());
                if (soi.overlaps(thisAsPeer, peer.getLocation(), peer.getMaximumRadiusSquared())) {
                    peer.perceive(thisAsPeer);
                }
            }
        }
    }

    @Override
    public void perceive(Peer neighbor) {
        if (!active) {
            neighbor.leave(thisAsPeer);
            return;
        }

        add(neighbor);
        handshakeWith(neighbor);
        notifySimNotice(neighbor);
    }

    @Override
    public void query(Peer from, Peer joiner) {
        if (!active) {
            from.leave(thisAsPeer);
            from.query(joiner, joiner);
            return;
        }

        Peer closest = soi.closestTo(joiner.getLocation());
        if (closest != null && !closest.equals(thisAsPeer) && !closest.equals(from)) {
            closest.query(thisAsPeer, joiner);
        } else {
            add(joiner);
            joiner.perceive(thisAsPeer);
            handshakeWith(joiner);
        }
    }

    protected void add(Peer node) {
        soi.insert(node.clone(), node.getLocation());
    }

    protected void handshakeWith(Peer node) {
        if (node.equals(thisAsPeer)) {
            return;
        }
        Collection<Peer> peers = soi.getEnclosingNeighbors(node);
        if (peers.size() > 0) {
            node.noticePeers(peers);
        }
    }

    protected void notifySimMove(Peer neighbor, Point3f oldLocation) {
        if (oldLocation == null || !soiSet.containsKey(neighbor.id)) {
            notifySimNotice(neighbor);
            return;
        }
        Vector3f distance = new Vector3f(location);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            Vector3f velocity = new Vector3f();
            velocity.sub(neighbor.getLocation(), oldLocation);
            Point3f nLocation = neighbor.getLocation();
            sim.move(neighbor.getSim(), nLocation, velocity);
        } else {
            soiSet.remove(neighbor.getId());
            sim.fade(neighbor.getSim());
        }
    }

    protected void notifySimNotice(Peer neighbor) {
        Vector3f distance = new Vector3f(location);
        distance.sub(neighbor.getLocation());
        if (distance.lengthSquared() <= maxRadiusSquared) {
            soiSet.put(neighbor.id, neighbor.getSim());
            sim.notice(neighbor.getSim(), neighbor.getLocation());
        }
    }

    protected void remove(Peer neighbor) {
        soi.remove(neighbor);
        Perceiving node = soiSet.remove(neighbor.id);
        if (node != null) {
            sim.fade(node);
        }
    }

    /**
     * disconnect neighbors no longer relevant (within AOI or is an enclosing neighbor)
     */
    protected void removeNonOverlapped() {
        ArrayList<Peer> removed = new ArrayList<>();
        for (Peer neighbor : soi.getPeers()) {
            if (!thisAsPeer.equals(neighbor) && !soi.overlaps(thisAsPeer, neighbor.getLocation(),
                                                              Math.max(maxRadiusSquared,
                                                                       neighbor.getMaximumRadiusSquared()))
            && !soi.isEnclosing(neighbor, thisAsPeer)) {
                removed.add(neighbor);
            }
        }
        for (Peer neighbor : removed) {
            remove(neighbor);
            neighbor.fadeFrom(thisAsPeer);
        }
    }

    protected Point3f update(Peer node) {
        Peer neighbor = soi.getAliased(node);
        if (neighbor == null) {
            soi.insert(node.clone(), node.getLocation());
            return null;
        }
        Point3f oldLocation = new Point3f(neighbor.getLocation());
        soi.update(neighbor, node.getLocation());
        return oldLocation;
    }
}
