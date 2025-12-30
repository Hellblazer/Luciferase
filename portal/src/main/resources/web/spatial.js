/**
 * Spatial Index Explorer - Three.js Visualization
 *
 * Phase 5a: Basic scene setup with camera controls, lighting, and API integration
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Scene Setup
// ============================================================================

const container = document.getElementById('canvas-container');

// Renderer
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setClearColor(0x1a1a2e);
container.appendChild(renderer.domElement);

// Scene
const scene = new THREE.Scene();

// Camera
const camera = new THREE.PerspectiveCamera(
    60,                                    // FOV
    window.innerWidth / window.innerHeight, // Aspect
    0.01,                                  // Near
    1000                                   // Far
);
camera.position.set(2, 1.5, 2);
camera.lookAt(0.5, 0.5, 0.5);

// Controls
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.05;
controls.target.set(0.5, 0.5, 0.5);
controls.minDistance = 0.5;
controls.maxDistance = 20;
controls.update();

// ============================================================================
// Lighting
// ============================================================================

// Ambient light for soft fill
const ambientLight = new THREE.AmbientLight(0xffffff, 0.4);
scene.add(ambientLight);

// Directional light (sun simulation)
const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
directionalLight.position.set(5, 10, 7);
directionalLight.castShadow = true;
scene.add(directionalLight);

// Hemisphere light for natural sky/ground coloring
const hemisphereLight = new THREE.HemisphereLight(0x87ceeb, 0x362312, 0.3);
scene.add(hemisphereLight);

// ============================================================================
// Visual Aids
// ============================================================================

// Axis helper (X=red, Y=green, Z=blue)
const axisHelper = new THREE.AxesHelper(1.5);
scene.add(axisHelper);

// Grid helper on XZ plane
const gridHelper = new THREE.GridHelper(2, 20, 0x444444, 0x333333);
gridHelper.position.set(0.5, 0, 0.5);
scene.add(gridHelper);

// Unit cube wireframe (bounds of [0,1]^3 space)
const unitCubeGeometry = new THREE.BoxGeometry(1, 1, 1);
const unitCubeEdges = new THREE.EdgesGeometry(unitCubeGeometry);
const unitCubeMaterial = new THREE.LineBasicMaterial({
    color: 0x4a90d9,
    transparent: true,
    opacity: 0.5
});
const unitCubeWireframe = new THREE.LineSegments(unitCubeEdges, unitCubeMaterial);
unitCubeWireframe.position.set(0.5, 0.5, 0.5);
scene.add(unitCubeWireframe);

// ============================================================================
// Entity Visualization (placeholder for Phase 5b)
// ============================================================================

// Group to hold entity meshes
const entityGroup = new THREE.Group();
scene.add(entityGroup);

// Placeholder sphere geometry for entities
const entityGeometry = new THREE.SphereGeometry(0.02, 16, 16);
const entityMaterial = new THREE.MeshStandardMaterial({
    color: 0x34d399,
    metalness: 0.3,
    roughness: 0.7
});

// ============================================================================
// API Integration
// ============================================================================

let sessionId = null;
let indexType = null;

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

async function createIndex(type) {
    if (!sessionId) {
        await createSession();
    }

    const request = {
        type: type,
        maxDepth: 8,
        maxEntitiesPerNode: 5
    };

    try {
        const response = await fetch(`/api/spatial/create?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (response.ok) {
            const info = await response.json();
            indexType = type;
            document.getElementById('stat-index-type').textContent = type;
            document.getElementById('btn-add-random').disabled = false;
            document.getElementById('btn-clear').disabled = false;
            return info;
        } else {
            const error = await response.json();
            console.error('Failed to create index:', error);
        }
    } catch (e) {
        console.error('Failed to create index:', e);
    }
    return null;
}

async function addRandomEntities(count = 50) {
    if (!sessionId) return;

    const entities = [];
    for (let i = 0; i < count; i++) {
        entities.push({
            x: Math.random(),
            y: Math.random(),
            z: Math.random(),
            content: null
        });
    }

    try {
        const response = await fetch(`/api/spatial/entities/bulk-insert?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(entities)
        });

        if (response.ok) {
            const result = await response.json();
            await refreshEntities();
            return result;
        }
    } catch (e) {
        console.error('Failed to add entities:', e);
    }
    return null;
}

async function refreshEntities() {
    if (!sessionId) return;

    try {
        const response = await fetch(`/api/spatial/entities?sessionId=${sessionId}&size=1000`);
        if (response.ok) {
            const data = await response.json();
            visualizeEntities(data.entities || []);
            document.getElementById('stat-entities').textContent = data.totalCount || 0;
        }
    } catch (e) {
        console.error('Failed to refresh entities:', e);
    }
}

async function clearAll() {
    if (!sessionId) return;

    try {
        await fetch(`/api/spatial?sessionId=${sessionId}`, { method: 'DELETE' });
        entityGroup.clear();
        document.getElementById('stat-entities').textContent = '0';
        document.getElementById('stat-index-type').textContent = '-';
        document.getElementById('btn-add-random').disabled = true;
        document.getElementById('btn-clear').disabled = true;
        indexType = null;
    } catch (e) {
        console.error('Failed to clear:', e);
    }
}

function visualizeEntities(entities) {
    // Clear existing
    entityGroup.clear();

    // Add new entity meshes
    for (const entity of entities) {
        const mesh = new THREE.Mesh(entityGeometry, entityMaterial);
        mesh.position.set(entity.x, entity.y, entity.z);
        mesh.userData = { entityId: entity.entityId };
        entityGroup.add(mesh);
    }
}

// ============================================================================
// UI Event Handlers
// ============================================================================

document.getElementById('btn-create-index').addEventListener('click', async () => {
    const type = document.getElementById('index-type').value;
    await createIndex(type);
});

document.getElementById('btn-add-random').addEventListener('click', async () => {
    await addRandomEntities(50);
});

document.getElementById('btn-clear').addEventListener('click', async () => {
    await clearAll();
});

document.getElementById('btn-reset-camera').addEventListener('click', () => {
    camera.position.set(2, 1.5, 2);
    controls.target.set(0.5, 0.5, 0.5);
    controls.update();
});

// ============================================================================
// Animation Loop
// ============================================================================

let frameCount = 0;
let lastFpsUpdate = performance.now();
let fps = 0;

function animate() {
    requestAnimationFrame(animate);

    // Update controls
    controls.update();

    // Render
    renderer.render(scene, camera);

    // FPS counter
    frameCount++;
    const now = performance.now();
    if (now - lastFpsUpdate >= 1000) {
        fps = Math.round(frameCount * 1000 / (now - lastFpsUpdate));
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
});

// ============================================================================
// Initialization
// ============================================================================

async function init() {
    // Check connection
    const connected = await checkConnection();

    if (connected) {
        // Create session on load
        await createSession();
    }

    // Start animation loop
    animate();

    console.log('Spatial Inspector initialized');
    console.log('Three.js r' + THREE.REVISION);
}

init();
