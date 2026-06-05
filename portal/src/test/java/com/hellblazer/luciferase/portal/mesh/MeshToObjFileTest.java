package com.hellblazer.luciferase.portal.mesh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Vector3d;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Mesh.toObjFile: resource leak fix (try-with-resources) and IOException propagation.
 */
class MeshToObjFileTest {

    @TempDir
    Path tempDir;

    @Test
    void toObjFile_writesVerticesAndFaces() throws IOException {
        Mesh mesh = new Mesh();
        mesh.addVertexPosition(new Vector3d(0, 0, 0));
        mesh.addVertexPosition(new Vector3d(1, 0, 0));
        mesh.addVertexPosition(new Vector3d(0, 1, 0));
        Face f = new Face(3);
        f.setAllVertexIndices(0, 1, 2);
        mesh.addFace(f);

        File outFile = tempDir.resolve("test.obj").toFile();
        mesh.toObjFile(outFile);

        String content = Files.readString(outFile.toPath());
        assertTrue(content.contains("v "), "should contain vertex lines");
        assertTrue(content.contains("f "), "should contain face lines");
    }

    @Test
    void toObjFile_propagatesIOException() {
        Mesh mesh = new Mesh();
        // Use a path that cannot be written (directory, not file)
        File unwritable = tempDir.resolve("subdir").toFile();
        unwritable.mkdir();

        // toObjFile must throw IOException rather than swallowing it
        assertThrows(IOException.class, () -> mesh.toObjFile(unwritable));
    }

    @Test
    void toObjFile_emptyMeshWritesEmptyFile() throws IOException {
        Mesh mesh = new Mesh();
        File outFile = tempDir.resolve("empty.obj").toFile();
        mesh.toObjFile(outFile);

        assertTrue(outFile.exists());
        assertEquals(0, outFile.length(), "empty mesh should produce empty file");
    }
}
