/**
 * Spatial Index Explorer - Three.js Visualization
 *
 * Phase 5a: Basic scene setup with camera controls, lighting, and API integration
 * Phase 5b: InstancedMesh entity rendering with interaction and query visualization
 * Phase 6: Shared modules integration with shape generation and color schemes
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { generateShapeEntities, SHAPE_TYPES } from './shared/shape-generators.js';
import { createColorSchemes, COLOR_SCHEME_NAMES } from './shared/color-schemes.js';

// ============================================================================
// Constants
// ============================================================================

const MAX_ENTITIES = 100000;  // Maximum entities for InstancedMesh
const ENTITY_SIZE = 0.015;    // Base entity size

// Color schemes by index type (legacy)
const INDEX_COLORS = {
    TETREE: new THREE.Color(0x34d399),  // Green
    OCTREE: new THREE.Color(0x60a5fa),  // Blue
    SFC: new THREE.Color(0xfbbf24)       // Orange/Yellow
};

// Initialize color schemes from shared module
const COLOR_SCHEMES = createColorSchemes(THREE);
let currentColorScheme = 'DEPTH';

const HIGHLIGHT_COLOR = new THREE.Color(0xf472b6);  // Pink for selected
const QUERY_RESULT_COLOR = new THREE.Color(0xa78bfa); // Purple for query results

// Clipping state
let clipMinX = 0, clipMinY = 0, clipMinZ = 0;
let allEntities = []; // Store all entities before clipping

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
controls.minDistance = 0.5;
controls.maxDistance = 20;
controls.update();

// ============================================================================
// Lighting
// ============================================================================

const ambientLight = new THREE.AmbientLight(0xffffff, 0.4);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
directionalLight.position.set(5, 10, 7);
scene.add(directionalLight);

const hemisphereLight = new THREE.HemisphereLight(0x87ceeb, 0x362312, 0.3);
scene.add(hemisphereLight);

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
    opacity: 0.5
});
const unitCubeWireframe = new THREE.LineSegments(unitCubeEdges, unitCubeMaterial);
unitCubeWireframe.position.set(0.5, 0.5, 0.5);
scene.add(unitCubeWireframe);

// ============================================================================
// InstancedMesh Entity Rendering (Phase 5b)
// ============================================================================

// Geometry for entities (small icosahedrons for visual appeal)
const entityGeometry = new THREE.IcosahedronGeometry(ENTITY_SIZE, 1);

// Material with vertex colors for per-instance coloring
const entityMaterial = new THREE.MeshStandardMaterial({
    vertexColors: false,
    metalness: 0.2,
    roughness: 0.6
});

// InstancedMesh for efficient rendering
let instancedMesh = null;
let entityData = [];  // Store entity metadata for raycasting

// Dummy object for matrix calculations
const dummy = new THREE.Object3D();
const instanceColor = new THREE.Color();

function createInstancedMesh(count) {
    // Remove existing mesh
    if (instancedMesh) {
        scene.remove(instancedMesh);
        instancedMesh.geometry.dispose();
        instancedMesh.material.dispose();
    }

    // Create new InstancedMesh with instance colors
    const material = new THREE.MeshStandardMaterial({
        metalness: 0.2,
        roughness: 0.6
    });

    instancedMesh = new THREE.InstancedMesh(entityGeometry, material, Math.max(count, 1));
    instancedMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);

    // Enable instance colors
    instancedMesh.instanceColor = new THREE.InstancedBufferAttribute(
        new Float32Array(Math.max(count, 1) * 3), 3
    );

    instancedMesh.count = 0;  // Start with 0 visible instances
    scene.add(instancedMesh);
}

function updateInstancedMesh(entities) {
    entityData = entities;
    const count = entities.length;

    // Recreate if needed (size changed significantly)
    if (!instancedMesh || instancedMesh.instanceMatrix.array.length / 16 < count) {
        createInstancedMesh(Math.max(count * 2, 1000));
    }

    // Compute max depth for color schemes
    const maxDepth = 10; // Default max depth

    // Update each instance
    for (let i = 0; i < count; i++) {
        const entity = entities[i];

        // Position
        dummy.position.set(entity.x, entity.y, entity.z);
        dummy.scale.setScalar(1);
        dummy.updateMatrix();
        instancedMesh.setMatrixAt(i, dummy.matrix);

        // Use shared color scheme or fall back to index-based coloring
        if (currentColorScheme === 'INDEX_TYPE') {
            const color = COLOR_SCHEMES.INDEX_TYPE(0, maxDepth, indexType);
            instancedMesh.setColorAt(i, color);
        } else if (COLOR_SCHEMES[currentColorScheme]) {
            // Use shared color scheme - simulate depth based on Y position
            const depth = Math.floor(entity.y * maxDepth);
            const normal = { x: entity.x - 0.5, y: entity.y - 0.5, z: entity.z - 0.5 };
            const color = COLOR_SCHEMES[currentColorScheme](depth, maxDepth, normal);
            instancedMesh.setColorAt(i, color);
        } else {
            // Fallback to index-based coloring
            const baseColor = INDEX_COLORS[indexType] || INDEX_COLORS.TETREE;
            const depthFactor = 0.7 + (entity.y * 0.3);
            instanceColor.copy(baseColor).multiplyScalar(depthFactor);
            instancedMesh.setColorAt(i, instanceColor);
        }
    }

    instancedMesh.count = count;
    instancedMesh.instanceMatrix.needsUpdate = true;
    if (instancedMesh.instanceColor) {
        instancedMesh.instanceColor.needsUpdate = true;
    }

    console.log(`Updated InstancedMesh with ${count} entities`);
}

function recolorEntities() {
    if (!instancedMesh || entityData.length === 0) return;
    updateInstancedMesh(entityData);
}

function applyClipping() {
    if (allEntities.length === 0) return;

    // Filter entities based on clipping planes
    const clipped = allEntities.filter(e =>
        e.x >= clipMinX &&
        e.y >= clipMinY &&
        e.z >= clipMinZ
    );

    updateInstancedMesh(clipped);
    document.getElementById('stat-entities').textContent = `${clipped.length}/${allEntities.length}`;
}

function resetClipping() {
    clipMinX = 0;
    clipMinY = 0;
    clipMinZ = 0;

    // Update UI sliders
    document.getElementById('clip-x-min').value = 0;
    document.getElementById('clip-y-min').value = 0;
    document.getElementById('clip-z-min').value = 0;
    document.getElementById('clip-x-min-value').textContent = '0.0';
    document.getElementById('clip-y-min-value').textContent = '0.0';
    document.getElementById('clip-z-min-value').textContent = '0.0';

    applyClipping();
}

// ============================================================================
// Raycaster Interaction (Phase 5b)
// ============================================================================

const raycaster = new THREE.Raycaster();
const mouse = new THREE.Vector2();
let hoveredIndex = -1;
let selectedIndex = -1;

// Store original colors for unhighlighting
const originalColors = new Map();

function onMouseMove(event) {
    mouse.x = (event.clientX / window.innerWidth) * 2 - 1;
    mouse.y = -(event.clientY / window.innerHeight) * 2 + 1;
}

function onMouseClick(event) {
    // Only handle left clicks
    if (event.button !== 0) return;

    // Don't select if clicking on UI panels
    if (event.target.closest('#info-panel') || event.target.closest('#controls-panel')) {
        return;
    }

    raycaster.setFromCamera(mouse, camera);

    if (instancedMesh && instancedMesh.count > 0) {
        const intersects = raycaster.intersectObject(instancedMesh);

        if (intersects.length > 0) {
            const instanceId = intersects[0].instanceId;

            // Unhighlight previous selection
            if (selectedIndex >= 0 && selectedIndex !== instanceId) {
                unhighlightEntity(selectedIndex);
            }

            // Select new entity
            selectedIndex = instanceId;
            highlightEntity(instanceId, HIGHLIGHT_COLOR);
            showEntityInfo(instanceId);
        } else {
            // Click on empty space - deselect
            if (selectedIndex >= 0) {
                unhighlightEntity(selectedIndex);
                selectedIndex = -1;
                hideEntityInfo();
            }
        }
    }
}

function highlightEntity(index, color) {
    if (!instancedMesh || index < 0 || index >= instancedMesh.count) return;

    // Store original color if not already stored
    if (!originalColors.has(index)) {
        const origColor = new THREE.Color();
        instancedMesh.getColorAt(index, origColor);
        originalColors.set(index, origColor.clone());
    }

    // Set highlight color
    instancedMesh.setColorAt(index, color);
    instancedMesh.instanceColor.needsUpdate = true;

    // Scale up slightly
    instancedMesh.getMatrixAt(index, dummy.matrix);
    dummy.matrix.decompose(dummy.position, dummy.quaternion, dummy.scale);
    dummy.scale.setScalar(1.5);
    dummy.updateMatrix();
    instancedMesh.setMatrixAt(index, dummy.matrix);
    instancedMesh.instanceMatrix.needsUpdate = true;
}

function unhighlightEntity(index) {
    if (!instancedMesh || index < 0 || index >= instancedMesh.count) return;

    // Restore original color
    if (originalColors.has(index)) {
        instancedMesh.setColorAt(index, originalColors.get(index));
        originalColors.delete(index);
        instancedMesh.instanceColor.needsUpdate = true;
    }

    // Restore scale
    instancedMesh.getMatrixAt(index, dummy.matrix);
    dummy.matrix.decompose(dummy.position, dummy.quaternion, dummy.scale);
    dummy.scale.setScalar(1);
    dummy.updateMatrix();
    instancedMesh.setMatrixAt(index, dummy.matrix);
    instancedMesh.instanceMatrix.needsUpdate = true;
}

function updateHover() {
    if (!instancedMesh || instancedMesh.count === 0) return;

    raycaster.setFromCamera(mouse, camera);
    const intersects = raycaster.intersectObject(instancedMesh);

    const newHoveredIndex = intersects.length > 0 ? intersects[0].instanceId : -1;

    if (newHoveredIndex !== hoveredIndex) {
        // Unhover previous (unless it's selected)
        if (hoveredIndex >= 0 && hoveredIndex !== selectedIndex) {
            unhighlightEntity(hoveredIndex);
        }

        // Hover new (unless it's selected)
        if (newHoveredIndex >= 0 && newHoveredIndex !== selectedIndex) {
            highlightEntity(newHoveredIndex, new THREE.Color(0xfef08a)); // Light yellow
        }

        hoveredIndex = newHoveredIndex;

        // Update cursor
        renderer.domElement.style.cursor = newHoveredIndex >= 0 ? 'pointer' : 'grab';
    }
}

// ============================================================================
// Entity Info Panel (Phase 5b)
// ============================================================================

function showEntityInfo(index) {
    if (index < 0 || index >= entityData.length) return;

    const entity = entityData[index];
    const infoPanel = document.getElementById('entity-info');

    if (!infoPanel) {
        // Create panel if it doesn't exist
        const panel = document.createElement('div');
        panel.id = 'entity-info';
        panel.innerHTML = '';
        document.body.appendChild(panel);
    }

    const panel = document.getElementById('entity-info');
    panel.innerHTML = `
        <h3>Entity Details</h3>
        <div class="info-row"><span>ID:</span><span>${entity.entityId ? entity.entityId.substring(0, 8) + '...' : 'N/A'}</span></div>
        <div class="info-row"><span>Position:</span><span>(${entity.x.toFixed(3)}, ${entity.y.toFixed(3)}, ${entity.z.toFixed(3)})</span></div>
        <div class="info-row"><span>Index:</span><span>${index}</span></div>
        ${entity.content ? `<div class="info-row"><span>Content:</span><span>${JSON.stringify(entity.content)}</span></div>` : ''}
        <button id="btn-close-info">Close</button>
    `;
    panel.style.display = 'block';

    document.getElementById('btn-close-info').addEventListener('click', () => {
        hideEntityInfo();
        if (selectedIndex >= 0) {
            unhighlightEntity(selectedIndex);
            selectedIndex = -1;
        }
    });
}

function hideEntityInfo() {
    const panel = document.getElementById('entity-info');
    if (panel) {
        panel.style.display = 'none';
    }
}

// ============================================================================
// Query Visualization (Phase 5b)
// ============================================================================

let rangeQueryBox = null;
let knnHighlights = [];

function showRangeQueryBox(minX, minY, minZ, maxX, maxY, maxZ) {
    // Remove existing box
    if (rangeQueryBox) {
        scene.remove(rangeQueryBox);
    }

    const width = maxX - minX;
    const height = maxY - minY;
    const depth = maxZ - minZ;

    const geometry = new THREE.BoxGeometry(width, height, depth);
    const edges = new THREE.EdgesGeometry(geometry);
    const material = new THREE.LineBasicMaterial({
        color: 0xfbbf24,
        linewidth: 2
    });

    rangeQueryBox = new THREE.LineSegments(edges, material);
    rangeQueryBox.position.set(
        minX + width / 2,
        minY + height / 2,
        minZ + depth / 2
    );
    scene.add(rangeQueryBox);
}

function hideRangeQueryBox() {
    if (rangeQueryBox) {
        scene.remove(rangeQueryBox);
        rangeQueryBox = null;
    }
}

function highlightQueryResults(entityIds) {
    // Clear previous highlights
    clearQueryHighlights();

    // Find indices of matching entities
    for (let i = 0; i < entityData.length; i++) {
        if (entityIds.includes(entityData[i].entityId)) {
            highlightEntity(i, QUERY_RESULT_COLOR);
            knnHighlights.push(i);
        }
    }
}

function clearQueryHighlights() {
    for (const index of knnHighlights) {
        if (index !== selectedIndex) {
            unhighlightEntity(index);
        }
    }
    knnHighlights = [];
    hideRangeQueryBox();
}

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

    // Clear any existing visualization
    clearQueryHighlights();
    if (selectedIndex >= 0) {
        unhighlightEntity(selectedIndex);
        selectedIndex = -1;
    }
    hideEntityInfo();

    const request = {
        indexType: type,
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
            document.getElementById('btn-range-query').disabled = false;
            document.getElementById('btn-knn-query').disabled = false;
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

async function addShapeEntities(shape = 'random', count = 500) {
    if (!sessionId) return;

    let entities;

    if (shape === 'bunny') {
        // Fetch Stanford Bunny from API
        try {
            const bunnyResponse = await fetch('/api/mesh/bunny');
            if (!bunnyResponse.ok) {
                const error = await bunnyResponse.json();
                console.error('Failed to load bunny mesh:', error);
                alert(`Failed to load bunny mesh: ${error.error || 'Unknown error'}`);
                return;
            }
            const bunnyData = await bunnyResponse.json();
            entities = bunnyData.entities;
            console.log(`Loaded Stanford Bunny: ${entities.length} voxels`);
        } catch (e) {
            console.error('Failed to load bunny:', e);
            return;
        }
    } else {
        // Use shared shape generator
        entities = generateShapeEntities(shape, count);
    }

    try {
        const response = await fetch(`/api/spatial/entities/bulk-insert?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(entities)
        });

        if (response.ok) {
            await refreshEntities();
        }
    } catch (e) {
        console.error('Failed to add entities:', e);
    }
}

// Legacy function for backwards compatibility
async function addRandomEntities(count = 100) {
    return addShapeEntities('random', count);
}

async function refreshEntities() {
    if (!sessionId) return;

    try {
        const response = await fetch(`/api/spatial/entities?sessionId=${sessionId}&size=50000`);
        if (response.ok) {
            const data = await response.json();
            allEntities = data.entities || [];

            // Apply clipping before display
            applyClipping();
        }
    } catch (e) {
        console.error('Failed to refresh entities:', e);
    }
}

async function clearAll() {
    if (!sessionId) return;

    try {
        await fetch(`/api/spatial?sessionId=${sessionId}`, { method: 'DELETE' });

        // Clear visualization
        if (instancedMesh) {
            instancedMesh.count = 0;
        }
        entityData = [];
        allEntities = [];
        clearQueryHighlights();
        selectedIndex = -1;
        hoveredIndex = -1;
        hideEntityInfo();
        resetClipping();

        document.getElementById('stat-entities').textContent = '0';
        document.getElementById('stat-index-type').textContent = '-';
        document.getElementById('btn-add-random').disabled = true;
        document.getElementById('btn-clear').disabled = true;
        document.getElementById('btn-range-query').disabled = true;
        document.getElementById('btn-knn-query').disabled = true;
        indexType = null;
    } catch (e) {
        console.error('Failed to clear:', e);
    }
}

async function performRangeQuery() {
    if (!sessionId) return;

    // Query center region [0.25, 0.75]^3
    const request = {
        minX: 0.25, minY: 0.25, minZ: 0.25,
        maxX: 0.75, maxY: 0.75, maxZ: 0.75
    };

    try {
        const response = await fetch(`/api/spatial/query/range?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (response.ok) {
            const data = await response.json();
            const entityIds = (data.entities || []).map(e => e.entityId);

            // Show query box
            showRangeQueryBox(
                request.minX, request.minY, request.minZ,
                request.maxX, request.maxY, request.maxZ
            );

            // Highlight results
            highlightQueryResults(entityIds);

            console.log(`Range query found ${entityIds.length} entities`);
        }
    } catch (e) {
        console.error('Failed to perform range query:', e);
    }
}

async function performKnnQuery() {
    if (!sessionId) return;

    // Query nearest to center
    const request = {
        x: 0.5, y: 0.5, z: 0.5,
        k: 10
    };

    try {
        const response = await fetch(`/api/spatial/query/knn?sessionId=${sessionId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (response.ok) {
            const data = await response.json();
            const entityIds = (data.entities || []).map(e => e.entityId);

            // Clear range box if any
            hideRangeQueryBox();

            // Highlight results
            highlightQueryResults(entityIds);

            console.log(`KNN query found ${entityIds.length} nearest entities`);
        }
    } catch (e) {
        console.error('Failed to perform KNN query:', e);
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
    const shapeSelect = document.getElementById('shape-select');
    const shape = shapeSelect ? shapeSelect.value : 'random';
    const entityCountSlider = document.getElementById('entity-count');
    const count = shape === 'bunny' ? 0 : (entityCountSlider ? parseInt(entityCountSlider.value) : 500);
    await addShapeEntities(shape, count);
});

// Color scheme buttons (if they exist)
document.querySelectorAll('.color-scheme-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.color-scheme-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        currentColorScheme = btn.dataset.scheme;
        recolorEntities();
    });
});

// Entity count slider
const entityCountSlider = document.getElementById('entity-count');
const entityCountValue = document.getElementById('entity-count-value');
if (entityCountSlider && entityCountValue) {
    entityCountSlider.addEventListener('input', () => {
        entityCountValue.textContent = entityCountSlider.value;
    });
}

// Clipping sliders
['x', 'y', 'z'].forEach(axis => {
    const slider = document.getElementById(`clip-${axis}-min`);
    const valueSpan = document.getElementById(`clip-${axis}-min-value`);
    if (slider && valueSpan) {
        slider.addEventListener('input', () => {
            const value = slider.value / 100;
            valueSpan.textContent = value.toFixed(2);
            if (axis === 'x') clipMinX = value;
            else if (axis === 'y') clipMinY = value;
            else clipMinZ = value;
            applyClipping();
        });
    }
});

// Reset clipping button
const resetClipBtn = document.getElementById('btn-reset-clipping');
if (resetClipBtn) {
    resetClipBtn.addEventListener('click', resetClipping);
}

document.getElementById('btn-clear').addEventListener('click', async () => {
    await clearAll();
});

document.getElementById('btn-reset-camera').addEventListener('click', () => {
    camera.position.set(2, 1.5, 2);
    controls.target.set(0.5, 0.5, 0.5);
    controls.update();
});

// Query buttons
const rangeBtn = document.getElementById('btn-range-query');
if (rangeBtn) {
    rangeBtn.addEventListener('click', performRangeQuery);
}

const knnBtn = document.getElementById('btn-knn-query');
if (knnBtn) {
    knnBtn.addEventListener('click', performKnnQuery);
}

const clearQueryBtn = document.getElementById('btn-clear-query');
if (clearQueryBtn) {
    clearQueryBtn.addEventListener('click', clearQueryHighlights);
}

// Mouse events for raycasting
window.addEventListener('mousemove', onMouseMove);
window.addEventListener('click', onMouseClick);

// ============================================================================
// Animation Loop
// ============================================================================

let frameCount = 0;
let lastFpsUpdate = performance.now();

function animate() {
    requestAnimationFrame(animate);

    // Update controls
    controls.update();

    // Update hover state
    updateHover();

    // Render
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

async function init() {
    // Create initial empty InstancedMesh
    createInstancedMesh(1000);

    // Check connection
    const connected = await checkConnection();

    if (connected) {
        await createSession();
    }

    // Start animation loop
    animate();

    console.log('Spatial Inspector initialized (Phase 5b)');
    console.log('Three.js r' + THREE.REVISION);
}

init();
