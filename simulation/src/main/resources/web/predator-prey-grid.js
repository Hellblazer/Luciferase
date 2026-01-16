/**
 * Grand Vision Demo - Pack Hunting Predator-Prey Grid Visualization
 *
 * Connects to MultiBubbleVisualizationServer and renders 1000 entities
 * across 8 tetrahedral bubbles in real-time using Three.js.
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_ENTITIES_PER_TYPE = 1000;
const ENTITY_SIZE = 2.0;
const WORLD_SIZE = 200;

const ENTITY_COLORS = {
    'PREY': 0x4A90E2,      // Blue
    'PREDATOR': 0xE74C3C,  // Red
    'DEFAULT': 0x34d399    // Green
};

const ENTITY_SIZES = {
    'PREY': ENTITY_SIZE,
    'PREDATOR': ENTITY_SIZE * 1.5,
    'DEFAULT': ENTITY_SIZE
};

// ============================================================================
// Scene Setup
// ============================================================================

const container = document.getElementById('canvas-container');

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setClearColor(0x0a0a1e);
container.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.fog = new THREE.FogExp2(0x0a0a1e, 0.0015);

const camera = new THREE.PerspectiveCamera(
    60,
    window.innerWidth / window.innerHeight,
    1,
    2000
);
camera.position.set(400, 300, 400);
camera.lookAt(100, 100, 100);

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.05;
controls.target.set(100, 100, 100);
controls.minDistance = 100;
controls.maxDistance = 800;
controls.update();

// ============================================================================
// Lighting
// ============================================================================

const ambientLight = new THREE.AmbientLight(0xffffff, 0.6);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
directionalLight.position.set(200, 400, 200);
scene.add(directionalLight);

const directionalLight2 = new THREE.DirectionalLight(0x4A90E2, 0.3);
directionalLight2.position.set(-200, 200, -200);
scene.add(directionalLight2);

const hemisphereLight = new THREE.HemisphereLight(0x87ceeb, 0x362312, 0.4);
scene.add(hemisphereLight);

// ============================================================================
// Visual Aids
// ============================================================================

const gridHelper = new THREE.GridHelper(WORLD_SIZE, 20, 0x444444, 0x222222);
gridHelper.position.set(WORLD_SIZE / 2, 0, WORLD_SIZE / 2);
scene.add(gridHelper);

// World bounds
const worldGeometry = new THREE.BoxGeometry(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
const worldEdges = new THREE.EdgesGeometry(worldGeometry);
const worldLine = new THREE.LineSegments(
    worldEdges,
    new THREE.LineBasicMaterial({ color: 0x4a90d9, opacity: 0.3, transparent: true })
);
worldLine.position.set(WORLD_SIZE / 2, WORLD_SIZE / 2, WORLD_SIZE / 2);
scene.add(worldLine);

// ============================================================================
// Entity Rendering (InstancedMesh)
// ============================================================================

const entityGeometries = {};
const entityMeshes = {};
const entityMaps = {};

function createEntityMesh(type, color, size) {
    const geometry = new THREE.SphereGeometry(size, 16, 16);
    const material = new THREE.MeshPhongMaterial({
        color: color,
        emissive: color,
        emissiveIntensity: 0.2,
        shininess: 30
    });

    const mesh = new THREE.InstancedMesh(geometry, material, MAX_ENTITIES_PER_TYPE);
    mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    mesh.count = 0; // Start with 0 visible instances
    scene.add(mesh);

    return mesh;
}

// Create meshes for each entity type
for (const [type, color] of Object.entries(ENTITY_COLORS)) {
    entityMeshes[type] = createEntityMesh(type, color, ENTITY_SIZES[type]);
    entityMaps[type] = new Map(); // entityId -> instance index
}

// ============================================================================
// Bubble Boundaries
// ============================================================================

const bubbleBoundaries = [];
let bubbleBoundariesVisible = true;

function createBubbleBoundary(bubble) {
    const min = bubble.min;
    const max = bubble.max;

    const width = max.x - min.x;
    const height = max.y - min.y;
    const depth = max.z - min.z;

    const geometry = new THREE.BoxGeometry(width, height, depth);
    const edges = new THREE.EdgesGeometry(geometry);
    const line = new THREE.LineSegments(
        edges,
        new THREE.LineBasicMaterial({
            color: 0x60a5fa,
            opacity: 0.5,
            transparent: true,
            linewidth: 2
        })
    );

    line.position.set(
        min.x + width / 2,
        min.y + height / 2,
        min.z + depth / 2
    );

    scene.add(line);
    return line;
}

function updateBubbleBoundaries(bubbles) {
    // Remove old boundaries
    bubbleBoundaries.forEach(boundary => scene.remove(boundary));
    bubbleBoundaries.length = 0;

    // Create new boundaries
    bubbles.forEach(bubble => {
        const boundary = createBubbleBoundary(bubble);
        bubbleBoundaries.push(boundary);
    });

    console.log(`Updated ${bubbles.length} bubble boundaries`);
}

// ============================================================================
// WebSocket Connection
// ============================================================================

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
const wsHost = window.location.host;

let entityWs = null;
let bubbleWs = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 5;

function connectWebSockets() {
    // Entity stream
    entityWs = new WebSocket(`${wsProtocol}//${wsHost}/ws/entities`);

    entityWs.onopen = () => {
        console.log('Entity WebSocket connected');
        updateStatus(true);
        reconnectAttempts = 0;
    };

    entityWs.onmessage = (event) => {
        const data = JSON.parse(event.data);
        if (data.entities) {
            updateEntities(data.entities);
        }
    };

    entityWs.onclose = () => {
        console.log('Entity WebSocket disconnected');
        updateStatus(false);
        attemptReconnect();
    };

    entityWs.onerror = (error) => {
        console.error('Entity WebSocket error:', error);
    };

    // Bubble boundaries stream
    bubbleWs = new WebSocket(`${wsProtocol}//${wsHost}/ws/bubbles`);

    bubbleWs.onopen = () => {
        console.log('Bubble WebSocket connected');
    };

    bubbleWs.onmessage = (event) => {
        const data = JSON.parse(event.data);
        if (data.bubbles) {
            updateBubbleBoundaries(data.bubbles);
            document.getElementById('stat-bubbles').textContent = data.bubbles.length;
        }
    };

    bubbleWs.onerror = (error) => {
        console.error('Bubble WebSocket error:', error);
    };
}

function attemptReconnect() {
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttempts++;
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 10000);
        console.log(`Reconnecting in ${delay}ms (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`);
        setTimeout(connectWebSockets, delay);
    } else {
        console.error('Max reconnection attempts reached');
        updateStatus(false);
    }
}

// ============================================================================
// Entity Updates
// ============================================================================

const matrix = new THREE.Matrix4();
const position = new THREE.Vector3();
const rotation = new THREE.Quaternion();
const scale = new THREE.Vector3(1, 1, 1);

function updateEntities(entities) {
    // Group by type
    const byType = {};
    for (const type of Object.keys(ENTITY_COLORS)) {
        byType[type] = [];
    }

    entities.forEach(entity => {
        const type = entity.type || 'DEFAULT';
        byType[type].push(entity);
    });

    // Update each type
    for (const [type, typeEntities] of Object.entries(byType)) {
        const mesh = entityMeshes[type];
        const entityMap = entityMaps[type];

        const newMap = new Map();

        typeEntities.forEach((entity, index) => {
            position.set(entity.x, entity.y, entity.z);
            matrix.compose(position, rotation, scale);
            mesh.setMatrixAt(index, matrix);
            newMap.set(entity.id, index);
        });

        mesh.count = typeEntities.length;
        mesh.instanceMatrix.needsUpdate = true;

        entityMaps[type] = newMap;
    }

    // Update stats
    const preyCount = byType['PREY'].length;
    const predatorCount = byType['PREDATOR'].length;
    const totalCount = entities.length;

    document.getElementById('stat-total').textContent = totalCount;
    document.getElementById('stat-prey').textContent = preyCount;
    document.getElementById('stat-predators').textContent = predatorCount;
}

// ============================================================================
// UI Controls
// ============================================================================

function updateStatus(connected) {
    const dot = document.getElementById('status-dot');
    const text = document.getElementById('status-text');

    if (connected) {
        dot.classList.add('connected');
        text.textContent = 'Connected';
    } else {
        dot.classList.remove('connected');
        text.textContent = 'Disconnected';
    }
}

document.getElementById('btn-reset-camera').addEventListener('click', () => {
    camera.position.set(400, 300, 400);
    controls.target.set(100, 100, 100);
    controls.update();
});

document.getElementById('btn-toggle-bubbles').addEventListener('click', () => {
    bubbleBoundariesVisible = !bubbleBoundariesVisible;
    bubbleBoundaries.forEach(boundary => {
        boundary.visible = bubbleBoundariesVisible;
    });
});

document.getElementById('btn-toggle-grid').addEventListener('click', () => {
    gridHelper.visible = !gridHelper.visible;
    worldLine.visible = !worldLine.visible;
});

// ============================================================================
// Animation Loop
// ============================================================================

let lastTime = performance.now();
let frameCount = 0;
let fpsUpdateTime = performance.now();

function animate() {
    requestAnimationFrame(animate);

    const currentTime = performance.now();
    const deltaTime = currentTime - lastTime;
    lastTime = currentTime;

    // Update FPS counter
    frameCount++;
    if (currentTime - fpsUpdateTime >= 1000) {
        const fps = Math.round(frameCount * 1000 / (currentTime - fpsUpdateTime));
        document.getElementById('stat-fps').textContent = fps;
        frameCount = 0;
        fpsUpdateTime = currentTime;
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

console.log('Initializing Grand Vision Demo...');
connectWebSockets();
animate();

// Hide banner after 4 seconds
setTimeout(() => {
    const banner = document.getElementById('vision-banner');
    if (banner) {
        banner.style.display = 'none';
    }
}, 4000);

console.log('Grand Vision Demo initialized');
console.log('Prey: Blue spheres | Predators: Red spheres (larger)');
console.log('Camera: Left mouse to rotate, right mouse to pan, scroll to zoom');
