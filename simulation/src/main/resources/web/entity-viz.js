/**
 * Entity Visualization - Three.js WebSocket Client
 *
 * Connects to EntityVisualizationServer via WebSocket and renders
 * entities in real-time using Three.js InstancedMesh.
 * Supports color-coded entity types (PREY=blue, PREDATOR=red, DEFAULT=green).
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_ENTITIES_PER_TYPE = 10000;
const ENTITY_SIZE = 2.0;
const WORLD_SIZE = 200;

// Entity type colors
const ENTITY_COLORS = {
    'PREY': 0x4A90E2,      // Blue
    'PREDATOR': 0xE74C3C,  // Red
    'DEFAULT': 0x34d399    // Green
};

// Entity type sizes
const ENTITY_SIZES = {
    'PREY': ENTITY_SIZE,
    'PREDATOR': ENTITY_SIZE * 1.5,
    'DEFAULT': ENTITY_SIZE
};

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

// Camera - position to see the world (0-200 range)
const camera = new THREE.PerspectiveCamera(
    60,
    window.innerWidth / window.innerHeight,
    1,
    2000
);
camera.position.set(300, 200, 300);
camera.lookAt(100, 100, 100);

// Controls
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.05;
controls.target.set(100, 100, 100);
controls.minDistance = 50;
controls.maxDistance = 1000;
controls.update();

// ============================================================================
// Lighting
// ============================================================================

const ambientLight = new THREE.AmbientLight(0xffffff, 0.5);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
directionalLight.position.set(200, 300, 200);
scene.add(directionalLight);

const hemisphereLight = new THREE.HemisphereLight(0x87ceeb, 0x362312, 0.3);
scene.add(hemisphereLight);

// ============================================================================
// Visual Aids
// ============================================================================

// Axis helper at origin
const axisHelper = new THREE.AxesHelper(50);
scene.add(axisHelper);

// Grid on the floor
const gridHelper = new THREE.GridHelper(WORLD_SIZE, 20, 0x444444, 0x333333);
gridHelper.position.set(WORLD_SIZE / 2, 0, WORLD_SIZE / 2);
scene.add(gridHelper);

// World bounds wireframe (0-200 cube)
const worldGeometry = new THREE.BoxGeometry(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
const worldEdges = new THREE.EdgesGeometry(worldGeometry);
const worldMaterial = new THREE.LineBasicMaterial({
    color: 0x4a90d9,
    transparent: true,
    opacity: 0.3
});
const worldWireframe = new THREE.LineSegments(worldEdges, worldMaterial);
worldWireframe.position.set(WORLD_SIZE / 2, WORLD_SIZE / 2, WORLD_SIZE / 2);
scene.add(worldWireframe);

// ============================================================================
// Boid Geometry Creation
// ============================================================================

/**
 * Create a fish-like geometry for prey boids.
 * Points forward along +Z axis.
 */
function createPreyGeometry(size) {
    const geometry = new THREE.BufferGeometry();

    // Fish-like shape: pointed nose, wider body, tapered tail
    const vertices = new Float32Array([
        // Nose (front)
        0, 0, size * 2,

        // Upper body
        -size * 0.5, size * 0.3, size * 0.5,
        size * 0.5, size * 0.3, size * 0.5,

        // Lower body
        -size * 0.5, -size * 0.3, size * 0.5,
        size * 0.5, -size * 0.3, size * 0.5,

        // Tail
        0, 0, -size * 1.5,

        // Top fin
        0, size * 0.8, size * 0.2,

        // Side fins
        -size * 1.2, 0, size * 0.5,
        size * 1.2, 0, size * 0.5
    ]);

    const indices = new Uint16Array([
        // Body triangles
        0, 1, 2,  // top front
        0, 3, 4,  // bottom front
        0, 2, 4,  // right front
        0, 4, 3,  // right front2
        0, 3, 1,  // left front

        // Tail
        1, 3, 5,
        1, 5, 2,
        2, 5, 4,
        4, 5, 3,

        // Top fin
        0, 6, 1,
        0, 2, 6,

        // Side fins
        1, 7, 3,
        2, 8, 4
    ]);

    geometry.setAttribute('position', new THREE.BufferAttribute(vertices, 3));
    geometry.setIndex(new THREE.BufferAttribute(indices, 1));
    geometry.computeVertexNormals();

    return geometry;
}

