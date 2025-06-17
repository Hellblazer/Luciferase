package com.hellblazer.luciferase.lucien.benchmark;

/**
 * Utility class to check if tests are running in a CI environment.
 * This helps skip performance and benchmark tests that should only run locally.
 */
public class CIEnvironmentCheck {
    
    /**
     * Check if running in any CI environment by looking for well-known environment variables
     * 
     * @return true if running in CI, false otherwise
     */
    public static boolean isRunningInCI() {
        // Check for generic CI indicator
        if ("true".equalsIgnoreCase(System.getenv("CI")) || 
            "true".equalsIgnoreCase(System.getProperty("CI"))) {
            return true;
        }
        
        // Check for specific CI platforms
        return System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_HOME") != null ||
               System.getenv("TRAVIS") != null ||
               System.getenv("CIRCLECI") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("BITBUCKET_PIPELINE_UUID") != null ||
               System.getenv("TEAMCITY_VERSION") != null ||
               System.getenv("BUILDKITE") != null ||
               System.getenv("DRONE") != null ||
               System.getenv("APPVEYOR") != null ||
               System.getenv("TF_BUILD") != null || // Azure DevOps
               System.getenv("CODEBUILD_BUILD_ID") != null; // AWS CodeBuild
    }
    
    /**
     * Get a descriptive message for why the test is being skipped
     * 
     * @return skip message
     */
    public static String getSkipMessage() {
        return "Skipping performance test in CI environment";
    }
}