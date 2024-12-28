/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.von;

import java.util.Collection;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public interface Node extends Locatable {
    int BUFFER_MULTIPLIER = 2;

    void fadeFrom(Node neighbor);

    void leave(Node leaving);

    void move(Node neighbor);

    void moveBoundary(Node neighbor);

    void noticePeers(Collection<Node> nodes);

    void perceive(Node neighbor);

    void query(Node from, Node joiner);

    float getAoiRadius();

    float getMaximumRadiusSquared();

    float getMaximumVelocity();

    Perceiving getSim();
}