/**
 * Create a shark-like geometry for predator boids.
 * Larger, more angular, more aggressive looking.
 */
function createPredatorGeometry(size) {
    const geometry = new THREE.BufferGeometry();

    // Shark-like: sharp nose, dorsal fin, powerful tail
    const vertices = new Float32Array([
        // Sharp nose
        0, 0, size * 2.5,

        // Upper jaw
        -size * 0.4, size * 0.2, size * 1.5,
        size * 0.4, size * 0.2, size * 1.5,

        // Lower jaw
        -size * 0.4, -size * 0.2, size * 1.5,
        size * 0.4, -size * 0.2, size * 1.5,

        // Wide body
        -size * 0.6, size * 0.4, size * 0.3,
        size * 0.6, size * 0.4, size * 0.3,
        -size * 0.6, -size * 0.4, size * 0.3,
        size * 0.6, -size * 0.4, size * 0.3,

        // Tail
        0, 0, -size * 2,
        0, size * 0.6, -size * 2,  // Upper tail fin

        // Dorsal fin
        0, size * 1.2, size * 0.5,

        // Pectoral fins (wider)
        -size * 1.5, 0, size * 0.8,
        size * 1.5, 0, size * 0.8
    ]);

    const indices = new Uint16Array([
        // Head
        0, 1, 2,
        0, 3, 4,

        // Upper body
        1, 5, 6,
        1, 6, 2,
        2, 6, 8,
        2, 8, 4,

        // Lower body
        3, 7, 5,
        3, 5, 1,
        4, 8, 7,
        4, 7, 3,

        // Tail
        5, 7, 9,
        6, 8, 9,
        5, 9, 10,
        6, 10, 9,

        // Dorsal fin
        5, 11, 6,

        // Pectoral fins
        1, 12, 5,
        2, 13, 6
    ]);

    geometry.setAttribute('position', new THREE.BufferAttribute(vertices, 3));
    geometry.setIndex(new THREE.BufferAttribute(indices, 1));
    geometry.computeVertexNormals();

    return geometry;
}

// ============================================================================
// InstancedMesh Entity Rendering (one per type)
// ============================================================================

const instancedMeshes = {};
const entityVelocities = {};  // Store previous positions for velocity calculation
const dummy = new THREE.Object3D();
const tempVector = new THREE.Vector3();
const tempQuaternion = new THREE.Quaternion();
const upVector = new THREE.Vector3(0, 1, 0);

// Create instanced meshes for each entity type with oriented geometries
function createInstancedMesh(type) {
    const size = ENTITY_SIZES[type] || ENTITY_SIZE;
    const color = ENTITY_COLORS[type] || ENTITY_COLORS['DEFAULT'];

    // Create appropriate geometry based on type
    let geometry;
    if (type === 'PREY') {
        geometry = createPreyGeometry(size);
    } else if (type === 'PREDATOR') {
        geometry = createPredatorGeometry(size);
    } else {
        geometry = new THREE.ConeGeometry(size, size * 3, 8);
        geometry.rotateX(Math.PI / 2);  // Point along Z axis
    }

    const material = new THREE.MeshStandardMaterial({
        color: color,
        metalness: 0.3,
        roughness: 0.6,
        flatShading: true
    });

    const mesh = new THREE.InstancedMesh(geometry, material, MAX_ENTITIES_PER_TYPE);
    mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    mesh.count = 0;
    scene.add(mesh);

    return mesh;
}

