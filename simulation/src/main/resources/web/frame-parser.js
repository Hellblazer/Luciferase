/**
 * Binary Frame Parser - ESVO/ESVT WebSocket Frame Decoder
 *
 * Parses binary frames produced by BinaryFrameCodec.java and decodes
 * ESVO (Efficient Sparse Voxel Octree) and ESVT (Efficient Sparse Voxel
 * Tetrahedral) payload structures serialized by SerializationUtils.java.
 *
 * All multi-byte fields are little-endian, matching the Java encoder.
 * Morton codes are handled as BigInt (64-bit unsigned).
 * GZIP-compressed payloads are decompressed transparently.
 */

// ============================================================================
// Constants
// ============================================================================

/** Frame magic number: 0x45535652 ("ESVR" as little-endian uint32) */
export const FRAME_MAGIC = 0x45535652;

/** Format code: ESVO (octree) */
export const FORMAT_ESVO = 0x01;

/** Format code: ESVT (tetrahedral) */
export const FORMAT_ESVT = 0x02;

/** Size of the binary frame header in bytes */
export const FRAME_HEADER_SIZE = 24;

// Internal constants
const GZIP_MAGIC_0 = 0x1F;
const GZIP_MAGIC_1 = 0x8B;

const ESVO_HEADER_SIZE = 21;  // 1 + 4 + 4 + 4 + 4 + 4
const ESVT_HEADER_SIZE = 33;  // 1 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4
const NODE_SIZE_BYTES  = 8;   // childDescriptor (4) + contourDescriptor (4)

// ============================================================================
// Morton Code Decoding
// ============================================================================

/**
 * Decode a Morton code (BigInt) into 3D region grid coordinates.
 *
 * Bits are interleaved in Z-curve order:
 *   rx occupies bits 0, 3, 6, ... (positions 0 + 3i)
 *   ry occupies bits 1, 4, 7, ... (positions 1 + 3i)
 *   rz occupies bits 2, 5, 8, ... (positions 2 + 3i)
 *
 * Supports up to 21 bits per axis (octree level 0-21).
 *
 * @param {BigInt} mortonCode - Morton (Z-curve) code read from the frame header
 * @returns {{ rx: number, ry: number, rz: number }} Decoded grid coordinates
 */
export function decodeMorton3D(mortonCode) {
    let rx = 0, ry = 0, rz = 0;

    for (let i = 0; i < 21; i++) {
        const shift = i * 3;
        rx |= Number((mortonCode >> BigInt(shift))     & 1n) << i;
        ry |= Number((mortonCode >> BigInt(shift + 1)) & 1n) << i;
        rz |= Number((mortonCode >> BigInt(shift + 2)) & 1n) << i;
    }

    return { rx, ry, rz };
}

// ============================================================================
// Child Descriptor Bit Layout
// ============================================================================

/**
 * Parse a childDescriptor int32 into its component fields.
 *
 * Bit layout (MSB to LSB):
 *   bit 31        : valid flag
 *   bits 30-17    : childPtr (14 bits)
 *   bit 16        : far (far-pointer flag)
 *   bits 15-8     : childMask (8 bits)
 *   bits 7-0      : leafMask (8 bits)
 *
 * @param {number} bits - Raw uint32 value of the childDescriptor field
 * @returns {{ valid: number, childPtr: number, far: number, childMask: number, leafMask: number }}
 */
function parseChildDescriptor(bits) {
    return {
        valid:     (bits >>> 31) & 1,
        childPtr:  (bits >>> 17) & 0x3FFF,
        far:       (bits >>> 16) & 1,
        childMask: (bits >>> 8)  & 0xFF,
        leafMask:   bits         & 0xFF
    };
}

/**
 * Parse a contourDescriptor int32 into its component fields.
 *
 * Bit layout (MSB to LSB):
 *   bits 31-8     : contourPtr (24 bits)
 *   bits 7-0      : contourMask (8 bits)
 *
 * @param {number} bits - Raw uint32 value of the contourDescriptor field
 * @returns {{ contourPtr: number, contourMask: number }}
 */
function parseContourDescriptor(bits) {
    return {
        contourPtr:  (bits >>> 8) & 0xFFFFFF,
        contourMask:  bits        & 0xFF
    };
}

// ============================================================================
// Frame Header Parsing
// ============================================================================

/**
 * Parse the 24-byte binary frame header from an ArrayBuffer.
 *
 * Validates the magic number (0x45535652). Returns null if the buffer is
 * too small or the magic does not match.
 *
 * @param {ArrayBuffer} buffer - Raw frame data (at least 24 bytes)
 * @returns {{ magic: number, format: number, lod: number, level: number,
 *             mortonCode: BigInt, buildVersion: number, dataSize: number } | null}
 */
