/**
 * SceneManager - 3D scene and viewport management for ESVO voxel streaming.
 *
 * Tracks received voxel regions, manages the camera state, and produces
 * viewport objects suitable for REGISTER_CLIENT / UPDATE_VIEWPORT messages.
 *
 * Usage:
 *   const scene = new SceneManager({
 *     worldMin: [0, 0, 0],
 *     worldMax: [1024, 1024, 1024],
 *     regionLevel: 4,
 *     maxViewportUpdatesPerSecond: 30
 *   });
 *
 *   scene.setCamera(
 *     { x: 64, y: 64, z: 30 },   // eye
 *     { x: 64, y: 64, z: 0 },    // lookAt
 *     { x: 0, y: 1, z: 0 }       // up
 *   );
 *
 *   scene.on('viewportChange', (viewport) => {
 *     client.sendUpdateViewport(viewport);
 *   });
 *
 *   // When a frame arrives (from frame-parser.js decodeFrame output):
 *   scene.addRegion(header, region, payload);
 *
 *   // Get all current regions:
 *   const regions = scene.getRegions(); // Map<string, RegionEntry>
 *
 *   // Get viewport for REGISTER_CLIENT:
 *   const vp = scene.getViewport(fovY, aspectRatio, nearPlane, farPlane);
 */

// ============================================================================
// Constants
// ============================================================================

const DEFAULT_MAX_VIEWPORT_UPDATES_PER_SECOND = 30;

// ============================================================================
// SceneManager
// ============================================================================

/**
 * Manages scene state for ESVO voxel streaming:
 * - Tracks received regions (add/update/remove)
 * - Manages camera state (eye, lookAt, up)
 * - Produces viewport objects for server messages
 * - Fires throttled viewport-change events when camera moves
 */
export class SceneManager {
    /**
     * Create a new SceneManager.
     *
     * @param {{ worldMin: number[], worldMax: number[], regionLevel: number,
     *           maxViewportUpdatesPerSecond?: number }} config
     */
    constructor(config) {
        this._worldMin = config.worldMin;
        this._worldMax = config.worldMax;
        this._regionLevel = config.regionLevel;
        this._maxViewportUpdatesPerSecond =
            config.maxViewportUpdatesPerSecond ?? DEFAULT_MAX_VIEWPORT_UPDATES_PER_SECOND;

        // Compute region size per axis: (worldMax[i] - worldMin[i]) / 2^regionLevel
        const divisor = Math.pow(2, this._regionLevel);
        this._regionSize = [
            (this._worldMax[0] - this._worldMin[0]) / divisor,
            (this._worldMax[1] - this._worldMin[1]) / divisor,
            (this._worldMax[2] - this._worldMin[2]) / divisor
        ];

        // Camera state
        /** @type {{ x: number, y: number, z: number }} */
        this._eye    = { x: 0, y: 0, z: 0 };
        /** @type {{ x: number, y: number, z: number }} */
        this._lookAt = { x: 0, y: 0, z: 0 };
        /** @type {{ x: number, y: number, z: number }} */
        this._up     = { x: 0, y: 1, z: 0 };

        // Viewport projection params (stored so setCamera() can build a full viewport event)
        this._fovY        = Math.PI / 4;  // 45 degrees default
        this._aspectRatio = 1.0;
        this._nearPlane   = 0.1;
        this._farPlane    = 10000.0;

        // Throttle state for 'viewportChange' events
        this._lastViewportFireTime = 0;

        // Region storage: Map<string, RegionEntry>
        /** @type {Map<string, RegionEntry>} */
        this._regions = new Map();

        // Event listeners: Map<string, Set<Function>>
        /** @type {Map<string, Set<Function>>} */
        this._listeners = new Map();
    }

    // ============================================================================
    // Event Emitter
    // ============================================================================

    /**
     * Register an event listener.
     *
     * Events:
     *   - 'viewportChange' : (viewport) => void
     *   - 'regionAdded'    : (RegionEntry) => void
     *   - 'regionRemoved'  : (RegionEntry) => void
     *
     * @param {string} event
     * @param {Function} handler
     */
    on(event, handler) {
        if (!this._listeners.has(event)) {
            this._listeners.set(event, new Set());
        }
        this._listeners.get(event).add(handler);
    }

