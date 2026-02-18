# Development Guide

**Version:** 1.0.0
**Date:** 2026-02-18
**Status:** Production-Ready

## Overview

This guide covers local development setup, testing patterns, and common development tasks for the Multi-Client WebSocket Streaming system.

## Project Layout

```
simulation/
  src/
    main/java/.../viz/render/
      RenderingServer.java          # Main coordinator
      RegionStreamer.java           # WebSocket session management
      RenderingServerConfig.java    # Configuration record
      ViewportTracker.java          # Frustum culling
      RegionCache.java              # Two-tier cache
      RegionBuilder.java            # GPU-accelerated ESVO builds
      AdaptiveRegionManager.java    # Region state + entity tracking
      EntityStreamConsumer.java     # Upstream WebSocket client
      ByteBufferPool.java           # GC-reducing buffer pool
      SecurityConfig.java           # Security sub-config
      CacheConfig.java              # Cache sub-config
      BuildConfig.java              # Build sub-config
      StreamingConfig.java          # Streaming sub-config
      PerformanceConfig.java        # Performance sub-config
      UpstreamConfig.java           # Upstream server config
      ClientViewport.java           # Camera/frustum definition
      ViewportDiff.java             # Added/removed/LOD-changed
      VisibleRegion.java            # Region + LOD level
      RegionId.java                 # Spatial identifier
      RegionBounds.java             # AABB bounds for region
      EntityPosition.java           # x,y,z + type
      SparseStructureType.java      # ESVO vs ESVT enum
      RateLimiter.java              # HTTP rate limiter
      ClientRateLimiter.java        # Per-client WS rate limiter
      AuthAttemptRateLimiter.java   # Brute-force protection
      RateLimitExceededException.java
      SerializationUtils.java       # Safe JSON helpers
      protocol/
        BinaryFrameCodec.java       # Frame encode/decode
        ProtocolConstants.java      # Protocol version, error codes

    test/java/.../viz/render/
      RegionStreamerTest.java
      ViewportTrackerTest.java
      RegionCacheTest.java
      RenderingServerIntegrationTest.java
      RenderingServerStreamingTest.java
      BuildIntegrationTest.java
      RenderingServerAuthTest.java
      RegionStreamerJsonInjectionTest.java
      RegionStreamerValidationTest.java
      RegionStreamerConcurrencyTest.java
      RegionStreamerBatchingBenchmark.java
      RegionStreamerPoolingBenchmark.java

  doc/viz-render/
    README.md                       # Quick start and overview
    ARCHITECTURE.md                 # System design (this repo)
    API.md                          # WebSocket + REST protocol
    SECURITY.md                     # Security architecture
    PERFORMANCE.md                  # Performance design
    CONFIGURATION.md                # Configuration reference
    DEVELOPMENT.md                  # This file
    CODE_REVIEW_IMPROVEMENTS.md     # Post-review changes log
```

## Running Tests

```bash
# All tests
mvn test -pl simulation

# Specific test class
mvn test -pl simulation -Dtest=RegionStreamerTest

# Skip surefire retries (faster local iteration)
mvn test -pl simulation -Dtest=RegionStreamerTest -Dsurefire.rerunFailingTestsCount=0

# Integration tests
mvn verify -pl simulation

# Performance benchmarks
mvn test -pl simulation -Dtest=RegionStreamerBatchingBenchmark -Pperformance
```

## Testing Patterns

### Pattern 1: WsContextWrapper for WebSocket Testing

