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
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTStructureValidator.
 *
 * @author hal.hildebrand
 */
class ESVTStructureValidatorTest {

    private ESVTStructureValidator validator;
    private Tetree<LongEntityID, String> tetree;
    private ESVTBuilder builder;

    @BeforeEach
    void setUp() {
        validator = new ESVTStructureValidator();
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        builder = new ESVTBuilder();
    }

    @Test
    void testValidateNullData() {
        var result = validator.validate(null);
        assertFalse(result.valid());
        assertTrue(result.errors().contains("ESVTData is null"));
    }

    @Test
    void testValidateEmptyData() {
        var data = builder.build(tetree); // Empty tetree
        var result = validator.validate(data);
        assertTrue(result.valid());
        assertEquals(0, result.totalNodes());
    }

    @Test
    void testValidateValidStructure() {
        // Build a valid tetree
        var random = new Random(42);
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 1000;
            float y = random.nextFloat() * 1000;
            float z = random.nextFloat() * 1000;
            tetree.insert(new Point3f(x, y, z), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);
        var result = validator.validate(data);

        assertTrue(result.valid(), "Valid structure should pass validation");
        assertTrue(result.accuracy() >= 99.0, "Accuracy should be >= 99%");
        assertEquals(0, result.invalidNodes());
        assertEquals(0, result.pointerErrors());
    }

    @Test
    void testValidateWithAllTypesInRange() {
        // Test that all types 0-5 are considered valid
        for (byte type = 0; type <= 5; type++) {
            var node = new ESVTNodeUnified(type);
            node.setLeafMask(0xFF);

            var errors = validator.validateNode(node, 0);
            assertTrue(errors.isEmpty(),
                "Type " + type + " should be valid but got errors: " + errors);
        }
    }

    @Test
    void testQuickValidate() {
        // Build a valid tetree
        tetree.insert(new Point3f(100, 100, 100), (byte) 8, "Test");
        var data = builder.build(tetree);

        assertTrue(validator.quickValidate(data));
        assertFalse(validator.quickValidate(null));
    }

    @Test
    void testValidateSingleNode() {
        var node = new ESVTNodeUnified((byte) 0);
        node.setLeafMask(0xFF);

        var errors = validator.validateNode(node, 0);
        assertTrue(errors.isEmpty(), "Valid node should have no errors");
    }

    @Test
    void testValidateSingleNodeWithInvalidLeafMask() {
        var node = new ESVTNodeUnified((byte) 0);
        node.setChildMask(0x0F);  // Only first 4 children exist
        node.setLeafMask(0xFF);   // But leaf mask claims all 8 are leaves

        var errors = validator.validateNode(node, 0);
        assertFalse(errors.isEmpty(), "Invalid leaf mask should produce error");
    }

    @Test
    void testStrictModeConfig() {
        var strictValidator = new ESVTStructureValidator(
            ESVTStructureValidator.Config.strictConfig());

        // Create valid data
        tetree.insert(new Point3f(100, 100, 100), (byte) 8, "Test");
        var data = builder.build(tetree);

        var result = strictValidator.validate(data);
        assertTrue(result.valid());
    }

    @Test
    void testCountValidation() {
        // Build a tetree and verify counts match
        for (int i = 0; i < 50; i++) {
            tetree.insert(new Point3f(i * 20, i * 20, i * 20), (byte) 8, "Entity" + i);
        }

        var data = builder.build(tetree);
        var result = validator.validate(data);

        assertEquals(0, result.countMismatches(), "Counts should match");
    }

    @Test
    void testTypePropagation() {
        // Build a deeper tree to test type propagation
        var random = new Random(42);
        for (int i = 0; i < 200; i++) {
            float x = random.nextFloat() * 500 + 100;
            float y = random.nextFloat() * 500 + 100;
            float z = random.nextFloat() * 500 + 100;
            tetree.insert(new Point3f(x, y, z), (byte) 10, "Entity" + i);
        }

        var data = builder.build(tetree);
        var result = validator.validate(data);

        assertEquals(0, result.typeErrors(), "Type propagation should be correct");
    }

    @Test
    void testValidationReport() {
        tetree.insert(new Point3f(100, 100, 100), (byte) 8, "Test");
        var data = builder.build(tetree);

        var result = validator.validate(data);

        assertTrue(result.totalNodes() > 0);
        assertTrue(result.accuracy() > 0);
        assertNotNull(result.errors());
        assertNotNull(result.warnings());
    }
}
