package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.3 tests for GhostLayerValidator.
 * <p>
 * Tests ghost layer consistency validation after partition recovery.
 * Uses mocks/simple objects since full GhostLayer API is not yet available.
 */
class Phase43GhostLayerValidatorTest {

    @Test
    void testValidateSuccessful_ReturnsValidTrueOnEmptyGhostLayer() {
        // Given: A validator and valid active ranks
        var validator = new GhostLayerValidator();
        var activeRanks = Set.of(0, 1, 2);  // Ranks 0,1,2 are healthy
        var failedRank = 3;  // Rank 3 failed
        var mockGhostLayer = new Object();  // Simple mock

        // When: Validate with failed rank NOT in active set
        var result = validator.validate(mockGhostLayer, activeRanks, failedRank);

        // Then: Should pass validation
        assertTrue(result.valid(), "Expected validation to pass");
        assertEquals(0, result.duplicateCount());
        assertEquals(0, result.orphanCount());
        assertEquals(0, result.boundaryGapCount());
        assertTrue(result.errors().isEmpty(), "Expected no errors");
    }

    @Test
    void testValidateWithActiveRanks_ReturnsValidResult() {
        // Given: Validator with multiple active ranks
        var validator = new GhostLayerValidator();
        var activeRanks = Set.of(0, 1, 2, 3, 4);  // 5 healthy partitions
        var failedRank = 5;  // Rank 5 failed (not in active set)
        var mockGhostLayer = new Object();

        // When: Validate
        var result = validator.validate(mockGhostLayer, activeRanks, failedRank);

        // Then: Should pass validation
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testValidationResult_RecordCreation_Success() {
        // Given: Success parameters
        boolean valid = true;
        int duplicates = 0;
        int orphans = 0;
        int gaps = 0;
        var errors = List.<String>of();

        // When: Create ValidationResult
        var result = new GhostLayerValidator.ValidationResult(valid, duplicates, orphans, gaps, errors);

        // Then: All fields should match
        assertTrue(result.valid());
        assertEquals(0, result.duplicateCount());
        assertEquals(0, result.orphanCount());
        assertEquals(0, result.boundaryGapCount());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testValidationResult_RecordCreation_WithErrors() {
        // Given: Validation with errors
        boolean valid = false;
        int duplicates = 2;
        int orphans = 3;
        int gaps = 1;
        var errors = List.of("Error 1", "Error 2", "Error 3");

        // When: Create ValidationResult with errors
        var result = new GhostLayerValidator.ValidationResult(valid, duplicates, orphans, gaps, errors);

        // Then: All fields should reflect errors
        assertFalse(result.valid());
        assertEquals(2, result.duplicateCount());
        assertEquals(3, result.orphanCount());
        assertEquals(1, result.boundaryGapCount());
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().contains("Error 1"));
        assertTrue(result.errors().contains("Error 2"));
        assertTrue(result.errors().contains("Error 3"));
    }

    @Test
    void testValidateUnknownPartition_WithAllActiveRanks() {
        // Given: All ranks active (normal topology state)
        var validator = new GhostLayerValidator();
        var activeRanks = Set.of(0, 1, 2, 3);  // All ranks registered
        var failedRank = 3;  // Rank 3 is being recovered (but still in topology)
        var mockGhostLayer = new Object();

        // When: Validate
        var result = validator.validate(mockGhostLayer, activeRanks, failedRank);

        // Then: Should pass (failedRank can be in activeRanks during recovery)
        // Note: activeRanks represents topology registration, not health status
        assertTrue(result.valid(), "Expected validation to pass");
        assertTrue(result.errors().isEmpty(), "Expected no errors");
    }

    @Test
    void testValidate_NullGhostLayer_ReturnsError() {
        // Given: Null ghost layer
        var validator = new GhostLayerValidator();
        var activeRanks = Set.of(0, 1, 2);
        var failedRank = 3;

        // When: Validate with null ghost layer
        var result = validator.validate(null, activeRanks, failedRank);

        // Then: Should detect error
        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
        assertTrue(
            result.errors().stream().anyMatch(e -> e.contains("Ghost layer is null")),
            "Expected error about null ghost layer"
        );
    }

    @Test
    void testValidate_NullActiveRanks_ThrowsException() {
        // Given: Null active ranks
        var validator = new GhostLayerValidator();
        var mockGhostLayer = new Object();

        // When/Then: Should throw IllegalArgumentException
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(mockGhostLayer, null, 3),
            "Expected exception for null activeRanks"
        );
    }

