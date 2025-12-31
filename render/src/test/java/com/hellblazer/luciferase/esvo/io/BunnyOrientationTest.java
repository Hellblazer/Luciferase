package com.hellblazer.luciferase.esvo.io;

import org.junit.jupiter.api.Test;
import java.io.IOException;

class BunnyOrientationTest {
    @Test
    void analyzeBunnyOrientation() throws IOException {
        var loader = new VOLLoader();
        var data = loader.loadResource("/voxels/bunny-64.vol");
        var voxels = data.voxels();
        
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0, sumY = 0, sumZ = 0;
        
        for (var v : voxels) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
            sumX += v.x; sumY += v.y; sumZ += v.z;
        }
        
        int n = voxels.size();
        System.out.println("Voxel count: " + n);
        System.out.println("X range: " + minX + "-" + maxX + " (span=" + (maxX-minX+1) + ", centroid=" + (sumX/n) + ")");
        System.out.println("Y range: " + minY + "-" + maxY + " (span=" + (maxY-minY+1) + ", centroid=" + (sumY/n) + ")");
        System.out.println("Z range: " + minZ + "-" + maxZ + " (span=" + (maxZ-minZ+1) + ", centroid=" + (sumZ/n) + ")");
        
        int[] xCounts = new int[64], yCounts = new int[64], zCounts = new int[64];
        for (var v : voxels) {
            xCounts[v.x]++; yCounts[v.y]++; zCounts[v.z]++;
        }
        
        System.out.println("\nSlice density (looking for thin ears at extremes):");
        System.out.print("High X (55-63): "); for(int i=55;i<64;i++) System.out.print(xCounts[i]+" "); System.out.println();
        System.out.print("High Y (55-63): "); for(int i=55;i<64;i++) System.out.print(yCounts[i]+" "); System.out.println();
        System.out.print("High Z (55-63): "); for(int i=55;i<64;i++) System.out.print(zCounts[i]+" "); System.out.println();
        System.out.print("Low X (0-8):    "); for(int i=0;i<9;i++) System.out.print(xCounts[i]+" "); System.out.println();
        System.out.print("Low Y (0-8):    "); for(int i=0;i<9;i++) System.out.print(yCounts[i]+" "); System.out.println();
        System.out.print("Low Z (0-8):    "); for(int i=0;i<9;i++) System.out.print(zCounts[i]+" "); System.out.println();
        
        // The axis with thin counts at high values and thick at low = ears at top, feet at bottom
        System.out.println("\nConclusion: The axis with small high values and large low values should be 'up'");
    }
}
