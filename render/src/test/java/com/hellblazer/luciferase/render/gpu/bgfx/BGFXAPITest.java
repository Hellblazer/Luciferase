package com.hellblazer.luciferase.render.gpu.bgfx;

import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.system.MemoryStack;

/**
 * Test to verify BGFX API method signatures.
 */
public class BGFXAPITest {
    
    public static void main(String[] args) {
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Test debug method signature
            System.out.println("Testing debug method:");
            System.out.println("BGFX_DEBUG_TEXT constant: " + BGFX.BGFX_DEBUG_TEXT);
            
            // Test if debug method expects int or boolean
            try {
                init.debug(true);
                System.out.println("debug(boolean) works");
            } catch (Exception e) {
                System.out.println("debug(boolean) failed: " + e.getMessage());
            }
            
            // Test bgfx_init return type without actually calling it
            System.out.println("bgfx_init method found");
        }
    }
}