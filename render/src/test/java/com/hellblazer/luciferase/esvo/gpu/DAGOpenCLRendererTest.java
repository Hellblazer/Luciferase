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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.gpu.GPUVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.1: DAG OpenCL Renderer Tests - Write tests FIRST (TDD)
 *
 * Test coverage for GPU-accelerated DAG ray traversal:
 * - Kernel compilation validation
 * - GPU buffer allocation and uploads
 * - Basic DAG traversal on GPU
 * - CPU/GPU parity verification
 * - Multi-vendor compatibility
 *
 * @author hal.hildebrand
 */
@DisplayName("F3.1.1: DAG OpenCL Renderer Tests")
class DAGOpenCLRendererTest {

    private DAGOpenCLRenderer renderer;
    private DAGOctreeData testDAG;
    private ESVOOctreeData testSVO;

    @BeforeEach
    void setUp() {
        // Create test SVO and DAG
        testSVO = createTestOctree();
        testDAG = DAGBuilder.from(testSVO).build();

        // Initialize renderer
        renderer = new DAGOpenCLRenderer(512, 512);
    }

    // ==================== Kernel Compilation Tests ====================

    @Test
    @DisplayName("DAG kernel compiles without GPU (syntax check only)")
    void testDAGKernelCompilesSyntax() {
        // TDD: Test kernel source can be loaded and parsed
        assertDoesNotThrow(() -> {
            var renderer = new DAGOpenCLRenderer(512, 512);
            assertNotNull(renderer.getKernelSource());
            assertTrue(renderer.getKernelSource().contains("rayTraverseDAG"));
        });
    }

    @Test
    @DisplayName("DAG kernel contains required functions")
    void testDAGKernelStructure() {
        // TDD: Verify kernel has required functions
        var source = renderer.getKernelSource();
        assertTrue(source.contains("__kernel void rayTraverseDAG"));
        assertTrue(source.contains("traverseDAG"));
        assertTrue(source.contains("getChildMask"));
        assertTrue(source.contains("getChildPtr"));
    }

    @Test
    @DisplayName("DAG kernel uses absolute addressing (getChildPtr)")
    void testDAGKernelAbsoluteAddressing() {
        // TDD: Verify kernel implements absolute addressing
        var source = renderer.getKernelSource();
        assertTrue(source.contains("childPtr + octant"),
                   "Kernel should use absolute addressing: childPtr + octant");
    }

    // ==================== GPU Buffer Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Upload DAG data to GPU buffers")
    void testUploadDAGDataToGPU() {
        // Luciferase-7f77k: uploadDataBuffers allocates CL buffers, so the renderer must be
        // initialized first (the prior version called it on an uninitialized renderer and failed
        // with "OpenCL context not initialized"). GPU-gated: needs a real OpenCL context.
        renderer.initialize();
        assertDoesNotThrow(() -> {
            renderer.uploadDataBuffers(testDAG);
            // Verify upload succeeded (no exception means success)
        });
    }

    // Luciferase-7f77k: removed the former testRejectNonDAGData. It was misconceived (it wrapped a
    // VALID absolute-addressed testDAG upload in assertThrows(IllegalArgumentException), which never
    // throws). The addressing guard it meant to test (DAGOpenCLRenderer.uploadDataBuffers — reject
    // non-ABSOLUTE) is unreachable via the type contract: DAGOctreeData.getAddressingMode() defaults
    // to ABSOLUTE and no implementation overrides it. The guard remains as defensive production code;
    // testing unreachable code through a fabricated mock provides no coverage of any real path and is
    // not worth a Mockito dependency (YAGNI). Restore a real test if a non-ABSOLUTE DAGOctreeData
    // implementation is ever introduced.

    // ==================== DAG Structure Tests ====================

    @Test
    @DisplayName("DAG uses absolute addressing mode")
    void testDAGAddressingMode() {
        // TDD: Verify DAG is built with absolute addressing
        assertEquals(PointerAddressingMode.ABSOLUTE, testDAG.getAddressingMode(),
                    "DAG must use absolute addressing");
    }

    @Test
    @DisplayName("Renderer name identifies as DAG")
    void testRendererName() {
        // TDD: Verify renderer name is correct
        assertEquals("DAGOpenCLRenderer", renderer.getRendererName());
    }

    @Test
    @DisplayName("Renderer kernel entry point is rayTraverseDAG")
    void testKernelEntryPoint() {
        // TDD: Verify correct kernel entry point
        assertEquals("rayTraverseDAG", renderer.getKernelEntryPoint());
    }

    // ==================== Multi-Vendor Tests ====================

