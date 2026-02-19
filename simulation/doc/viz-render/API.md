# WebSocket Streaming API Documentation

**Version:** 1.1.0
**Date:** 2026-02-18

## WebSocket Endpoint

### Connection

**Endpoint:** `ws://server:7090/ws/render` (or `wss://` for TLS)

**Authentication:**
```http
GET /ws/render HTTP/1.1
Host: server:7090
Upgrade: websocket
Connection: Upgrade
Authorization: Bearer YOUR_API_KEY_HERE
```

**Connection States:**
- `CONNECTED` - Connection established, not yet registered
- `STREAMING` - Client registered with viewport, receiving binary frames
- `DISCONNECTING` - Cleanup in progress

**Error Codes:**
- `4001` - Server full (maxClientsPerServer reached)
- `4002` - Message size limit exceeded
- `4003` - Unauthorized (authentication failed or rate limited)
- `1001` - Server shutdown (going away)
- `1011` - Internal error (serialization failure)

### JSON Messages (Client → Server)

#### REGISTER_CLIENT

Initial registration with viewport:

```json
{
  "type": "REGISTER_CLIENT",
  "clientId": "browser-12345",
  "viewport": {
    "eye": { "x": 10.0, "y": 5.0, "z": 20.0 },
    "lookAt": { "x": 0.0, "y": 0.0, "z": 0.0 },
    "up": { "x": 0.0, "y": 1.0, "z": 0.0 },
    "fovY": 1.0472,
    "aspectRatio": 1.777777,
    "nearPlane": 0.1,
    "farPlane": 1000.0
  }
}
```

**Fields:**
- `clientId` (string, required): Unique client identifier
- `viewport` (object, required): Camera configuration
  - `eye` (Point3f, required): Camera position in world coordinates
  - `lookAt` (Point3f, required): Target point to look at
  - `up` (Vector3f, required): Up direction (usually `{x:0, y:1, z:0}`)
  - `fovY` (float, required): Vertical field of view **in radians** — must be in (0, π).
    Common values: `1.0472` (60°), `0.7854` (45°), `1.3963` (80°).
    **Do not send degrees** — values ≥ π are rejected and the session will not enter STREAMING state.
  - `aspectRatio` (float, required): Width / height ratio (e.g. `16/9 ≈ 1.7778`)
  - `nearPlane` (float, required): Near clipping plane distance (positive, e.g. `0.1`)
  - `farPlane` (float, required): Far clipping plane distance (positive, > nearPlane, e.g. `1000.0`)

**Response:** Session transitions to STREAMING state, binary frames begin

**Validation errors** (sent as ERROR message, session stays CONNECTED):
- `"fovY must be in (0, pi)"` — fovY was in degrees or otherwise out of range
- `"Invalid viewport: nearPlane must be positive"`
- `"Invalid viewport: farPlane must be > nearPlane"`
- `"Missing eye, lookAt, or up field"`

#### UPDATE_VIEWPORT

Update camera position/orientation:

```json
{
  "type": "UPDATE_VIEWPORT",
  "clientId": "browser-12345",
  "viewport": {
    "eye": { "x": 15.0, "y": 10.0, "z": 25.0 },
    "lookAt": { "x": 5.0, "y": 0.0, "z": 5.0 },
    "up": { "x": 0.0, "y": 1.0, "z": 0.0 },
    "fovY": 1.0472,
    "aspectRatio": 1.777777,
    "nearPlane": 0.1,
    "farPlane": 1000.0
  }
}
```

**Fields:** Same as REGISTER_CLIENT

**Response:** Viewport diffing occurs, new regions streamed in next cycle

### JSON Messages (Server → Client)

#### ERROR

Error response:

```json
{
  "type": "ERROR",
  "message": "Rate limit exceeded"
}
```

**Common error messages:**
- `"Missing 'type' field"`
- `"Unknown message type: XYZ"`
- `"Missing 'clientId' field"`
- `"Missing 'viewport' field"`
- `"Invalid viewport: fovY must be in (0, pi)"` — fovY was ≥ π (sent in degrees)
- `"Invalid viewport: missing eye.x"`
- `"Rate limit exceeded"`

### Binary Messages (Server → Client)

Binary WebSocket frames containing ESVO/ESVT region data.

---

## Binary Frame Format

### Frame Structure

```
+------------------+
| Frame Header     | 24 bytes (fixed)
+------------------+
| Payload          | dataSize bytes (variable)
+------------------+
```