export function parseFrameHeader(buffer) {
    if (buffer.byteLength < FRAME_HEADER_SIZE) {
        return null;
    }

    const view = new DataView(buffer);

    // Validate magic (little-endian uint32 at offset 0)
    const magic = view.getUint32(0, /* littleEndian */ true);
    if (magic !== FRAME_MAGIC) {
        return null;
    }

    const format       = view.getUint8(4);
    const lod          = view.getUint8(5);
    const level        = view.getUint8(6);
    // byte 7 is reserved

    // mortonCode is a 64-bit little-endian value; read as two 32-bit halves
    const mortonLo     = view.getUint32(8,  /* littleEndian */ true);
    const mortonHi     = view.getUint32(12, /* littleEndian */ true);
    const mortonCode   = (BigInt(mortonHi) << 32n) | BigInt(mortonLo);

    const buildVersion = view.getUint32(16, /* littleEndian */ true);
    const dataSize     = view.getUint32(20, /* littleEndian */ true);

    return { magic, format, lod, level, mortonCode, buildVersion, dataSize };
}

// ============================================================================
// Payload Extraction and Decompression
// ============================================================================

/**
 * Extract the raw payload bytes from a complete frame (header + payload).
 *
 * If the payload begins with GZIP magic bytes (0x1F 0x8B), it is
 * decompressed transparently using the Web Streams DecompressionStream API.
 * The returned Uint8Array is always uncompressed.
 *
 * @param {ArrayBuffer} buffer   - Complete frame buffer (header + payload)
 * @param {number}      dataSize - Payload byte count from the frame header
 * @returns {Promise<Uint8Array>} Uncompressed payload bytes
 */
export async function extractPayload(buffer, dataSize) {
    const payloadBytes = new Uint8Array(buffer, FRAME_HEADER_SIZE, dataSize);

    // Check for GZIP magic number
    if (payloadBytes.length >= 2 &&
        payloadBytes[0] === GZIP_MAGIC_0 &&
        payloadBytes[1] === GZIP_MAGIC_1) {
        return decompressGzip(payloadBytes);
    }

    // Not compressed - return a copy so callers get a stable Uint8Array
    return payloadBytes.slice();
}

/**
 * Decompress a GZIP-encoded Uint8Array using the Web Streams API.
 *
 * @param {Uint8Array} compressed - GZIP-compressed bytes
 * @returns {Promise<Uint8Array>} Decompressed bytes
 */
async function decompressGzip(compressed) {
    const stream = new DecompressionStream('gzip');
    const writer = stream.writable.getWriter();
    const reader = stream.readable.getReader();

    // Write compressed data and close
    writer.write(compressed);
    writer.close();

    // Collect all decompressed chunks
    const chunks = [];
    let totalLength = 0;

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        totalLength += value.length;
    }

    // Concatenate chunks into a single Uint8Array
    const result = new Uint8Array(totalLength);
    let offset = 0;
    for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.length;
    }

    return result;
}

// ============================================================================
// ESVO Payload Parsing
// ============================================================================

/**
 * Parse an ESVO (Efficient Sparse Voxel Octree) payload into a structured object.
 *
 * Expected binary layout (little-endian):
 *   Byte  0       : version (uint8)
 *   Bytes 1-4     : nodeCount (int32)
 *   Bytes 5-8     : maxDepth (int32)
 *   Bytes 9-12    : leafCount (int32)
 *   Bytes 13-16   : internalCount (int32)
 *   Bytes 17-20   : farPtrCount (int32)
 *   Bytes 21+     : nodes (nodeCount * 8 bytes each)
 *   After nodes   : farPointers (farPtrCount * 4 bytes each)
 *
 * Each node is two int32 fields:
 *   childDescriptor  (4 bytes)
 *   contourDescriptor (4 bytes)
 *
 * @param {Uint8Array} bytes - Uncompressed ESVO payload bytes
 * @returns {{ version: number, nodeCount: number, maxDepth: number,
 *             leafCount: number, internalCount: number, farPtrCount: number,
 *             nodes: Array<{ childDescriptor: object, contourDescriptor: object }>,
 *             farPointers: Uint32Array }}
 */
export function parseEsvoPayload(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let offset = 0;

    // Header (21 bytes)
    const version       = view.getUint8(offset);     offset += 1;
    const nodeCount     = view.getInt32(offset, true); offset += 4;
    const maxDepth      = view.getInt32(offset, true); offset += 4;
    const leafCount     = view.getInt32(offset, true); offset += 4;
    const internalCount = view.getInt32(offset, true); offset += 4;
    const farPtrCount   = view.getInt32(offset, true); offset += 4;

    // Nodes (nodeCount * 8 bytes)
    const nodes = [];
    for (let i = 0; i < nodeCount; i++) {
        const childRaw   = view.getUint32(offset,     true);
        const contourRaw = view.getUint32(offset + 4, true);
        offset += NODE_SIZE_BYTES;

        nodes.push({
            childDescriptor:   parseChildDescriptor(childRaw),
            contourDescriptor: parseContourDescriptor(contourRaw)
        });
    }

    // Far pointers (farPtrCount * 4 bytes)
    const farPointers = new Uint32Array(farPtrCount);
    for (let i = 0; i < farPtrCount; i++) {
        farPointers[i] = view.getUint32(offset, true);
        offset += 4;
    }

    return { version, nodeCount, maxDepth, leafCount, internalCount, farPtrCount, nodes, farPointers };
}

