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
package com.hellblazer.luciferase.portal;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for JavaFX UI tests providing proper JavaFX toolkit initialization and teardown.
 * 
 * <p>This class ensures that:</p>
 * <ul>
 *   <li>JavaFX toolkit is initialized once before all tests</li>
 *   <li>Tests run on the JavaFX Application Thread when needed</li>
 *   <li>Proper cleanup occurs after all tests complete</li>
 *   <li>Headless mode is supported for CI/CD environments</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * public class MyJavaFXTest extends JavaFXTestBase {
 *     {@literal @}Test
 *     public void testSomething() {
 *         // Test code here - can create JavaFX nodes
 *     }
 * }
 * </pre>
 * 
 * @author hal.hildebrand
 */
public abstract class JavaFXTestBase {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final CountDownLatch initLatch = new CountDownLatch(1);

    /**
     * Launcher application for JavaFX initialization.
     * This is required to properly start the JavaFX toolkit.
     */
    public static class TestApplication extends Application {
        @Override
        public void start(Stage primaryStage) {
            // Don't show the stage
            initLatch.countDown();
        }
    }

    /**
     * Initialize JavaFX toolkit before any tests run.
     * This is called once per test class.
     */
    @BeforeAll
    public static void initializeJavaFX() throws Exception {
        if (initialized.compareAndSet(false, true)) {
            // Check if we're running in headless mode
            if (Boolean.getBoolean("testfx.headless")) {
                System.setProperty("java.awt.headless", "true");
                System.setProperty("testfx.robot", "glass");
                System.setProperty("testfx.headless", "true");
                System.setProperty("prism.order", "sw");
                System.setProperty("prism.text", "t2k");
                System.setProperty("glass.platform", "Monocle");
                System.setProperty("monocle.platform", "Headless");
            }

            // Launch JavaFX toolkit on a separate thread
            new Thread(() -> {
                try {
                    Application.launch(TestApplication.class);
                } catch (Exception e) {
                    System.err.println("Failed to launch JavaFX: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();

            // Wait for initialization
            if (!initLatch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("JavaFX initialization timeout");
            }

            // Give the toolkit a moment to fully initialize
            Thread.sleep(100);
        }
    }

    /**
     * Shutdown JavaFX toolkit after all tests complete.
     * Note: This may not be called in all test scenarios due to JUnit lifecycle.
     */
    @AfterAll
    public static void shutdownJavaFX() {
        if (initialized.get()) {
            Platform.exit();
        }
    }

    /**
     * Run code on the JavaFX Application Thread and wait for completion.
     * 
     * @param runnable Code to execute on the FX thread
     * @throws Exception if execution fails or times out
     */
    protected void runOnFxThreadAndWait(Runnable runnable) throws Exception {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            var latch = new CountDownLatch(1);
            var exception = new Exception[1];
            
            Platform.runLater(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    exception[0] = e;
                } finally {
                    latch.countDown();
                }
            });

            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("FX thread execution timeout");
            }

            if (exception[0] != null) {
                throw exception[0];
            }
        }
    }

    /**
     * Check if JavaFX toolkit is properly initialized.
     * 
     * @return true if initialized
     */
    protected boolean isJavaFXInitialized() {
        return initialized.get();
    }
}
