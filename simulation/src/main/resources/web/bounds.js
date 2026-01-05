/**
 * Bubble Bounds Visualization - Three.js Wireframe Rendering
 *
 * Visualizes tetrahedral bubble bounds with:
 * - Wireframe tetrahedra for bubble bounds
 * - Lines connecting neighbor bubbles
 * - Color-coded bubbles
 * - Auto-refresh from REST API
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const API_BASE = window.location.origin;
const WIREFRAME_COLORS = [
    0x34d399,  // Green
    0x60a5fa,  // Blue
    0xfbbf24,  // Yellow
    0xf472b6,  // Pink
    0xa78bfa,  // Purple
    0xfbbf24,  // Orange
    0x10b981,  // Emerald
    0x3b82f6,  // Sky blue
];

const NEIGHBOR_LINE_COLOR = 0x6366f1;  // Indigo for connections
const NEIGHBOR_LINE_OPACITY = 0.6;

// ============================================================================
// Scene Setup
// ============================================================================

const container = document.getElementById('canvas-container');

// Renderer
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setClearColor(0x1a1a2e);
container.appendChild(renderer.domElement);

// Scene
const scene = new THREE.Scene();

// Camera
const camera = new THREE.PerspectiveCamera(
    60,
    window.innerWidth / window.innerHeight,
    0.01,
    1000
);
camera.position.set(2, 1.5, 2);
camera.lookAt(0.5, 0.5, 0.5);

// Controls
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

const ambientLight = new THREE.AmbientLight(0xffffff, 0.6);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 0.6);
directionalLight.position.set(5, 10, 7);
scene.add(directionalLight);

// ============================================================================
// Visual Aids
// ============================================================================

const axisHelper = new THREE.AxesHelper(1.5);
scene.add(axisHelper);

const gridHelper = new THREE.GridHelper(2, 20, 0x444444, 0x333333);
gridHelper.position.set(0.5, 0, 0.5);
scene.add(gridHelper);

// Unit cube wireframe
const unitCubeGeometry = new THREE.BoxGeometry(1, 1, 1);
const unitCubeEdges = new THREE.EdgesGeometry(unitCubeGeometry);
const unitCubeMaterial = new THREE.LineBasicMaterial({
    color: 0x4a90d9,
    transparent: true,
    opacity: 0.3
});
const unitCubeWireframe = new THREE.LineSegments(unitCubeEdges, unitCubeMaterial);
unitCubeWireframe.position.set(0.5, 0.5, 0.5);
scene.add(unitCubeWireframe);

// ============================================================================
// Bubble Rendering
// ============================================================================

const bubbleGroup = new THREE.Group();
scene.add(bubbleGroup);

const connectionGroup = new THREE.Group();
scene.add(connectionGroup);

let bubbleData = [];
let colorIndex = 0;

/**
 * Create wireframe tetrahedron from 4 vertices.
 */
function createTetrahedronWireframe(vertices, color) {
    // Create geometry from vertices
    const geometry = new THREE.BufferGeometry();
    const positions = new Float32Array([
        // Edge 0-1
        vertices[0].x, vertices[0].y, vertices[0].z,
        vertices[1].x, vertices[1].y, vertices[1].z,
        // Edge 0-2
        vertices[0].x, vertices[0].y, vertices[0].z,
        vertices[2].x, vertices[2].y, vertices[2].z,
        // Edge 0-3
        vertices[0].x, vertices[0].y, vertices[0].z,
        vertices[3].x, vertices[3].y, vertices[3].z,
        // Edge 1-2
        vertices[1].x, vertices[1].y, vertices[1].z,
        vertices[2].x, vertices[2].y, vertices[2].z,
        // Edge 1-3
        vertices[1].x, vertices[1].y, vertices[1].z,
        vertices[3].x, vertices[3].y, vertices[3].z,
        // Edge 2-3
        vertices[2].x, vertices[2].y, vertices[2].z,
        vertices[3].x, vertices[3].y, vertices[3].z,
    ]);

    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));

    const material = new THREE.LineBasicMaterial({
        color: color,
        linewidth: 2,
        transparent: true,
        opacity: 0.8
    });

    return new THREE.LineSegments(geometry, material);
}

/**
 * Create line between two points.
 */
function createConnectionLine(point1, point2) {
    const geometry = new THREE.BufferGeometry();
    const positions = new Float32Array([
        point1.x, point1.y, point1.z,
        point2.x, point2.y, point2.z
    ]);
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));

    const material = new THREE.LineBasicMaterial({
        color: NEIGHBOR_LINE_COLOR,
        transparent: true,
        opacity: NEIGHBOR_LINE_OPACITY,
        linewidth: 1
    });

    return new THREE.Line(geometry, material);
}

