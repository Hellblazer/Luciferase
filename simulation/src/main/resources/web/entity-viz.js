/**
 * Entity Visualization - Three.js WebSocket Client
 *
 * Connects to EntityVisualizationServer via WebSocket and renders
 * entities in real-time using Three.js InstancedMesh.
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_ENTITIES = 10000;
const ENTITY_SIZE = 2.0;
const WORLD_SIZE = 200;

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
// InstancedMesh Entity Rendering
// ============================================================================

const entityGeometry = new THREE.IcosahedronGeometry(ENTITY_SIZE, 1);
const entityMaterial = new THREE.MeshStandardMaterial({
    color: 0x34d399,
    metalness: 0.3,
    roughness: 0.6
});

let instancedMesh = new THREE.InstancedMesh(entityGeometry, entityMaterial, MAX_ENTITIES);
instancedMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
instancedMesh.count = 0;
scene.add(instancedMesh);

const dummy = new THREE.Object3D();

function updateEntities(entities) {
    const count = Math.min(entities.length, MAX_ENTITIES);

    for (let i = 0; i < count; i++) {
        const entity = entities[i];
        dummy.position.set(entity.x, entity.y, entity.z);
        dummy.updateMatrix();
        instancedMesh.setMatrixAt(i, dummy.matrix);
    }

    instancedMesh.count = count;
    instancedMesh.instanceMatrix.needsUpdate = true;

    // Update stats
    document.getElementById('stat-entities').textContent = count;
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