`RegionStreamer` is designed to avoid Mockito (which has issues with Javalin's final methods). Instead, `WsContextWrapper` is an interface with a fake implementation for tests:

```java
interface WsContextWrapper {
    String sessionId();
    void send(String message);
    void sendBinary(ByteBuffer data);
    void closeSession(int statusCode, String reason);
}
```

**Public methods delegate to internal testable methods:**

```java
// Production: called by Javalin
public void onConnect(WsContext ctx) {
    onConnectInternal(WsContextWrapper.wrap(ctx));
}

// Test: called directly with fake context
void onConnectInternal(WsContextWrapper ctx) { ... }
```

**Test fake:**

```java
class FakeWsContext implements WsContextWrapper {
    final String id;
    final List<String> sentMessages = new ArrayList<>();
    final List<ByteBuffer> sentFrames = new ArrayList<>();
    int closeCode = -1;
    String closeReason;

    FakeWsContext(String id) { this.id = id; }

    @Override public String sessionId() { return id; }
    @Override public void send(String msg) { sentMessages.add(msg); }
    @Override public void sendBinary(ByteBuffer data) { sentFrames.add(data.duplicate()); }
    @Override public void closeSession(int code, String reason) {
        this.closeCode = code;
        this.closeReason = reason;
    }
}
```

### Pattern 2: Clock Injection for Deterministic Tests

All time-dependent components use the `Clock` interface instead of `System.currentTimeMillis()`:

```java
private volatile Clock clock = Clock.system();

public void setClock(Clock clock) { this.clock = clock; }

// Usage:
long now = clock.currentTimeMillis();
```

**Test usage:**

```java
var testClock = new TestClock();
testClock.setTime(1000L);

var streamer = new RegionStreamer(...);
streamer.setClock(testClock);

// Test time-dependent behavior
testClock.setTime(2000L);  // Fast-forward 1 second
```

**Affected components:**
- `RegionStreamer` — rate limiter timestamps, flush timeout
- `RegionBuilder` — build time tracking
- `ViewportTracker` — viewport update timestamps
- `RegionCache` — access timestamps (Caffeine TTL still uses system time internally)
- `AuthAttemptRateLimiter` — lockout expiration
- `EntityStreamConsumer` — circuit breaker timeout, backoff timing

**Note:** `RegionCache.setClock()` controls `lastAccessedMs` tracking, but Caffeine's `expireAfterAccess(ttl)` uses system time. TTL expiration tests still require `Thread.sleep()`.

### Pattern 3: Package-Private Fields for Benchmarks

Internal fields used by benchmarks are package-private (not private):

```java
// RegionStreamer.java
final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
final ByteBufferPool bufferPool;

// Package-private methods for benchmarks
void streamingCycle() { ... }
void sendBinaryFrameAsync(ClientSession session, RegionId id, BuiltRegion region) { ... }
```

This avoids reflection in benchmarks while maintaining encapsulation from external packages.

### Pattern 4: Dynamic Ports for Integration Tests

Always use port 0 (dynamic assignment) in tests:

```java
var config = RenderingServerConfig.testing();  // port = 0
var server = new RenderingServer(config);
server.start();
int port = server.port();  // Actual assigned port

var wsClient = new WebSocket("ws://localhost:" + port + "/ws/render");
```

**Never hardcode a port number in tests** — this causes flaky failures from port conflicts in CI.

### Pattern 5: Build Integration Testing

`BuildIntegrationTest` uses a mock `RegionBuilder` to verify callback wiring without GPU:

```java
class MockRegionBuilder extends RegionBuilder {
    final BlockingQueue<RegionId> buildRequests = new LinkedBlockingQueue<>();
    Consumer<BuiltRegion> callback;

    @Override
    public CompletableFuture<BuiltRegion> build(RegionId id, List<EntityPosition> entities) {
        buildRequests.add(id);
        var future = new CompletableFuture<BuiltRegion>();
        // Complete asynchronously in test
        return future;
    }
}
```

## Writing New Tests

### Unit Test Checklist

- [ ] Use `FakeWsContext` (not Mockito) for WebSocket testing
- [ ] Inject `TestClock` for any time-dependent behavior
- [ ] Use `RenderingServerConfig.testing()` for server instances
- [ ] Assert on `FakeWsContext.sentMessages` and `sentFrames`
- [ ] Verify error codes on `FakeWsContext.closeCode`

### Concurrency Test Pattern

```java
@Test
void testConcurrentClientOperations() throws Exception {
    var streamer = new RegionStreamer(...);
    var executor = Executors.newFixedThreadPool(10);
    var latch = new CountDownLatch(100);

    for (int i = 0; i < 100; i++) {
        final int id = i;
        executor.submit(() -> {
            var ctx = new FakeWsContext("session-" + id);
            streamer.onConnectInternal(ctx);
            streamer.onMessageInternal(ctx, buildRegisterMessage(id));
            latch.countDown();
        });
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(100, streamer.sessions.size());
    executor.shutdown();
}
```

### Flaky Test Policy

Use `@DisabledIfEnvironmentVariable` for tests that have inherent non-determinism that cannot be eliminated with Clock injection:

```java
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Flaky: streaming latency varies with CI runner speed"
)
@Test
void testStreamingLatency() { ... }
```

See `TEST_FRAMEWORK_GUIDE.md` for full policy.

## Adding a New Configuration Parameter

1. Add field to appropriate sub-record (`SecurityConfig`, `CacheConfig`, etc.)
2. Update `defaults()`, `testing()`, and `secureDefaults()` factory methods
3. Add builder/`with*` method for fluent configuration
4. Update the configuration table in `CONFIGURATION.md`
5. Add to the test config with a smaller/faster test value

## Adding a New Component

1. Create the class with Clock injection: `private volatile Clock clock = Clock.system()`
2. Add `setClock(Clock)` method for test injection
3. Register in `RenderingServer` constructor and `setClock()` propagation
4. Add to the Mermaid diagram in `ARCHITECTURE.md`
5. Add component description section to `ARCHITECTURE.md`
6. Add file reference to the README component list

## Development Workflow

1. **Find work:** `bd ready`
2. **Read the issue:** `bd show <id>`
3. **Mark in progress:** `bd update <id> --status in_progress`
4. **Write failing test first** (TDD) — see Testing Patterns above
5. **Implement the change**
6. **Run tests:** `mvn test -pl simulation -Dsurefire.rerunFailingTestsCount=0`
7. **Close issue:** `bd close <id>`
8. **Sync and push:** `bd sync && git push`

## Common Pitfalls

| Pitfall | Correct Approach |
|---------|-----------------|
| Using `System.currentTimeMillis()` | Use `clock.currentTimeMillis()` |
| Hardcoding port 7090 in tests | Use `RenderingServerConfig.testing()` (port 0) |
| Using Mockito on Javalin types | Use `FakeWsContext` interface |
| `ConcurrentHashMap` for auth limiters | Caffeine cache with expiration |
| Forgetting to return ByteBuffer to pool | Always `pool.returnBuffer(buf)` after send |
| Synchronized on `this` in RegionStreamer | Synchronize on `ClientSession` only |

## References

- **Architecture Overview:** `ARCHITECTURE.md`
- **API Documentation:** `API.md`
- **Security Architecture:** `SECURITY.md`
- **Performance Design:** `PERFORMANCE.md`
- **Configuration Guide:** `CONFIGURATION.md`
- **Test Framework Guide:** `../../TEST_FRAMEWORK_GUIDE.md`
- **Determinism Architecture:** `../H3_DETERMINISM_EPIC.md`

---

**Document Version:** 1.0.0
**Last Updated:** 2026-02-18
**Maintained By:** Rendering Team
**Review Cycle:** Quarterly
