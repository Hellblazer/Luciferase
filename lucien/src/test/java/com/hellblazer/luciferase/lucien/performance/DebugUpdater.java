package com.hellblazer.luciferase.lucien.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class DebugUpdater {
    public static void main(String[] args) throws Exception {
        Path resultsFile = Paths.get("target/debug-test.csv");
        Map<String, String> updates = RobustPerformanceUpdater.parseResults(resultsFile);
        
        System.out.println("Parsed updates:");
        updates.forEach((k, v) -> System.out.println("  " + k + " -> " + v));
    }
}
