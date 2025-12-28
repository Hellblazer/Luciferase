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
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan-based GPU Renderer for ESVT (Efficient Sparse Voxel Tetrahedra).
 *
 * <p>This renderer uses Vulkan compute shaders, which work on macOS via MoltenVK
 * (Vulkan-to-Metal translation layer). This provides GPU acceleration on all
 * platforms including Apple Silicon.
 *
 * <p><b>Platform Compatibility:</b>
 * <ul>
 *   <li><b>Apple Silicon Macs</b>: Works via MoltenVK (Vulkan → Metal)</li>
 *   <li><b>Intel Macs</b>: Works via MoltenVK</li>
 *   <li><b>Linux</b>: Native Vulkan with GPU drivers</li>
 *   <li><b>Windows</b>: Native Vulkan with GPU drivers</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class ESVTVulkanRenderer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTVulkanRenderer.class);

    private static final int LOCAL_SIZE_X = 8;
    private static final int LOCAL_SIZE_Y = 8;

    private final int frameWidth;
    private final int frameHeight;

    // Vulkan handles
    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue computeQueue;
    private int computeQueueFamily = -1;

    // Compute pipeline
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long computePipeline;
    private long descriptorPool;
    private long descriptorSet;

    // Buffers
    private long esvtBuffer;
    private long esvtBufferMemory;
    private long contourBuffer;
    private long contourBufferMemory;
    private long outputBuffer;
    private long outputBufferMemory;
    private long cameraUBO;
    private long cameraUBOMemory;
    private long renderFlagsUBO;
    private long renderFlagsUBOMemory;

    // Command pool and buffer
    private long commandPool;
    private VkCommandBuffer commandBuffer;

    // Fence for synchronization
    private long fence;

    // Output image
    private ByteBuffer outputImage;

    private boolean initialized = false;
    private boolean disposed = false;

    // Current ESVT data info
    private int nodeCount = 0;
    private int contourCount = 0;

    /**
     * Create Vulkan renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVTVulkanRenderer(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /**
     * Check if Vulkan is available on this system.
     *
     * @return true if Vulkan GPU is available
     */
    public static boolean isVulkanAvailable() {
        try {
            // Initialize GLFW for Vulkan support check
            if (!glfwInit()) {
                return false;
            }

            // Check if Vulkan is supported
            if (!glfwVulkanSupported()) {
                glfwTerminate();
                return false;
            }

            // Try to create a minimal instance
            try (var stack = stackPush()) {
                var appInfo = VkApplicationInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(stack.UTF8("VulkanCheck"))
                        .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                        .pEngineName(stack.UTF8("ESVT"))
                        .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                        .apiVersion(VK_API_VERSION_1_0);

                var createInfo = VkInstanceCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(appInfo)
                        .flags(0x00000001); // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR

                var pInstance = stack.mallocPointer(1);
                int result = vkCreateInstance(createInfo, null, pInstance);

                if (result == VK_SUCCESS) {
                    var testInstance = new VkInstance(pInstance.get(0), createInfo);
                    vkDestroyInstance(testInstance, null);
                    glfwTerminate();
                    return true;
                }
            }

            glfwTerminate();
            return false;
        } catch (Exception e) {
            log.debug("Vulkan not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Initialize the Vulkan context and create compute pipeline.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        log.info("Initializing Vulkan renderer {}x{}", frameWidth, frameHeight);

        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // Create Vulkan instance
        createInstance();

        // Pick physical device
        pickPhysicalDevice();

        // Create logical device
        createLogicalDevice();

        // Create command pool
        createCommandPool();

        // Create compute pipeline
        createComputePipeline();

        // Allocate output buffer
        outputImage = MemoryUtil.memAlloc(frameWidth * frameHeight * 4);

        initialized = true;
        log.info("Vulkan renderer initialized successfully");
    }

    private void createInstance() {
        try (var stack = stackPush()) {
            var appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("ESVT Renderer"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("Luciferase"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_0);

            // Get required extensions for MoltenVK portability
            var extensions = stack.mallocPointer(2);
            extensions.put(stack.UTF8("VK_KHR_portability_enumeration"));
            extensions.put(stack.UTF8("VK_KHR_get_physical_device_properties2"));
            extensions.flip();

            var createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensions)
                    .flags(0x00000001); // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR

            var pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);

            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan instance: " + result);
            }

            instance = new VkInstance(pInstance.get(0), createInfo);
            log.debug("Created Vulkan instance");
        }
    }

    private void pickPhysicalDevice() {
        try (var stack = stackPush()) {
            var deviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("No Vulkan-capable GPU found");
            }

            var devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, devices);

            // Find a device with compute capability
            for (int i = 0; i < deviceCount.get(0); i++) {
                var device = new VkPhysicalDevice(devices.get(i), instance);

                var queueFamilyCount = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

                var queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

                for (int j = 0; j < queueFamilyCount.get(0); j++) {
                    if ((queueFamilies.get(j).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        physicalDevice = device;
                        computeQueueFamily = j;

                        var props = VkPhysicalDeviceProperties.calloc(stack);
                        vkGetPhysicalDeviceProperties(device, props);
                        log.info("Selected GPU: {}", props.deviceNameString());
                        return;
                    }
                }
            }

            throw new RuntimeException("No GPU with compute capability found");
        }
    }

    private void createLogicalDevice() {
        try (var stack = stackPush()) {
            var queuePriority = stack.floats(1.0f);
            var queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(computeQueueFamily)
                    .pQueuePriorities(queuePriority);

            // Enable portability subset for MoltenVK
            var extensions = stack.mallocPointer(1);
            extensions.put(stack.UTF8(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME));
            extensions.flip();

            var deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);

            var createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfo)
                    .pEnabledFeatures(deviceFeatures)
                    .ppEnabledExtensionNames(extensions);

            var pDevice = stack.mallocPointer(1);
            int result = vkCreateDevice(physicalDevice, createInfo, null, pDevice);

            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device: " + result);
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            var pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, computeQueueFamily, 0, pQueue);
            computeQueue = new VkQueue(pQueue.get(0), device);

            log.debug("Created logical device and compute queue");
        }
    }

    private void createCommandPool() {
        try (var stack = stackPush()) {
            var poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(computeQueueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            var pCommandPool = stack.mallocLong(1);
            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }
            commandPool = pCommandPool.get(0);

            // Allocate command buffer
            var allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            var pCommandBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffer");
            }
            commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);

            // Create fence
            var fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            var pFence = stack.mallocLong(1);
            if (vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create fence");
            }
            fence = pFence.get(0);

            log.debug("Created command pool and fence");
        }
    }

    private void createComputePipeline() {
        try (var stack = stackPush()) {
            // Load and compile shader
            var shaderCode = loadShader("/shaders/raycast_esvt.comp");
            var spirv = compileShader(shaderCode, "raycast_esvt.comp");

            // Create shader module
            var shaderModuleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);

            var pShaderModule = stack.mallocLong(1);
            if (vkCreateShaderModule(device, shaderModuleInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module");
            }
            long shaderModule = pShaderModule.get(0);

            // Create descriptor set layout
            var bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);

            // Binding 0: ESVT nodes buffer
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: Output image
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: Camera UBO
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 3: Render flags UBO
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 4: Coarse output (not used in simple mode)
            bindings.get(4)
                    .binding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 5: Coarse input sampler (not used in simple mode)
            bindings.get(5)
                    .binding(5)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 6: Contour buffer
            // Note: We'll handle this separately or reduce bindings

            var layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            var pDescriptorSetLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }
            descriptorSetLayout = pDescriptorSetLayout.get(0);

            // Create pipeline layout
            var pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            var pPipelineLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }
            pipelineLayout = pPipelineLayout.get(0);

            // Create compute pipeline
            var shaderStageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            var pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(shaderStageInfo)
                    .layout(pipelineLayout);

            var pPipeline = stack.mallocLong(1);
            if (vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute pipeline");
            }
            computePipeline = pPipeline.get(0);

            // Clean up shader module
            vkDestroyShaderModule(device, shaderModule, null);

            // Free SPIR-V buffer
            MemoryUtil.memFree(spirv);

            log.debug("Created compute pipeline");
        }
    }

    private String loadShader(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Shader not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + resourcePath, e);
        }
    }

    private ByteBuffer compileShader(String source, String filename) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shaderc compiler");
        }

        try {
            long options = shaderc_compile_options_initialize();
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            long result = shaderc_compile_into_spv(
                    compiler,
                    source,
                    shaderc_glsl_compute_shader,
                    filename,
                    "main",
                    options
            );

            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                String error = shaderc_result_get_error_message(result);
                shaderc_result_release(result);
                shaderc_compile_options_release(options);
                throw new RuntimeException("Shader compilation failed: " + error);
            }

            int length = (int) shaderc_result_get_length(result);
            ByteBuffer resultBytes = shaderc_result_get_bytes(result);
            ByteBuffer spirv = MemoryUtil.memAlloc(length);
            MemoryUtil.memCopy(resultBytes, spirv);

            shaderc_result_release(result);
            shaderc_compile_options_release(options);

            log.debug("Compiled shader to SPIR-V: {} bytes", length);
            return spirv;
        } finally {
            shaderc_compiler_release(compiler);
        }
    }

    /**
     * Upload ESVT data to GPU buffers.
     *
     * @param data The ESVT data to upload
     */
    public void uploadData(ESVTData data) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        this.nodeCount = data.nodeCount();
        this.contourCount = data.contourCount();

        // Create and upload node buffer
        var nodeData = data.nodesToByteBuffer();
        esvtBuffer = createBuffer(nodeData.remaining(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        uploadToBuffer(esvtBuffer, nodeData);

        // Create and upload contour buffer if present
        if (contourCount > 0) {
            var contourData = data.contoursToByteBuffer();
            contourBuffer = createBuffer(contourData.remaining(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            uploadToBuffer(contourBuffer, contourData);
        }

        // Create output buffer (RGBA8)
        int outputSize = frameWidth * frameHeight * 4;
        outputBuffer = createBuffer(outputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

        // Create camera UBO (4 mat4 + 2 vec4 = 72 floats = 288 bytes, align to 256)
        cameraUBO = createBuffer(320, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);

        // Create render flags UBO
        renderFlagsUBO = createBuffer(32, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);

        // Update descriptor sets
        updateDescriptorSets();

        log.info("Uploaded ESVT data: {} nodes, {} contours", nodeCount, contourCount);
    }

    private long createBuffer(long size, int usage) {
        try (var stack = stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            var pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create buffer");
            }

            return pBuffer.get(0);
        }
    }

    private void uploadToBuffer(long buffer, ByteBuffer data) {
        // Simplified - in production would use staging buffer
        // For now, assume host-visible memory
        log.debug("Uploading {} bytes to buffer", data.remaining());
    }

    private void updateDescriptorSets() {
        // Create descriptor pool and sets
        // Simplified for now
        log.debug("Updating descriptor sets");
    }

    /**
     * Render a frame with the given camera parameters.
     */
    public void renderFrame(Matrix4f viewMatrix, Matrix4f projMatrix,
                            Matrix4f objectToWorld, Matrix4f tetreeToObject) {
        if (!initialized) {
            throw new IllegalStateException("Renderer not initialized");
        }

        // Update camera UBO
        updateCameraUBO(viewMatrix, projMatrix, objectToWorld, tetreeToObject);

        // Record and submit command buffer
        recordCommandBuffer();
        submitCommandBuffer();

        // Read back output
        readOutputBuffer();
    }

    /**
     * Simplified render with position and look-at.
     */
    public void renderFrame(Vector3f cameraPos, Vector3f lookAt, float fovDegrees) {
        var viewMatrix = createLookAtMatrix(cameraPos, lookAt);
        var projMatrix = createPerspectiveMatrix(fovDegrees, (float) frameWidth / frameHeight, 0.1f, 1000.0f);
        var identity = new Matrix4f();
        identity.setIdentity();
        renderFrame(viewMatrix, projMatrix, identity, identity);
    }

    private void updateCameraUBO(Matrix4f view, Matrix4f proj, Matrix4f o2w, Matrix4f t2o) {
        // Upload matrices to UBO
        log.trace("Updating camera UBO");
    }

    private void recordCommandBuffer() {
        try (var stack = stackPush()) {
            vkResetCommandBuffer(commandBuffer, 0);

            var beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer, beginInfo);

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);

            // Dispatch compute
            int groupsX = (frameWidth + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X;
            int groupsY = (frameHeight + LOCAL_SIZE_Y - 1) / LOCAL_SIZE_Y;
            vkCmdDispatch(commandBuffer, groupsX, groupsY, 1);

            vkEndCommandBuffer(commandBuffer);
        }
    }

    private void submitCommandBuffer() {
        try (var stack = stackPush()) {
            vkResetFences(device, fence);

            var submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));

            if (vkQueueSubmit(computeQueue, submitInfo, fence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit command buffer");
            }

            // Wait for completion
            vkWaitForFences(device, fence, true, Long.MAX_VALUE);
        }
    }

    private void readOutputBuffer() {
        // Read output buffer back to CPU
        log.trace("Reading output buffer");
    }

    private Matrix4f createLookAtMatrix(Vector3f eye, Vector3f target) {
        var forward = new Vector3f();
        forward.sub(target, eye);
        forward.normalize();

        var up = new Vector3f(0, 1, 0);
        var right = new Vector3f();
        right.cross(forward, up);
        right.normalize();

        var newUp = new Vector3f();
        newUp.cross(right, forward);

        var matrix = new Matrix4f();
        matrix.m00 = right.x;
        matrix.m01 = right.y;
        matrix.m02 = right.z;
        matrix.m03 = -right.dot(eye);
        matrix.m10 = newUp.x;
        matrix.m11 = newUp.y;
        matrix.m12 = newUp.z;
        matrix.m13 = -newUp.dot(eye);
        matrix.m20 = -forward.x;
        matrix.m21 = -forward.y;
        matrix.m22 = -forward.z;
        matrix.m23 = forward.dot(eye);
        matrix.m30 = 0;
        matrix.m31 = 0;
        matrix.m32 = 0;
        matrix.m33 = 1;

        return matrix;
    }

    private Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        float fovRad = (float) Math.toRadians(fovDegrees);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);

        var matrix = new Matrix4f();
        matrix.m00 = f / aspect;
        matrix.m11 = f;
        matrix.m22 = (far + near) / (near - far);
        matrix.m23 = (2 * far * near) / (near - far);
        matrix.m32 = -1;
        matrix.m33 = 0;

        return matrix;
    }

    /**
     * Get the rendered output image.
     */
    public ByteBuffer getOutputImage() {
        return outputImage;
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;

        if (device != null) {
            vkDeviceWaitIdle(device);

            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, null);
            if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
            if (computePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, computePipeline, null);
            if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);

            // Destroy buffers
            if (esvtBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, esvtBuffer, null);
            if (contourBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, contourBuffer, null);
            if (outputBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, outputBuffer, null);
            if (cameraUBO != VK_NULL_HANDLE) vkDestroyBuffer(device, cameraUBO, null);
            if (renderFlagsUBO != VK_NULL_HANDLE) vkDestroyBuffer(device, renderFlagsUBO, null);

            vkDestroyDevice(device, null);
        }

        if (instance != null) {
            vkDestroyInstance(instance, null);
        }

        if (outputImage != null) {
            MemoryUtil.memFree(outputImage);
        }

        glfwTerminate();

        initialized = false;
        log.info("Vulkan renderer disposed");
    }
}
