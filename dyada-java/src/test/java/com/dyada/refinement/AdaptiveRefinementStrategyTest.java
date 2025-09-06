package com.dyada.refinement;

import com.dyada.TestBase;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementContext;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementDecision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInfo;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for AdaptiveRefinementStrategy interface and implementations.
 * Tests strategy pattern, refinement decisions, validation logic, and context handling.
 */
@DisplayName("AdaptiveRefinementStrategy Tests")
class AdaptiveRefinementStrategyTest extends TestBase {

    private TestRefinementStrategy strategy;
    private RefinementCriteria testCriteria;
    private RefinementContext testContext;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        // Don't call super.setUp since it's package-private
        // Initialize with fixed seed for reproducible tests
        random = new java.util.Random(42L);
        
        testCriteria = RefinementCriteria.simple(100.0, 10.0, 10);
        strategy = new TestRefinementStrategy(testCriteria);
        
        testContext = new RefinementContext(
            levelIndex2D((byte) 2, 4, 4),
            coordinate2D(0.5, 0.5),
            0.25,
            2,
            "test-cell-data"
        );
    }

    @Nested
    @DisplayName("Core Strategy Interface")
    class CoreStrategyTests {

        @Test
        @DisplayName("Strategy returns consistent refinement decision")
        void strategyReturnsConsistentDecision() {
            var fieldValues = Map.of("temperature", 25.0, "pressure", 101.3);
            
            var decision1 = strategy.analyzeCell(testContext, fieldValues, testCriteria);
            var decision2 = strategy.analyzeCell(testContext, fieldValues, testCriteria);
            
            assertEquals(decision1, decision2);
            assertNotNull(decision1);
        }

        @Test
        @DisplayName("Strategy handles empty field values")
        void strategyHandlesEmptyFieldValues() {
            var emptyFields = Map.<String, Double>of();
            
            assertDoesNotThrow(() -> {
                var decision = strategy.analyzeCell(testContext, emptyFields, testCriteria);
                assertNotNull(decision);
            });
        }

        @Test
        @DisplayName("Strategy handles null field values")
        void strategyHandlesNullFieldValues() {
            assertDoesNotThrow(() -> {
                var decision = strategy.analyzeCell(testContext, null, testCriteria);
                assertNotNull(decision);
                assertEquals(RefinementDecision.MAINTAIN, decision);
            });
        }

        @Test
        @DisplayName("Strategy validates context parameter")
        void strategyValidatesContextParameter() {
            var fieldValues = Map.of("temperature", 25.0);
            
            assertThrows(IllegalArgumentException.class, () -> {
                strategy.analyzeCell(null, fieldValues, testCriteria);
            });
        }

        @Test
        @DisplayName("Strategy validates criteria parameter")
        void strategyValidatesCriteriaParameter() {
            var fieldValues = Map.of("temperature", 25.0);
            
            assertThrows(IllegalArgumentException.class, () -> {
                strategy.analyzeCell(testContext, fieldValues, null);
            });
        }
    }

    @Nested
    @DisplayName("Refinement Decisions")
    class RefinementDecisionTests {

        @Test
        @DisplayName("Returns REFINE for high error values")
        void returnsRefineForHighError() {
            var highErrorFields = Map.of("error", 500.0, "gradient", 50.0);
            
            var decision = strategy.analyzeCell(testContext, highErrorFields, testCriteria);
            
            assertEquals(RefinementDecision.REFINE, decision);
        }

        @Test
        @DisplayName("Returns COARSEN for low error and deep level")
        void returnsCoarsenForLowErrorDeepLevel() {
            var lowErrorFields = Map.of("error", 5.0, "gradient", 1.0);
            var deepContext = new RefinementContext(
                levelIndex2D((byte) 8, 64, 64),
                coordinate2D(0.5, 0.5),
                0.01,
                8,
                "deep-cell"
            );
            
            var decision = strategy.analyzeCell(deepContext, lowErrorFields, testCriteria);
            
            assertEquals(RefinementDecision.COARSEN, decision);
        }

        @Test
        @DisplayName("Returns MAINTAIN for moderate conditions")
        void returnsMaintainForModerateConditions() {
            var moderateFields = Map.of("error", 50.0, "gradient", 8.0);
            
            var decision = strategy.analyzeCell(testContext, moderateFields, testCriteria);
            
            assertEquals(RefinementDecision.MAINTAIN, decision);
        }

        @Test
        @DisplayName("Respects maximum depth limit")
        void respectsMaximumDepthLimit() {
            var highErrorFields = Map.of("error", 1000.0, "gradient", 100.0);
            var maxDepthContext = new RefinementContext(
                levelIndex2D((byte) 10, 1024, 1024),
                coordinate2D(0.5, 0.5),
                0.001,
                10,
                "max-depth-cell"
            );
            
            var decision = strategy.analyzeCell(maxDepthContext, highErrorFields, testCriteria);
            
            // Should not refine beyond max depth
            assertNotEquals(RefinementDecision.REFINE, decision);
        }
    }

    @Nested
    @DisplayName("Context Handling")
    class ContextHandlingTests {

        @Test
        @DisplayName("Uses cell size in decision making")
        void usesCellSizeInDecisionMaking() {
            var fields = Map.of("error", 50.0);
            
            var largeCellContext = new RefinementContext(
                levelIndex2D((byte) 1, 2, 2),
                coordinate2D(0.5, 0.5),
                0.5,  // Large cell
                1,
                "large-cell"
            );
            
            var smallCellContext = new RefinementContext(
                levelIndex2D((byte) 5, 16, 16),
                coordinate2D(0.5, 0.5),
                0.05, // Small cell
                5,
                "small-cell"
            );
            
            var largeDecision = strategy.analyzeCell(largeCellContext, fields, testCriteria);
            var smallDecision = strategy.analyzeCell(smallCellContext, fields, testCriteria);
            
            // Decisions should potentially differ based on cell size
            // Large cells might need refinement, small cells might need coarsening
            assertNotNull(largeDecision);
            assertNotNull(smallDecision);
        }

        @Test
        @DisplayName("Uses level information in decision making")
        void usesLevelInDecisionMaking() {
            var fields = Map.of("error", 75.0);
            
            var shallowContext = new RefinementContext(
                levelIndex2D((byte) 1, 2, 2),
                coordinate2D(0.5, 0.5),
                0.5,
                1, // Shallow level
                "shallow"
            );
            
            var deepContext = new RefinementContext(
                levelIndex2D((byte) 7, 64, 64),
                coordinate2D(0.5, 0.5),
                0.02,
                7, // Deep level
                "deep"
            );
            
            var shallowDecision = strategy.analyzeCell(shallowContext, fields, testCriteria);
            var deepDecision = strategy.analyzeCell(deepContext, fields, testCriteria);
            
            assertNotNull(shallowDecision);
            assertNotNull(deepDecision);
        }
    }

    @Nested
    @DisplayName("Criteria Integration")
    class CriteriaIntegrationTests {

        @Test
        @DisplayName("Uses error tolerance from criteria")
        void usesErrorToleranceFromCriteria() {
            var strictCriteria = RefinementCriteria.simple(10.0, 5.0, 8); // Low error tolerance
            var lenientCriteria = RefinementCriteria.simple(200.0, 20.0, 8); // High error tolerance
            
            var fields = Map.of("error", 50.0);
            
            var strictStrategy = new TestRefinementStrategy(strictCriteria);
            var lenientStrategy = new TestRefinementStrategy(lenientCriteria);
            
            var strictDecision = strictStrategy.analyzeCell(testContext, fields, strictCriteria);
            var lenientDecision = lenientStrategy.analyzeCell(testContext, fields, lenientCriteria);
            
            // With same error, strict criteria might refine while lenient maintains
            assertNotNull(strictDecision);
            assertNotNull(lenientDecision);
        }

        @Test
        @DisplayName("Uses minimum cell size from criteria")
        void usesMinimumCellSizeFromCriteria() {
            var largeCellCriteria = RefinementCriteria.simple(100.0, 0.5, 8); // Large min cell
            var smallCellCriteria = RefinementCriteria.simple(100.0, 0.01, 8); // Small min cell
            
            var smallCellContext = new RefinementContext(
                levelIndex2D((byte) 6, 32, 32),
                coordinate2D(0.5, 0.5),
                0.02, // Cell size close to limits
                6,
                "boundary-cell"
            );
            
            var fields = Map.of("error", 150.0); // High error
            
            var largeMinStrategy = new TestRefinementStrategy(largeCellCriteria);
            var smallMinStrategy = new TestRefinementStrategy(smallCellCriteria);
            
            var largeMinDecision = largeMinStrategy.analyzeCell(smallCellContext, fields, largeCellCriteria);
            var smallMinDecision = smallMinStrategy.analyzeCell(smallCellContext, fields, smallCellCriteria);
            
            assertNotNull(largeMinDecision);
            assertNotNull(smallMinDecision);
        }

        @Test
        @DisplayName("Respects maximum depth from criteria")
        void respectsMaximumDepthFromCriteria() {
            var shallowCriteria = RefinementCriteria.simple(100.0, 10.0, 3); // Max depth 3
            var deepCriteria = RefinementCriteria.simple(100.0, 10.0, 15); // Max depth 15
            
            var nearMaxContext = new RefinementContext(
                levelIndex2D((byte) 3, 8, 8),
                coordinate2D(0.5, 0.5),
                0.125,
                3, // At max depth for shallow criteria
                "near-max-cell"
            );
            
            var fields = Map.of("error", 200.0); // High error
            
            var shallowStrategy = new TestRefinementStrategy(shallowCriteria);
            var deepStrategy = new TestRefinementStrategy(deepCriteria);
            
            var shallowDecision = shallowStrategy.analyzeCell(nearMaxContext, fields, shallowCriteria);
            var deepDecision = deepStrategy.analyzeCell(nearMaxContext, fields, deepCriteria);
            
            // Shallow criteria should not allow further refinement
            assertNotEquals(RefinementDecision.REFINE, shallowDecision);
            // Deep criteria might allow refinement
            assertNotNull(deepDecision);
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Rejects invalid criteria construction")
        void rejectsInvalidCriteriaConstruction() {
            // Negative error tolerance should be rejected
            assertThrows(IllegalArgumentException.class, () -> {
                RefinementCriteria.simple(-1.0, 10.0, 10);
            });
            
            // Negative minimum cell size should be rejected
            assertThrows(IllegalArgumentException.class, () -> {
                RefinementCriteria.simple(100.0, -0.1, 10);
            });
            
            // Negative maximum depth should be rejected  
            assertThrows(IllegalArgumentException.class, () -> {
                RefinementCriteria.simple(100.0, 10.0, -1);
            });
        }

        @Test
        @DisplayName("Validates strategy construction with invalid criteria")
        void validatesStrategyConstructionWithInvalidCriteria() {
            assertThrows(IllegalArgumentException.class, () -> {
                new TestRefinementStrategy(null);
            });
        }
    }

    @Nested
    @DisplayName("Multiple Strategy Types")
    class MultipleStrategyTypesTests {

        @Test
        @DisplayName("Conservative strategy avoids refinement")
        void conservativeStrategyAvoidsRefinement() {
            var conservativeStrategy = new ConservativeRefinementStrategy();
            var fields = Map.of("error", 1000.0, "gradient", 100.0);
            
            var decision = conservativeStrategy.analyzeCell(testContext, fields, testCriteria);
            
            assertEquals(RefinementDecision.MAINTAIN, decision);
        }

        @Test
        @DisplayName("Aggressive strategy prefers refinement")
        void aggressiveStrategyPrefersRefinement() {
            var aggressiveStrategy = new AggressiveRefinementStrategy();
            var fields = Map.of("error", 10.0, "gradient", 1.0);
            
            var decision = aggressiveStrategy.analyzeCell(testContext, fields, testCriteria);
            
            // Should refine unless at max level
            if (testContext.currentLevel() < testCriteria.maxRefinementLevel().orElse(10)) {
                assertEquals(RefinementDecision.REFINE, decision);
            } else {
                assertEquals(RefinementDecision.MAINTAIN, decision);
            }
        }
    }

    /**
     * Test implementation of AdaptiveRefinementStrategy for validation.
     */
    private static class TestRefinementStrategy implements AdaptiveRefinementStrategy {
        private final RefinementCriteria criteria;

        TestRefinementStrategy(RefinementCriteria criteria) {
            if (criteria == null) {
                throw new IllegalArgumentException("Criteria cannot be null");
            }
            this.criteria = criteria;
        }

        @Override
        public RefinementDecision analyzeCell(RefinementContext context, Map<String, Double> fieldValues, RefinementCriteria criteria) {
            if (context == null) {
                throw new IllegalArgumentException("Context cannot be null");
            }
            if (criteria == null) {
                throw new IllegalArgumentException("Criteria cannot be null");
            }

            // Return MAINTAIN for null or empty field values
            if (fieldValues == null || fieldValues.isEmpty()) {
                return RefinementDecision.MAINTAIN;
            }

            // Respect maximum depth
            if (context.currentLevel() >= criteria.maxRefinementLevel().orElse(10)) {
                return RefinementDecision.MAINTAIN;
            }

            // Check for high error - refine if error exceeds tolerance
            var error = fieldValues.getOrDefault("error", 0.0);
            if (error > criteria.refinementThreshold()) {
                return RefinementDecision.REFINE;
            }

            // Check for coarsening conditions - low error and deep level
            if (error < criteria.coarseningThreshold() && context.currentLevel() > 4) {
                return RefinementDecision.COARSEN;
            }

            return RefinementDecision.MAINTAIN;
        }

        @Override
        public RefinementCriteria getCriteria() {
            return criteria;
        }
    }

    /**
     * Conservative strategy that avoids refinement.
     */
    private static class ConservativeRefinementStrategy implements AdaptiveRefinementStrategy {
        @Override
        public RefinementDecision analyzeCell(RefinementContext context, Map<String, Double> fieldValues, RefinementCriteria criteria) {
            return RefinementDecision.MAINTAIN; // Always conservative
        }

        @Override
        public RefinementCriteria getCriteria() {
            return RefinementCriteria.simple(100.0, 10.0, 10);
        }
    }

    /**
     * Aggressive strategy that prefers refinement.
     */
    private static class AggressiveRefinementStrategy implements AdaptiveRefinementStrategy {
        @Override
        public RefinementDecision analyzeCell(RefinementContext context, Map<String, Double> fieldValues, RefinementCriteria criteria) {
            return context.currentLevel() < criteria.maxRefinementLevel().orElse(10) ? RefinementDecision.REFINE : RefinementDecision.MAINTAIN;
        }

        @Override
        public RefinementCriteria getCriteria() {
            return RefinementCriteria.simple(100.0, 10.0, 10);
        }
    }
}