/**
 * VoxelRenderer - Three.js WebGL renderer for ESVO/ESVT voxel streaming.
 *
 * Traverses parsed ESVO node trees to extract leaf voxels and renders them
 * as instanced cubes using Three.js InstancedMesh.  Integrates with
 * SceneManager for region lifecycle events and camera synchronization.
 *
 * Usage:
 *   const vr = new VoxelRenderer(document.getElementById('container'));
 *   const scene = new SceneManager({ worldMin:[0,0,0], worldMax:[1024,1024,1024], regionLevel:4 });
 *   vr.attach(scene);
 *   vr.start();
 *   vr.syncCamera({ x:512, y:512, z:1800 }, { x:512, y:512, z:512 }, { x:0, y:1, z:0 });
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { FORMAT_ESVO, FORMAT_ESVT } from './frame-parser.js';

// ============================================================================
// Constants
// ============================================================================

/**
 * LOD level → voxel color (LOD 0 = most detail = brightest green,
 * higher LODs fade through cyan/amber/orange to red).
 */
const LOD_COLORS = [
    0x00ff88,  // LOD 0: bright green  (closest, most detail)
    0x00ccff,  // LOD 1: cyan
    0xffcc00,  // LOD 2: amber
    0xff6600,  // LOD 3: orange
    0xff2200,  // LOD 4+: red          (furthest, least detail)
];

/** Hard cap on leaf voxels per region to prevent GPU overload. */
const MAX_VOXELS_PER_REGION = 65_536;

/** Default maximum ESVO traversal depth during rendering. */
const DEFAULT_MAX_RENDER_DEPTH = 10;

/** Default Three.js scene background color. */
const DEFAULT_BACKGROUND_COLOR = 0x1a1a2e;

// ============================================================================
// VoxelRenderer
// ============================================================================

/**
 * Three.js-based WebGL renderer for ESVO/ESVT voxel data.
 *
 * Attaches to a SceneManager, listens for regionAdded/regionRemoved events,
 * traverses ESVO trees to extract leaf voxels, and renders each region as a
 * Three.js InstancedMesh.  Unknown or empty regions fall back to a wireframe
 * bounding box.
 */
export class VoxelRenderer {

    /**
     * Create a VoxelRenderer and initialise the Three.js scene.
     *
     * @param {HTMLElement} container - Container element; the WebGL canvas is
     *   appended here automatically.
     * @param {object} [options]
     * @param {number} [options.maxRenderDepth=10] - Maximum ESVO traversal depth
     * @param {number} [options.backgroundColor=0x1a1a2e] - Scene background color
     * @param {number} [options.voxelOpacity=0.9]  - Voxel material opacity (0–1)
     */
    constructor(container, options = {}) {
        this._container = container;
        this._maxRenderDepth  = options.maxRenderDepth  ?? DEFAULT_MAX_RENDER_DEPTH;
        this._backgroundColor = options.backgroundColor ?? DEFAULT_BACKGROUND_COLOR;
        this._voxelOpacity    = options.voxelOpacity    ?? 0.9;

        /** @type {THREE.WebGLRenderer|null} */
        this._renderer = null;
        /** @type {THREE.Scene|null} */
        this._scene = null;
        /** @type {THREE.PerspectiveCamera|null} */
        this._camera = null;
        /** @type {OrbitControls|null} */
        this._controls = null;
        /** @type {THREE.GridHelper|null} */
        this._grid = null;

        /** Region key → Three.js mesh
         * @type {Map<string, THREE.InstancedMesh|THREE.Mesh>} */
        this._regionMeshes = new Map();

        /** @type {number|null} */
        this._animFrameId = null;
        this._running = false;

        /** @type {object|null} bound SceneManager */
        this._sceneManager = null;

        // Pre-bind event handlers so on/off calls are symmetric
        this._onRegionAdded   = this._onRegionAdded.bind(this);
        this._onRegionRemoved = this._onRegionRemoved.bind(this);
        this._onWindowResize  = this._onWindowResize.bind(this);

        this._initThree();
    }

    // ============================================================================
    // Three.js Initialisation
    // ============================================================================

    _initThree() {
        const w = this._container.clientWidth  || window.innerWidth;
        const h = this._container.clientHeight || window.innerHeight;

        // Renderer
        this._renderer = new THREE.WebGLRenderer({ antialias: true });
        this._renderer.setSize(w, h);
        this._renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this._renderer.setClearColor(this._backgroundColor);
        this._container.appendChild(this._renderer.domElement);

        // Scene
        this._scene = new THREE.Scene();

        // Camera — positioned to view a 1024-unit world from a distance
        this._camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 100_000);
        this._camera.position.set(512, 512, 1800);
        this._camera.lookAt(512, 512, 512);

