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

package com.hellblazer.sentry;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public interface Cursor {

    Point3f getLocation();

    void moveBy(Tuple3f delta);

    void moveTo(Tuple3f position);

    Stream<Cursor> neighbors();

    void visitNeighbors(Consumer<Cursor> consumer);
}
