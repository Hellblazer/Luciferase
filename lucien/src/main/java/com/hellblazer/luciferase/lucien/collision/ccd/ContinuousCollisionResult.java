/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.collision.ccd;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Result of a continuous collision detection query.
 * Contains the time of impact and collision information.
 *
 * @author hal.hildebrand
 */
public record ContinuousCollisionResult(
    boolean collides,           // Whether a collision will occur
    float timeOfImpact,         // Time of impact (0.0 to 1.0)
    Point3f contactPoint,       // Contact point at time of impact
    Vector3f contactNormal,     // Contact normal at time of impact
    float penetrationDepth      // Penetration depth at time of impact
) {
    
    /**
     * Create a result indicating no collision
     */
    public static ContinuousCollisionResult noCollision() {
        return new ContinuousCollisionResult(false, 1.0f, null, null, 0.0f);
    }
    
    /**
     * Create a collision result
     */
    public static ContinuousCollisionResult collision(float timeOfImpact, Point3f contactPoint, 
                                                     Vector3f contactNormal, float penetrationDepth) {
        return new ContinuousCollisionResult(true, timeOfImpact, contactPoint, contactNormal, penetrationDepth);
    }
    
    /**
     * Check if this collision happens before another
     */
    public boolean happensBefore(ContinuousCollisionResult other) {
        if (!collides) return false;
        if (!other.collides) return true;
        return timeOfImpact < other.timeOfImpact;
    }
}