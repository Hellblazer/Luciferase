/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import java.net.URI;

/**
 * Configuration for an upstream simulation server connection.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param uri   WebSocket URI of the upstream server (e.g., ws://localhost:7080/ws/entities)
 * @param label Human-readable label for this upstream (used as entity ID prefix for multi-upstream)
 * @author hal.hildebrand
 */
public record UpstreamConfig(URI uri, String label) {
}