function updateEntities(entities) {
    // Group entities by type
    const entitiesByType = {};
    let totalCount = 0;

    entities.forEach(entity => {
        const type = entity.type || 'DEFAULT';
        if (!entitiesByType[type]) {
            entitiesByType[type] = [];
        }
        entitiesByType[type].push(entity);
        totalCount++;
    });

    // Update or create instanced meshes for each type
    Object.keys(entitiesByType).forEach(type => {
        if (!instancedMeshes[type]) {
            instancedMeshes[type] = createInstancedMesh(type);
        }

        const mesh = instancedMeshes[type];
        const typeEntities = entitiesByType[type];
        const count = Math.min(typeEntities.length, MAX_ENTITIES_PER_TYPE);

        for (let i = 0; i < count; i++) {
            const entity = typeEntities[i];
            const entityId = entity.id;

            // Calculate velocity from position change
            const currentPos = new THREE.Vector3(entity.x, entity.y, entity.z);
            let velocity = new THREE.Vector3(0, 0, 1); // Default forward

            if (entityVelocities[entityId]) {
                velocity.subVectors(currentPos, entityVelocities[entityId]);
                if (velocity.length() > 0.01) {  // Only update if moving
                    velocity.normalize();
                } else {
                    // Use previous velocity if barely moving
                    velocity = entityVelocities[entityId + '_vel'] || velocity;
                }
            }

            // Store current position and velocity for next frame
            entityVelocities[entityId] = currentPos.clone();
            entityVelocities[entityId + '_vel'] = velocity.clone();

            // Set position
            dummy.position.copy(currentPos);

            // Orient boid to face movement direction
            // The boid geometry points along +Z, so we need to rotate it to face velocity
            const targetDir = velocity.clone().normalize();

            // Create rotation matrix that aligns +Z axis with velocity
            tempQuaternion.setFromUnitVectors(
                new THREE.Vector3(0, 0, 1),  // Model's forward direction
                targetDir                      // Desired direction
            );

            dummy.quaternion.copy(tempQuaternion);

            // Update matrix
            dummy.updateMatrix();
            mesh.setMatrixAt(i, dummy.matrix);
        }

        mesh.count = count;
        mesh.instanceMatrix.needsUpdate = true;
    });

    // Hide unused meshes
    Object.keys(instancedMeshes).forEach(type => {
        if (!entitiesByType[type]) {
            instancedMeshes[type].count = 0;
        }
    });

    // Update stats
    document.getElementById('stat-entities').textContent = totalCount;

    // Update type breakdown
    const preyCount = (entitiesByType['PREY'] || []).length;
    const predatorCount = (entitiesByType['PREDATOR'] || []).length;
    const defaultCount = (entitiesByType['DEFAULT'] || []).length;

    let breakdown = '';
    if (preyCount > 0) breakdown += `Prey: ${preyCount} `;
    if (predatorCount > 0) breakdown += `Predators: ${predatorCount} `;
    if (defaultCount > 0) breakdown += `Default: ${defaultCount}`;

    if (breakdown) {
        document.getElementById('stat-breakdown').textContent = breakdown.trim();
    }
}

// ============================================================================
// WebSocket Connection
// ============================================================================

let ws = null;
let reconnectAttempts = 0;
const maxReconnectAttempts = 10;
let lastMessageTime = 0;

