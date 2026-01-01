/**
 * Shape Generators - Shared module for generating 3D shape entities
 *
 * Used by both render.js (ESVO/ESVT) and spatial.js (Spatial Index Explorer)
 */

/**
 * Generate entities for various 3D shapes
 * @param {string} shape - Shape type: 'sphere', 'cube', 'torus', 'random'
 * @param {number} count - Number of entities to generate
 * @returns {Array<{x: number, y: number, z: number, content: any}>}
 */
export function generateShapeEntities(shape, count) {
    const entities = [];

    for (let i = 0; i < count; i++) {
        let x, y, z;

        switch (shape) {
            case 'sphere': {
                // Random point on sphere surface
                const theta = Math.random() * Math.PI * 2;
                const phi = Math.acos(2 * Math.random() - 1);
                const r = 0.3 + Math.random() * 0.1;
                x = 0.5 + r * Math.sin(phi) * Math.cos(theta);
                y = 0.5 + r * Math.sin(phi) * Math.sin(theta);
                z = 0.5 + r * Math.cos(phi);
                break;
            }
            case 'cube': {
                // Random point on cube surface
                const face = Math.floor(Math.random() * 6);
                const u = Math.random() * 0.6 + 0.2;
                const v = Math.random() * 0.6 + 0.2;
                switch (face) {
                    case 0: x = 0.2; y = u; z = v; break;
                    case 1: x = 0.8; y = u; z = v; break;
                    case 2: x = u; y = 0.2; z = v; break;
                    case 3: x = u; y = 0.8; z = v; break;
                    case 4: x = u; y = v; z = 0.2; break;
                    case 5: x = u; y = v; z = 0.8; break;
                }
                break;
            }
            case 'torus': {
                const theta = Math.random() * Math.PI * 2;
                const phi = Math.random() * Math.PI * 2;
                const R = 0.25; // Major radius
                const r = 0.1;  // Minor radius
                x = 0.5 + (R + r * Math.cos(phi)) * Math.cos(theta);
                y = 0.5 + r * Math.sin(phi);
                z = 0.5 + (R + r * Math.cos(phi)) * Math.sin(theta);
                break;
            }
            default: // random
                x = Math.random();
                y = Math.random();
                z = Math.random();
        }

        // Clamp to [0,1]
        x = Math.max(0.01, Math.min(0.99, x));
        y = Math.max(0.01, Math.min(0.99, y));
        z = Math.max(0.01, Math.min(0.99, z));

        entities.push({ x, y, z, content: null });
    }

    return entities;
}

/**
 * Available shape types
 */
export const SHAPE_TYPES = ['sphere', 'cube', 'torus', 'random', 'bunny'];