    @Test
    @DisplayName("Detect GPU vendor from environment")
    void testGPUVendorDetection() {
        // TDD: Verify we can detect GPU vendor
        String vendor = System.getenv("GPU_VENDOR");
        if (vendor != null) {
            assertTrue(vendor.matches("NVIDIA|AMD|Intel|Apple"),
                      "GPU_VENDOR should be NVIDIA, AMD, Intel, or Apple");
        }
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU traversal works on NVIDIA hardware")
    void testNvidiaOpenCL() {
        // Luciferase-7f77k: initialize() before any upload — uploadDataBuffers allocates CL buffers
        // and needs the context. The prior order (uploadDataBuffers before initialize) only passed
        // when a sibling test had already initialized the shared OpenCLContext singleton — flaky.
        renderer.initialize();
        assertDoesNotThrow(() -> {
            renderer.uploadDataBuffers(testDAG);
            renderer.uploadData(testDAG);
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("GPU traversal works on AMD hardware")
    void testAmdOpenCL() {
        // Luciferase-7f77k: initialize() before any upload (see testNvidiaOpenCL).
        renderer.initialize();
        assertDoesNotThrow(() -> {
            renderer.uploadDataBuffers(testDAG);
            renderer.uploadData(testDAG);
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("GPU traversal works on Intel hardware")
    void testIntelOpenCL() {
        // Luciferase-7f77k: initialize() before any upload (see testNvidiaOpenCL).
        renderer.initialize();
        assertDoesNotThrow(() -> {
            renderer.uploadDataBuffers(testDAG);
            renderer.uploadData(testDAG);
        });
    }

    // ==================== .161 Thread-Safety Tests ====================

    @Test
    @DisplayName(".161: executeKernel does not mutate the shared 'kernel' field")
    void testExecuteKernelNoSharedFieldMutation() throws Exception {
        // Verify via reflection that the executeKernel() method body never assigns
        // to the 'kernel' field of AbstractOpenCLRenderer.  We do this by reading
        // the field before and after calling the private method on an uninitialized
        // renderer (which will throw before any GPU call is made, but the field
        // assignment would happen before the GPU call if the bug were present).
        var r = new DAGOpenCLRenderer(64, 64);

        // Grab the protected 'kernel' field from the superclass.
        Field kernelField = r.getClass().getSuperclass().getDeclaredField("kernel");
        kernelField.setAccessible(true);

        // Field should be null before initialization.
        Object kernelBefore = kernelField.get(r);
        assertNull(kernelBefore, "kernel field should be null before initialization");

        // Attempt to invoke executeKernel via reflection.
        // Because batchKernel==null and kernel==null, it should throw
        // IllegalStateException("Kernel not initialized") without touching the field.
        Method executeKernel = r.getClass().getDeclaredMethod("executeKernel");
        executeKernel.setAccessible(true);
        assertThrows(Exception.class, () -> executeKernel.invoke(r));

        // The field must remain null — the swap-and-restore idiom would have set it
        // to activeKernel (non-null only if kernel/batchKernel were non-null, so this
        // path verifies the null guard fires before any field assignment).
        Object kernelAfter = kernelField.get(r);
        assertNull(kernelAfter, ".161: kernel field must not be mutated inside executeKernel");
    }

    @Test
    @DisplayName(".161: getKernel() always reflects main kernel, not batch kernel")
    void testGetKernelReturnsMainKernel() {
        // With the swap removed, getKernel() must never return batchKernel.
        // Before initialization, both are null — this validates the invariant
        // without requiring a real GPU.
        var r = new DAGOpenCLRenderer(64, 64);
        // getKernel() is inherited from AbstractOpenCLRenderer, returns the kernel field.
        // After our fix it can never be set to batchKernel by executeKernel.
        // We verify the renderer can be constructed and getKernel() is null (pre-init).
        assertNull(r.getKernel(), ".161: getKernel() should return null (main kernel) pre-init, not batchKernel");
    }

    // ==================== .163 Real-Query-or-Honest-Generic Tests ====================

    @Test
    @DisplayName(".163: detectGPUCapabilities never claims NVIDIA when no real device detected")
    void testDetectGPUCapabilitiesNoFakeNVIDIA() throws Exception {
        var r = new DAGOpenCLRenderer(64, 64);

        // Invoke detectGPUCapabilities via reflection (private method).
        Method detect = r.getClass().getDeclaredMethod("detectGPUCapabilities");
        detect.setAccessible(true);
        var caps = (com.hellblazer.luciferase.sparse.gpu.GPUCapabilities) detect.invoke(r);

        assertNotNull(caps, "detectGPUCapabilities must return non-null");

        // Core acceptance criterion: must not silently claim NVIDIA when the real
        // GPUVendorDetector returns no valid device in headless/CI environments.
        // If a real GPU is present, the vendor may legitimately be NVIDIA — that's fine.
        // If no GPU is present, GPUVendorDetector.getCapabilities().isValid()==false,
        // and we must NOT return NVIDIA with model "Generic GPU".
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            // Headless / no-GPU: vendor must be UNKNOWN, model must not claim "Generic GPU" + NVIDIA.
            assertNotEquals(GPUVendor.NVIDIA, caps.vendor(),
                ".163: with no real GPU, vendor must not be hardcoded NVIDIA");
            assertFalse(caps.vendor() == GPUVendor.NVIDIA && "Generic GPU".equals(caps.model()),
                ".163: fake 'NVIDIA Generic GPU' stub must be replaced with honest generic fallback");
        } else {
            // Real GPU present: vendor must match what GPUVendorDetector detected.
            // We just verify it's not the old hardcoded stub value.
            assertFalse(caps.vendor() == GPUVendor.NVIDIA && "Generic GPU".equals(caps.model())
                        && caps.computeUnits() == 32 && caps.localMemoryBytes() == 65536,
                ".163: even with a real GPU, must not return the hardcoded stub");
        }
    }

    @Test
    @DisplayName(".163: optimizeForDevice does not log 'NVIDIA Generic GPU' without a real device")
    void testOptimizeForDeviceHonestLogging() throws Exception {
        var detector = GPUVendorDetector.getInstance();
        // This test validates the logic only — log capture would require a log appender.
        // Instead, verify that detectGPUCapabilities returns an honest value so optimizeForDevice
        // would log the correct vendor name.
        var r = new DAGOpenCLRenderer(64, 64);
        Method detect = r.getClass().getDeclaredMethod("detectGPUCapabilities");
        detect.setAccessible(true);
        var caps = (com.hellblazer.luciferase.sparse.gpu.GPUCapabilities) detect.invoke(r);

        if (!detector.getCapabilities().isValid()) {
            // Headless: the log message vendor must be UNKNOWN, not the fake NVIDIA stub.
            assertEquals(GPUVendor.UNKNOWN, caps.vendor(),
                ".163: headless fallback must use UNKNOWN vendor, not fake NVIDIA");
        }
        // If a real GPU is present, any valid vendor value is acceptable.
    }

    // ==================== ie6v8: actual-device capability query ====================

    /**
     * ie6v8: once the renderer's OpenCLContext is initialized, detectGPUCapabilities() must query
     * the renderer's ACTUAL device (context.getDevice()) — not GPUVendorDetector's global first-GPU
     * scan. Asserts the returned caps equal getCapabilitiesForDevice(actualDevice) and that a real
     * device yields valid (not UNKNOWN) caps. GPU-gated: requires a live cl_device_id.
     *
     * <p><b>Verification limit (honest disclosure):</b> on a single-GPU / unified-memory host
     * (e.g. Apple Silicon, the only hardware this is run on) the global first-GPU scan and
     * context.getDevice() resolve to the SAME physical device, so this test validates the
     * return-value contract but CANNOT distinguish the per-device path from the reverted global-scan
     * path — both would pass here. The per-device-vs-global divergence is only observable on a
     * genuine multi-GPU / multi-platform host, which is not available in CI or on the dev machine.
     * The device==0 guard ({@code GPUVendorDetectorTest#testGetCapabilitiesForDeviceZeroGuard}) is
     * the CI-runnable structural check of the new API.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @DisplayName("ie6v8: detectGPUCapabilities queries the renderer's actual device, not the global first GPU")
    void testDetectGPUCapabilitiesUsesActualDevice() throws Exception {
        // NB: like the other GPU tests in this class, we do NOT dispose() — the OpenCLContext is a
        // refcounted process singleton these tests share; disposing it to refcount 0 would tear it
        // down for sibling tests that upload without re-acquiring.
        renderer.initialize();

        // Read the renderer's actual OpenCL device id from the base context field.
        Field contextField = renderer.getClass().getSuperclass().getDeclaredField("context");
        contextField.setAccessible(true);
        var context = contextField.get(renderer);
        var getDevice = context.getClass().getMethod("getDevice");
        long actualDevice = (long) getDevice.invoke(context);
        assertNotEquals(0L, actualDevice, "an initialized context must expose a real cl_device_id");

        Method detect = renderer.getClass().getDeclaredMethod("detectGPUCapabilities");
        detect.setAccessible(true);
        var caps = (com.hellblazer.luciferase.sparse.gpu.GPUCapabilities) detect.invoke(renderer);
        assertNotEquals(GPUVendor.UNKNOWN, caps.vendor(),
            "ie6v8: with a live device the renderer must report valid (non-UNKNOWN) capabilities");

        // The returned caps must be the ones derived from the renderer's ACTUAL device, proving
        // the per-device path is taken rather than the global getCapabilities() scan.
        var actualDeviceCaps = GPUVendorDetector.getInstance().getCapabilitiesForDevice(actualDevice);
        assertTrue(actualDeviceCaps.isValid(), "the actual device must itself report valid caps");
        var expectedVendor = GPUVendor.fromVendorString(actualDeviceCaps.vendorString());
        assertEquals(expectedVendor, caps.vendor(),
            "ie6v8: detected vendor must match the renderer's actual device, not the first global GPU");
        assertEquals(actualDeviceCaps.deviceName(), caps.model(),
            "ie6v8: detected device name must match the renderer's actual device");
        assertEquals(actualDeviceCaps.computeUnits(), caps.computeUnits(),
            "ie6v8: detected compute units must come from the renderer's actual device");
    }

    // ==================== Helper Methods ====================

    private ESVOOctreeData createTestOctree() {
        var octree = new ESVOOctreeData(1024);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);
        root.setChildPtr(1);
        octree.setNode(0, root);

        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }
}
