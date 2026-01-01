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
package com.hellblazer.luciferase.esvt.validation;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTQualityValidator.
 *
 * @author hal.hildebrand
 */
class ESVTQualityValidatorTest {

    private ESVTQualityValidator validator;
    private Tetree<LongEntityID, String> tetree;
    private ESVTBuilder builder;

    @BeforeEach
    void setUp() {
        validator = new ESVTQualityValidator();
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        builder = new ESVTBuilder();
    }

    @Test
    void testAnalyzeNullData() {
        var report = validator.analyze(null, 100, 42L);
        assertEquals(0, report.totalRays());
        assertFalse(report.warnings().isEmpty());
    }

    @Test
    void testAnalyzeEmptyData() {
        var data = builder.build(tetree);
        var report = validator.analyze(data, 100, 42L);
        assertEquals(0, report.totalRays());
    }

    @Test
    void testAnalyzeValidData() {
        // Build tetree with entities
        var random = new Random(42);
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);
        var report = validator.analyze(data, 1000, 42L);

        assertEquals(1000, report.totalRays());
        assertEquals(report.hits() + report.misses(), report.totalRays());
        assertTrue(report.hitRate() >= 0.0 && report.hitRate() <= 1.0);
    }

    @Test
    void testDepthDistribution() {
        // Build tetree
        var random = new Random(42);
        for (int i = 0; i < 50; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 10, "Entity" + i);
        }

        var data = builder.build(tetree);
        var histogram = validator.analyzeDepthDistribution(data, 1000, 42L);

        assertNotNull(histogram);
        assertTrue(histogram.maxDepth() > 0);
    }

    @Test
    void testConsistencyValidation() {
        // Build tetree
        tetree.insert(new Point3f(100, 100, 100), (byte) 8, "Test");
        var data = builder.build(tetree);

        assertTrue(validator.validateConsistency(data, 100, 42L),
            "Traversal should be consistent");
    }

    @Test
    void testMemoryAnalysis() {
        // Build tetree
        for (int i = 0; i < 100; i++) {
            tetree.insert(new Point3f(i * 10, i * 10, i * 10), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);
        var memory = validator.analyzeMemory(data);

        assertTrue(memory.nodeBytes() > 0);
        assertTrue(memory.totalBytes() > 0);
        assertTrue(memory.bytesPerNode() > 0);
    }

    @Test
    void testBalanceAnalysis() {
        // Build tetree
        var random = new Random(42);
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);
        var balance = validator.analyzeBalance(data);

        assertTrue(balance.avgChildrenPerNode() >= 0);
        assertTrue(balance.branchingFactor() >= 0);
    }

    @Test
    void testQualityThresholds() {
        var defaultThresholds = ESVTQualityValidator.QualityThresholds.defaultThresholds();
        var strictThresholds = ESVTQualityValidator.QualityThresholds.strictThresholds();

        assertNotNull(defaultThresholds);
        assertNotNull(strictThresholds);
        assertTrue(strictThresholds.minMemoryEfficiency() >= defaultThresholds.minMemoryEfficiency());
    }

    @Test
    void testReproducibility() {
        // Build tetree
        var random = new Random(42);
        for (int i = 0; i < 50; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);

        // Same seed should produce same results
        var report1 = validator.analyze(data, 100, 42L);
        var report2 = validator.analyze(data, 100, 42L);

        assertEquals(report1.hits(), report2.hits());
        assertEquals(report1.misses(), report2.misses());
    }
}
