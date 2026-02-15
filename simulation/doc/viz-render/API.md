# WebSocket Streaming API Documentation

**Version:** 1.0.0
**Date:** 2026-02-15

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
    "fovY": 60.0,
    "aspectRatio": 1.777777,
    "nearPlane": 0.1,
    "farPlane": 1000.0
  }
}
```

**Fields:**
- `clientId` (string, required): Unique client identifier
- `viewport` (object, required): Camera configuration
  - `eye` (Point3f, required): Camera position
  - `lookAt` (Point3f, required): Target point
  - `up` (Vector3f, required): Up direction
  - `fovY` (float, required): Vertical field of view (degrees)
  - `aspectRatio` (float, required): Width/height ratio
  - `nearPlane` (float, required): Near clipping plane
  - `farPlane` (float, required): Far clipping plane

**Response:** Session transitions to STREAMING state, binary frames begin

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
    "fovY": 60.0,
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
- `"Invalid viewport: missing eye.x"`
- `"Rate limit exceeded"`

### Binary Messages (Server → Client)

Binary WebSocket frames containing ESVO/ESVT region data.

**Frame Format:**
```
+------------------+
| Frame Header     | 20 bytes
+------------------+
| Serialized Data  | Variable
+------------------+
```

**Frame Header (20 bytes):**
- Version (4 bytes): Protocol version
- Region ID (8 bytes): X, Y, Z coordinates (packed)
- LOD Level (4 bytes): Level of detail
- Data Length (4 bytes): Serialized data size

**Serialized Data:**
- ESVO/ESVT voxel structure (format TBD by RegionBuilder)

**Delivery:**
- Frames buffered (up to 10 messages)
- Flushed on threshold or 50ms timeout
- Immediate delivery for build completion callbacks

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
- `maxBuildDepth` (int): Maximum ESVO tree depth
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
- `caffeineHitRate` (double): Cache hit rate (0.0-1.0)
- `caffeineMissRate` (double): Cache miss rate (0.0-1.0)
- `caffeineEvictionCount` (long): Total evictions
- `memoryPressure` (double): Memory usage ratio (0.0-1.0)

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
    this.ws = null;
  }

  connect() {
    // WebSocket doesn't support custom headers, use query param or subprotocol
    // For production, use wss:// and implement authentication properly
    this.ws = new WebSocket(this.serverUrl, ['authorization', `Bearer ${this.apiKey}`]);

    this.ws.onopen = () => {
      console.log('Connected to rendering server');
      this.register();
    };

    this.ws.onmessage = (event) => {
      if (event.data instanceof Blob) {
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
      clientId: 'browser-' + Date.now(),
      viewport: {
        eye: { x: 10.0, y: 5.0, z: 20.0 },
        lookAt: { x: 0.0, y: 0.0, z: 0.0 },
        up: { x: 0.0, y: 1.0, z: 0.0 },
        fovY: 60.0,
        aspectRatio: window.innerWidth / window.innerHeight,
        nearPlane: 0.1,
        farPlane: 1000.0
      }
    };
    this.ws.send(JSON.stringify(message));
  }

  updateViewport(eye, lookAt, up) {
    const message = {
      type: 'UPDATE_VIEWPORT',
      clientId: this.clientId,
      viewport: {
        eye: eye,
        lookAt: lookAt,
        up: up,
        fovY: 60.0,
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

  async handleBinaryFrame(blob) {
    const buffer = await blob.arrayBuffer();
    const view = new DataView(buffer);

    // Parse frame header
    const version = view.getUint32(0, true);
    const regionId = view.getBigUint64(4, true);  // Packed X,Y,Z
    const lodLevel = view.getUint32(12, true);
    const dataLength = view.getUint32(16, true);

    // Extract serialized data
    const data = buffer.slice(20);

    console.log(`Received region ${regionId} LOD ${lodLevel}: ${dataLength} bytes`);

    // TODO: Decode ESVO/ESVT structure and render
  }

  disconnect() {
    if (this.ws) {
      this.ws.close(1000, 'Client disconnect');
    }
  }
}

// Usage
const client = new RenderingClient('ws://localhost:7090/ws/render', 'your-api-key');
client.connect();

// Update viewport on camera movement
function onCameraMove(eye, lookAt, up) {
  client.updateViewport(eye, lookAt, up);
}
```

## Protocol Versioning

**Current Version:** 1 (Frame header version field)

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
4. **Set buildPoolSize** based on GPU concurrency and CPU cores

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-15
