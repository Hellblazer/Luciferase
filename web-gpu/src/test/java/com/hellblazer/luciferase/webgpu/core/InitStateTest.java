package com.hellblazer.luciferase.webgpu.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InitState enum.
 * Validates state transitions and properties.
 */
class InitStateTest {
    
    @Test
    void testInitialState() {
        assertThat(InitState.NOT_STARTED.getDescription()).isEqualTo("Not started");
        assertThat(InitState.NOT_STARTED.isTerminal()).isFalse();
        assertThat(InitState.NOT_STARTED.isError()).isFalse();
        assertThat(InitState.NOT_STARTED.isReady()).isFalse();
    }
    
    @Test
    void testTerminalStates() {
        assertThat(InitState.READY.isTerminal()).isTrue();
        assertThat(InitState.FAILED.isTerminal()).isTrue();
        assertThat(InitState.DISPOSED.isTerminal()).isTrue();
        
        assertThat(InitState.STARTING.isTerminal()).isFalse();
        assertThat(InitState.WINDOW_CREATED.isTerminal()).isFalse();
    }
    
    @Test
    void testErrorState() {
        assertThat(InitState.FAILED.isError()).isTrue();
        assertThat(InitState.READY.isError()).isFalse();
        assertThat(InitState.NOT_STARTED.isError()).isFalse();
    }
    
    @Test
    void testReadyState() {
        assertThat(InitState.READY.isReady()).isTrue();
        assertThat(InitState.FAILED.isReady()).isFalse();
        assertThat(InitState.STARTING.isReady()).isFalse();
    }
    
    @Test
    void testStateDescriptions() {
        assertThat(InitState.LIBRARY_LOADED.getDescription()).isEqualTo("Native library loaded");
        assertThat(InitState.WINDOW_CREATED.getDescription()).isEqualTo("Window created");
        assertThat(InitState.INSTANCE_CREATED.getDescription()).isEqualTo("WebGPU instance created");
        assertThat(InitState.SURFACE_CONFIGURED.getDescription()).isEqualTo("Surface configured");
        assertThat(InitState.READY.getDescription()).isEqualTo("Ready for rendering");
    }
}