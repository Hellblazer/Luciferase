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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public interface Perceiving {

    void fade(Perceiving neighbor);

    void move(Perceiving neighbor, Point3f location, Vector3f velocity);

    void notice(Perceiving neighbor, Point3f location);

    void setCursor(Cursor cursor);
}
