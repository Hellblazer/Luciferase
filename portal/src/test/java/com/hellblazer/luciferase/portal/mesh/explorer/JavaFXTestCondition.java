/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Custom JUnit 5 condition to check if JavaFX can be initialized.
 * This properly detects headless environments for JavaFX tests.
 * 
 * @author hal.hildebrand
 */
public class JavaFXTestCondition implements ExecutionCondition {
    
    private static final String SKIP_JAVAFX_TESTS = "skip.javafx.tests";
    
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check if explicitly disabled
        if (Boolean.parseBoolean(System.getProperty(SKIP_JAVAFX_TESTS))) {
            return ConditionEvaluationResult.disabled("JavaFX tests disabled by system property");
        }
        
        // Check for CI environment indicators
        if (isRunningInCI()) {
            return ConditionEvaluationResult.disabled("JavaFX tests disabled in CI environment");
        }
        
        // Check if JavaFX runtime is available
        if (!isJavaFXAvailable()) {
            return ConditionEvaluationResult.disabled("JavaFX runtime not available");
        }
        
        // Check for display availability on Unix-like systems
        String os = System.getProperty("os.name").toLowerCase();
        if ((os.contains("nix") || os.contains("nux") || os.contains("mac")) && 
            System.getenv("DISPLAY") == null && System.getProperty("java.awt.headless", "false").equals("true")) {
            return ConditionEvaluationResult.disabled("No display available for JavaFX");
        }
        
        return ConditionEvaluationResult.enabled("JavaFX tests can run");
    }
    
    private boolean isRunningInCI() {
        // Check common CI environment variables
        return System.getenv("CI") != null ||
               System.getenv("CONTINUOUS_INTEGRATION") != null ||
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_URL") != null ||
               System.getenv("TRAVIS") != null ||
               System.getenv("CIRCLECI") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("BUILDKITE") != null ||
               System.getenv("DRONE") != null ||
               System.getenv("TF_BUILD") != null; // Azure DevOps
    }
    
    private boolean isJavaFXAvailable() {
        try {
            // Try to load a JavaFX class
            Class.forName("javafx.application.Platform");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}