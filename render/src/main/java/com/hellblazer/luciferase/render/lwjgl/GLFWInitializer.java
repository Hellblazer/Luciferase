package com.hellblazer.luciferase.render.lwjgl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.Platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Centralized GLFW initialization handler that manages platform-specific requirements.
 * 
 * CRITICAL: On macOS, GLFW MUST be initialized on the main thread (thread 0).
 * This class handles the platform detection and provides clear error messages
 * when the constraints are not met.
 * 
 * Usage:
 * - For applications: Call GLFWInitializer.initialize() at the start of main()
 * - For tests: Use @BeforeAll with GLFWInitializer.initializeForTesting()
 * - For Maven: Ensure -XstartOnFirstThread is in MAVEN_OPTS for macOS
 */
public class GLFWInitializer {
    
    private static final Logger log = Logger.getLogger(GLFWInitializer.class.getName());
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean errorCallbackSet = new AtomicBoolean(false);
    
    // Platform detection
    private static final boolean IS_MACOS = Platform.get() == Platform.MACOSX;
    private static final boolean IS_WINDOWS = Platform.get() == Platform.WINDOWS;
    private static final boolean IS_LINUX = Platform.get() == Platform.LINUX;
    
    // Thread checking
    private static final String MAIN_THREAD_NAME = "main";
    
    /**
     * Initialize GLFW with platform-specific checks.
     * This method ensures GLFW is initialized correctly for the current platform.
     * 
     * @return true if initialization successful, false otherwise
     * @throws GLFWInitializationException if platform constraints are not met
     */
    public static boolean initialize() throws GLFWInitializationException {
        if (initialized.get()) {
            log.fine("GLFW already initialized");
            return true;
        }
        
        // Check platform constraints
        checkPlatformConstraints();
        
        // Set error callback if not already set
        if (!errorCallbackSet.getAndSet(true)) {
            GLFWErrorCallback.createPrint(System.err).set();
        }
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new GLFWInitializationException("Failed to initialize GLFW");
        }
        
        initialized.set(true);
        log.info("GLFW initialized successfully on " + Platform.get());
        
        // Log version info
        log.fine("GLFW Version: " + glfwGetVersionString());
        
        return true;
    }
    
    /**
     * Initialize GLFW for testing with relaxed constraints.
     * This method attempts to initialize GLFW even if not on the main thread,
     * but will warn about potential issues on macOS.
     * 
     * @return true if initialization successful
     */
    public static boolean initializeForTesting() {
        if (initialized.get()) {
            return true;
        }
        
        try {
            // Try normal initialization first
            return initialize();
        } catch (GLFWInitializationException e) {
            // On macOS, if we're not on the main thread, warn but try anyway
            if (IS_MACOS && !isMainThread()) {
                log.warning("GLFW initialization on macOS outside main thread. " +
                           "Tests may fail. Add -XstartOnFirstThread to JVM args.");
                
                // Try to initialize anyway for testing
                if (!errorCallbackSet.getAndSet(true)) {
                    GLFWErrorCallback.createPrint(System.err).set();
                }
                
                if (!glfwInit()) {
                    log.severe("GLFW initialization failed on non-main thread");
                    return false;
                }
                
                initialized.set(true);
                return true;
            }
            
            throw e;
        }
    }
    
    /**
     * Check if GLFW is initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Terminate GLFW and clean up resources.
     */
    public static void terminate() {
        if (initialized.getAndSet(false)) {
            glfwTerminate();
            
            if (errorCallbackSet.getAndSet(false)) {
                var callback = glfwSetErrorCallback(null);
                if (callback != null) {
                    callback.free();
                }
            }
            
            log.info("GLFW terminated");
        }
    }
    
    /**
     * Check platform-specific constraints.
     */
    private static void checkPlatformConstraints() throws GLFWInitializationException {
        if (IS_MACOS) {
            checkMacOSConstraints();
        }
        // Add other platform checks as needed
    }
    
    /**
     * Check macOS-specific constraints.
     * On macOS, GLFW must be initialized on the main thread.
     */
    private static void checkMacOSConstraints() throws GLFWInitializationException {
        if (!isMainThread()) {
            String message = """
                
                ========================================
                GLFW INITIALIZATION ERROR ON MACOS
                ========================================
                GLFW must be initialized on the main thread on macOS.
                Current thread: %s
                
                SOLUTIONS:
                
                1. For Maven execution:
                   export MAVEN_OPTS="-XstartOnFirstThread"
                   mvn test
                
                2. For IDE execution:
                   Add VM option: -XstartOnFirstThread
                
                3. For command line:
                   java -XstartOnFirstThread -jar your-app.jar
                
                4. For scripts:
                   Add to run-demo.sh or similar:
                   if [[ "$OSTYPE" == "darwin"* ]]; then
                       MAVEN_OPTS="-XstartOnFirstThread" mvn exec:java ...
                   fi
                ========================================
                """.formatted(Thread.currentThread().getName());
            
            throw new GLFWInitializationException(message);
        }
    }
    
    /**
     * Check if we're running on the main thread.
     * Note: This is a heuristic check - the actual requirement is thread 0,
     * but thread names can vary.
     */
    private static boolean isMainThread() {
        Thread current = Thread.currentThread();
        
        // Check thread ID (thread 0 is typically main)
        if (current.getId() == 1) {
            return true;
        }
        
        // Check thread name
        String threadName = current.getName();
        if (MAIN_THREAD_NAME.equalsIgnoreCase(threadName)) {
            return true;
        }
        
        // Check if we have the -XstartOnFirstThread flag effect
        // This is indicated by certain system properties on macOS
        String startOnFirstThread = System.getProperty("java.awt.headless");
        if (startOnFirstThread != null) {
            log.fine("Detected java.awt.headless=" + startOnFirstThread);
        }
        
        // If none of the checks pass, we're probably not on the main thread
        return false;
    }
    
    /**
     * Get platform-specific run instructions.
     */
    public static String getPlatformRunInstructions() {
        if (IS_MACOS) {
            return """
                To run on macOS:
                - Maven: MAVEN_OPTS="-XstartOnFirstThread" mvn test
                - IDE: Add -XstartOnFirstThread to VM options
                - Java: java -XstartOnFirstThread -cp ... MainClass
                """;
        } else if (IS_WINDOWS) {
            return """
                To run on Windows:
                - Maven: mvn test
                - IDE: Run normally
                - Java: java -cp ... MainClass
                """;
        } else if (IS_LINUX) {
            return """
                To run on Linux:
                - Maven: mvn test
                - IDE: Run normally
                - Java: java -cp ... MainClass
                - Note: Ensure DISPLAY is set for X11
                """;
        }
        return "Platform not recognized";
    }
    
    /**
     * Custom exception for GLFW initialization failures.
     */
    public static class GLFWInitializationException extends RuntimeException {
        public GLFWInitializationException(String message) {
            super(message);
        }
        
        public GLFWInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}