        // Lighting: ambient fill + directional key light
        this._scene.add(new THREE.AmbientLight(0xffffff, 0.4));
        const dirLight = new THREE.DirectionalLight(0xffffff, 0.8);
        dirLight.position.set(1, 2, 3).normalize();
        this._scene.add(dirLight);

        // OrbitControls for mouse orbit / pan / zoom
        this._controls = new OrbitControls(this._camera, this._renderer.domElement);
        this._controls.enableDamping = true;
        this._controls.dampingFactor = 0.05;
        this._controls.target.set(512, 512, 512);
        this._controls.update();

        // Reference grid at y=0
        this._grid = new THREE.GridHelper(1024, 16, 0x555566, 0x333344);
        this._grid.position.set(512, 0, 512);
        this._scene.add(this._grid);

        window.addEventListener('resize', this._onWindowResize);
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================

    /**
     * Attach to a SceneManager to receive region add/remove events.
     * Any regions already tracked by the manager are rendered immediately.
     *
     * @param {object} sceneManager - SceneManager instance
     */
    attach(sceneManager) {
        if (this._sceneManager) {
            this.detach();
        }
        this._sceneManager = sceneManager;
        sceneManager.on('regionAdded',   this._onRegionAdded);
        sceneManager.on('regionRemoved', this._onRegionRemoved);

        // Render any already-tracked regions
        for (const entry of sceneManager.getRegions().values()) {
            this._onRegionAdded(entry);
        }
    }

    /**
     * Detach from the current SceneManager and clear all meshes.
     */
    detach() {
        if (!this._sceneManager) return;
        this._sceneManager.off('regionAdded',   this._onRegionAdded);
        this._sceneManager.off('regionRemoved', this._onRegionRemoved);
        this._sceneManager = null;
        this.clearRegions();
    }

    /**
     * Start the animation loop.
     */
    start() {
        if (this._running) return;
        this._running = true;
        this._animate();
    }

    /**
     * Stop the animation loop.
     */
    stop() {
        this._running = false;
        if (this._animFrameId !== null) {
            cancelAnimationFrame(this._animFrameId);
            this._animFrameId = null;
        }
    }

    /**
     * Release all Three.js resources and remove the canvas from the DOM.
     */
    dispose() {
        this.stop();
        this.detach();
        this.clearRegions();
        window.removeEventListener('resize', this._onWindowResize);
        this._controls?.dispose();
        this._renderer?.dispose();
        const el = this._renderer?.domElement;
        if (el?.parentNode) {
            el.parentNode.removeChild(el);
        }
    }

    // ============================================================================
    // Camera
    // ============================================================================

    /**
     * Synchronise the camera to an explicit position (e.g. at connect time).
     * OrbitControls remain active for subsequent mouse interaction.
     *
     * @param {{ x: number, y: number, z: number }} eye
     * @param {{ x: number, y: number, z: number }} lookAt
     * @param {{ x: number, y: number, z: number }} up
     */
    syncCamera(eye, lookAt, up) {
        this._camera.position.set(eye.x, eye.y, eye.z);
        this._camera.up.set(up.x, up.y, up.z);
        this._controls.target.set(lookAt.x, lookAt.y, lookAt.z);
        this._controls.update();
    }

    // ============================================================================
    // Scene Helpers
    // ============================================================================

    /**
     * Show or hide the reference grid.
     *
     * @param {boolean} visible
     */
    setGridVisible(visible) {
        if (this._grid) this._grid.visible = visible;
    }

    /**
     * Remove all region meshes from the scene.
     */
    clearRegions() {
        for (const key of [...this._regionMeshes.keys()]) {
            this._disposeMesh(key);
        }
    }

    /**
     * Get renderer statistics.
     *
     * @returns {{ regionCount: number, voxelCount: number }}
     */
    getStats() {
        let voxelCount = 0;
        for (const mesh of this._regionMeshes.values()) {
            voxelCount += mesh.isInstancedMesh ? mesh.count : 1;
        }
        return { regionCount: this._regionMeshes.size, voxelCount };
    }

    // ============================================================================
    // Animation
    // ============================================================================

    _animate() {
        if (!this._running) return;
        this._animFrameId = requestAnimationFrame(() => this._animate());
        this._controls.update();
        this._renderer.render(this._scene, this._camera);
    }

    // ============================================================================
    // Region Events
    // ============================================================================

