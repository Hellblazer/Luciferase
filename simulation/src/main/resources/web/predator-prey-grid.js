/**
 * Grand Vision Demo - Pack Hunting Predator-Prey Grid Visualization
 *
 * Connects to MultiBubbleVisualizationServer and renders 2000 entities
 * across 24 tetrahedral bubbles in a 4×4×4 grid using Three.js.
 * Features fish-like prey and shark-like predators with velocity-based orientation.
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_ENTITIES_PER_TYPE = 2500;
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
// Boid Geometry Creation (Fish/Shark shapes)
// ============================================================================

/**
 * Create a fish-like geometry for prey boids.
 * Points forward along +Z axis.
 */
function createPreyGeometry(size) {
    const geometry = new THREE.BufferGeometry();

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
// Entity Rendering (InstancedMesh with Velocity-Based Orientation)
// ============================================================================

const entityMeshes = {};
const entityMaps = {};
const entityVelocities = {};  // Store previous positions for velocity calculation
const dummy = new THREE.Object3D();

function createEntityMesh(type, color, size) {
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
    // If we have tetrahedral vertices, render the actual tetrahedron
    if (bubble.vertices && bubble.vertices.length === 4) {
        return createTetrahedronBoundary(bubble.vertices);
    }

    // Fallback to AABB rendering if no vertices provided
    const min = bubble.min;
    const max = bubble.max;

    const width = max.x - min.x;
    const height = max.y - min.y;
    const depth = max.z - min.z;

    const geometry = new THREE.BoxGeometry(width, height, depth);
    const edges = new THREE.EdgesGeometry(geometry);

    // Create artistic glowing lines with gradient effect
    const line = new THREE.LineSegments(
        edges,
        new THREE.LineBasicMaterial({
            color: 0x60a5fa,
            opacity: 0.3,
            transparent: true,
            linewidth: 1
        })
    );

    // Add a second layer with stronger glow for artistic effect
    const glowLine = new THREE.LineSegments(
        edges.clone(),
        new THREE.LineBasicMaterial({
            color: 0x3b82f6,
            opacity: 0.15,
            transparent: true,
            linewidth: 3,
            depthWrite: false
        })
    );

    // Group both layers together
    const group = new THREE.Group();
    group.add(line);
    group.add(glowLine);

    group.position.set(
        min.x + width / 2,
        min.y + height / 2,
        min.z + depth / 2
    );

    // Store animation data
    group.userData = {
        phase: Math.random() * Math.PI * 2, // Random starting phase for variation
        baseOpacity: 0.3,
        glowBaseOpacity: 0.15
    };

    scene.add(group);
    return group;
}

function createTetrahedronBoundary(vertices) {
    // Create tetrahedron geometry from 4 vertices
    const v0 = new THREE.Vector3(vertices[0].x, vertices[0].y, vertices[0].z);
    const v1 = new THREE.Vector3(vertices[1].x, vertices[1].y, vertices[1].z);
    const v2 = new THREE.Vector3(vertices[2].x, vertices[2].y, vertices[2].z);
    const v3 = new THREE.Vector3(vertices[3].x, vertices[3].y, vertices[3].z);

    // Create edges: v0-v1, v0-v2, v0-v3, v1-v2, v1-v3, v2-v3
    const edgePoints = [
        v0, v1,  // Edge 0-1
        v0, v2,  // Edge 0-2
        v0, v3,  // Edge 0-3
        v1, v2,  // Edge 1-2
        v1, v3,  // Edge 1-3
        v2, v3   // Edge 2-3
    ];

    const edgeGeometry = new THREE.BufferGeometry().setFromPoints(edgePoints);

    // Create artistic glowing lines
    const line = new THREE.LineSegments(
        edgeGeometry,
        new THREE.LineBasicMaterial({
            color: 0x60a5fa,
            opacity: 0.3,
            transparent: true,
            linewidth: 1
        })
    );

    // Add glow layer
    const glowLine = new THREE.LineSegments(
        edgeGeometry.clone(),
        new THREE.LineBasicMaterial({
            color: 0x3b82f6,
            opacity: 0.15,
            transparent: true,
            linewidth: 3,
            depthWrite: false
        })
    );

    // Group both layers
    const group = new THREE.Group();
    group.add(line);
    group.add(glowLine);

    // Store animation data
    group.userData = {
        phase: Math.random() * Math.PI * 2,
        baseOpacity: 0.3,
        glowBaseOpacity: 0.15
    };

    scene.add(group);
    return group;
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

    // Update each type with velocity-based orientation
    for (const [type, typeEntities] of Object.entries(byType)) {
        const mesh = entityMeshes[type];
        const entityMap = entityMaps[type];

        const newMap = new Map();

        typeEntities.forEach((entity, index) => {
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

            // Create rotation that aligns +Z axis with velocity
            // Use lookAt to orient the dummy object
            const lookAtPos = currentPos.clone().add(targetDir);
            dummy.lookAt(lookAtPos);

            // Update matrix for this instance
            dummy.updateMatrix();
            mesh.setMatrixAt(index, dummy.matrix);
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

    // Animate bubble boundaries with subtle pulsing glow
    const time = currentTime * 0.001; // Convert to seconds
    bubbleBoundaries.forEach(boundary => {
        if (boundary.userData && boundary.visible) {
            const phase = boundary.userData.phase;
            const pulse = Math.sin(time * 0.5 + phase) * 0.5 + 0.5; // 0.0 to 1.0

            // Pulse the opacity for breathing effect
            const line = boundary.children[0];
            const glowLine = boundary.children[1];

            if (line && line.material) {
                line.material.opacity = boundary.userData.baseOpacity + pulse * 0.2;
            }
            if (glowLine && glowLine.material) {
                glowLine.material.opacity = boundary.userData.glowBaseOpacity + pulse * 0.25;
            }
        }
    });

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
