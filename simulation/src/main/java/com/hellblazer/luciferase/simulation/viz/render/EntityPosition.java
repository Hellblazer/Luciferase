/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

/**
 * Entity position snapshot at a point in time.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param id   Entity identifier (prefixed with upstream label for multi-upstream)
 * @param x    X coordinate
 * @param y    Y coordinate
 * @param z    Z coordinate
 * @param type Entity type (e.g., "PREY", "PREDATOR")
 * @author hal.hildebrand
 */
public record EntityPosition(String id, float x, float y, float z, String type) {
}
