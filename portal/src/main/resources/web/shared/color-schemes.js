/**
 * Color Schemes - Shared module for voxel/entity coloring
 *
 * Used by both render.js (ESVO/ESVT) and spatial.js (Spatial Index Explorer)
 * Note: These functions return THREE.Color objects, so THREE must be imported by the consumer
 */

/**
 * @typedef {Object} ColorScheme
 * @property {function(number, number, Object): THREE.Color} fn - Color function
 * @property {string} label - Human-readable label
 * @property {string} description - Description of the color scheme
 */

/**
 * Create color scheme functions
 * @param {Object} THREE - Three.js module
 * @returns {Object<string, function>}
 */
export function createColorSchemes(THREE) {
    return {
        DEPTH: (depth, maxDepth, normal) => {
            const t = depth / maxDepth;
            return new THREE.Color().setHSL(0.7 - t * 0.5, 0.8, 0.5 + t * 0.3);
        },
        NORMAL: (depth, maxDepth, normal) => {
            return new THREE.Color(
                Math.abs(normal?.x || 0.5) * 0.5 + 0.5,
                Math.abs(normal?.y || 0.5) * 0.5 + 0.5,
                Math.abs(normal?.z || 0.5) * 0.5 + 0.5
            );
        },
        SOLID: (depth, maxDepth, normal) => {
            return new THREE.Color(0xf472b6); // Pink
        },
        RAINBOW: (depth, maxDepth, normal) => {
            const hue = (depth * 0.15 + Math.random() * 0.1) % 1;
            return new THREE.Color().setHSL(hue, 0.9, 0.6);
        },
        // Additional schemes for spatial index visualization
        INDEX_TYPE: (depth, maxDepth, indexType) => {
            // Color by spatial index type
            switch (indexType) {
                case 'OCTREE': return new THREE.Color(0x3b82f6); // Blue
                case 'TETREE': return new THREE.Color(0x22c55e); // Green
                case 'SFC': return new THREE.Color(0xf59e0b); // Amber
                default: return new THREE.Color(0x9ca3af); // Gray
            }
        },
        QUERY_RESULT: (depth, maxDepth, isResult) => {
            // Highlight query results
            return isResult
                ? new THREE.Color(0xf472b6) // Pink for results
                : new THREE.Color(0x4b5563); // Gray for non-results
        }
    };
}

/**
 * Available color scheme names
 */
export const COLOR_SCHEME_NAMES = ['DEPTH', 'NORMAL', 'SOLID', 'RAINBOW', 'INDEX_TYPE', 'QUERY_RESULT'];

/**
 * Color scheme metadata for UI generation
 */
export const COLOR_SCHEME_INFO = {
    DEPTH: { label: 'Depth', description: 'Color by tree depth (purple to cyan)' },
    NORMAL: { label: 'Normal', description: 'Color by surface normal direction' },
    SOLID: { label: 'Solid', description: 'Single pink color' },
    RAINBOW: { label: 'Rainbow', description: 'Rainbow colors by depth' },
    INDEX_TYPE: { label: 'Index Type', description: 'Color by spatial index type' },
    QUERY_RESULT: { label: 'Query', description: 'Highlight query results' }
};
