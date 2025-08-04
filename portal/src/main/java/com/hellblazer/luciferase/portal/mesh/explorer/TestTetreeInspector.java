/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.application.Application;

/**
 * Simple test launcher for TetreeInspector.
 *
 * @author hal.hildebrand
 */
public class TestTetreeInspector {
    
    public static void main(String[] args) {
        System.out.println("Launching TetreeInspector...");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("JavaFX runtime: " + System.getProperty("javafx.runtime.version"));
        
        try {
            Application.launch(TetreeInspector.class, args);
        } catch (Exception e) {
            System.err.println("Error launching TetreeInspector:");
            e.printStackTrace();
        }
    }
}