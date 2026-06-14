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

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

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
@Tag("javafx")
public abstract class JavaFXTestBase {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Initialize the JavaFX toolkit before any tests run, exactly once per JVM (surefire fork).
     *
     * <p>The toolkit is started via {@link Platform#startup(Runnable)} and {@code implicitExit} is
     * disabled so it stays alive for the ENTIRE fork. It is deliberately <b>never</b> torn down with
     * {@link Platform#exit()} (Luciferase-tugep): JavaFX cannot be restarted within a JVM once
     * exited, so a per-class {@code @AfterAll Platform.exit()} would kill the toolkit for every
     * later FX test class in the same fork — a subsequent re-init (e.g. {@code new JFXPanel()} or
     * {@code Platform.startup}) then deadlocks in {@code QuantumRenderer.createResourceFactory},
     * wedging the fork at 0% CPU. The daemon FX thread dies with the JVM at fork exit instead.</p>
     *
     * <p>{@code IllegalStateException} from {@code startup()} (toolkit already started by another
     * FX test in this fork) is benign — we adopt the running toolkit. Skipped in CI where the
     * {@code javafx} tag is excluded and no display is available.</p>
     */
    @BeforeAll
    public static void initializeJavaFX() {
        ensureStarted();
    }

    /**
     * Idempotently start the shared JavaFX toolkit for this fork. Safe to call from any FX test
     * class's {@code @BeforeAll}; the single shared start-and-never-exit lifecycle is the fix for
     * the suite-context FX wedge (Luciferase-tugep). Public so non-{@code JavaFXTestBase} FX tests
     * can route through the same path instead of their own divergent init/exit idioms.
     */
    public static void ensureStarted() {
        // Skip JavaFX initialization in CI (the javafx tag is excluded there; no display available).
        if ("true".equals(System.getenv("CI"))) {
            initialized.set(true);
            return;
        }
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        if (Boolean.getBoolean("testfx.headless")) {
            System.setProperty("java.awt.headless", "true");
            System.setProperty("testfx.robot", "glass");
            System.setProperty("testfx.headless", "true");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
        }
        // Never auto-exit: keep the toolkit alive for the whole fork (see method javadoc). Set
        // BEFORE startup so the policy is in force the instant the toolkit comes up — the startup
        // runnable fires on the FX thread asynchronously and could otherwise observe the default
        // implicitExit=true (stageless toolkit) before we flip it.
        Platform.setImplicitExit(false);
        var latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // Toolkit already started by another FX test in this fork — adopt it.
            latch.countDown();
        }
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                System.err.println("JavaFX initialization timeout - tests may fail or be skipped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
