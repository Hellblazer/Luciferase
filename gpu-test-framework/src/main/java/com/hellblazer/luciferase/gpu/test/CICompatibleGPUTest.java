package com.hellblazer.luciferase.gpu.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CI-compatible base class for GPU tests that gracefully handles environments 
 * without OpenCL support (typical in CI systems).
 * 
 * This class provides:
 * - Automatic OpenCL availability detection
 * - Graceful test skipping when OpenCL unavailable
 * - Clear logging for CI debugging
 * - Environment-specific behavior
 */
public abstract class CICompatibleGPUTest extends GPUComputeHeadlessTest {
    
    private static final Logger log = LoggerFactory.getLogger(CICompatibleGPUTest.class);
    
    // Static flag to track OpenCL availability across all tests
    private static Boolean openCLAvailable = null;
    
    @BeforeAll
    static void checkOpenCLAvailability() {
        if (openCLAvailable == null) {
            openCLAvailable = detectOpenCLSupport();
            
            if (openCLAvailable) {
                log.info("✅ OpenCL detected - GPU tests will run");
            } else {
                log.warn("⚠️  OpenCL not available - GPU tests will be skipped");
                log.info("This is normal in CI environments without GPU drivers");
            }
        }
    }
    
    @BeforeEach
    void ensureOpenCLAvailable() {
        assumeTrue(openCLAvailable, "OpenCL not available - skipping GPU test");
    }
    
    /**
     * Detect if OpenCL support is available on this system.
     * This method safely tests OpenCL initialization without throwing exceptions.
     * 
     * @return true if OpenCL is available, false otherwise
     */
    private static boolean detectOpenCLSupport() {
        try {
            var testInstance = new CICompatibleGPUTest() {};
            testInstance.configureTestEnvironment();
            
            try {
                testInstance.loadRequiredNativeLibraries();
                testInstance.cleanupTestEnvironment();
                return true;
            } catch (LinkageError e) {
                log.debug("OpenCL native libraries not found: {}", e.getMessage());
                log.info("OpenCL libraries unavailable - this is expected in CI environments");
                return false;
            } catch (OpenCLHeadlessTest.OpenCLUnavailableException e) {
                log.debug("OpenCL unavailable: {}", e.getMessage());
                return false;
            }
        } catch (Exception e) {
            log.debug("OpenCL detection failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Static method for use with @EnabledIf annotations.
     * 
     * @return true if OpenCL is available
     */
    public static boolean isOpenCLAvailable() {
        if (openCLAvailable == null) {
            checkOpenCLAvailability();
        }
        return openCLAvailable;
    }
    
    /**
     * Static method for use with @DisabledIf annotations.
     * 
     * @return true if OpenCL is NOT available
     */
    public static boolean isOpenCLUnavailable() {
        return !isOpenCLAvailable();
    }
    
    /**
     * Check if we're running in a CI environment.
     * 
     * @return true if detected CI environment
     */
    public static boolean isCIEnvironment() {
        return System.getenv("CI") != null || 
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_URL") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("TRAVIS") != null ||
               System.getenv("CIRCLECI") != null;
    }
    
    /**
     * Get environment information for debugging.
     * 
     * @return environment description
     */
    public static String getEnvironmentInfo() {
        var sb = new StringBuilder();
        sb.append("Environment: ");
        
        if (isCIEnvironment()) {
            sb.append("CI");
            if (System.getenv("GITHUB_ACTIONS") != null) sb.append(" (GitHub Actions)");
            if (System.getenv("JENKINS_URL") != null) sb.append(" (Jenkins)");
            if (System.getenv("GITLAB_CI") != null) sb.append(" (GitLab CI)");
        } else {
            sb.append("Local");
        }
        
        sb.append(", OpenCL: ").append(isOpenCLAvailable() ? "Available" : "Unavailable");
        return sb.toString();
    }
}
