// SPDX-License-Identifier: AGPL-3.0-only
package com.hellblazer.luciferase.portal.web.service;

/**
 * Thrown when building a render structure (ESVO/ESVT) fails. Carries the failure
 * message from the {@code SpatialBridge.BuildResult} guard. Maps to HTTP 422
 * Unprocessable Entity.
 */
public class RenderBuildException extends RuntimeException {

    public RenderBuildException(String message) {
        super(message);
    }
}