Total frame size: `24 + dataSize` bytes.

### Frame Header (24 bytes, all fields little-endian)

```
Offset | Size | Field        | Type   | Description
-------|------|--------------|--------|--------------------------------------------
  0    |  4   | magic        | uint32 | Always 0x45535652 ("ESVR" LE). Validates frame.
  4    |  1   | format       | uint8  | 0x01 = ESVO, 0x02 = ESVT
  5    |  1   | lod          | uint8  | LOD level (0 = highest detail). Currently always 0.
  6    |  1   | level        | uint8  | Region octree depth (0–21)
  7    |  1   | reserved     | uint8  | Reserved, always 0x00
  8    |  8   | mortonCode   | uint64 | Morton-encoded region grid coordinates
 16    |  4   | buildVersion | uint32 | Monotonic build counter. Starts at 1, increments on rebuild.
 20    |  4   | dataSize     | uint32 | Payload size in bytes
 24    |  N   | payload      | bytes  | ESVO or ESVT binary data (see Payload Formats below)
```

**Magic number byte layout** (`0x45535652` little-endian):

```
Byte 0: 0x52 ('R')
Byte 1: 0x56 ('V')
Byte 2: 0x53 ('S')
Byte 3: 0x45 ('E')
```

**mortonCode** encodes the 3D integer region grid coordinates `(rx, ry, rz)` via Morton
(Z-curve) interleaving. To decode a region's world-space bounds:

```
rx, ry, rz = morton_decode(mortonCode)   // 3D Z-curve decode
regionSize  = worldSize / 2^level         // e.g. 1024 / 4 = 256 for level=2
minX = rx * regionSize
minY = ry * regionSize
minZ = rz * regionSize
maxX = minX + regionSize
maxY = minY + regionSize
maxZ = minZ + regionSize
```

**buildVersion** is a monotonic counter per region. It starts at 1 for the first successful
build and increments each time the region is rebuilt (entity changes trigger a rebuild).
A value of 1 means the region has been built exactly once since server start.

**Delivery:**
- Frames are buffered (up to 10 messages) and flushed every 50 ms or on threshold
- Immediate delivery for build completion callbacks (no buffering delay)

---

## Payload Formats

The payload at offset 24 contains the serialized voxel structure. It may be
GZIP-compressed; detect compression by checking the first two bytes for the GZIP
magic number `0x1F 0x8B`. Decompress before parsing.

> **Compression rule:** Data ≥ 200 bytes is GZIP-compressed. Data < 200 bytes is stored raw.
> Always check the magic number rather than relying on size.

All payload fields use **little-endian** byte order.

### ESVO Payload (format = 0x01)

Efficient Sparse Voxel Octree — recursive cubic subdivision.

**Header (21 bytes):**

```
Offset | Size | Field          | Type   | Description
-------|------|----------------|--------|-----------------------------------
  0    |  1   | version        | uint8  | Format version, currently 0x01
  1    |  4   | nodeCount      | uint32 | Total number of nodes
  5    |  4   | maxDepth       | uint32 | Maximum tree depth reached
  9    |  4   | leafCount      | uint32 | Number of leaf nodes
 13    |  4   | internalCount  | uint32 | Number of internal nodes
 17    |  4   | farPtrCount    | uint32 | Number of far pointer entries
```

**Node Array** (`nodeCount × 8 bytes`, immediately after header):

Each node is exactly 8 bytes (two `int32` fields):

```
Offset | Size | Field             | Type   | Description
-------|------|-------------------|--------|----------------------------------
  0    |  4   | childDescriptor   | int32  | Packed child/leaf/valid bits (see below)
  4    |  4   | contourDescriptor | int32  | Packed contour mask and pointer
```

**childDescriptor bit layout:**

```
Bit 31    : valid flag      — 1 if node is active
Bits 17-30: childptr        — child pointer offset (14 bits)
Bit 16    : far flag        — 1 if childptr is a far pointer index
Bits 8-15 : childmask       — bitmask of 8 children that exist (one bit per octant)
Bits 0-7  : leafmask        — bitmask of children that are leaves
```

**contourDescriptor bit layout:**

```
Bits 8-31 : contour_ptr     — contour data pointer (24 bits)
Bits 0-7  : contour_mask    — bitmask of children with contour data
```

**Far Pointer Array** (`farPtrCount × 4 bytes`, immediately after nodes):