// ============================================================================
// ESVT Payload Parsing
// ============================================================================

/**
 * Parse an ESVT (Efficient Sparse Voxel Tetrahedral) payload into a structured object.
 *
 * Expected binary layout (little-endian):
 *   Byte  0       : version (uint8)
 *   Bytes 1-4     : nodeCount (int32)
 *   Bytes 5-8     : rootType (int32)
 *   Bytes 9-12    : maxDepth (int32)
 *   Bytes 13-16   : leafCount (int32)
 *   Bytes 17-20   : internalCount (int32)
 *   Bytes 21-24   : gridResolution (int32)
 *   Bytes 25-28   : contourCount (int32)
 *   Bytes 29-32   : farPtrCount (int32)
 *   Bytes 33+     : nodes (nodeCount * 8 bytes each)
 *   After nodes   : contours (contourCount * 4 bytes each)
 *   After contours: farPointers (farPtrCount * 4 bytes each)
 *
 * @param {Uint8Array} bytes - Uncompressed ESVT payload bytes
 * @returns {{ version: number, nodeCount: number, rootType: number, maxDepth: number,
 *             leafCount: number, internalCount: number, gridResolution: number,
 *             contourCount: number, farPtrCount: number,
 *             nodes: Array<{ childDescriptor: object, contourDescriptor: object }>,
 *             contours: Uint32Array, farPointers: Uint32Array }}
 */
export function parseEsvtPayload(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let offset = 0;

    // Header (33 bytes)
    const version        = view.getUint8(offset);      offset += 1;
    const nodeCount      = view.getInt32(offset, true); offset += 4;
    const rootType       = view.getInt32(offset, true); offset += 4;
    const maxDepth       = view.getInt32(offset, true); offset += 4;
    const leafCount      = view.getInt32(offset, true); offset += 4;
    const internalCount  = view.getInt32(offset, true); offset += 4;
    const gridResolution = view.getInt32(offset, true); offset += 4;
    const contourCount   = view.getInt32(offset, true); offset += 4;
    const farPtrCount    = view.getInt32(offset, true); offset += 4;

    // Nodes (nodeCount * 8 bytes)
    const nodes = [];
    for (let i = 0; i < nodeCount; i++) {
        const childRaw   = view.getUint32(offset,     true);
        const contourRaw = view.getUint32(offset + 4, true);
        offset += NODE_SIZE_BYTES;

        nodes.push({
            childDescriptor:   parseChildDescriptor(childRaw),
            contourDescriptor: parseContourDescriptor(contourRaw)
        });
    }

    // Contours (contourCount * 4 bytes)
    const contours = new Uint32Array(contourCount);
    for (let i = 0; i < contourCount; i++) {
        contours[i] = view.getUint32(offset, true);
        offset += 4;
    }

    // Far pointers (farPtrCount * 4 bytes)
    const farPointers = new Uint32Array(farPtrCount);
    for (let i = 0; i < farPtrCount; i++) {
        farPointers[i] = view.getUint32(offset, true);
        offset += 4;
    }

    return {
        version, nodeCount, rootType, maxDepth, leafCount, internalCount,
        gridResolution, contourCount, farPtrCount,
        nodes, contours, farPointers
    };
}

// ============================================================================
// Top-Level Frame Decoder
// ============================================================================

/**
 * Decode a complete binary frame (24-byte header + payload) into a structured object.
 *
 * Handles GZIP decompression automatically. Dispatches to parseEsvoPayload or
 * parseEsvtPayload based on the format field in the header.
 *
 * Returns null if the magic number is wrong or the buffer is too small.
 *
 * @param {ArrayBuffer} buffer - Raw binary frame received from WebSocket
 * @returns {Promise<{ header: object, region: { rx: number, ry: number, rz: number },
 *                     payload: object } | null>}
 *   header  - Parsed frame header fields
 *   region  - Decoded 3D region grid coordinates from mortonCode
 *   payload - Parsed ESVO or ESVT structure (type determined by header.format)
 */
export async function decodeFrame(buffer) {
    const header = parseFrameHeader(buffer);
    if (header === null) {
        return null;
    }

    const region = decodeMorton3D(header.mortonCode);
    const payloadBytes = await extractPayload(buffer, header.dataSize);

    let payload;
    if (header.format === FORMAT_ESVO) {
        payload = parseEsvoPayload(payloadBytes);
    } else if (header.format === FORMAT_ESVT) {
        payload = parseEsvtPayload(payloadBytes);
    } else {
        console.warn('frame-parser: unknown format code', header.format);
        payload = { raw: payloadBytes };
    }

    return { header, region, payload };
}