    _onRegionAdded(entry) {
        const key = `${entry.mortonCode.toString()}_${entry.lod}`;
        this._disposeMesh(key);   // remove stale mesh if same region key already exists

        let mesh;
        try {
            mesh = this._buildRegionMesh(entry);
        } catch (err) {
            console.error('VoxelRenderer: error building mesh for region', key, err);
            mesh = this._buildWireframeBounds(entry.bounds, 0xff0000);
        }

        if (mesh) {
            this._regionMeshes.set(key, mesh);
            this._scene.add(mesh);
        }
    }

    _onRegionRemoved(entry) {
        const key = `${entry.mortonCode.toString()}_${entry.lod}`;
        this._disposeMesh(key);
    }

    _disposeMesh(key) {
        const mesh = this._regionMeshes.get(key);
        if (!mesh) return;
        this._scene.remove(mesh);
        mesh.geometry.dispose();
        const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
        mats.forEach(m => m.dispose());
        this._regionMeshes.delete(key);
    }

    // ============================================================================
    // Mesh Construction
    // ============================================================================

    /**
     * Build a Three.js mesh for a decoded region entry.
     *
     * ESVO/ESVT regions are rendered as InstancedMesh (one cube per leaf voxel).
     * Unknown formats fall back to a white wireframe bounding box.
     * Empty regions render a dim wireframe to indicate the region boundary.
     *
     * @param {object} entry - RegionEntry from SceneManager
     * @returns {THREE.InstancedMesh|THREE.Mesh|null}
     */
    _buildRegionMesh(entry) {
        const { payload, bounds, lod, format } = entry;

        if (format !== FORMAT_ESVO && format !== FORMAT_ESVT) {
            console.warn('VoxelRenderer: unknown format', format, '— rendering bounding box');
            return this._buildWireframeBounds(bounds, 0xffffff);
        }

        const voxels = this._collectLeafVoxels(payload, bounds);

        if (voxels.length === 0) {
            // Empty region: dim wireframe to indicate region boundary
            return this._buildWireframeBounds(bounds, 0x333355);
        }

        const color = LOD_COLORS[Math.min(lod, LOD_COLORS.length - 1)];
        return this._buildInstancedMesh(voxels, color);
    }

    // ============================================================================
    // ESVO Traversal (Laine & Karras 2010 sparse indexing)
    // ============================================================================

    /**
     * Traverse an ESVO/ESVT payload and collect all leaf voxel bounds.
     *
     * @param {object} payload      - Parsed ESVO or ESVT payload from frame-parser.js
     * @param {{ min: number[], max: number[] }} regionBounds - World-space region bounds
     * @returns {Array<{ min: number[], max: number[] }>}
     */
    _collectLeafVoxels(payload, regionBounds) {
        const { nodes, farPointers, maxDepth } = payload;
        if (!nodes || nodes.length === 0) return [];

        const renderDepth = Math.min(maxDepth, this._maxRenderDepth);
        const voxels = [];
        this._traverseNode(nodes, farPointers, 0, regionBounds, 0, renderDepth, voxels);
        return voxels;
    }

    /**
     * Recursively traverse an ESVO node and accumulate leaf voxel bounds.
     *
     * Child pointer arithmetic (Laine & Karras CUDA reference):
     *   - childPtr (14 bits): relative offset from current node index to child block
     *   - far flag: when set, childPtr indexes farPointers[]; the stored value is the offset
     *   - sparse offset: i-th active child is at  nodeIdx + relOffset + popcount(childMask & ((1<<i)-1))
     *
     * Octant bit layout: bit0=X, bit1=Y, bit2=Z  (0=min half, 1=max half)
     *
     * @param {Array<{childDescriptor,contourDescriptor}>} nodes
     * @param {Uint32Array} farPointers
     * @param {number} nodeIdx   - Index of the current node in the nodes array
     * @param {{ min: number[], max: number[] }} bounds - World bounds for this node
     * @param {number} depth     - Current traversal depth
     * @param {number} maxDepth  - Depth at which to stop and treat node as a leaf
     * @param {Array}  voxels    - Accumulator
     */
    _traverseNode(nodes, farPointers, nodeIdx, bounds, depth, maxDepth, voxels) {
        if (voxels.length >= MAX_VOXELS_PER_REGION) return;
        if (nodeIdx < 0 || nodeIdx >= nodes.length) return;

        const node = nodes[nodeIdx];
        const { valid, childPtr, far, childMask, leafMask } = node.childDescriptor;

        if (!valid) return;

        // At render depth cap: treat this whole node cell as a filled voxel
        if (depth >= maxDepth) {
            voxels.push(bounds);
            return;
        }

        const [minX, minY, minZ] = bounds.min;
        const [maxX, maxY, maxZ] = bounds.max;
        const hw = (maxX - minX) * 0.5;
        const hh = (maxY - minY) * 0.5;
        const hd = (maxZ - minZ) * 0.5;

        // Resolve the relative offset to the child block.
        // When far=1, childPtr is an index into farPointers[]; otherwise it is the offset itself.
        const relOffset = (far && childPtr < farPointers.length)
            ? farPointers[childPtr]
            : childPtr;

        for (let i = 0; i < 8; i++) {
            const bit = 1 << i;
            if (!(childMask & bit)) continue;

            // Octant origin offset (bit0=X, bit1=Y, bit2=Z)
            const ox = (i & 1) ? hw : 0;
            const oy = (i & 2) ? hh : 0;
            const oz = (i & 4) ? hd : 0;

            const childBounds = {
                min: [minX + ox,      minY + oy,      minZ + oz],
                max: [minX + ox + hw, minY + oy + hh, minZ + oz + hd]
            };

            if (leafMask & bit) {
                // Leaf child: emit as a renderable voxel
                voxels.push(childBounds);
            } else {
                // Internal child: recurse; compute sparse slot index
                const sparseOffset = popcount8(childMask & ((1 << i) - 1));
                const childNodeIdx = nodeIdx + relOffset + sparseOffset;
                this._traverseNode(nodes, farPointers, childNodeIdx,
                                   childBounds, depth + 1, maxDepth, voxels);
            }
        }
    }