Each far pointer is one `int32` (little-endian). Used when a child pointer offset
exceeds the 14-bit range of the inline `childptr` field.

**Total ESVO payload size** (uncompressed):
```
21 + (nodeCount × 8) + (farPtrCount × 4)  bytes
```

---

### ESVT Payload (format = 0x02)

Efficient Sparse Voxel Tetree — tetrahedral subdivision (S0–S5 characteristic tetrahedra).

**Header (33 bytes):**

```
Offset | Size | Field          | Type   | Description
-------|------|----------------|--------|-----------------------------------
  0    |  1   | version        | uint8  | Format version, currently 0x01
  1    |  4   | nodeCount      | uint32 | Total number of nodes
  5    |  4   | rootType       | uint32 | Root tetrahedron type (0–5, S0–S5)
  9    |  4   | maxDepth       | uint32 | Maximum tree depth reached
 13    |  4   | leafCount      | uint32 | Number of leaf nodes
 17    |  4   | internalCount  | uint32 | Number of internal nodes
 21    |  4   | gridResolution | uint32 | Voxel grid resolution (e.g. 64 for 64³)
 25    |  4   | contourCount   | uint32 | Number of contour entries
 29    |  4   | farPtrCount    | uint32 | Number of far pointer entries
```

**Node Array** (`nodeCount × 8 bytes`, immediately after header):

Same 8-byte node structure as ESVO:

```
Offset | Size | Field             | Type   | Description
-------|------|-------------------|--------|----------------------------------
  0    |  4   | childDescriptor   | int32  | Same bit layout as ESVO nodes
  4    |  4   | contourDescriptor | int32  | Same bit layout as ESVO nodes
```

**Contour Array** (`contourCount × 4 bytes`, immediately after nodes):

Each contour entry is one `int32` (little-endian).

**Far Pointer Array** (`farPtrCount × 4 bytes`, immediately after contours):

Each far pointer is one `int32` (little-endian).

**Total ESVT payload size** (uncompressed):
```
33 + (nodeCount × 8) + (contourCount × 4) + (farPtrCount × 4)  bytes
```

---

### Payload Parsing Walkthrough (ESVO)

```
1. Receive binary WebSocket frame (ArrayBuffer)
2. Check frame.length >= 24 (minimum: header only, empty payload)
3. Read magic at offset 0 (uint32 LE): must equal 0x45535652
4. Read dataSize at offset 20 (uint32 LE): expected payload length
5. Extract payload = frame[24 .. 24+dataSize]
6. Detect compression: payload[0]==0x1F && payload[1]==0x8B → decompress(payload)
7. Read payload version at offset 0 (uint8): must equal 0x01 for ESVO
8. Read nodeCount at offset 1 (uint32 LE)
9. Read farPtrCount at offset 17 (uint32 LE)
10. Parse nodeCount nodes starting at offset 21 (8 bytes each)
11. Parse farPtrCount far pointers starting at offset 21 + nodeCount*8 (4 bytes each)
```

---

## REST Endpoints

### GET /api/health

Health check with component status.

**Request:**
```http
GET /api/health HTTP/1.1
Host: server:7090
Authorization: Bearer YOUR_API_KEY_HERE
```

**Response (200 OK):**
```json
{
  "status": "healthy",
  "uptime": 123456,
  "regions": 42,
  "entities": 1567
}
```

**Fields:**
- `status` (string): Always "healthy" if server responding
- `uptime` (long): Milliseconds since server start
- `regions` (int): Number of active regions
- `entities` (int): Total entities across all regions

**Caching:** 1 second TTL (reduces monitoring overhead)

### GET /api/info

Server capabilities and configuration.

**Request:**
```http
GET /api/info HTTP/1.1
Host: server:7090
Authorization: Bearer YOUR_API_KEY_HERE
```

**Response (200 OK):**
```json
{
  "version": "1.0.0-SNAPSHOT",
  "port": 7090,
  "upstreamCount": 3,
  "regionLevel": 4,
  "gridResolution": 64,
  "maxBuildDepth": 8,
  "buildPoolSize": 4,
  "structureType": "ESVO",
  "worldMin": [-512.0, -512.0, -512.0],
  "worldMax": [512.0, 512.0, 512.0],
  "regionSize": 64.0,
  "regionsPerAxis": 16
}
```

