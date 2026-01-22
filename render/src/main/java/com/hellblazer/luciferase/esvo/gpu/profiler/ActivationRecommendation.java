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
package com.hellblazer.luciferase.esvo.gpu.profiler;

import java.util.List;
import java.util.Map;

/**
 * Phase 4 P2: Stream C Beam Optimization Activation Recommendation
 *
 * Result record containing the activation decision, rationale, key metrics,
 * warnings, and implementation recommendations.
 *
 * @param state            Activation state (ENABLE, ENABLE_CONDITIONAL, SKIP, MONITOR)
 * @param rationale        Human-readable explanation of the decision
 * @param metrics          Key metrics used in the decision (coherence, latency, occupancy, etc.)
 * @param warnings         Conditions to monitor or potential issues
 * @param recommendations  Implementation suggestions and next steps
 *
 * @author hal.hildebrand
 */
public record ActivationRecommendation(
    StreamCActivationDecision.ActivationState state,
    String rationale,
    Map<String, Float> metrics,
    List<String> warnings,
    List<String> recommendations
) {

    /**
     * Check if beam optimization should be enabled.
     *
     * @return true if state is ENABLE or MONITOR
     */
    public boolean shouldEnable() {
        return state == StreamCActivationDecision.ActivationState.ENABLE ||
               state == StreamCActivationDecision.ActivationState.MONITOR;
    }

    /**
     * Check if conditional activation is recommended.
     *
     * @return true if state is ENABLE_CONDITIONAL
     */
    public boolean isConditional() {
        return state == StreamCActivationDecision.ActivationState.ENABLE_CONDITIONAL;
    }

    /**
     * Check if beam optimization should be skipped.
     *
     * @return true if state is SKIP
     */
    public boolean shouldSkip() {
        return state == StreamCActivationDecision.ActivationState.SKIP;
    }

    /**
     * Generate human-readable summary of the recommendation.
     *
     * @return formatted summary string
     */
    public String generateSummary() {
        var sb = new StringBuilder();
        sb.append("Stream C Activation Decision: ").append(state).append("\n\n");
        sb.append("Rationale:\n");
        sb.append("  ").append(rationale).append("\n\n");

        if (!metrics.isEmpty()) {
            sb.append("Key Metrics:\n");
            metrics.forEach((key, value) ->
                sb.append(String.format("  %s: %.2f\n", key, value))
            );
            sb.append("\n");
        }

        if (!warnings.isEmpty()) {
            sb.append("Warnings:\n");
            warnings.forEach(w -> sb.append("  ⚠️  ").append(w).append("\n"));
            sb.append("\n");
        }

        if (!recommendations.isEmpty()) {
            sb.append("Recommendations:\n");
            recommendations.forEach(r -> sb.append("  → ").append(r).append("\n"));
        }

        return sb.toString();
    }

    /**
     * Get metric value by key.
     *
     * @param key metric key
     * @return metric value or 0.0 if not found
     */
    public float getMetric(String key) {
        return metrics.getOrDefault(key, 0.0f);
    }
}