function connect() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/entities`;

    console.log('Connecting to:', wsUrl);

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket connected');
        reconnectAttempts = 0;
        document.getElementById('status-dot').classList.add('connected');
        document.getElementById('status-text').textContent = 'Connected';
    };

    ws.onclose = () => {
        console.log('WebSocket disconnected');
        document.getElementById('status-dot').classList.remove('connected');
        document.getElementById('status-text').textContent = 'Disconnected';

        // Attempt reconnect with exponential backoff
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++;
            const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);
            console.log(`Reconnecting in ${delay}ms (attempt ${reconnectAttempts}/${maxReconnectAttempts})`);
            document.getElementById('status-text').textContent = `Reconnecting (${reconnectAttempts}/${maxReconnectAttempts})...`;
            setTimeout(connect, delay);
        } else {
            console.error('Max reconnection attempts reached. Please refresh the page.');
            document.getElementById('status-text').textContent = 'Connection failed - refresh page';
            document.getElementById('status-dot').style.backgroundColor = '#ef4444'; // Red color
        }
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
    };

    ws.onmessage = (event) => {
        const now = performance.now();
        const data = JSON.parse(event.data);

        if (data.entities) {
            updateEntities(data.entities);
        }

        // Update latency (time since last message)
        if (lastMessageTime > 0) {
            const delta = now - lastMessageTime;
            document.getElementById('stat-latency').textContent = `${delta.toFixed(1)}ms`;
        }
        lastMessageTime = now;
    };
}

// ============================================================================
// UI Event Handlers
// ============================================================================

document.getElementById('btn-reset-camera').addEventListener('click', () => {
    camera.position.set(300, 200, 300);
    controls.target.set(100, 100, 100);
    controls.update();
});

document.getElementById('btn-toggle-grid').addEventListener('click', () => {
    gridHelper.visible = !gridHelper.visible;
    worldWireframe.visible = !worldWireframe.visible;
});

// Parameter slider event handlers
document.getElementById('prey-speed').addEventListener('input', (e) => {
    document.getElementById('prey-speed-value').textContent = parseFloat(e.target.value).toFixed(1);
});

document.getElementById('predator-speed').addEventListener('input', (e) => {
    document.getElementById('predator-speed-value').textContent = parseFloat(e.target.value).toFixed(1);
});

document.getElementById('flee-weight').addEventListener('input', (e) => {
    document.getElementById('flee-weight-value').textContent = parseFloat(e.target.value).toFixed(1);
});

document.getElementById('detection-range').addEventListener('input', (e) => {
    document.getElementById('detection-range-value').textContent = parseFloat(e.target.value).toFixed(1);
});

// ============================================================================
// Video Recording
// ============================================================================

let mediaRecorder = null;
let recordedChunks = [];
let recordingStartTime = 0;
let recordingTimerInterval = null;

document.getElementById('btn-record').addEventListener('click', () => {
    try {
        // Capture canvas stream at 60fps
        const stream = renderer.domElement.captureStream(60);

        // Create MediaRecorder with high quality settings
        const options = {
            mimeType: 'video/webm;codecs=vp9',
            videoBitsPerSecond: 8000000 // 8 Mbps for high quality
        };

        // Fallback to vp8 if vp9 not supported
        if (!MediaRecorder.isTypeSupported(options.mimeType)) {
            options.mimeType = 'video/webm;codecs=vp8';
        }

        mediaRecorder = new MediaRecorder(stream, options);
        recordedChunks = [];

        mediaRecorder.ondataavailable = (event) => {
            if (event.data.size > 0) {
                recordedChunks.push(event.data);
            }
        };

        mediaRecorder.onstop = () => {
            // Create blob and download
            const blob = new Blob(recordedChunks, { type: 'video/webm' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `luciferase-boids-${Date.now()}.webm`;
            a.click();
            URL.revokeObjectURL(url);

            console.log('Recording saved:', a.download);
        };

        // Start recording
        mediaRecorder.start(100); // Collect data every 100ms
        recordingStartTime = Date.now();

        // Update UI
        document.getElementById('btn-record').style.display = 'none';
        document.getElementById('btn-stop-record').style.display = 'inline-block';
        document.getElementById('recording-status').style.display = 'block';

        // Start timer
        recordingTimerInterval = setInterval(() => {
            const elapsed = Math.floor((Date.now() - recordingStartTime) / 1000);
            const minutes = Math.floor(elapsed / 60).toString().padStart(2, '0');
            const seconds = (elapsed % 60).toString().padStart(2, '0');
            document.getElementById('recording-time').textContent = `${minutes}:${seconds}`;
        }, 1000);

        console.log('Recording started');

    } catch (err) {
        console.error('Failed to start recording:', err);
        alert('Failed to start recording. Check console for details.');
    }
});

document.getElementById('btn-stop-record').addEventListener('click', () => {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        mediaRecorder.stop();

        // Stop all tracks
        mediaRecorder.stream.getTracks().forEach(track => track.stop());

        // Clear timer
        if (recordingTimerInterval) {
            clearInterval(recordingTimerInterval);
            recordingTimerInterval = null;
        }

        // Update UI
        document.getElementById('btn-record').style.display = 'inline-block';
        document.getElementById('btn-stop-record').style.display = 'none';
        document.getElementById('recording-status').style.display = 'none';
        document.getElementById('recording-time').textContent = '00:00';

        console.log('Recording stopped');
    }
});

// ============================================================================
// Animation Loop
// ============================================================================

let frameCount = 0;
let lastFpsUpdate = performance.now();

function animate() {
    requestAnimationFrame(animate);

    controls.update();
    renderer.render(scene, camera);

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
});

// ============================================================================
// Initialization
// ============================================================================

console.log('Entity Visualization initializing...');
console.log('Three.js r' + THREE.REVISION);

// Start animation loop
animate();

// Connect to WebSocket
connect();

console.log('Entity Visualization ready');