**Fields:**
- `version` (string): Server version
- `port` (int): Actual listening port
- `upstreamCount` (int): Number of upstream simulation servers (if redactSensitiveInfo=true)
- `upstreams` (array): Upstream URIs and labels (if redactSensitiveInfo=false)
- `regionLevel` (int): Octree depth for regions (3-6)
- `gridResolution` (int): Voxel grid resolution per region
- `maxBuildDepth` (int): Maximum ESVO/ESVT tree depth
- `buildPoolSize` (int): Concurrent build threads
- `structureType` (string): "ESVO" or "ESVT"
- `worldMin` (float[3]): World bounding box min
- `worldMax` (float[3]): World bounding box max
- `regionSize` (float): Size of each region
- `regionsPerAxis` (int): 2^regionLevel

### GET /api/metrics

Performance metrics (builder, cache, rate limiter).

**Request:**
```http
GET /api/metrics HTTP/1.1
Host: server:7090
Authorization: Bearer YOUR_API_KEY_HERE
```

**Response (200 OK):**
```json
{
  "builder": {
    "totalBuilds": 12345,
    "failedBuilds": 3,
    "queueDepth": 5,
    "avgBuildTimeMs": 45.67
  },
  "cache": {
    "pinnedCount": 42,
    "unpinnedCount": 158,
    "totalCount": 200,
    "totalMemoryBytes": 134217728,
    "caffeineHitRate": 0.856,
    "caffeineMissRate": 0.144,
    "caffeineEvictionCount": 23,
    "memoryPressure": 0.524
  },
  "rateLimiter": {
    "rejectionCount": 17
  }
}
```

**Builder Fields:**
- `totalBuilds` (long): Total builds completed
- `failedBuilds` (long): Build failures
- `queueDepth` (int): Current build queue size
- `avgBuildTimeMs` (double): Average build time

**Cache Fields:**
- `pinnedCount` (int): Regions actively viewed
- `unpinnedCount` (int): Cached but not viewed
- `totalCount` (int): Total cached regions
- `totalMemoryBytes` (long): Current cache memory usage
- `caffeineHitRate` (double): Cache hit rate (0.0–1.0)
- `caffeineMissRate` (double): Cache miss rate (0.0–1.0)
- `caffeineEvictionCount` (long): Total evictions
- `memoryPressure` (double): Memory usage ratio (0.0–1.0)

**Rate Limiter Fields:**
- `rejectionCount` (long): Total requests rejected

**Caching:** 1 second TTL (reduces monitoring overhead)

## Error Responses

### 401 Unauthorized

Missing or invalid API key:

```json
{
  "error": "Unauthorized"
}
```

### 429 Too Many Requests

Rate limit exceeded:

```json
{
  "error": "Too Many Requests"
}
```

### 503 Service Unavailable

Components not available (during shutdown):

```json
{
  "error": "Service unavailable"
}
```

## Rate Limiting

### Global HTTP Rate Limiting

**Default:** Configurable requests/minute per client IP

**Headers:** None (429 response only)

