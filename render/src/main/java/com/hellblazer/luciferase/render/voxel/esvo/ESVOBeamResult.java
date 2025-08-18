/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import java.nio.ByteBuffer;

/**
 * Result of ESVO beam optimization containing GPU computation results.
 */
public class ESVOBeamResult {
    
    private final int numBeams;
    private final ByteBuffer resultData;
    
    public ESVOBeamResult(int numBeams, ByteBuffer resultData) {
        this.numBeams = numBeams;
        this.resultData = resultData;
    }
    
    public int getNumBeams() {
        return numBeams;
    }
    
    public ByteBuffer getResultData() {
        return resultData;
    }
    
    public boolean hasResults() {
        return resultData != null && resultData.remaining() > 0;
    }
    
    @Override
    public String toString() {
        return String.format("ESVOBeamResult{numBeams=%d, dataSize=%d}", 
                           numBeams, resultData != null ? resultData.remaining() : 0);
    }
}