    /**
     * Unregister an event listener.
     *
     * @param {string} event
     * @param {Function} handler
     */
    off(event, handler) {
        const handlers = this._listeners.get(event);
        if (handlers) {
            handlers.delete(handler);
        }
    }

    /**
     * Emit an event to all registered listeners.
     *
     * @param {string} event
     * @param {*} payload
     */
    _emit(event, payload) {
        const handlers = this._listeners.get(event);
        if (!handlers) return;
        for (const handler of handlers) {
            try {
                handler(payload);
            } catch (err) {
                console.error(`SceneManager: error in '${event}' handler:`, err);
            }
        }
    }

    // ============================================================================
    // Camera Management
    // ============================================================================

    /**
     * Update the camera position. Fires a throttled 'viewportChange' event.
     *
     * @param {{ x: number, y: number, z: number }} eye
     * @param {{ x: number, y: number, z: number }} lookAt
     * @param {{ x: number, y: number, z: number }} up
     */
    setCamera(eye, lookAt, up) {
        this._eye    = { x: eye.x,    y: eye.y,    z: eye.z };
        this._lookAt = { x: lookAt.x, y: lookAt.y, z: lookAt.z };
        this._up     = { x: up.x,     y: up.y,     z: up.z };

        this._maybeFireViewportChange();
    }

    /**
     * Get the current camera eye position.
     *
     * @returns {{ x: number, y: number, z: number }}
     */
    getEye() {
        return { x: this._eye.x, y: this._eye.y, z: this._eye.z };
    }

    // ============================================================================
    // Viewport Params
    // ============================================================================

    /**
     * Store the viewport projection parameters so that 'viewportChange' events
     * fired from setCamera() contain a complete viewport object.
     *
     * @param {number} fovY        - Vertical field of view in radians
     * @param {number} aspectRatio - Width / height
     * @param {number} nearPlane
     * @param {number} farPlane
     */
    setViewportParams(fovY, aspectRatio, nearPlane, farPlane) {
        this._fovY        = fovY;
        this._aspectRatio = aspectRatio;
        this._nearPlane   = nearPlane;
        this._farPlane    = farPlane;
    }

    /**
     * Build a viewport object suitable for REGISTER_CLIENT / UPDATE_VIEWPORT.
     * Also updates the internally stored projection params.
     *
     * @param {number} fovY        - Vertical field of view in radians
     * @param {number} aspectRatio - Width / height
     * @param {number} nearPlane
     * @param {number} farPlane
     * @returns {{ eye: {x,y,z}, lookAt: {x,y,z}, up: {x,y,z},
     *             fovY: number, aspectRatio: number, nearPlane: number, farPlane: number }}
     */
    getViewport(fovY, aspectRatio, nearPlane, farPlane) {
        this.setViewportParams(fovY, aspectRatio, nearPlane, farPlane);
        return this._buildViewport();
    }

    /**
     * Build the current viewport object from stored camera + projection state.
     *
     * @returns {{ eye: {x,y,z}, lookAt: {x,y,z}, up: {x,y,z},
     *             fovY: number, aspectRatio: number, nearPlane: number, farPlane: number }}
     */
    _buildViewport() {
        return {
            eye:         { x: this._eye.x,    y: this._eye.y,    z: this._eye.z },
            lookAt:      { x: this._lookAt.x, y: this._lookAt.y, z: this._lookAt.z },
            up:          { x: this._up.x,     y: this._up.y,     z: this._up.z },
            fovY:        this._fovY,
            aspectRatio: this._aspectRatio,
            nearPlane:   this._nearPlane,
            farPlane:    this._farPlane
        };
    }

    /**
     * Fire 'viewportChange' if enough time has elapsed since the last fire
     * (throttled to maxViewportUpdatesPerSecond).
     */
    _maybeFireViewportChange() {
        const now = Date.now();
        const minIntervalMs = 1000 / this._maxViewportUpdatesPerSecond;
        if (now - this._lastViewportFireTime >= minIntervalMs) {
            this._lastViewportFireTime = now;
            this._emit('viewportChange', this._buildViewport());
        }
    }

    // ============================================================================
    // Region Management
    // ============================================================================

