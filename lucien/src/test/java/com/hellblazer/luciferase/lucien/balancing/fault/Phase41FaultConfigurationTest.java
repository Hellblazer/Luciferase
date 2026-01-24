package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Phase 4.1 FaultConfiguration record (revised specification).
 *
 * Verifies:
 * - Default configuration values
 * - Compact constructor validation
 * - Builder-style with* methods
 *
 * Note: This tests the REVISED FaultConfiguration spec with suspect/failure confirmation timeouts,
 * replacing the earlier heartbeat-based configuration.
 */
class Phase41FaultConfigurationTest {

    @Test
    void testDefaultConfig() {
        var config = FaultConfiguration.defaultConfig();

        // Verify default values as per Issue 2 resolution in plan
        assertThat(config.suspectTimeoutMs()).isEqualTo(3000);
        assertThat(config.failureConfirmationMs()).isEqualTo(5000);
        assertThat(config.maxRecoveryRetries()).isEqualTo(3);
        assertThat(config.recoveryTimeoutMs()).isEqualTo(30000);
        assertThat(config.autoRecoveryEnabled()).isTrue();
        assertThat(config.maxConcurrentRecoveries()).isEqualTo(3);
    }

    @Test
    void testValidation() {
        // Test compact constructor validation

        // Negative suspectTimeoutMs should throw
        assertThatThrownBy(() -> new FaultConfiguration(-1, 5000, 3, 30000, true, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("suspectTimeoutMs");

        // Negative failureConfirmationMs should throw
        assertThatThrownBy(() -> new FaultConfiguration(3000, -1, 3, 30000, true, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureConfirmationMs");

        // Negative maxRecoveryRetries should throw
        assertThatThrownBy(() -> new FaultConfiguration(3000, 5000, -1, 30000, true, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxRecoveryRetries");

        // Negative recoveryTimeoutMs should throw
        assertThatThrownBy(() -> new FaultConfiguration(3000, 5000, 3, -1, true, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recoveryTimeoutMs");

        // Negative maxConcurrentRecoveries should throw
        assertThatThrownBy(() -> new FaultConfiguration(3000, 5000, 3, 30000, true, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxConcurrentRecoveries");

        // Zero maxConcurrentRecoveries should throw (must be at least 1)
        assertThatThrownBy(() -> new FaultConfiguration(3000, 5000, 3, 30000, true, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxConcurrentRecoveries");

        // Zero timeouts are valid (disable feature)
        var config = new FaultConfiguration(0, 0, 0, 0, false, 1);
        assertThat(config.suspectTimeoutMs()).isZero();
        assertThat(config.failureConfirmationMs()).isZero();
    }

    @Test
    void testBuilder() {
        // Test builder-style with* methods for configuration
        var baseConfig = FaultConfiguration.defaultConfig();

        // Test withSuspectTimeout
        var config1 = baseConfig.withSuspectTimeout(2000);
        assertThat(config1.suspectTimeoutMs()).isEqualTo(2000);
        assertThat(config1.failureConfirmationMs()).isEqualTo(5000); // unchanged
        assertThat(baseConfig.suspectTimeoutMs()).isEqualTo(3000); // original unchanged

        // Test withFailureConfirmation
        var config2 = baseConfig.withFailureConfirmation(10000);
        assertThat(config2.failureConfirmationMs()).isEqualTo(10000);
        assertThat(config2.suspectTimeoutMs()).isEqualTo(3000); // unchanged

        // Test withMaxRetries
        var config3 = baseConfig.withMaxRetries(5);
        assertThat(config3.maxRecoveryRetries()).isEqualTo(5);

        // Test withRecoveryTimeout
        var config4 = baseConfig.withRecoveryTimeout(60000);
        assertThat(config4.recoveryTimeoutMs()).isEqualTo(60000);

        // Test withAutoRecovery
        var config5 = baseConfig.withAutoRecovery(false);
        assertThat(config5.autoRecoveryEnabled()).isFalse();

        // Test withMaxConcurrentRecoveries
        var config6 = baseConfig.withMaxConcurrentRecoveries(5);
        assertThat(config6.maxConcurrentRecoveries()).isEqualTo(5);

        // Test chaining
        var chainedConfig = baseConfig
            .withSuspectTimeout(1000)
            .withFailureConfirmation(2000)
            .withMaxRetries(1)
            .withRecoveryTimeout(15000)
            .withAutoRecovery(false)
            .withMaxConcurrentRecoveries(2);

        assertThat(chainedConfig.suspectTimeoutMs()).isEqualTo(1000);
        assertThat(chainedConfig.failureConfirmationMs()).isEqualTo(2000);
        assertThat(chainedConfig.maxRecoveryRetries()).isEqualTo(1);
        assertThat(chainedConfig.recoveryTimeoutMs()).isEqualTo(15000);
        assertThat(chainedConfig.autoRecoveryEnabled()).isFalse();
        assertThat(chainedConfig.maxConcurrentRecoveries()).isEqualTo(2);
    }
}