    // ============================================================================
    // Three.js Mesh Builders
    // ============================================================================

    /**
     * Build an InstancedMesh from an array of voxel bounds.
     *
     * Each voxel is rendered as a scaled unit cube centered at the voxel's
     * centroid.  A 3% gap between adjacent voxels makes individual voxels
     * visually distinct.
     *
     * @param {Array<{ min: number[], max: number[] }>} voxels
     * @param {number} color - Hex color
     * @returns {THREE.InstancedMesh}
     */
    _buildInstancedMesh(voxels, color) {
        const geo = new THREE.BoxGeometry(1, 1, 1);
        const mat = new THREE.MeshLambertMaterial({
            color,
            transparent: this._voxelOpacity < 1.0,
            opacity: this._voxelOpacity
        });

        const mesh = new THREE.InstancedMesh(geo, mat, voxels.length);
        const dummy = new THREE.Object3D();

        for (let i = 0; i < voxels.length; i++) {
            const { min, max } = voxels[i];
            dummy.position.set(
                (min[0] + max[0]) * 0.5,
                (min[1] + max[1]) * 0.5,
                (min[2] + max[2]) * 0.5
            );
            // 97% of cell size leaves a 3% gap between adjacent voxels
            dummy.scale.set(
                (max[0] - min[0]) * 0.97,
                (max[1] - min[1]) * 0.97,
                (max[2] - min[2]) * 0.97
            );
            dummy.updateMatrix();
            mesh.setMatrixAt(i, dummy.matrix);
        }

        mesh.instanceMatrix.needsUpdate = true;
        return mesh;
    }

    /**
     * Build a wireframe bounding-box mesh for a region.
     *
     * Used for unknown formats and as a placeholder for empty regions.
     *
     * @param {{ min: number[], max: number[] }} bounds
     * @param {number} color - Hex color
     * @returns {THREE.Mesh}
     */
    _buildWireframeBounds(bounds, color) {
        const [minX, minY, minZ] = bounds.min;
        const [maxX, maxY, maxZ] = bounds.max;
        const geo = new THREE.BoxGeometry(maxX - minX, maxY - minY, maxZ - minZ);
        const mat = new THREE.MeshBasicMaterial({ color, wireframe: true });
        const mesh = new THREE.Mesh(geo, mat);
        mesh.position.set(
            (minX + maxX) * 0.5,
            (minY + maxY) * 0.5,
            (minZ + maxZ) * 0.5
        );
        return mesh;
    }

    // ============================================================================
    // Resize
    // ============================================================================

    _onWindowResize() {
        const w = this._container.clientWidth  || window.innerWidth;
        const h = this._container.clientHeight || window.innerHeight;
        this._camera.aspect = w / h;
        this._camera.updateProjectionMatrix();
        this._renderer.setSize(w, h);
    }
}

// ============================================================================
// Utility
// ============================================================================

/**
 * Count the number of set bits in the low 8 bits of n.
 * Used for computing ESVO sparse child offsets.
 *
 * @param {number} n
 * @returns {number}
 */
function popcount8(n) {
    let count = 0;
    n = n & 0xFF;
    while (n) {
        count += n & 1;
        n >>>= 1;
    }
    return count;
}
