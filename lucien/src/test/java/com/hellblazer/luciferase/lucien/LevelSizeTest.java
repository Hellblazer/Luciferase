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
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

/**
 * Test to understand cell sizes at different levels
 * 
 * @author hal.hildebrand
 */
public class LevelSizeTest {
    
    @Test
    void printLevelSizes() {
        System.out.println("Level | Cell Size | World Coverage");
        System.out.println("------|-----------|---------------");
        
        for (byte level = 0; level <= 20; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            float worldCoverage = (float)cellSize / Constants.MAX_COORD * 100;
            System.out.printf("%5d | %9d | %6.2f%%%n", level, cellSize, worldCoverage);
        }
        
        System.out.println("\nMAX_COORD = " + Constants.MAX_COORD);
        System.out.println("\nAt level 5, cell size = " + Constants.lengthAtLevel((byte)5));
        System.out.println("This means at level 5, each cell covers " + 
                          (100.0f * Constants.lengthAtLevel((byte)5) / Constants.MAX_COORD) + 
                          "% of the world");
    }
}