    /**
     * Compute the string key used to identify a region in the Map.
     *
     * @param {BigInt} mortonCode
     * @param {number} lod
     * @returns {string}
     */
    _regionKey(mortonCode, lod) {
        return `${mortonCode.toString()}_${lod}`;
    }

    /**
     * Add or update a region from a decoded frame.
     *
     * Rules:
     *   - Same mortonCode + lod, lower buildVersion → skip (stale data).
     *   - Same mortonCode + lod, equal/higher buildVersion → replace.
     *   - Lower lod number (higher detail) → replace any existing entry for the
     *     same mortonCode regardless of lod, provided it is less detailed.
     *
     * Fires 'regionAdded' after the region is stored.
     *
     * @param {{ magic: number, format: number, lod: number, level: number,
     *           mortonCode: BigInt, buildVersion: number, dataSize: number }} header
     * @param {{ rx: number, ry: number, rz: number }} region - decoded Morton grid coords
     * @param {object} payload - parsed ESVO or ESVT structure
     */
    addRegion(header, region, payload) {
        const { mortonCode, lod, level, buildVersion, format } = header;
        const { rx, ry, rz } = region;
        const key = this._regionKey(mortonCode, lod);

        // Check for stale data: same key, lower buildVersion → skip.
        const existing = this._regions.get(key);
        if (existing !== undefined && buildVersion < existing.buildVersion) {
            return;
        }

        const bounds = this.getRegionBounds(rx, ry, rz);

        /** @type {RegionEntry} */
        const entry = {
            mortonCode,
            rx,
            ry,
            rz,
            lod,
            level,
            buildVersion,
            format,
            payload,
            bounds,
            receivedAt: Date.now()
        };

        this._regions.set(key, entry);
        this._emit('regionAdded', entry);
    }

    /**
     * Remove a region by its mortonCode + lod key. Fires 'regionRemoved'.
     *
     * @param {BigInt} mortonCode
     * @param {number} lod
     */
    removeRegion(mortonCode, lod) {
        const key = this._regionKey(mortonCode, lod);
        const entry = this._regions.get(key);
        if (entry !== undefined) {
            this._regions.delete(key);
            this._emit('regionRemoved', entry);
        }
    }

    /**
     * Get all currently tracked regions.
     *
     * @returns {Map<string, RegionEntry>}
     */
    getRegions() {
        return this._regions;
    }

    /**
     * Compute the world-space bounding box for a region at grid coords (rx, ry, rz).
     *
     * Region min[i] = worldMin[i] + r[i] * regionSize[i]
     * Region max[i] = min[i] + regionSize[i]
     * Region center[i] = min[i] + regionSize[i] / 2
     *
     * @param {number} rx
     * @param {number} ry
     * @param {number} rz
     * @returns {{ min: number[], max: number[], center: number[], size: number[] }}
     */
    getRegionBounds(rx, ry, rz) {
        const [sx, sy, sz] = this._regionSize;
        const [wx, wy, wz] = this._worldMin;

        const minX = wx + rx * sx;
        const minY = wy + ry * sy;
        const minZ = wz + rz * sz;

        return {
            min:    [minX,             minY,             minZ],
            max:    [minX + sx,        minY + sy,        minZ + sz],
            center: [minX + sx / 2,    minY + sy / 2,    minZ + sz / 2],
            size:   [sx,               sy,               sz]
        };
    }

    /**
     * Remove all regions. Does NOT fire 'regionRemoved' per region.
     */
    clearRegions() {
        this._regions.clear();
    }
}

// ============================================================================
// JSDoc typedefs
// ============================================================================

/**
 * @typedef {object} RegionEntry
 * @property {BigInt}  mortonCode   - Morton-encoded grid coordinates
 * @property {number}  rx           - Grid X index
 * @property {number}  ry           - Grid Y index
 * @property {number}  rz           - Grid Z index
 * @property {number}  lod          - Level of detail (lower = more detail)
 * @property {number}  level        - Octree/Tetree subdivision level
 * @property {number}  buildVersion - Server build version for staleness checks
 * @property {number}  format       - FORMAT_ESVO or FORMAT_ESVT constant from frame header
 * @property {object}  payload      - Parsed ESVO or ESVT structure
 * @property {{ min: number[], max: number[], center: number[], size: number[] }} bounds
 * @property {number}  receivedAt   - Date.now() timestamp when the entry was stored
 */