/**
 * Render bubbles and their connections.
 */
function renderBubbles(bubbles) {
    // Clear existing bubbles and connections
    bubbleGroup.clear();
    connectionGroup.clear();

    if (!bubbles || bubbles.length === 0) {
        return;
    }

    // Create bubble ID to centroid map for connection rendering
    const bubbleCentroids = new Map();
    bubbles.forEach(bubble => {
        bubbleCentroids.set(bubble.bubbleId, bubble.centroid);
    });

    // Render each bubble
    colorIndex = 0;
    bubbles.forEach(bubble => {
        // Create wireframe tetrahedron
        const color = WIREFRAME_COLORS[colorIndex % WIREFRAME_COLORS.length];
        const wireframe = createTetrahedronWireframe(bubble.tetrahedralBounds, color);
        bubbleGroup.add(wireframe);

        colorIndex++;
    });

    // Render neighbor connections
    let connectionCount = 0;
    const renderedConnections = new Set();

    bubbles.forEach(bubble => {
        bubble.neighbors.forEach(neighborId => {
            // Create unique connection ID (sorted to avoid duplicates)
            const connectionId = [bubble.bubbleId, neighborId].sort().join('-');

            if (!renderedConnections.has(connectionId)) {
                const neighborCentroid = bubbleCentroids.get(neighborId);
                if (neighborCentroid) {
                    const line = createConnectionLine(bubble.centroid, neighborCentroid);
                    connectionGroup.add(line);
                    renderedConnections.add(connectionId);
                    connectionCount++;
                }
            }
        });
    });

    // Update stats
    updateStats(bubbles.length, connectionCount);
}

// ============================================================================
// API Integration
// ============================================================================

let refreshInterval = null;
let lastError = null;

async function fetchBubbles() {
    try {
        const response = await fetch(`${API_BASE}/api/bubbles`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        const data = await response.json();
        bubbleData = data;
        renderBubbles(bubbleData);
        updateStatus('connected', 'Connected');
        clearError();
    } catch (error) {
        console.error('Failed to fetch bubbles:', error);
        updateStatus('error', `Error: ${error.message}`);
        showError(error.message);
    }
}

function updateStatus(status, text) {
    const statusDot = document.getElementById('status-dot');
    const statusText = document.getElementById('status-text');

    statusDot.className = 'status-dot';
    if (status === 'connected') {
        statusDot.classList.add('connected');
    } else if (status === 'error') {
        statusDot.classList.add('error');
    }

    statusText.textContent = text;
}

function updateStats(bubbleCount, connectionCount) {
    document.getElementById('bubble-count').textContent = bubbleCount;
    document.getElementById('connection-count').textContent = connectionCount;
}

function showError(message) {
    const errorContainer = document.getElementById('error-container');
    errorContainer.innerHTML = `<div class="error-message">${message}</div>`;
}

function clearError() {
    const errorContainer = document.getElementById('error-container');
    errorContainer.innerHTML = '';
}

// ============================================================================
// Controls
// ============================================================================

document.getElementById('refresh-btn').addEventListener('click', () => {
    fetchBubbles();
});

document.getElementById('refresh-rate').addEventListener('change', (e) => {
    const interval = parseInt(e.target.value);

    if (refreshInterval) {
        clearInterval(refreshInterval);
        refreshInterval = null;
    }

    if (interval > 0) {
        refreshInterval = setInterval(fetchBubbles, interval);
    }
});

// ============================================================================
// Animation Loop
// ============================================================================

let lastTime = performance.now();
let frameCount = 0;
let fpsUpdateTime = 0;

function animate() {
    requestAnimationFrame(animate);

    const currentTime = performance.now();
    const delta = currentTime - lastTime;
    lastTime = currentTime;

    // Update FPS counter
    frameCount++;
    fpsUpdateTime += delta;
    if (fpsUpdateTime >= 1000) {
        const fps = Math.round(frameCount / (fpsUpdateTime / 1000));
        document.getElementById('fps').textContent = fps;
        frameCount = 0;
        fpsUpdateTime = 0;
    }

    controls.update();
    renderer.render(scene, camera);
}

// ============================================================================
// Window Resize
// ============================================================================

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
});

// ============================================================================
// Initialization
// ============================================================================

// Initial fetch
fetchBubbles();

// Start auto-refresh (2 seconds default)
refreshInterval = setInterval(fetchBubbles, 2000);

// Start animation loop
animate();
