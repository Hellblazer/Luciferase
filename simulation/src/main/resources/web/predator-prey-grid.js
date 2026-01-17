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
const bubbleSpheres = [];
const bubbleSphereLabels = [];
let bubbleBoundariesVisible = true;
let bubbleSpheresVisible = false; // Start hidden, user can toggle with 'S' key

function createBubbleBoundary(bubble) {
    // If we have RDGCS bounding box (8 vertices), render the actual simulation volume
    if (bubble.vertices && bubble.vertices.length === 8) {
        return createBoxBoundary(bubble.vertices, bubble.tetType || 0);
    }

    // If we have tetrahedral vertices (4), render the spatial index tetrahedron
    if (bubble.vertices && bubble.vertices.length === 4) {
        return createTetrahedronBoundary(bubble.vertices, bubble.tetType || 0);
    }

    // Fallback to generic AABB if no vertices provided
    const min = bubble.min;
    const max = bubble.max;

    const width = max.x - min.x;
    const height = max.y - min.y;
    const depth = max.z - min.z;

    const geometry = new THREE.BoxGeometry(width, height, depth);
    const edges = new THREE.EdgesGeometry(geometry);

    // Create bright, highly visible lines
    const line = new THREE.LineSegments(
        edges,
        new THREE.LineBasicMaterial({
            color: 0x00ffff,  // Bright cyan
            opacity: 0.9,      // Much more opaque
            transparent: true,
            linewidth: 4       // Thicker lines
        })
    );

    // Add strong glow layer
    const glowLine = new THREE.LineSegments(
        edges.clone(),
        new THREE.LineBasicMaterial({
            color: 0x00ff00,   // Bright green glow
            opacity: 0.6,      // Strong glow
            transparent: true,
            linewidth: 8,      // Thick glow
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

    // Store animation data (higher base opacity)
    group.userData = {
        phase: Math.random() * Math.PI * 2,
        baseOpacity: 0.9,
        glowBaseOpacity: 0.6
    };

    scene.add(group);
    return group;
}

/**
 * Create bounding box wireframe from 8 RDGCS box corners.
 * This represents the actual simulation volume where entities live.
 *
 * Vertex ordering:
 * 0: (minX, minY, minZ)  4: (minX, minY, maxZ)
 * 1: (maxX, minY, minZ)  5: (maxX, minY, maxZ)
 * 2: (maxX, maxY, minZ)  6: (maxX, maxY, maxZ)
 * 3: (minX, maxY, minZ)  7: (minX, maxY, maxZ)
 */
function createBoxBoundary(vertices, tetType) {
    // Create box geometry from 8 vertices
    const v0 = new THREE.Vector3(vertices[0].x, vertices[0].y, vertices[0].z);
    const v1 = new THREE.Vector3(vertices[1].x, vertices[1].y, vertices[1].z);
    const v2 = new THREE.Vector3(vertices[2].x, vertices[2].y, vertices[2].z);
    const v3 = new THREE.Vector3(vertices[3].x, vertices[3].y, vertices[3].z);
    const v4 = new THREE.Vector3(vertices[4].x, vertices[4].y, vertices[4].z);
    const v5 = new THREE.Vector3(vertices[5].x, vertices[5].y, vertices[5].z);
    const v6 = new THREE.Vector3(vertices[6].x, vertices[6].y, vertices[6].z);
    const v7 = new THREE.Vector3(vertices[7].x, vertices[7].y, vertices[7].z);

    // Color palette for tetrahedral types (S0-S5)
    const typeColors = [
        0xFF0000,  // S0: Red
        0x00FF00,  // S1: Green
        0x0000FF,  // S2: Blue
        0xFFFF00,  // S3: Yellow
        0xFF00FF,  // S4: Magenta
        0x00FFFF   // S5: Cyan
    ];
    const color = typeColors[tetType % 6] || 0xFFFFFF;

    console.log(`Creating RDGCS box type ${tetType}: min=(${v0.x.toFixed(1)},${v0.y.toFixed(1)},${v0.z.toFixed(1)}) max=(${v6.x.toFixed(1)},${v6.y.toFixed(1)},${v6.z.toFixed(1)}) color=0x${color.toString(16)}`);

    // Create 12 edges for box wireframe
    // Bottom face (4 edges)
    // Top face (4 edges)
    // Vertical edges (4 edges)
    const edgePoints = [
        // Bottom face (minZ)
        v0, v1,  // 0-1
        v1, v2,  // 1-2
        v2, v3,  // 2-3
        v3, v0,  // 3-0
        // Top face (maxZ)
        v4, v5,  // 4-5
        v5, v6,  // 5-6
        v6, v7,  // 6-7
        v7, v4,  // 7-4
        // Vertical edges
        v0, v4,  // 0-4
        v1, v5,  // 1-5
        v2, v6,  // 2-6
        v3, v7   // 3-7
    ];

    const edgeGeometry = new THREE.BufferGeometry().setFromPoints(edgePoints);

    // Create bright, highly visible lines with type-based color
    const line = new THREE.LineSegments(
        edgeGeometry,
        new THREE.LineBasicMaterial({
            color: color,      // Type-specific color
            opacity: 0.9,      // Much more opaque
            transparent: true,
            linewidth: 4       // Thicker lines
        })
    );

    // Add strong glow layer
    const glowLine = new THREE.LineSegments(
        edgeGeometry.clone(),
        new THREE.LineBasicMaterial({
            color: color,      // Same color as main line
            opacity: 0.4,      // Lighter glow
            transparent: true,
            linewidth: 8,      // Thick glow
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
        baseOpacity: 0.9,
        glowBaseOpacity: 0.6
    };

    scene.add(group);
    return group;
}

function createTetrahedronBoundary(vertices, tetType) {
    // Create tetrahedron geometry from 4 vertices
    const v0 = new THREE.Vector3(vertices[0].x, vertices[0].y, vertices[0].z);
    const v1 = new THREE.Vector3(vertices[1].x, vertices[1].y, vertices[1].z);
    const v2 = new THREE.Vector3(vertices[2].x, vertices[2].y, vertices[2].z);
    const v3 = new THREE.Vector3(vertices[3].x, vertices[3].y, vertices[3].z);

    // Color palette for tetrahedral types (S0-S5)
    const typeColors = [
        0xFF0000,  // S0: Red
        0x00FF00,  // S1: Green
        0x0000FF,  // S2: Blue
        0xFFFF00,  // S3: Yellow
        0xFF00FF,  // S4: Magenta
        0x00FFFF   // S5: Cyan
    ];
    const color = typeColors[tetType % 6] || 0xFFFFFF;

    console.log(`Creating tetrahedron type ${tetType}: v0=(${v0.x.toFixed(1)},${v0.y.toFixed(1)},${v0.z.toFixed(1)}) v3=(${v3.x.toFixed(1)},${v3.y.toFixed(1)},${v3.z.toFixed(1)}) color=0x${color.toString(16)}`);

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

    // Create bright, highly visible lines with type-based color
    const line = new THREE.LineSegments(
        edgeGeometry,
        new THREE.LineBasicMaterial({
            color: color,      // Type-specific color
            opacity: 0.9,      // Much more opaque
            transparent: true,
            linewidth: 4       // Thicker lines
        })
    );

    // Add strong glow layer (slightly lighter version of main color)
    const glowLine = new THREE.LineSegments(
        edgeGeometry.clone(),
        new THREE.LineBasicMaterial({
            color: color,      // Same color as main line
            opacity: 0.4,      // Lighter glow
            transparent: true,
            linewidth: 8,      // Thick glow
            depthWrite: false
        })
    );

    // Group both layers
    const group = new THREE.Group();
    group.add(line);
    group.add(glowLine);

    // Store animation data (higher base opacity)
    group.userData = {
        phase: Math.random() * Math.PI * 2,
        baseOpacity: 0.9,
        glowBaseOpacity: 0.6
    };

    scene.add(group);
    return group;
}

/**
 * Create a translucent sphere inscribed in the tetrahedral simulation volume.
 */
function createTextSprite(message, color = 'rgba(255, 255, 255, 1.0)') {
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    canvas.width = 256;
    canvas.height = 128;

    context.font = 'Bold 80px Arial';
    context.fillStyle = color;
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText(message, canvas.width / 2, canvas.height / 2);

    const texture = new THREE.CanvasTexture(canvas);
    const material = new THREE.SpriteMaterial({ map: texture });
    const sprite = new THREE.Sprite(material);
    sprite.scale.set(10, 5, 1);
    return sprite;
}

function createBubbleSphere(bubble, index) {
    if (!bubble.sphere) {
        return null;
    }

    const center = bubble.sphere.center;
    const radius = bubble.sphere.radius;
    const tetType = bubble.tetType || 0;

    // Color based on tetType (matching tetrahedron colors)
    const typeColors = [
        0xFF0000,  // S0: Red
        0x00FF00,  // S1: Green
        0x0000FF,  // S2: Blue
        0xFFFF00,  // S3: Yellow
        0xFF00FF,  // S4: Magenta
        0x00FFFF   // S5: Cyan
    ];
    const color = typeColors[tetType % 6] || 0xFFFFFF;

    const geometry = new THREE.SphereGeometry(radius, 16, 16);
    const material = new THREE.MeshBasicMaterial({
        color: color,
        transparent: true,
        opacity: 0.15,
        side: THREE.DoubleSide,
        depthWrite: false
    });

    const sphere = new THREE.Mesh(geometry, material);
    sphere.position.set(center.x, center.y, center.z);
    sphere.visible = bubbleSpheresVisible;

    scene.add(sphere);

    // Add text label
    const label = createTextSprite(index.toString());
    label.position.set(center.x, center.y, center.z);
    label.visible = bubbleSpheresVisible;
    scene.add(label);
    bubbleSphereLabels.push(label);

    return sphere;
}

function updateBubbleBoundaries(bubbles) {
    // Remove old boundaries, spheres, and labels
    bubbleBoundaries.forEach(boundary => scene.remove(boundary));
    bubbleBoundaries.length = 0;
    bubbleSpheres.forEach(sphere => scene.remove(sphere));
    bubbleSpheres.length = 0;
    bubbleSphereLabels.forEach(label => scene.remove(label));
    bubbleSphereLabels.length = 0;
    bubbleBoundaryMap.clear();

    // Create new boundaries and spheres with labels
    bubbles.forEach((bubble, index) => {
        const boundary = createBubbleBoundary(bubble);
        bubbleBoundaries.push(boundary);
        bubbleBoundaryMap.set(bubble.id, boundary); // Store mapping for state updates

        const sphere = createBubbleSphere(bubble, index);
        if (sphere) {
            bubbleSpheres.push(sphere);
        }
    });

    console.log(`Updated ${bubbles.length} bubble boundaries and ${bubbleSpheres.length} inscribed spheres with labels`);
}

// ============================================================================
// WebSocket Connection
// ============================================================================

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
const wsHost = window.location.host;

let entityWs = null;
let bubbleWs = null;
let topologyWs = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 5;

// Density metrics tracking
let densityMetrics = new Map();
let bubbleBoundaryMap = new Map(); // Maps bubble ID to boundary object

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

    // Topology events stream
    topologyWs = new WebSocket(`${wsProtocol}//${wsHost}/ws/topology`);

    topologyWs.onopen = () => {
        console.log('Topology WebSocket connected');
        fetchDensityMetrics(); // Fetch initial density data
    };

    topologyWs.onmessage = (event) => {
        const data = JSON.parse(event.data);
        handleTopologyEvent(data);
    };

    topologyWs.onerror = (error) => {
        console.error('Topology WebSocket error:', error);
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
// Topology Events & Density Metrics
// ============================================================================

function fetchDensityMetrics() {
    fetch(`${window.location.protocol}//${window.location.host}/api/density`)
        .then(response => response.json())
        .then(data => {
            if (data.density) {
                updateDensityPanel(data.density);
            }
        })
        .catch(error => {
            console.error('Failed to fetch density metrics:', error);
        });

    // Poll for updates every 5 seconds
    setInterval(() => {
        fetch(`${window.location.protocol}//${window.location.host}/api/density`)
            .then(response => response.json())
            .then(data => {
                if (data.density) {
                    updateDensityPanel(data.density);
                }
            })
            .catch(error => {
                console.error('Failed to fetch density metrics:', error);
            });
    }, 5000);
}

function handleTopologyEvent(event) {
    console.log('Topology event:', event);

    switch (event.eventType) {
        case 'split':
            console.log(`Split: Bubble ${event.sourceBubbleId} → ${event.newBubbleId} (${event.entitiesMoved} entities)`);
            animateSplit(event.sourceBubbleId, event.newBubbleId, event.entitiesMoved);
            break;
        case 'merge':
            console.log(`Merge: Bubble ${event.sourceBubbleId} → ${event.targetBubbleId} (${event.entitiesMoved} entities)`);
            animateMerge(event.sourceBubbleId, event.targetBubbleId, event.entitiesMoved);
            break;
        case 'move':
            console.log(`Move: Bubble ${event.bubbleId} relocated`);
            animateMove(event.bubbleId, event.oldPosition, event.newPosition);
            break;
        case 'density_state_change':
            console.log(`Density state change: Bubble ${event.bubbleId} ${event.oldState} → ${event.newState}`);
            // Update density metrics immediately
            fetchDensityMetrics();
            break;
        case 'consensus_vote':
            console.log(`Consensus vote: ${event.vote} on proposal ${event.proposalId} (${event.quorum}/${event.needed})`);
            break;
    }
}

/**
 * Animate bubble split: source bubble splits into two with entity redistribution.
 * Shows flash effect on source bubble, then fetches updated topology.
 */
function animateSplit(sourceBubbleId, newBubbleId, entitiesMoved) {
    const sourceBoundary = bubbleBoundaryMap.get(sourceBubbleId);
    if (!sourceBoundary) {
        console.warn(`Source bubble ${sourceBubbleId} not found for split animation`);
        fetchUpdatedBubbleGeometry();
        return;
    }

    // Flash animation: bright white pulse indicating split
    const originalMaterial = sourceBoundary.material.clone();
    const flashMaterial = new THREE.LineBasicMaterial({
        color: 0xffffff,
        opacity: 1.0,
        transparent: true,
        linewidth: 6
    });

    // Apply flash
    sourceBoundary.material = flashMaterial;

    // Animate fade back to normal over 500ms, then fetch new topology
    const startTime = performance.now();
    const duration = 500;

    function animateFlash() {
        const elapsed = performance.now() - startTime;
        const progress = Math.min(elapsed / duration, 1.0);

        if (progress < 1.0) {
            // Fade from white back to original color
            flashMaterial.opacity = 1.0 - (progress * 0.5);  // 1.0 → 0.5
            requestAnimationFrame(animateFlash);
        } else {
            // Animation complete - fetch updated topology
            console.log(`Split animation complete: ${sourceBubbleId} → [${sourceBubbleId}, ${newBubbleId}]`);
            fetchUpdatedBubbleGeometry();
        }
    }

    animateFlash();
}

/**
 * Animate bubble merge: two bubbles combine into one.
 * Shows fade effect on both bubbles, then fetches updated topology.
 */
function animateMerge(sourceBubbleId, targetBubbleId, entitiesMoved) {
    const sourceBoundary = bubbleBoundaryMap.get(sourceBubbleId);
    const targetBoundary = bubbleBoundaryMap.get(targetBubbleId);

    if (!sourceBoundary || !targetBoundary) {
        console.warn(`Bubbles ${sourceBubbleId} or ${targetBubbleId} not found for merge animation`);
        fetchUpdatedBubbleGeometry();
        return;
    }

    // Fade both bubbles to indicate merge
    const startTime = performance.now();
    const duration = 500;

    function animateFade() {
        const elapsed = performance.now() - startTime;
        const progress = Math.min(elapsed / duration, 1.0);

        if (progress < 1.0) {
            // Pulse both boundaries (opacity oscillation)
            const opacity = 0.5 + Math.sin(progress * Math.PI * 4) * 0.3;
            sourceBoundary.material.opacity = opacity;
            targetBoundary.material.opacity = opacity;
            requestAnimationFrame(animateFade);
        } else {
            // Animation complete - fetch updated topology
            console.log(`Merge animation complete: [${sourceBubbleId}, ${targetBubbleId}] → ${targetBubbleId}`);
            fetchUpdatedBubbleGeometry();
        }
    }

    animateFade();
}

/**
 * Animate bubble move: boundary relocates to new position.
 * Shows movement trail effect, then fetches updated topology.
 */
function animateMove(bubbleId, oldPosition, newPosition) {
    const boundary = bubbleBoundaryMap.get(bubbleId);
    if (!boundary) {
        console.warn(`Bubble ${bubbleId} not found for move animation`);
        fetchUpdatedBubbleGeometry();
        return;
    }

    // Simple pulse to indicate boundary changed (actual geometry update from server)
    const startTime = performance.now();
    const duration = 300;

    function animatePulse() {
        const elapsed = performance.now() - startTime;
        const progress = Math.min(elapsed / duration, 1.0);

        if (progress < 1.0) {
            // Pulse boundary (opacity oscillation)
            const opacity = 0.5 + Math.sin(progress * Math.PI * 2) * 0.3;
            boundary.material.opacity = opacity;
            requestAnimationFrame(animatePulse);
        } else {
            // Animation complete - fetch updated topology
            console.log(`Move animation complete: ${bubbleId} relocated`);
            fetchUpdatedBubbleGeometry();
        }
    }

    animatePulse();
}

/**
 * Fetch updated bubble geometry from server after topology change.
 * The server sends complete bubble vertex data via WebSocket, which triggers
 * updateBubbleBoundaries() to rebuild all bubble geometries.
 */
function fetchUpdatedBubbleGeometry() {
    // The bubble WebSocket automatically receives updates from the server
    // The updateBubbleBoundaries() function will be called when data arrives
    // Just trigger an immediate density metrics refresh to show new state
    fetchDensityMetrics();
}

function updateDensityPanel(metrics) {
    const container = document.getElementById('density-bubbles');
    container.innerHTML = '';

    metrics.forEach(bubble => {
        const stateClass = bubble.state.toLowerCase().replace('_', '-');
        const densityPercent = Math.min(bubble.densityRatio * 100, 100);

        const bubbleDiv = document.createElement('div');
        bubbleDiv.className = `bubble-density ${stateClass}`;
        bubbleDiv.innerHTML = `
            <div class="bubble-id">Bubble ${bubble.bubbleId.substring(0, 8)}</div>
            <div class="density-stats">
                <span class="density-state ${stateClass}">${bubble.state.replace(/_/g, ' ')}</span>
                <span class="density-count">${bubble.entityCount} entities</span>
            </div>
            <div class="density-bar-container">
                <div class="density-bar" style="width: ${densityPercent}%"></div>
            </div>
        `;

        container.appendChild(bubbleDiv);

        // Store metrics for bubble visual updates
        densityMetrics.set(bubble.bubbleId, bubble);

        // Update bubble boundary color based on density state
        updateBubbleBoundaryColor(bubble.bubbleId, bubble.state);
    });
}

function updateBubbleBoundaryColor(bubbleId, densityState) {
    const boundary = bubbleBoundaryMap.get(bubbleId);
    if (!boundary) return;

    // Color mapping for density states
    const stateColors = {
        'NORMAL': 0x34d399,           // Green
        'APPROACHING_SPLIT': 0xfbbf24, // Yellow
        'NEEDS_SPLIT': 0xef4444,       // Red (pulsing)
        'APPROACHING_MERGE': 0x60a5fa, // Blue
        'NEEDS_MERGE': 0x8b5cf6        // Purple (pulsing)
    };

    const color = stateColors[densityState] || 0x34d399;

    // Update all line materials in the boundary group
    boundary.traverse((child) => {
        if (child.material && child.material.color) {
            child.material.color.setHex(color);

            // Add pulsing animation for critical states
            if (densityState === 'NEEDS_SPLIT' || densityState === 'NEEDS_MERGE') {
                child.userData.pulsePhase = child.userData.pulsePhase || 0;
                child.userData.isPulsing = true;
            } else {
                child.userData.isPulsing = false;
                child.material.opacity = child.userData.baseOpacity || 0.9;
            }
        }
    });
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
// Video Recording
// ============================================================================

let mediaRecorder = null;
let recordedChunks = [];
let recordingStartTime = 0;
let recordingTimerInterval = null;

let windowMediaRecorder = null;
let windowRecordedChunks = [];
let windowRecordingStartTime = 0;
let windowRecordingTimerInterval = null;

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
            a.download = `luciferase-s0s5-forest-${Date.now()}.webm`;
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

// Full Window Recording (captures entire browser window with UI)
document.getElementById('btn-record-window').addEventListener('click', async () => {
    try {
        // Request screen/window capture
        const displayStream = await navigator.mediaDevices.getDisplayMedia({
            video: {
                cursor: 'always',
                displaySurface: 'window'
            },
            audio: false
        });

        // Create MediaRecorder with high quality settings
        const options = {
            mimeType: 'video/webm;codecs=vp9',
            videoBitsPerSecond: 10000000 // 10 Mbps for full window
        };

        // Fallback to vp8 if vp9 not supported
        if (!MediaRecorder.isTypeSupported(options.mimeType)) {
            options.mimeType = 'video/webm;codecs=vp8';
        }

        windowMediaRecorder = new MediaRecorder(displayStream, options);
        windowRecordedChunks = [];

        windowMediaRecorder.ondataavailable = (event) => {
            if (event.data.size > 0) {
                windowRecordedChunks.push(event.data);
            }
        };

        windowMediaRecorder.onstop = () => {
            // Create blob and download
            const blob = new Blob(windowRecordedChunks, { type: 'video/webm' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `luciferase-s0s5-fullwindow-${Date.now()}.webm`;
            a.click();
            URL.revokeObjectURL(url);

            console.log('Window recording saved:', a.download);
        };

        // Handle user stopping the share (via browser UI)
        displayStream.getVideoTracks()[0].addEventListener('ended', () => {
            document.getElementById('btn-stop-record-window').click();
        });

        // Start recording
        windowMediaRecorder.start(100);
        windowRecordingStartTime = Date.now();

        // Update UI
        document.getElementById('btn-record-window').style.display = 'none';
        document.getElementById('btn-stop-record-window').style.display = 'inline-block';
        document.getElementById('recording-status').style.display = 'block';

        // Start timer
        windowRecordingTimerInterval = setInterval(() => {
            const elapsed = Math.floor((Date.now() - windowRecordingStartTime) / 1000);
            const minutes = Math.floor(elapsed / 60).toString().padStart(2, '0');
            const seconds = (elapsed % 60).toString().padStart(2, '0');
            document.getElementById('recording-time').textContent = `${minutes}:${seconds}`;
        }, 1000);

        console.log('Window recording started');

    } catch (err) {
        console.error('Failed to start window recording:', err);
        if (err.name === 'NotAllowedError') {
            alert('Screen capture permission denied. Please allow screen sharing to record the window.');
        } else {
            alert('Failed to start window recording. Check console for details.');
        }
    }
});

document.getElementById('btn-stop-record-window').addEventListener('click', () => {
    if (windowMediaRecorder && windowMediaRecorder.state !== 'inactive') {
        windowMediaRecorder.stop();

        // Stop all tracks
        windowMediaRecorder.stream.getTracks().forEach(track => track.stop());

        // Clear timer
        if (windowRecordingTimerInterval) {
            clearInterval(windowRecordingTimerInterval);
            windowRecordingTimerInterval = null;
        }

        // Update UI
        document.getElementById('btn-record-window').style.display = 'inline-block';
        document.getElementById('btn-stop-record-window').style.display = 'none';
        document.getElementById('recording-status').style.display = 'none';
        document.getElementById('recording-time').textContent = '00:00';

        console.log('Window recording stopped');
    }
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

            // Apply density state pulsing animation
            boundary.traverse((child) => {
                if (child.userData && child.userData.isPulsing && child.material) {
                    // Faster, more prominent pulsing for critical states
                    child.userData.pulsePhase = (child.userData.pulsePhase || 0) + deltaTime * 0.003;
                    const pulse = 0.5 + Math.sin(child.userData.pulsePhase * Math.PI * 2) * 0.4;
                    child.material.opacity = (child.userData.baseOpacity || 0.9) * (0.6 + pulse * 0.4);
                }
            });
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
// Keyboard Controls
// ============================================================================

document.addEventListener('keydown', (event) => {
    if (event.key === 's' || event.key === 'S') {
        // Toggle sphere visibility
        bubbleSpheresVisible = !bubbleSpheresVisible;
        bubbleSpheres.forEach(sphere => {
            sphere.visible = bubbleSpheresVisible;
        });
        bubbleSphereLabels.forEach(label => {
            label.visible = bubbleSpheresVisible;
        });
        console.log(`Bubble spheres ${bubbleSpheresVisible ? 'shown' : 'hidden'} (press 'S' to toggle, showing ${bubbleSpheres.length} spheres with labels)`);
    } else if (event.key === 'b' || event.key === 'B') {
        // Toggle boundary visibility
        bubbleBoundariesVisible = !bubbleBoundariesVisible;
        bubbleBoundaries.forEach(boundary => {
            boundary.visible = bubbleBoundariesVisible;
        });
        console.log(`Bubble boundaries ${bubbleBoundariesVisible ? 'shown' : 'hidden'} (press 'B' to toggle)`);
    }
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
console.log('Keyboard: Press \'S\' to toggle inscribed spheres, \'B\' to toggle boundaries');
