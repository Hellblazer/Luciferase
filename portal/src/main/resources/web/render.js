/**
 * ESVO/ESVT Renderer - Three.js Visualization
 *
 * Phase 5c: Voxel rendering with GPU toggle and color schemes
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_VOXELS = 500000;
const VOXEL_BASE_SIZE = 0.02;

// Color schemes
const COLOR_SCHEMES = {
    DEPTH: (depth, maxDepth, normal) => {
        const t = depth / maxDepth;
        return new THREE.Color().setHSL(0.7 - t * 0.5, 0.8, 0.5 + t * 0.3);
    },
    NORMAL: (depth, maxDepth, normal) => {
        return new THREE.Color(
            Math.abs(normal?.x || 0.5) * 0.5 + 0.5,
            Math.abs(normal?.y || 0.5) * 0.5 + 0.5,
            Math.abs(normal?.z || 0.5) * 0.5 + 0.5
        );
    },
    SOLID: (depth, maxDepth, normal) => {
        return new THREE.Color(0xf472b6); // Pink
    },
    RAINBOW: (depth, maxDepth, normal) => {
        const hue = (depth * 0.15 + Math.random() * 0.1) % 1;
        return new THREE.Color().setHSL(hue, 0.9, 0.6);
    }
};

// ============================================================================
// Scene Setup
// ============================================================================

const container = document.getElementById('canvas-container');

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setClearColor(0x0f0f1a);
container.appendChild(renderer.domElement);

const scene = new THREE.Scene();

const camera = new THREE.PerspectiveCamera(
    60,
    window.innerWidth / window.innerHeight,
    0.01,
    1000
);
camera.position.set(1.5, 1.2, 1.5);
camera.lookAt(0.5, 0.5, 0.5);

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.05;
controls.target.set(0.5, 0.5, 0.5);
controls.minDistance = 0.3;
controls.maxDistance = 10;
controls.update();

// ============================================================================
// Lighting
// ============================================================================

const ambientLight = new THREE.AmbientLight(0xffffff, 0.3);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
directionalLight.position.set(5, 10, 7);
scene.add(directionalLight);

const directionalLight2 = new THREE.DirectionalLight(0xffffff, 0.4);
directionalLight2.position.set(-5, -3, -5);
scene.add(directionalLight2);

// ============================================================================
// Visual Aids
// ============================================================================

const axisHelper = new THREE.AxesHelper(1.2);
scene.add(axisHelper);

// Unit cube wireframe
const unitCubeGeometry = new THREE.BoxGeometry(1, 1, 1);
const unitCubeEdges = new THREE.EdgesGeometry(unitCubeGeometry);
const unitCubeMaterial = new THREE.LineBasicMaterial({
    color: 0x8b5cf6,
    transparent: true,
    opacity: 0.3
});
const unitCubeWireframe = new THREE.LineSegments(unitCubeEdges, unitCubeMaterial);
unitCubeWireframe.position.set(0.5, 0.5, 0.5);
scene.add(unitCubeWireframe);

// ============================================================================
// Voxel InstancedMesh
// ============================================================================

const voxelGeometry = new THREE.BoxGeometry(1, 1, 1);
let instancedMesh = null;
let voxelData = [];
let currentColorScheme = 'DEPTH';

const dummy = new THREE.Object3D();

function createInstancedMesh(count) {
    if (instancedMesh) {
        scene.remove(instancedMesh);
        instancedMesh.geometry.dispose();
        instancedMesh.material.dispose();
    }

    const material = new THREE.MeshStandardMaterial({
        metalness: 0.1,
        roughness: 0.8
    });

    instancedMesh = new THREE.InstancedMesh(voxelGeometry, material, Math.max(count, 1));
    instancedMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    instancedMesh.instanceColor = new THREE.InstancedBufferAttribute(
        new Float32Array(Math.max(count, 1) * 3), 3
    );
    instancedMesh.count = 0;
    scene.add(instancedMesh);
}

function updateVoxels(voxels, maxDepth) {
    voxelData = voxels;
    const count = voxels.length;

    if (!instancedMesh || instancedMesh.instanceMatrix.array.length / 16 < count) {
        createInstancedMesh(Math.max(count * 2, 1000));
    }

    const colorFn = COLOR_SCHEMES[currentColorScheme];

    for (let i = 0; i < count; i++) {
        const voxel = voxels[i];
        const size = voxel.size || VOXEL_BASE_SIZE;

        dummy.position.set(voxel.x, voxel.y, voxel.z);
        dummy.scale.setScalar(size);
        dummy.updateMatrix();
        instancedMesh.setMatrixAt(i, dummy.matrix);

        const color = colorFn(voxel.depth || 0, maxDepth, voxel.normal);
        instancedMesh.setColorAt(i, color);
    }

    instancedMesh.count = count;
    instancedMesh.instanceMatrix.needsUpdate = true;
    if (instancedMesh.instanceColor) {
        instancedMesh.instanceColor.needsUpdate = true;
    }

    console.log(`Updated voxels: ${count} instances`);
}

function recolorVoxels() {
    if (!instancedMesh || !voxelData.length) return;

    const colorFn = COLOR_SCHEMES[currentColorScheme];
    const maxDepth = Math.max(...voxelData.map(v => v.depth || 0), 1);

    for (let i = 0; i < voxelData.length; i++) {
        const voxel = voxelData[i];
        const color = colorFn(voxel.depth || 0, maxDepth, voxel.normal);
        instancedMesh.setColorAt(i, color);
    }

    instancedMesh.instanceColor.needsUpdate = true;
}

// ============================================================================
// GPU Rendering
// ============================================================================

let gpuEnabled = false;
let gpuAvailable = false;
let gpuAnimationId = null;

const gpuCanvas = document.getElementById('gpu-frame');
const gpuCtx = gpuCanvas.getContext('2d');

async function checkGpuAvailability() {
    try {
        const response = await fetch('/api/gpu/info');
        if (response.ok) {
            const info = await response.json();
            gpuAvailable = info.available;

            const statusEl = document.getElementById('gpu-status');
            const textEl = document.getElementById('gpu-status-text');

            if (gpuAvailable) {
                statusEl.classList.add('available');
                statusEl.classList.remove('unavailable');
                textEl.textContent = info.deviceName || 'Available';
                document.getElementById('gpu-toggle').disabled = false;
            } else {
                statusEl.classList.add('unavailable');
                statusEl.classList.remove('available');
                textEl.textContent = 'Not available';
            }

            return gpuAvailable;
        }
    } catch (e) {
        console.error('GPU check failed:', e);
    }

    document.getElementById('gpu-status').classList.add('unavailable');
    document.getElementById('gpu-status-text').textContent = 'Error';
    return false;
}

async function enableGpuRendering() {
    if (!sessionId || !gpuAvailable) return false;

    try {
        const response = await fetch(`/api/gpu/enable?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                frameWidth: Math.floor(window.innerWidth / 2),
                frameHeight: Math.floor(window.innerHeight / 2)
            })
        });

        if (response.ok) {
            gpuEnabled = true;
            gpuCanvas.width = Math.floor(window.innerWidth / 2);
            gpuCanvas.height = Math.floor(window.innerHeight / 2);
            gpuCanvas.style.display = 'block';
            renderer.domElement.style.display = 'none';

            document.getElementById('btn-benchmark').disabled = false;
            startGpuRenderLoop();
            return true;
        } else {
            const error = await response.json();
            console.error('Failed to enable GPU:', error);
            alert(`GPU Error: ${error.error}`);
        }
    } catch (e) {
        console.error('Failed to enable GPU:', e);
    }
    return false;
}

async function disableGpuRendering() {
    if (!sessionId) return;

    stopGpuRenderLoop();

    try {
        await fetch(`/api/gpu/disable?sessionId=${sessionId}`, { method: 'POST' });
    } catch (e) {
        console.error('Failed to disable GPU:', e);
    }

    gpuEnabled = false;
    gpuCanvas.style.display = 'none';
    renderer.domElement.style.display = 'block';
    document.getElementById('btn-benchmark').disabled = true;
}

function startGpuRenderLoop() {
    if (gpuAnimationId) return;

    async function gpuRenderFrame() {
        if (!gpuEnabled) return;

        try {
            const response = await fetch(`/api/gpu/render?sessionId=${sessionId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    cameraPosX: camera.position.x,
                    cameraPosY: camera.position.y,
                    cameraPosZ: camera.position.z,
                    lookAtX: controls.target.x,
                    lookAtY: controls.target.y,
                    lookAtZ: controls.target.z,
                    fovDegrees: camera.fov,
                    outputFormat: 'base64'
                })
            });

            if (response.ok) {
                const result = await response.json();
                displayGpuFrame(result);
            }
        } catch (e) {
            console.error('GPU render failed:', e);
        }

        gpuAnimationId = requestAnimationFrame(gpuRenderFrame);
    }

    gpuRenderFrame();
}

function stopGpuRenderLoop() {
    if (gpuAnimationId) {
        cancelAnimationFrame(gpuAnimationId);
        gpuAnimationId = null;
    }
}

function displayGpuFrame(result) {
    const img = new Image();
    img.onload = () => {
        gpuCtx.drawImage(img, 0, 0, gpuCanvas.width, gpuCanvas.height);
    };

    // Decode base64 RGBA data to image
    const binary = atob(result.imageData);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }

    // Create ImageData and put it on canvas
    const imageData = gpuCtx.createImageData(result.width, result.height);
    imageData.data.set(bytes);
    gpuCtx.putImageData(imageData, 0, 0);
}

async function runGpuBenchmark() {
    if (!sessionId || !gpuEnabled) return;

    try {
        const response = await fetch(`/api/gpu/benchmark?sessionId=${sessionId}&iterations=20`, {
            method: 'POST'
        });

        if (response.ok) {
            const result = await response.json();
            alert(`GPU Benchmark Results:\n\n` +
                `Iterations: ${result.iterations}\n` +
                `Avg Time: ${result.avgRenderTimeMs.toFixed(2)} ms\n` +
                `Min Time: ${result.minRenderTimeMs.toFixed(2)} ms\n` +
                `Max Time: ${result.maxRenderTimeMs.toFixed(2)} ms\n` +
                `Rays/sec: ${(result.raysPerSecond / 1e6).toFixed(2)} M\n` +
                `Device: ${result.deviceName}`
            );
        }
    } catch (e) {
        console.error('Benchmark failed:', e);
    }
}

// ============================================================================
// API Integration
// ============================================================================

let sessionId = null;
let currentRenderType = null;

async function checkConnection() {
    const statusDot = document.getElementById('status-dot');
    const statusText = document.getElementById('status-text');

    try {
        const response = await fetch('/api/health');
        if (response.ok) {
            statusDot.classList.add('connected');
            statusDot.classList.remove('error');
            statusText.textContent = 'Connected';
            return true;
        }
    } catch (e) {
        // Connection failed
    }

    statusDot.classList.add('error');
    statusDot.classList.remove('connected');
    statusText.textContent = 'Disconnected';
    return false;
}

async function createSession() {
    try {
        const response = await fetch('/api/session/create', { method: 'POST' });
        if (response.ok) {
            const data = await response.json();
            sessionId = data.sessionId;
            document.getElementById('session-info').innerHTML =
                `<div class="stat-row"><span class="stat-label">Session</span><span class="stat-value">${sessionId.substring(0, 8)}...</span></div>`;
            return sessionId;
        }
    } catch (e) {
        console.error('Failed to create session:', e);
    }
    return null;
}

async function generateVoxels() {
    if (!sessionId) {
        await createSession();
    }

    const shape = document.getElementById('shape-select').value;
    const renderType = document.getElementById('render-type').value;
    const maxDepth = parseInt(document.getElementById('max-depth').value) || 8;

    // First, create spatial index (Tetree for ESVT, Octree for ESVO)
    const indexType = renderType === 'ESVT' ? 'TETREE' : 'OCTREE';

    try {
        // Delete existing spatial index if any
        await fetch(`/api/spatial?sessionId=${sessionId}`, { method: 'DELETE' }).catch(() => {});

        // Create spatial index
        await fetch(`/api/spatial/create?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                type: indexType,
                maxDepth: maxDepth,
                maxEntitiesPerNode: 5
            })
        });

        // Generate entities based on shape
        const entities = generateShapeEntities(shape, 500);
        await fetch(`/api/spatial/entities/bulk-insert?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(entities)
        });

        // Delete existing render if any
        await fetch(`/api/render?sessionId=${sessionId}`, { method: 'DELETE' }).catch(() => {});

        // Create render structure
        const startTime = performance.now();
        const renderResponse = await fetch(`/api/render/create?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                type: renderType,
                maxDepth: maxDepth,
                gridResolution: 64
            })
        });

        const buildTime = performance.now() - startTime;

        if (renderResponse.ok) {
            const renderInfo = await renderResponse.json();
            currentRenderType = renderType;

            // Update stats
            document.getElementById('stat-render-type').textContent = renderType;
            document.getElementById('stat-nodes').textContent = renderInfo.nodeCount || 0;
            document.getElementById('stat-leaves').textContent = renderInfo.leafCount || 0;
            document.getElementById('stat-build-time').textContent = `${buildTime.toFixed(0)} ms`;

            // Enable GPU toggle for ESVT
            if (renderType === 'ESVT' && gpuAvailable) {
                document.getElementById('gpu-toggle').disabled = false;
            } else {
                document.getElementById('gpu-toggle').disabled = true;
                if (gpuEnabled) {
                    document.getElementById('gpu-toggle').checked = false;
                    await disableGpuRendering();
                }
            }

            // Visualize voxels
            await visualizeVoxels(entities, maxDepth);
        }
    } catch (e) {
        console.error('Failed to generate voxels:', e);
    }
}

function generateShapeEntities(shape, count) {
    const entities = [];

    for (let i = 0; i < count; i++) {
        let x, y, z;

        switch (shape) {
            case 'sphere': {
                // Random point on sphere surface
                const theta = Math.random() * Math.PI * 2;
                const phi = Math.acos(2 * Math.random() - 1);
                const r = 0.3 + Math.random() * 0.1;
                x = 0.5 + r * Math.sin(phi) * Math.cos(theta);
                y = 0.5 + r * Math.sin(phi) * Math.sin(theta);
                z = 0.5 + r * Math.cos(phi);
                break;
            }
            case 'cube': {
                // Random point on cube surface
                const face = Math.floor(Math.random() * 6);
                const u = Math.random() * 0.6 + 0.2;
                const v = Math.random() * 0.6 + 0.2;
                switch (face) {
                    case 0: x = 0.2; y = u; z = v; break;
                    case 1: x = 0.8; y = u; z = v; break;
                    case 2: x = u; y = 0.2; z = v; break;
                    case 3: x = u; y = 0.8; z = v; break;
                    case 4: x = u; y = v; z = 0.2; break;
                    case 5: x = u; y = v; z = 0.8; break;
                }
                break;
            }
            case 'torus': {
                const theta = Math.random() * Math.PI * 2;
                const phi = Math.random() * Math.PI * 2;
                const R = 0.25; // Major radius
                const r = 0.1;  // Minor radius
                x = 0.5 + (R + r * Math.cos(phi)) * Math.cos(theta);
                y = 0.5 + r * Math.sin(phi);
                z = 0.5 + (R + r * Math.cos(phi)) * Math.sin(theta);
                break;
            }
            default: // random
                x = Math.random();
                y = Math.random();
                z = Math.random();
        }

        // Clamp to [0,1]
        x = Math.max(0.01, Math.min(0.99, x));
        y = Math.max(0.01, Math.min(0.99, y));
        z = Math.max(0.01, Math.min(0.99, z));

        entities.push({ x, y, z, content: null });
    }

    return entities;
}

async function visualizeVoxels(entities, maxDepth) {
    // Convert entities to voxel format for visualization
    const voxels = entities.map((e, i) => ({
        x: e.x,
        y: e.y,
        z: e.z,
        size: VOXEL_BASE_SIZE * (1 + Math.random() * 0.5),
        depth: Math.floor(Math.random() * maxDepth),
        normal: {
            x: Math.random() - 0.5,
            y: Math.random() - 0.5,
            z: Math.random() - 0.5
        }
    }));

    updateVoxels(voxels, maxDepth);
}

// ============================================================================
// UI Event Handlers
// ============================================================================

document.getElementById('btn-generate').addEventListener('click', generateVoxels);

document.getElementById('btn-reset-camera').addEventListener('click', () => {
    camera.position.set(1.5, 1.2, 1.5);
    controls.target.set(0.5, 0.5, 0.5);
    controls.update();
});

document.getElementById('gpu-toggle').addEventListener('change', async (e) => {
    if (e.target.checked) {
        const success = await enableGpuRendering();
        if (!success) {
            e.target.checked = false;
        }
    } else {
        await disableGpuRendering();
    }
});

document.getElementById('btn-benchmark').addEventListener('click', runGpuBenchmark);

// Color scheme buttons
document.querySelectorAll('.color-scheme-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.color-scheme-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        currentColorScheme = btn.dataset.scheme;
        recolorVoxels();
    });
});

// ============================================================================
// Animation Loop
// ============================================================================

let frameCount = 0;
let lastFpsUpdate = performance.now();

function animate() {
    requestAnimationFrame(animate);

    controls.update();

    if (!gpuEnabled) {
        renderer.render(scene, camera);
    }

    // FPS counter
    frameCount++;
    const now = performance.now();
    if (now - lastFpsUpdate >= 1000) {
        const fps = Math.round(frameCount * 1000 / (now - lastFpsUpdate));
        document.getElementById('stat-fps').textContent = fps;
        frameCount = 0;
        lastFpsUpdate = now;
    }
}

// ============================================================================
// Window Resize Handler
// ============================================================================

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);

    if (gpuEnabled) {
        gpuCanvas.width = Math.floor(window.innerWidth / 2);
        gpuCanvas.height = Math.floor(window.innerHeight / 2);
    }
});

// ============================================================================
// Initialization
// ============================================================================

async function init() {
    createInstancedMesh(1000);

    const connected = await checkConnection();

    if (connected) {
        await createSession();
        await checkGpuAvailability();
    }

    animate();

    console.log('ESVO/ESVT Renderer initialized (Phase 5c)');
    console.log('Three.js r' + THREE.REVISION);
}

init();
