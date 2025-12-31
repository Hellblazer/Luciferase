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
package com.hellblazer.luciferase.esvt.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTCommandLine argument parsing.
 *
 * @author hal.hildebrand
 */
class ESVTCommandLineTest {

    @Test
    void testDefaultMode() {
        var config = ESVTCommandLine.parse(new String[]{});
        assertEquals(ESVTCommandLine.Mode.HELP, config.mode);
    }

    @Test
    void testHelpMode() {
        var config = ESVTCommandLine.parse(new String[]{"help"});
        assertEquals(ESVTCommandLine.Mode.HELP, config.mode);
    }

    @Test
    void testBuildMode() {
        var config = ESVTCommandLine.parse(new String[]{"build", "-i", "input.obj", "-o", "output.esvt"});
        assertEquals(ESVTCommandLine.Mode.BUILD, config.mode);
        assertEquals("input.obj", config.inputFile);
        assertEquals("output.esvt", config.outputFile);
    }

    @Test
    void testInspectMode() {
        var config = ESVTCommandLine.parse(new String[]{"inspect", "-i", "test.esvt"});
        assertEquals(ESVTCommandLine.Mode.INSPECT, config.mode);
        assertEquals("test.esvt", config.inputFile);
    }

    @Test
    void testBenchmarkMode() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "-i", "test.esvt"});
        assertEquals(ESVTCommandLine.Mode.BENCHMARK, config.mode);
    }

    @Test
    void testOptimizeMode() {
        var config = ESVTCommandLine.parse(new String[]{"optimize", "-i", "input.esvt", "-o", "output.esvt"});
        assertEquals(ESVTCommandLine.Mode.OPTIMIZE, config.mode);
    }

    @Test
    void testInteractiveMode() {
        var config = ESVTCommandLine.parse(new String[]{"interactive", "-i", "test.esvt"});
        assertEquals(ESVTCommandLine.Mode.INTERACTIVE, config.mode);
    }

    @Test
    void testVerboseFlag() {
        var config = ESVTCommandLine.parse(new String[]{"build", "-v", "-i", "in.obj", "-o", "out.esvt"});
        assertTrue(config.verbose);
    }

    @Test
    void testQuietFlag() {
        var config = ESVTCommandLine.parse(new String[]{"build", "-q", "-i", "in.obj", "-o", "out.esvt"});
        assertTrue(config.quiet);
    }

    @Test
    void testMaxDepthOption() {
        var config = ESVTCommandLine.parse(new String[]{"build", "--depth", "16", "-i", "in.obj", "-o", "out.esvt"});
        assertEquals(16, config.maxDepth);
    }

    @Test
    void testGridResolutionOption() {
        var config = ESVTCommandLine.parse(new String[]{"build", "--grid", "512", "-i", "in.obj", "-o", "out.esvt"});
        assertEquals(512, config.gridResolution);
    }

    @Test
    void testNumRaysOption() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "--rays", "100000", "-i", "test.esvt"});
        assertEquals(100000, config.numRays);
    }

    @Test
    void testBenchmarkIterationsOption() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "--iterations", "5", "-i", "test.esvt"});
        assertEquals(5, config.benchmarkIterations);
    }

    @Test
    void testCompressionOption() {
        var config = ESVTCommandLine.parse(new String[]{"build", "--compress", "-i", "in.obj", "-o", "out.esvt"});
        assertTrue(config.useCompression);
    }

    @Test
    void testOptimizationPasses() {
        var config = ESVTCommandLine.parse(new String[]{"optimize", "--passes", "5", "-i", "in.esvt", "-o", "out.esvt"});
        assertEquals(5, config.optimizationPasses);
    }

    @Test
    void testFrameSizeOptions() {
        var config = ESVTCommandLine.parse(new String[]{"interactive", "-w", "1920", "-h", "1080", "-i", "test.esvt"});
        assertEquals(1920, config.frameWidth);
        assertEquals(1080, config.frameHeight);
    }

    @Test
    void testFullscreenOption() {
        var config = ESVTCommandLine.parse(new String[]{"interactive", "--fullscreen", "-i", "test.esvt"});
        assertTrue(config.fullscreen);
    }

    @Test
    void testBuildContoursOption() {
        var config = ESVTCommandLine.parse(new String[]{"build", "--contours", "-i", "in.obj", "-o", "out.esvt"});
        assertTrue(config.buildContours);
    }

    @Test
    void testDefaultValues() {
        var config = new ESVTCommandLine.Config();

        // Check default values
        assertEquals(ESVTCommandLine.Mode.HELP, config.mode);
        assertNull(config.inputFile);
        assertNull(config.outputFile);
        assertFalse(config.verbose);
        assertFalse(config.quiet);
        assertEquals(12, config.maxDepth);
        assertEquals(256, config.gridResolution);
        assertEquals(100000, config.numRays);
        assertEquals(1000, config.benchmarkIterations);
        assertEquals(100, config.warmupIterations);
        assertFalse(config.useCompression);
        assertTrue(config.optimizeMemory);
        assertTrue(config.optimizeBandwidth);
        assertTrue(config.optimizeCoalescing);
        assertEquals(3, config.optimizationPasses);
        assertEquals(1024, config.frameWidth);
        assertEquals(768, config.frameHeight);
        assertTrue(config.vsync);
        assertFalse(config.fullscreen);
        assertTrue(config.buildContours);
    }

    @Test
    void testUsagePrinting() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);

        ESVTCommandLine.printUsage(out);

        var output = baos.toString();
        assertTrue(output.contains("ESVT"), "Should contain 'ESVT'");
        assertTrue(output.contains("build"), "Should contain 'build'");
        assertTrue(output.contains("inspect"), "Should contain 'inspect'");
        assertTrue(output.contains("benchmark"), "Should contain 'benchmark'");
        assertTrue(output.contains("optimize"), "Should contain 'optimize'");
        assertTrue(output.contains("interactive"), "Should contain 'interactive'");
    }

    @Test
    void testModeDescriptions() {
        for (var mode : ESVTCommandLine.Mode.values()) {
            assertNotNull(mode.getCommand(), "Mode should have command");
            assertNotNull(mode.getDescription(), "Mode should have description");
        }
    }

    @Test
    void testModeByCommand() {
        assertEquals(ESVTCommandLine.Mode.BUILD, ESVTCommandLine.Mode.fromCommand("build"));
        assertEquals(ESVTCommandLine.Mode.INSPECT, ESVTCommandLine.Mode.fromCommand("inspect"));
        assertEquals(ESVTCommandLine.Mode.BENCHMARK, ESVTCommandLine.Mode.fromCommand("benchmark"));
        assertEquals(ESVTCommandLine.Mode.OPTIMIZE, ESVTCommandLine.Mode.fromCommand("optimize"));
        assertEquals(ESVTCommandLine.Mode.INTERACTIVE, ESVTCommandLine.Mode.fromCommand("interactive"));
        assertEquals(ESVTCommandLine.Mode.HELP, ESVTCommandLine.Mode.fromCommand("help"));
        assertNull(ESVTCommandLine.Mode.fromCommand("unknown"));
    }

    @Test
    void testReportFileOption() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "--report", "report.csv", "-i", "test.esvt"});
        assertEquals("report.csv", config.reportFile);
    }

    @Test
    void testStressTestOption() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "--stress", "-i", "test.esvt"});
        assertTrue(config.stressTest);
    }

    @Test
    void testLongInputOption() {
        var config = ESVTCommandLine.parse(new String[]{"inspect", "--input", "data.esvt"});
        assertEquals("data.esvt", config.inputFile);
    }

    @Test
    void testLongOutputOption() {
        var config = ESVTCommandLine.parse(new String[]{"build", "-i", "in.obj", "--output", "out.esvt"});
        assertEquals("out.esvt", config.outputFile);
    }

    @Test
    void testUnknownModeDefaultsToHelp() {
        var config = ESVTCommandLine.parse(new String[]{"unknown_mode"});
        assertEquals(ESVTCommandLine.Mode.HELP, config.mode);
    }

    @Test
    void testNoVsyncOption() {
        var config = ESVTCommandLine.parse(new String[]{"interactive", "--no-vsync", "-i", "test.esvt"});
        assertFalse(config.vsync);
    }

    @Test
    void testWarmupIterations() {
        var config = ESVTCommandLine.parse(new String[]{"benchmark", "--warmup", "10", "-i", "test.esvt"});
        assertEquals(10, config.warmupIterations);
    }
}
