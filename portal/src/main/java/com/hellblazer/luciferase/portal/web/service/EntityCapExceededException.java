// SPDX-License-Identifier: AGPL-3.0-only
package com.hellblazer.luciferase.portal.web.service;

/**
 * Thrown when an insert operation would exceed a configured entity cap
 * (per-bulk-array or per-session total). Maps to HTTP 413 Payload Too Large.
 */
public class EntityCapExceededException extends RuntimeException {

    public EntityCapExceededException(String message) {
        super(message);
    }
}