**Enforcement:** All HTTP endpoints (/api/*)

### Per-Client WebSocket Rate Limiting

**Default:** 100 messages/second per WebSocket session

**Enforcement:** All JSON messages (REGISTER_CLIENT, UPDATE_VIEWPORT)

**Response:** ERROR message, connection remains open

### Auth Attempt Rate Limiting

**Default:** 3 failed attempts, 60 second lockout

**Enforcement:** WebSocket /ws/render authentication

**Response:** Close with 4003 (Unauthorized)

## Message Size Limits

**Default:** 64 KB per JSON message

**Counting:** UTF-8 byte-accurate (not character count)

**Enforcement:** All JSON messages

**Response:** Close with 4002 (Message size limit exceeded)

## Client Limits

**Default:** 1000 concurrent WebSocket clients

**Enforcement:** Atomic check during connection

**Response:** Close with 4001 (Server full)

## Backpressure

**Per-Client Pending Sends:** Default 100 binary frames

**Behavior:** Skip region delivery if limit exceeded

**Purpose:** Prevent memory exhaustion from slow clients

## Example Client Implementation (JavaScript)

```javascript
class RenderingClient {
  constructor(serverUrl, apiKey) {
    this.serverUrl = serverUrl;
    this.apiKey = apiKey;
    this.clientId = 'browser-' + Date.now();
    this.ws = null;
  }

  connect() {
    // WebSocket doesn't support custom headers, use query param or subprotocol
    // For production, use wss:// and implement authentication properly
    this.ws = new WebSocket(this.serverUrl, ['authorization', `Bearer ${this.apiKey}`]);

    this.ws.binaryType = 'arraybuffer';  // Required: receive binary as ArrayBuffer

    this.ws.onopen = () => {
      console.log('Connected to rendering server');
      this.register();
    };

    this.ws.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) {
        this.handleBinaryFrame(event.data);
      } else {
        this.handleJsonMessage(JSON.parse(event.data));
      }
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error);
    };

    this.ws.onclose = (event) => {
      console.log(`Connection closed: ${event.code} ${event.reason}`);
    };
  }

  register() {
    const message = {
      type: 'REGISTER_CLIENT',
      clientId: this.clientId,
      viewport: {
        eye: { x: 10.0, y: 5.0, z: 20.0 },
        lookAt: { x: 0.0, y: 0.0, z: 0.0 },
        up: { x: 0.0, y: 1.0, z: 0.0 },
        fovY: Math.PI / 3,        // 60 degrees in RADIANS — must be < Math.PI
        aspectRatio: window.innerWidth / window.innerHeight,
        nearPlane: 0.1,
        farPlane: 1000.0
      }
    };
    this.ws.send(JSON.stringify(message));
  }

  updateViewport(eye, lookAt, up, fovYRadians) {
    const message = {
      type: 'UPDATE_VIEWPORT',
      clientId: this.clientId,
      viewport: {
        eye: eye,
        lookAt: lookAt,
        up: up,
        fovY: fovYRadians,        // RADIANS, not degrees
        aspectRatio: window.innerWidth / window.innerHeight,
        nearPlane: 0.1,
        farPlane: 1000.0
      }
    };
    this.ws.send(JSON.stringify(message));
  }

  handleJsonMessage(message) {
    if (message.type === 'ERROR') {
      console.error('Server error:', message.message);
    }
  }

  handleBinaryFrame(buffer) {
    const view = new DataView(buffer);

    // --- Validate and parse 24-byte frame header (all fields little-endian) ---
    if (buffer.byteLength < 24) {
      console.error('Frame too short:', buffer.byteLength);
      return;
    }

    const magic = view.getUint32(0, true);           // bytes 0-3
    if (magic !== 0x45535652) {
      console.error('Invalid frame magic:', magic.toString(16));
      return;
    }

    const format       = view.getUint8(4);            // byte  4: 0x01=ESVO, 0x02=ESVT
    const lod          = view.getUint8(5);            // byte  5: LOD level (currently always 0)
    const level        = view.getUint8(6);            // byte  6: region octree depth
    // byte 7 is reserved
    const mortonCode   = view.getBigUint64(8, true);  // bytes 8-15: morton-encoded grid coords
    const buildVersion = view.getUint32(16, true);    // bytes 16-19: monotonic build counter
    const dataSize     = view.getUint32(20, true);    // bytes 20-23: payload byte count

    if (buffer.byteLength < 24 + dataSize) {
      console.error('Truncated frame: expected', 24 + dataSize, 'got', buffer.byteLength);
      return;
    }

    // Extract payload
    let payload = new Uint8Array(buffer, 24, dataSize);

    // Detect and decompress GZIP (magic bytes 0x1F 0x8B)
    if (payload.length >= 2 && payload[0] === 0x1F && payload[1] === 0x8B) {
      payload = decompressGzip(payload);  // implement using DecompressionStream API
    }

    console.log(
      `Region mortonCode=${mortonCode} level=${level} format=${format === 1 ? 'ESVO' : 'ESVT'}` +
      ` lod=${lod} buildVersion=${buildVersion} payloadBytes=${dataSize}`
    );

    if (format === 0x01) {
      this.parseESVOPayload(payload);
    } else if (format === 0x02) {
      this.parseESVTPayload(payload);
    }
  }

  parseESVOPayload(data) {
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    let offset = 0;

    const version       = view.getUint8(offset);     offset += 1;
    const nodeCount     = view.getUint32(offset, true); offset += 4;
    const maxDepth      = view.getUint32(offset, true); offset += 4;
    const leafCount     = view.getUint32(offset, true); offset += 4;
    const internalCount = view.getUint32(offset, true); offset += 4;
    const farPtrCount   = view.getUint32(offset, true); offset += 4;
    // offset is now 21

    // Read nodes (8 bytes each)
    const nodes = [];
    for (let i = 0; i < nodeCount; i++) {
      const childDescriptor   = view.getInt32(offset, true); offset += 4;
      const contourDescriptor = view.getInt32(offset, true); offset += 4;
      nodes.push({ childDescriptor, contourDescriptor });
    }

    // Read far pointers (4 bytes each)
    const farPointers = [];
    for (let i = 0; i < farPtrCount; i++) {
      farPointers.push(view.getInt32(offset, true)); offset += 4;
    }

    console.log(`ESVO: version=${version} nodes=${nodeCount} depth=${maxDepth}` +
                ` leaves=${leafCount} farPtrs=${farPtrCount}`);

    // TODO: traverse and render ESVO octree
    return { version, nodeCount, maxDepth, leafCount, internalCount, nodes, farPointers };
  }

  parseESVTPayload(data) {
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    let offset = 0;

    const version        = view.getUint8(offset);       offset += 1;
    const nodeCount      = view.getUint32(offset, true); offset += 4;
    const rootType       = view.getUint32(offset, true); offset += 4;
    const maxDepth       = view.getUint32(offset, true); offset += 4;
    const leafCount      = view.getUint32(offset, true); offset += 4;
    const internalCount  = view.getUint32(offset, true); offset += 4;
    const gridResolution = view.getUint32(offset, true); offset += 4;
    const contourCount   = view.getUint32(offset, true); offset += 4;
    const farPtrCount    = view.getUint32(offset, true); offset += 4;
    // offset is now 33

    // Read nodes (8 bytes each)
    const nodes = [];
    for (let i = 0; i < nodeCount; i++) {
      const childDescriptor   = view.getInt32(offset, true); offset += 4;
      const contourDescriptor = view.getInt32(offset, true); offset += 4;
      nodes.push({ childDescriptor, contourDescriptor });
    }

    // Read contours (4 bytes each)
    const contours = [];
    for (let i = 0; i < contourCount; i++) {
      contours.push(view.getInt32(offset, true)); offset += 4;
    }

    // Read far pointers (4 bytes each)
    const farPointers = [];
    for (let i = 0; i < farPtrCount; i++) {
      farPointers.push(view.getInt32(offset, true)); offset += 4;
    }

    console.log(`ESVT: version=${version} nodes=${nodeCount} rootType=${rootType}` +
                ` depth=${maxDepth} gridRes=${gridResolution} farPtrs=${farPtrCount}`);

    // TODO: traverse and render ESVT tetree
    return { version, nodeCount, rootType, maxDepth, leafCount, internalCount,
             gridResolution, nodes, contours, farPointers };
  }

  disconnect() {
    if (this.ws) {
      this.ws.close(1000, 'Client disconnect');
    }
  }
}

// GZIP decompression helper (requires modern browser with DecompressionStream API)
async function decompressGzip(compressedBytes) {
  const stream = new DecompressionStream('gzip');
  const writer = stream.writable.getWriter();
  writer.write(compressedBytes);
  writer.close();
  const chunks = [];
  const reader = stream.readable.getReader();
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    chunks.push(value);
  }
  const totalLength = chunks.reduce((acc, c) => acc + c.length, 0);
  const result = new Uint8Array(totalLength);
  let pos = 0;
  for (const chunk of chunks) { result.set(chunk, pos); pos += chunk.length; }
  return result;
}

// Usage
const client = new RenderingClient('ws://localhost:7090/ws/render', 'your-api-key');
client.connect();

// Update viewport on camera movement (fovY in radians)
function onCameraMove(eye, lookAt, up) {
  client.updateViewport(eye, lookAt, up, Math.PI / 3);  // 60°
}
```

## Protocol Versioning

**Current Version:** 1 (frame header buildVersion counter)

**Backward Compatibility:** Server supports only current version

**Future:** Version negotiation during WebSocket handshake

## Security Best Practices

1. **Always use TLS in production** (`wss://`, not `ws://`)
2. **Rotate API keys regularly** (recommended: 90 days)
3. **Monitor rate limiter metrics** (track rejectionCount)
4. **Set appropriate message size limits** (default 64KB may be too large for your use case)
5. **Configure client limits** based on server capacity

## Performance Tuning

1. **Adjust streamingIntervalMs** (default 100ms) based on latency requirements
2. **Tune maxPendingSendsPerClient** (default 100) for slow clients
3. **Configure regionCacheTtlMs** (default 60s) based on memory vs. build cost
4. **Set buildPoolSize** based on CPU cores (2–8 recommended)

---

**Document Version:** 1.1.0
**Last Updated:** 2026-02-18
