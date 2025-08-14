package com.hellblazer.luciferase.webgpu.core;

/**
 * WebGPU initialization state machine.
 * Tracks the initialization progress and allows for proper error handling.
 * Based on successful patterns from jWebGPU reference implementation.
 */
public enum InitState {
    NOT_STARTED("Not started"),
    STARTING("Starting initialization"),
    LIBRARY_LOADED("Native library loaded"),
    WINDOW_CREATED("Window created"),
    INSTANCE_CREATED("WebGPU instance created"),
    SURFACE_CREATED("Surface created"),
    ADAPTER_REQUESTED("Adapter requested"),
    ADAPTER_RECEIVED("Adapter received"),
    DEVICE_REQUESTED("Device requested"),
    DEVICE_CREATED("Device created"),
    SURFACE_CONFIGURED("Surface configured"),
    READY("Ready for rendering"),
    FAILED("Initialization failed"),
    DISPOSED("Resources disposed");

    private final String description;

    InitState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == DISPOSED;
    }

    public boolean isError() {
        return this == FAILED;
    }

    public boolean isReady() {
        return this == READY;
    }
}