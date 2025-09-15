package com.hellblazer.luciferase.esvo.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test the inspection functionality in ESVOInspectMode
 */
public class ESVOInspectModeTest {

    @Test
    public void testOctreeInspection(@TempDir Path tempDir) throws Exception {
        // First, create an octree file using ESVOBuildMode
        var objFile = tempDir.resolve("test.obj");
        var objContent = """
                # Simple triangle OBJ file
                v 0.0 0.0 0.0
                v 1.0 0.0 0.0
                v 0.5 1.0 0.0
                vn 0.0 0.0 1.0
                vt 0.0 0.0
                vt 1.0 0.0
                vt 0.5 1.0
                f 1/1/1 2/2/1 3/3/1
                """;
        Files.writeString(objFile, objContent);
        
        var octreeFile = tempDir.resolve("test.octree");
        
        // Build octree first
        var buildConfig = new ESVOCommandLine.Config();
        buildConfig.inputFile = objFile.toString();
        buildConfig.outputFile = octreeFile.toString();
        buildConfig.numLevels = 3;
        buildConfig.buildContours = true; // Enable contours for more comprehensive testing
        buildConfig.colorError = 0.01f;
        buildConfig.normalError = 0.05f;
        buildConfig.contourError = 0.001f;
        buildConfig.maxThreads = 1;
        
        ESVOBuildMode.runBuild(buildConfig);
        
        // Now inspect the generated octree
        var inspectConfig = new ESVOCommandLine.Config();
        inspectConfig.inputFile = octreeFile.toString();
        
        // Test the inspect process (should not throw exceptions)
        ESVOInspectMode.runInspect(inspectConfig);
        
        System.out.println("Octree inspection test completed successfully");
    }
}