    @Test
    void testValidate_NegativeFailedRank_ThrowsException() {
        // Given: Negative failed rank (except -1 which is allowed in overload)
        var validator = new GhostLayerValidator();
        var mockGhostLayer = new Object();
        var activeRanks = Set.of(0, 1, 2);

        // When/Then: Should throw IllegalArgumentException for negative rank
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(mockGhostLayer, activeRanks, -5),
            "Expected exception for invalid negative failedRank"
        );
    }

    @Test
    void testValidationResultSuccess_FactoryMethod() {
        // When: Create success result via factory method
        var result = GhostLayerValidator.ValidationResult.success();

        // Then: Should have success values
        assertTrue(result.valid());
        assertEquals(0, result.duplicateCount());
        assertEquals(0, result.orphanCount());
        assertEquals(0, result.boundaryGapCount());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testValidationResultWithErrors_FactoryMethod() {
        // Given: Error details
        var errorMessages = List.of("Duplicate entity X", "Orphan ghost from partition 5");

        // When: Create error result via factory method
        var result = GhostLayerValidator.ValidationResult.withErrors(
            1,  // duplicates
            1,  // orphans
            0,  // gaps
            errorMessages
        );

        // Then: Should have error values
        assertFalse(result.valid());
        assertEquals(1, result.duplicateCount());
        assertEquals(1, result.orphanCount());
        assertEquals(0, result.boundaryGapCount());
        assertEquals(2, result.errors().size());
    }

    @Test
    void testValidate_ConvenienceOverload_NoFailedRank() {
        // Given: Validator and active ranks, no specific failed rank
        var validator = new GhostLayerValidator();
        var activeRanks = Set.of(0, 1, 2);
        var mockGhostLayer = new Object();

        // When: Use convenience method without failedRank
        var result = validator.validate(mockGhostLayer, activeRanks);

        // Then: Should succeed (failedRank defaults to -1)
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void testValidationResult_NegativeCountsThrowException() {
        // When/Then: Negative duplicate count should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> new GhostLayerValidator.ValidationResult(true, -1, 0, 0, List.of()),
            "Expected exception for negative duplicateCount"
        );

        // When/Then: Negative orphan count should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> new GhostLayerValidator.ValidationResult(true, 0, -1, 0, List.of()),
            "Expected exception for negative orphanCount"
        );

        // When/Then: Negative gap count should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> new GhostLayerValidator.ValidationResult(true, 0, 0, -1, List.of()),
            "Expected exception for negative boundaryGapCount"
        );
    }

    @Test
    void testValidationResult_NullErrorsThrowException() {
        // When/Then: Null errors list should throw
        assertThrows(
            IllegalArgumentException.class,
            () -> new GhostLayerValidator.ValidationResult(true, 0, 0, 0, null),
            "Expected exception for null errors list"
        );
    }

    @Test
    void testValidationResult_ErrorsAreImmutable() {
        // Given: Mutable error list
        var mutableErrors = new java.util.ArrayList<String>();
        mutableErrors.add("Error 1");

        // When: Create ValidationResult
        var result = new GhostLayerValidator.ValidationResult(false, 1, 0, 0, mutableErrors);

        // Then: Modifying original list should not affect result
        mutableErrors.add("Error 2");
        assertEquals(1, result.errors().size(), "Result errors should be immutable");

        // And: Result errors should be unmodifiable
        assertThrows(
            UnsupportedOperationException.class,
            () -> result.errors().add("Error 3"),
            "Expected errors list to be unmodifiable"
        );
    }
}
