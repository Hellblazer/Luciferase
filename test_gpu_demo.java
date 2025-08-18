// Quick test to validate our BGFX integration compiles
import com.hellblazer.luciferase.render.gpu.GPUConfig;
import com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXIntegration;

public class test_gpu_demo {
    public static void main(String[] args) {
        System.out.println("=== Quick BGFX Integration Test ===");
        
        try {
            // Test if our classes compile and can be instantiated
            GPUConfig config = GPUConfig.builder()
                .withBackend(GPUConfig.Backend.BGFX_METAL)
                .withHeadless(true)
                .withDebugEnabled(true)
                .withWidth(1)
                .withHeight(1)
                .build();
            
            System.out.println("✅ GPUConfig created successfully");
            System.out.println("Backend: " + config.getBackend());
            System.out.println("Headless: " + config.isHeadless());
            
            // Try to create integration (this will fail if BGFX libs are missing)
            try {
                ESVOBGFXIntegration integration = new ESVOBGFXIntegration(config);
                System.out.println("✅ ESVOBGFXIntegration created successfully");
                
                if (integration.isInitialized()) {
                    System.out.println("✅ BGFX Metal backend initialized!");
                    integration.cleanup();
                } else {
                    System.out.println("⚠️ BGFX Metal backend not initialized (expected without proper environment)");
                }
            } catch (Exception e) {
                System.out.println("⚠️ ESVOBGFXIntegration creation failed: " + e.getMessage());
                System.out.println("This is expected if BGFX native libraries are not available");
            }
            
            System.out.println("🎉 Our implemented methods and classes compile correctly!");
            
        } catch (Exception e) {
            System.err.println("❌ Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}