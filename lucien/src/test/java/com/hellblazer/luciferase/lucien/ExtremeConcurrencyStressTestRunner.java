/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

/**
 * Simple runner for ExtremeConcurrencyStressTest to demonstrate usage
 * 
 * @author hal.hildebrand
 */
public class ExtremeConcurrencyStressTestRunner {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Extreme Concurrency Stress Test Runner ===");
        System.out.println("This demonstrates the ExtremeConcurrencyStressTest capabilities");
        System.out.println();
        
        ExtremeConcurrencyStressTest test = new ExtremeConcurrencyStressTest();
        
        try {
            // Run a short demonstration
            System.out.println("Running Octree stress test with 50 threads for 5 seconds...");
            test.setUp();
            test.testOctreeExtremeStress50Threads();
            test.tearDown();
            
            System.out.println("\nTest completed successfully!");
            System.out.println("\nThe full test suite includes:");
            System.out.println("- 50-100 thread stress tests");
            System.out.println("- Rapid entity movement tests");
            System.out.println("- Boundary crossing stress tests");
            System.out.println("- Extended duration tests (60 seconds)");
            System.out.println("- Data integrity verification");
            System.out.println("\nOperation mix: 40% inserts, 30% queries, 20% updates, 10% deletes");
            
        } catch (Exception e) {
            System.err.println("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}