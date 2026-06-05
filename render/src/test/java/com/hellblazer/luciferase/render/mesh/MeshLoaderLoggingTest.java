/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.render.mesh;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.174: structural guard — MeshLoader must use SLF4J (not System.out /
 * System.currentTimeMillis).
 *
 * <p>Tests confirm:
 * <ol>
 *   <li>A {@code private static final Logger log} field exists in MeshLoader.</li>
 *   <li>The source file contains no {@code System.out.println} or
 *       {@code System.currentTimeMillis} references in the logging paths.</li>
 * </ol>
 */
class MeshLoaderLoggingTest {

    /** MeshLoader must have a private static final SLF4J Logger field named "log". */
    @Test
    void meshLoaderHasSLF4JLoggerField() throws NoSuchFieldException {
        Field log = MeshLoader.class.getDeclaredField("log");
        int mods = log.getModifiers();
        assertTrue(Modifier.isPrivate(mods),  "log must be private");
        assertTrue(Modifier.isStatic(mods),   "log must be static");
        assertTrue(Modifier.isFinal(mods),    "log must be final");
        assertTrue(Logger.class.isAssignableFrom(log.getType()),
                   "log must be of type org.slf4j.Logger");
    }

    /**
     * Source-level guard: the MeshLoader source must not contain System.out.println
     * or System.currentTimeMillis in any form. This pins the Luciferase-7wzml.174 fix
     * against regression.
     */
    @Test
    void meshLoaderSourceContainsNoSystemOutOrCurrentTimeMillis() throws IOException {
        // Walk the source roots to find MeshLoader.java
        Path sourceFile = null;
        for (String root : new String[]{
            "render/src/main/java",
            "../render/src/main/java",
            "src/main/java"
        }) {
            Path candidate = Path.of(root,
                "com/hellblazer/luciferase/render/mesh/MeshLoader.java");
            if (Files.exists(candidate)) {
                sourceFile = candidate;
                break;
            }
        }
        if (sourceFile == null) {
            // Cannot locate source in this environment — skip source scan
            return;
        }
        String source = Files.readString(sourceFile);
        assertFalse(source.contains("System.out.println"),
                    "MeshLoader must not use System.out.println");
        assertFalse(source.contains("System.currentTimeMillis"),
                    "MeshLoader must not call System.currentTimeMillis");
    }
}
