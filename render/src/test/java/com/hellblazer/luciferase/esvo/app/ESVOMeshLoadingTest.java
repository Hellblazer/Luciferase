package com.hellblazer.luciferase.esvo.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test the mesh loading functionality in ESVOBuildMode
 */
public class ESVOMeshLoadingTest {

    @Test
    public void testOBJMeshLoading(@TempDir Path tempDir) throws Exception {
        // Create a simple OBJ file for testing
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
        
        var outputFile = tempDir.resolve("test_output.octree");
        
        // Create configuration for testing
        var config = new ESVOCommandLine.Config();
        config.inputFile = objFile.toString();
        config.outputFile = outputFile.toString();
        config.numLevels = 3;
        config.buildContours = false;
        config.colorError = 0.01f;
        config.normalError = 0.05f;
        config.contourError = 0.001f;
        config.maxThreads = 1;
        
        // Test the build process (should not throw exceptions)
        ESVOBuildMode.runBuild(config);
        
        // Verify that the output file was created
        assertThat(outputFile).exists();
        assertThat(Files.size(outputFile)).isGreaterThan(0);
        
        System.out.println("Mesh loading test completed successfully");
        System.out.println("Output file size: " + Files.size(outputFile) + " bytes");
    }
}