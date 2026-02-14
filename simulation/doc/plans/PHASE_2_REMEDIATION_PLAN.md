# Phase 2 Code Review Remediation Plan

**Epic**: Luciferase-wbvn (Phase 2 Code Review Remediation Planning)
**Parent Epic**: Luciferase-zq0j (Address all Phase 2 code review findings)
**Date**: 2026-02-14
**Status**: PLANNED

## Executive Summary

This plan addresses 18 findings from the comprehensive Phase 2 GPU Integration code review.
The findings span security vulnerabilities (5 P0), correctness/robustness issues (7 P1),
test coverage gaps (1 P2), and optimization suggestions (4 P3). Work is organized into
4 execution phases over approximately 8-9 development days, prioritizing security and
correctness before performance and polish.

**Total Effort**: ~35-45 hours across 18 beads
**Critical Path**: 7jjx (auth) -> jc5f (TLS) = 2-3 days
**Parallelizable**: 60% of work can be done in parallel within phases

---

## Dependency Graph

```
PHASE 1: Foundation & Quick Wins
  h86g (pin cleanUp)           [XS] ---> bkji (forceAccurate) [Phase 3]
                                    \--> ko4u (pinned access)  [Phase 4]
  dtnt (defensive copy)        [XS] ---> sg70 (circuit test)   [Phase 4]
  bjjn (input validation)      [S]  (independent)
  8nbh (pool validation)       [XS] (independent)
  oqh3 (config extraction)     [M]  (independent, reduces churn)

PHASE 2: Security Hardening
  1sa4 (info redaction)        [S]  (independent)
  w1tk (rate limiting)         [S]  ---> rp9u (cache responses) [Phase 4]
  7jjx (authentication)        [M-L] --> jc5f (TLS)            [Phase 2]
  jc5f (TLS/HTTPS)             [L]  (blocked by 7jjx)

PHASE 3: Performance & Robustness
  bkji (forceAccurate)         [S]  (blocked by h86g) ---> rp9u [Phase 4]
  xox5 (queue depth check)     [S]  (independent)
  vtet (entity count limit)    [S]  (independent)
  mauo (min-heap eviction)     [M]  (independent)

PHASE 4: Polish & Testing
  sg70 (circuit breaker test)  [M]  (blocked by dtnt)
  rp9u (cache responses)       [S]  (blocked by bkji, w1tk)
  ko4u (pinned access optim.)  [S]  (blocked by h86g)
  8chv (TestClock simplify)    [M]  (independent but risky)
```

---

## Phase 1: Foundation & Quick Wins (Days 1-2)

**Goal**: Fix correctness bugs, establish configuration foundation, build momentum.
**Completion Criteria**: All 5 beads closed, `mvn test -pl simulation` passes.

### 1. Luciferase-h86g: Add cleanUp() to RegionCache.pin() [XS - 30 min]

**Priority**: P1 (Bug Fix)
**Component**: RegionCache.java
**Risk**: LOW - single line addition with clear semantics

**Problem**: `pin()` calls `unpinnedCache.invalidate()` but not `cleanUp()`, so Caffeine's
`weightedSize` is stale until the next async maintenance cycle. This causes temporary
double-counting when `getTotalMemoryBytes()` is called immediately after `pin()`.

**Implementation**:
- Add `unpinnedCache.cleanUp()` after `invalidate()` on line 172 of RegionCache.java
- This forces Caffeine to update its internal weight tracking immediately

**Acceptance Criteria**:
- [ ] `unpinnedCache.cleanUp()` called after `invalidate()` in `pin()` method
- [ ] New test in RegionCacheTest: pin a region, immediately call getTotalMemoryBytes(), verify no double-counting
- [ ] Existing RegionCacheTest tests still pass
- [ ] Javadoc updated to document the cleanUp() call

**Test Requirements**:
- Unit test: `testPinImmediateMemoryAccuracy()` - put region, pin it, verify totalMemoryBytes equals region size (not 2x)
- Regression: Run full RegionCacheTest suite

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (line 172)
- `simulation/src/test/java/.../RegionCacheTest.java` (new test)

---

### 2. Luciferase-dtnt: Add defensive copy to BuildRequest.positions [XS - 30 min]

**Priority**: P1 (Bug Fix)
**Component**: RegionBuilder.java
**Risk**: LOW - straightforward immutability fix

**Problem**: `BuildRequest` record stores `List<Point3f> positions` without defensive copy.
Callers can mutate the list after construction, violating record immutability.

**Implementation**:
- Add compact constructor to BuildRequest record with `List.copyOf(positions)`
- This creates an unmodifiable copy, preventing external mutation

**Acceptance Criteria**:
- [ ] BuildRequest compact constructor calls `List.copyOf(positions)`
- [ ] Test that modifying original list after BuildRequest construction has no effect
- [ ] Existing BuildIntegrationTest tests still pass
- [ ] Verify `positionsToVoxels()` works with the unmodifiable list

**Test Requirements**:
- Unit test: Create BuildRequest, modify original list, verify request.positions() unchanged
- Regression: Run BuildIntegrationTest suite

**Files**:
- `simulation/src/main/java/.../RegionBuilder.java` (BuildRequest record, ~line 393)
- `simulation/src/test/java/.../BuildIntegrationTest.java` (verify no list reuse issues)

---

### 3. Luciferase-bjjn: Add input validation for entity updates [S - 1-2 hours]

**Priority**: P0 (Security)
**Component**: AdaptiveRegionManager.java
**Risk**: LOW - additive validation, no existing logic changed

**Problem**: `updateEntity()` accepts any float values including NaN, Infinity, and negative
coordinates. Malicious or buggy upstream data could poison the region grid.

**Implementation**:
- Add validation at start of `updateEntity()`:
  - Reject NaN/Inf coordinates: `Float.isNaN(x) || Float.isInfinite(x)` -> IllegalArgumentException
  - Validate entity ID: non-null, non-empty, max 256 characters
  - Validate entity type: non-null, non-empty
- Clamp coordinates to world bounds (already done by regionForPosition, but validate early)

**Acceptance Criteria**:
- [ ] NaN coordinates rejected with IllegalArgumentException
- [ ] Infinite coordinates rejected with IllegalArgumentException
- [ ] Null/empty entityId rejected with IllegalArgumentException
- [ ] EntityId > 256 chars rejected with IllegalArgumentException
- [ ] Null/empty type rejected with IllegalArgumentException
- [ ] Valid coordinates still work (regression)
- [ ] Javadoc documents validation rules

**Test Requirements**:
- `testUpdateEntityNanCoordinates()` - verify IllegalArgumentException
- `testUpdateEntityInfiniteCoordinates()` - verify IllegalArgumentException
- `testUpdateEntityNullId()` - verify IllegalArgumentException
- `testUpdateEntityEmptyId()` - verify IllegalArgumentException
- `testUpdateEntityLongId()` - verify IllegalArgumentException at 257 chars
- `testUpdateEntityNullType()` - verify IllegalArgumentException
- `testUpdateEntityValidInput()` - verify acceptance (regression)

**Files**:
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (updateEntity method)
- `simulation/src/test/java/.../AdaptiveRegionManagerTest.java` (new or extended)

---

### 4. Luciferase-8nbh: Add thread pool size validation in RegionBuilder [XS - 30 min]

**Priority**: P3 (Suggestion)
**Component**: RegionBuilder.java
**Risk**: LOW - constructor guard clause

**Problem**: RegionBuilder constructor accepts any int for `buildPoolSize` including 0 and
negative values, which would create an invalid thread pool.

**Implementation**:
- Add validation at start of constructor:
  - `if (buildPoolSize < 1) throw new IllegalArgumentException(...)`
  - Log warning if `buildPoolSize > Runtime.getRuntime().availableProcessors()`
  - Also validate: `maxQueueDepth >= 1`, `maxDepth >= 1`, `gridResolution >= 1`

**Acceptance Criteria**:
- [ ] `buildPoolSize < 1` throws IllegalArgumentException
- [ ] `buildPoolSize > availableProcessors` logs warning (not error)
- [ ] `maxQueueDepth < 1` throws IllegalArgumentException
- [ ] `maxDepth < 1` throws IllegalArgumentException
- [ ] `gridResolution < 1` throws IllegalArgumentException
- [ ] Valid parameters still work (regression)

**Test Requirements**:
- `testInvalidPoolSize()` - zero and negative
- `testExcessivePoolSize()` - verify warning logged (not exception)
- `testInvalidQueueDepth()` - zero and negative
- Regression: existing RegionBuilder tests still pass

**Files**:
- `simulation/src/main/java/.../RegionBuilder.java` (constructor, ~line 68)
- `simulation/src/test/java/.../RegionBuilderTest.java` (new or extended)

---

### 5. Luciferase-oqh3: Move hardcoded config to RenderingServerConfig [M - 3-4 hours]

**Priority**: P1 (Chore)
**Component**: RenderingServerConfig.java, AdaptiveRegionManager.java, RenderingServer.java, RegionBuilder.java
**Risk**: MEDIUM - touches 4 files, must maintain backward compatibility

**Problem**: Several values are hardcoded:
- World bounds `0.0f` / `1024.0f` in AdaptiveRegionManager (line 76-77)
- `maxQueueDepth = 100` in RenderingServer (line 101)
- Circuit breaker timeout `60_000` in RegionBuilder.CircuitBreakerState (line 470)
- Circuit breaker failure threshold `3` in CircuitBreakerState (line 469)

**IMPORTANT - Constructor Compatibility**: RenderingServerConfig is a Java record with a
positional constructor. Adding 5 fields BREAKS the existing 9-parameter constructor. All
code that calls `new RenderingServerConfig(...)` directly must be updated. The `defaults()`
and `testing()` factory methods are updated here. Search for ALL call sites with:
`grep -rn "new RenderingServerConfig(" simulation/src/`

**Implementation**:
- Add fields to RenderingServerConfig:
  - `float worldMin` (default: 0.0f)
  - `float worldMax` (default: 1024.0f)
  - `int maxQueueDepth` (default: 100)
  - `long circuitBreakerTimeoutMs` (default: 60000)
  - `int circuitBreakerFailureThreshold` (default: 3)
- Update AdaptiveRegionManager constructor to use config values
- Update RenderingServer.start() to pass config.maxQueueDepth()
- Update RegionBuilder to accept circuit breaker config (or pass via constructor)
- Update defaults() and testing() factory methods
- **Search and update ALL direct constructor call sites** (not just factory methods)

**NOTE - CircuitBreakerState Thread Safety**: The existing CircuitBreakerState has
unsynchronized fields (consecutiveFailures, lastFailureTime) but is stored in a
ConcurrentHashMap. Current usage relies on map-level atomicity from compute operations.
If extracting config values, do NOT change the concurrency model - just parameterize the
constants. Thread safety is a separate concern tracked outside this plan.

**Acceptance Criteria**:
- [ ] All 5 config fields added to RenderingServerConfig
- [ ] AdaptiveRegionManager uses config.worldMin()/worldMax()
- [ ] RenderingServer passes config.maxQueueDepth() to RegionBuilder
- [ ] CircuitBreakerState uses configurable timeout and threshold
- [ ] defaults() returns original hardcoded values (backward compatible)
- [ ] testing() returns appropriate test values
- [ ] ALL direct RenderingServerConfig constructor call sites updated
- [ ] All existing tests pass without modification
- [ ] New tests verify non-default config values are honored

**Test Requirements**:
- `testCustomWorldBounds()` - verify non-default bounds work
- `testCustomQueueDepth()` - verify builder gets configured depth
- `testCustomCircuitBreakerConfig()` - verify timeout/threshold honored
- Regression: ALL simulation module tests pass

**Files**:
- `simulation/src/main/java/.../RenderingServerConfig.java` (new fields)
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (use config)
- `simulation/src/main/java/.../RenderingServer.java` (pass config values)
- `simulation/src/main/java/.../RegionBuilder.java` (accept CB config)
- All test files (regression)

---

## Phase 2: Security Hardening (Days 3-5)

**Goal**: Close all P0 security vulnerabilities.
**Completion Criteria**: All 4 security beads closed, no unauthenticated access to sensitive
endpoints, all traffic encryptable.

### 6. Luciferase-1sa4: Redact sensitive information from /api/info endpoint [S - 1 hour]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: LOW - output filtering only

**Problem**: `/api/info` exposes upstream URIs, memory limits, and internal configuration
details. This enables reconnaissance for targeted attacks.

**Implementation**:
- Add `boolean redactSensitiveInfo` to RenderingServerConfig (default: true in production)
- When redacted:
  - Replace upstream URIs with count only: `"upstreamCount": 3`
  - Replace maxCacheMemoryBytes with percentage: `"cacheUtilization": 45.2`
  - Remove buildPoolSize, maxBuildDepth, gridResolution
- Keep version, port, regionLevel, regionsPerAxis (non-sensitive)

**Acceptance Criteria**:
- [ ] New config flag `redactSensitiveInfo` (default: true)
- [ ] Upstream URIs hidden when redacted (count shown instead)
- [ ] Memory limits hidden when redacted (percentage shown instead)
- [ ] Internal config details hidden when redacted
- [ ] Testing config sets redact=false for backward-compatible tests
- [ ] New test verifies redaction works correctly

**Test Requirements**:
- `testInfoEndpointRedacted()` - verify sensitive fields absent, safe fields present
- `testInfoEndpointUnredacted()` - verify all fields present (test mode)
- Regression: existing RenderingServerTest passes

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (handleInfo method)
- `simulation/src/main/java/.../RenderingServerConfig.java` (new flag)
- `simulation/src/test/java/.../RenderingServerTest.java` (new + updated tests)

---

### 7. Luciferase-w1tk: Add rate limiting to RenderingServer REST endpoints [S - 2 hours]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: LOW - Javalin has built-in rate limiting support

**Problem**: Repeated `/api/metrics` calls force expensive `cleanUp()` operations. Without
rate limiting, a single client can DoS the server.

**Implementation**:
- Add Javalin rate limiting middleware (javalin-rate-limiter or custom)
- Configuration in RenderingServerConfig:
  - `int rateLimitRequestsPerMinute` (default: 100)
  - `boolean rateLimitEnabled` (default: true)
- Apply rate limiting to all `/api/*` endpoints
- Return HTTP 429 Too Many Requests when exceeded
- Disable in test configuration for simpler testing

**Acceptance Criteria**:
- [ ] Rate limiting middleware applied to /api/* endpoints
- [ ] Returns 429 when limit exceeded
- [ ] Limit configurable via RenderingServerConfig
- [ ] Disabled in testing() config
- [ ] Rate limit headers included in response (X-RateLimit-Remaining, X-RateLimit-Reset)
- [ ] Test validates rate limiting triggers at configured threshold

**Test Requirements**:
- `testRateLimitTriggered()` - send 101 requests, verify 429 on last
- `testRateLimitHeaders()` - verify X-RateLimit-* headers present
- `testRateLimitDisabled()` - verify no 429 when disabled
- Regression: existing tests pass (rate limiting disabled in test config)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (middleware setup)
- `simulation/src/main/java/.../RenderingServerConfig.java` (rate limit config)
- `simulation/src/test/java/.../RenderingServerTest.java` (new tests)
- `simulation/pom.xml` (if new dependency needed)

---

### 8. Luciferase-7jjx: Add authentication to RenderingServer REST endpoints [M-L - 4-6 hours]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: HIGH - authentication middleware must not break existing functionality

**Problem**: All REST endpoints are open with no authentication. Anyone with network access
can query server status, metrics, and eventually stream rendered data.

**Implementation**:
- Phase A: API key authentication (simpler, immediate value)
  - Add `String apiKey` to RenderingServerConfig (null = no auth)
  - Javalin before() handler checks Authorization header
  - `Authorization: Bearer <api-key>` format
  - Return 401 Unauthorized if key missing/invalid
- Phase B (future): JWT token support (separate bead if needed)
- Exempt /api/health from auth (load balancer health checks)
- **Edge case**: Empty string apiKey should be treated same as null (no auth).
  Validate with: `apiKey == null || apiKey.isBlank()` -> auth disabled

**Acceptance Criteria**:
- [ ] API key configured in RenderingServerConfig
- [ ] /api/info requires valid API key (401 without)
- [ ] /api/metrics requires valid API key (401 without)
- [ ] /api/health exempt from auth (for load balancers)
- [ ] /ws/render requires valid API key in query param or header
- [ ] Null or blank apiKey in config disables auth (backward compatible)
- [ ] Testing config has null apiKey (no auth for tests)
- [ ] Production config requires non-null, non-blank apiKey

**Test Requirements**:
- `testAuthRequiredForInfo()` - 401 without key
- `testAuthRequiredForMetrics()` - 401 without key
- `testHealthExemptFromAuth()` - 200 without key
- `testAuthAccepted()` - 200 with valid key
- `testAuthRejected()` - 401 with invalid key
- Regression: existing tests pass (auth disabled in test config)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (auth middleware)
- `simulation/src/main/java/.../RenderingServerConfig.java` (apiKey field)
- `simulation/src/test/java/.../RenderingServerTest.java` (auth tests)
- `simulation/src/test/java/.../RenderingServerIntegrationTest.java` (update if needed)

---

### 9. Luciferase-jc5f: Add TLS/HTTPS support to RenderingServer [L - 6-8 hours]

**Priority**: P0 (Security)
**Dependency**: Luciferase-7jjx (authentication should be in place first)
**Component**: RenderingServer.java
**Risk**: HIGH - SSL configuration complexity, cert management, test infrastructure

**Problem**: All REST and WebSocket traffic is unencrypted clear text. Sensitive data
(entity positions, server configuration) can be intercepted.

**Implementation**:
- Add TLS configuration to RenderingServerConfig:
  - `String keystorePath` (null = no TLS)
  - `String keystorePassword`
  - `String keyManagerPassword`
  - `boolean tlsEnabled` (default: false)
- Configure Javalin's embedded Jetty with SSL:
  - `SslContextFactory.Server` for HTTPS
  - Optional HTTP->HTTPS redirect
- Self-signed certificate generation utility for development
- WebSocket secure (wss://) support

**Acceptance Criteria**:
- [ ] TLS configuration fields in RenderingServerConfig
- [ ] HTTPS endpoints work with valid keystore
- [ ] WSS (secure WebSocket) works
- [ ] HTTP fallback when TLS not configured
- [ ] Self-signed cert utility for development/testing
- [ ] Test with self-signed certificate validates TLS works
- [ ] Null keystorePath disables TLS (backward compatible)
- [ ] Javadoc documents TLS setup procedure

**Test Requirements**:
- `testTlsEndpoints()` - HTTPS health check with self-signed cert
- `testWssWebSocket()` - secure WebSocket connection
- `testHttpFallback()` - verify HTTP still works when TLS disabled
- `testTlsHandshakeFailure()` - verify rejection of invalid certs
- Regression: all existing tests pass (TLS disabled by default)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (SSL config)
- `simulation/src/main/java/.../RenderingServerConfig.java` (TLS fields)
- `simulation/src/test/java/.../RenderingServerTest.java` (TLS tests)
- `simulation/src/test/resources/test-keystore.jks` (test certificate)

---

## Phase 3: Performance & Robustness (Days 6-7)

**Goal**: Fix performance bottlenecks and add robustness guards.
**Completion Criteria**: All 4 beads closed, emergency eviction O(n+k log n), queue protected.

### 10. Luciferase-bkji: Add forceAccurate parameter to getUnpinnedMemoryBytes() [S - 1-2 hours]

**Priority**: P1 (Performance)
**Dependency**: Luciferase-h86g (understand cleanUp semantics first)
**Component**: RegionCache.java
**Risk**: LOW - method overload, backward compatible

**Problem**: `getUnpinnedMemoryBytes()` calls `cleanUp()` every time. This is expensive
for high-frequency monitoring via `/api/metrics` but necessary for critical decisions
like emergency eviction.

**Implementation**:
- Add `getUnpinnedMemoryBytes(boolean forceAccurate)` overload
- When `forceAccurate=true`: call `cleanUp()` first (current behavior)
- When `forceAccurate=false`: read `weightedSize` directly (stale OK for monitoring)
- Update call sites:
  - `getStats()`: use `forceAccurate=false` (monitoring)
  - `emergencyEvict()`: use `forceAccurate=true` (critical decision)
  - `getTotalMemoryBytes()`: keep `forceAccurate=true` (default behavior)

**Acceptance Criteria**:
- [ ] New overload `getUnpinnedMemoryBytes(boolean forceAccurate)`
- [ ] Existing no-arg method delegates to `forceAccurate=true` (backward compatible)
- [ ] `getStats()` uses `forceAccurate=false`
- [ ] `emergencyEvict()` uses `forceAccurate=true`
- [ ] Test validates both paths return reasonable values
- [ ] Javadoc explains when to use each mode

**Test Requirements**:
- `testForceAccurateTrueCallsCleanUp()` - verify cleanUp behavior
- `testForceAccurateFalseSkipsCleanUp()` - verify no cleanUp (may return stale value)
- Regression: existing cache tests pass

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (new overload + call site updates)
- `simulation/src/test/java/.../RegionCacheTest.java` (new tests)

---

### 11. Luciferase-xox5: Add queue depth check to backfillDirtyRegions() [S - 1 hour]

**Priority**: P1 (Bug Fix)
**Component**: AdaptiveRegionManager.java
**Risk**: LOW - additive guard clause

**Problem**: `backfillDirtyRegions()` submits builds for ALL dirty regions without checking
queue capacity. With thousands of dirty regions, this saturates the build queue.

**Implementation**:
- Check `builder.getQueueDepth()` before each `scheduleBuild()` call
- Skip if queue depth > `maxQueueDepth * 0.8` (80% threshold)
- Log warning with count of skipped regions
- Return count of submitted vs skipped

**Acceptance Criteria**:
- [ ] Queue depth checked before each build submission
- [ ] Skips submission when queue > 80% full
- [ ] Logs warning with skipped region count
- [ ] Returns or logs both submitted and skipped counts
- [ ] Existing backfill behavior unchanged when queue is empty

**Test Requirements**:
- `testBackfillRespectsQueueDepth()` - fill queue to 80%, verify backfill skips
- `testBackfillEmptyQueue()` - verify all dirty regions submitted
- Regression: existing tests pass

**Files**:
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (backfillDirtyRegions)
- `simulation/src/test/java/.../BuildIntegrationTest.java` or new test

---

### 12. Luciferase-vtet: Add entity count limit per region [S - 1-2 hours]

**Priority**: P1 (Robustness)
**Component**: AdaptiveRegionManager.java, RenderingServerConfig.java
**Risk**: LOW - additive guard with configurable limit

**Problem**: No limit on entities per region. A single region receiving high entity density
could cause unbounded memory growth in CopyOnWriteArrayList.

**Implementation**:
- Add `int maxEntitiesPerRegion` to RenderingServerConfig (default: 10000)
- Pass to AdaptiveRegionManager constructor
- Check in `updateEntity()` before adding to region
- Log warning when limit approached (>90%), reject at limit

**Acceptance Criteria**:
- [ ] New config field `maxEntitiesPerRegion` (default: 10000)
- [ ] `updateEntity()` rejects when region at capacity
- [ ] Throws IllegalStateException with descriptive message
- [ ] Moving entity to full region is rejected (entity stays in old region)
- [ ] Warning logged at 90% capacity
- [ ] Test validates rejection at limit

**Test Requirements**:
- `testEntityLimitEnforced()` - add 10001 entities, verify rejection
- `testEntityLimitWarning()` - add 9001 entities, verify warning logged
- `testEntityMoveBetweenRegions()` - verify move to full region rejected gracefully
- Regression: existing tests pass with default limit

**Files**:
- `simulation/src/main/java/.../RenderingServerConfig.java` (new field)
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (limit check)
- `simulation/src/test/java/.../AdaptiveRegionManagerTest.java` (new tests)

---

### 13. Luciferase-mauo: Optimize emergency eviction with min-heap [M - 2-3 hours]

**Priority**: P1 (Performance)
**Component**: RegionCache.java
**Risk**: MEDIUM - algorithm change in critical eviction path

**Problem**: `emergencyEvict()` uses `stream().sorted().collect()` which is O(n log n)
on all pinned entries. For large pinned sets, this creates GC pressure and latency.

**Implementation**:
- Replace sorted stream with `PriorityQueue<Map.Entry<CacheKey, CachedRegion>>`
- Comparator: `Comparator.comparingLong(e -> e.getValue().lastAccessedMs())`
- Add all entries to PriorityQueue (O(n))
- Poll entries until `bytesEvicted >= bytesToEvict` (O(k log n))
- Total: O(n + k log n) vs O(n log n), where k << n typically

**Acceptance Criteria**:
- [ ] PriorityQueue replaces stream().sorted().collect()
- [ ] Same eviction order (oldest lastAccessedMs first)
- [ ] Same bytesToEvict calculation
- [ ] Same C4 atomic remove behavior preserved
- [ ] Performance test shows improvement for large pinned sets (>1000 entries)
- [ ] Existing emergency eviction tests pass with identical results

**Test Requirements**:
- `testMinHeapEvictionOrder()` - verify oldest evicted first
- `testMinHeapEvictionAmount()` - verify correct bytes evicted
- `testMinHeapConcurrency()` - verify C4 guard still works
- Performance: benchmark with 1000+ pinned entries, compare stream vs PQ
- Regression: all emergency eviction tests pass

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (emergencyEvict method, ~line 347-384)
- `simulation/src/test/java/.../RegionCacheTest.java` (updated + new tests)

---

## Phase 4: Polish & Testing (Days 8-9)

**Goal**: Close test gaps, apply final optimizations, simplify code.
**Completion Criteria**: All 4 beads closed, full test suite passes, code coverage improved.

### 14. Luciferase-sg70: Add circuit breaker integration test [M - 2-3 hours]

**Priority**: P2 (Test Gap)
**Dependency**: Luciferase-dtnt (defensive copy should be in place)
**Component**: BuildIntegrationTest.java
**Risk**: MEDIUM - test must reliably trigger failure and recovery

**Problem**: Current circuit breaker tests only validate wiring, not actual open/close
state transitions. Need end-to-end test of failure -> open -> timeout -> close cycle.

**Implementation**:
- Create test that injects build failures (mock or use invalid data)
- Trigger 3 consecutive failures for same regionId
- Verify 4th build rejected with CircuitBreakerOpenException
- Advance clock past 60s timeout
- Verify next build succeeds
- Verify circuit breaker cleared on success

**Acceptance Criteria**:
- [ ] Test forces 3 consecutive failures for same region
- [ ] 4th build attempt throws CircuitBreakerOpenException
- [ ] After timeout, next build attempt succeeds
- [ ] Successful build clears circuit breaker state
- [ ] Test uses TestClock for deterministic timeout control
- [ ] Test completes in < 5 seconds (no real 60s wait)

**Test Requirements**:
- `testCircuitBreakerFullLifecycle()`:
  1. Submit 3 failing builds -> verify failures recorded
  2. Submit 4th build -> verify CircuitBreakerOpenException
  3. Advance TestClock by 61 seconds
  4. Submit 5th build -> verify acceptance and success
  5. Verify circuit breaker cleared

**Files**:
- `simulation/src/test/java/.../BuildIntegrationTest.java` (new test method)

---

### 15. Luciferase-rp9u: Cache health/metrics responses with 1s TTL [S - 1-2 hours]

**Priority**: P3 (Suggestion)
**Dependencies**: Luciferase-bkji (forceAccurate), Luciferase-w1tk (rate limiting)
**Component**: RenderingServer.java
**Risk**: LOW - response caching is a well-understood pattern

**Problem**: Monitoring systems poll health/metrics every 1-5 seconds. Each call to
`/api/metrics` triggers `getStats()` which calls `cleanUp()`. With `forceAccurate=false`
(bkji fix), this is mitigated, but response caching adds another layer.

**NOTE**: Evaluate after bkji and w1tk are complete. If those fixes sufficiently address
the performance concern, this bead may be deprioritized or closed as won't-fix.

**Implementation**:
- Add `Caffeine<String, Map<String, Object>>` response cache in RenderingServer
- TTL: 1 second (expireAfterWrite)
- Keys: "health", "metrics"
- On request: check cache first, compute if absent/expired
- Clear cache on server shutdown

**Acceptance Criteria**:
- [ ] Response cache for /api/health and /api/metrics
- [ ] 1 second TTL (responses stale by at most 1s)
- [ ] Second request within 1s returns cached response
- [ ] Cache cleared on shutdown
- [ ] Test validates caching behavior

**Test Requirements**:
- `testHealthResponseCached()` - two calls within 100ms return same response
- `testMetricsResponseCached()` - two calls within 100ms return same response
- `testCacheExpiry()` - call after 1.1s returns fresh response
- Regression: existing tests pass

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (response cache)
- `simulation/src/test/java/.../RenderingServerTest.java` (new tests)

---

### 16. Luciferase-ko4u: Optimize pinned cache access overhead [S - 1-2 hours]

**Priority**: P3 (Suggestion)
**Dependency**: Luciferase-h86g (understand pin cleanUp interaction)
**Component**: RegionCache.java
**Risk**: MEDIUM - Option A introduces two-map consistency concern during emergency eviction

**Problem**: `RegionCache.get()` for pinned entries calls `computeIfPresent()` which
creates a new `CachedRegion` record on EVERY access (line 109-111). This generates
garbage for high-frequency reads.

**Implementation**:
- Option A (recommended): Track access time in separate `ConcurrentHashMap<CacheKey, AtomicLong>`
  - Read from pinnedCache, update timestamp in separate map
  - Emergency eviction reads timestamps from separate map
  - Zero allocation on get()
- Option B: Update timestamp only every N accesses (sampling)
- Option C: Use volatile long field in mutable wrapper

**Acceptance Criteria**:
- [ ] No new object allocation per `get()` call for pinned entries
- [ ] Access timestamp still tracked (for emergency eviction ordering)
- [ ] Emergency eviction still evicts oldest-accessed entries first
- [ ] Benchmark shows measurable improvement (fewer GC pauses)
- [ ] Existing tests pass with identical behavior

**Test Requirements**:
- `testPinnedGetNoAllocation()` - verify get() doesn't allocate (GC-free path)
- `testEmergencyEvictionStillOrdered()` - verify oldest access evicted first
- Performance benchmark: 1M gets, measure allocation rate before/after
- Regression: all RegionCache tests pass

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (get method + emergency eviction)
- `simulation/src/test/java/.../RegionCacheTest.java` (new + updated tests)

---

### 17. Luciferase-8chv: Simplify TestClock to absolute-mode only [M - 2-3 hours]

**Priority**: P3 (Chore)
**Component**: TestClock.java + 5 dependent test files
**Risk**: MEDIUM - must migrate all setSkew() callers

**Problem**: TestClock supports both relative mode (offset from System time) and absolute
mode (fixed time). All Phase 2 tests use absolute mode. Relative mode adds complexity
(dual code paths, mode switching) with no current benefit.

**Pre-check**: Verify ALL callers of removed methods can be replaced:
- Search for `setSkew()` callers (6 files known):
  - InjectableClockTest.java - uses setSkew for drift testing
  - IntegrationInfrastructureTest.java - uses setSkew
  - ClockTest.java - tests setSkew directly
  - TESTING_PATTERNS.md - documents setSkew
  - P52_PERFORMANCE_PROFILING_IMPLEMENTATION.md - references setSkew
- **Also search for**: `reset()`, `isAbsoluteMode()`, `getSkew()`, `getOffset()` callers
  - Run: `grep -rn "\.reset()\|\.isAbsoluteMode()\|\.getSkew()\|\.getOffset()" simulation/src/`
  - These methods are also being removed and may have callers not yet identified

**Implementation**:
- Remove fields: `offset`, `nanoOffset`, `absoluteMode`
- Remove methods: `setSkew()`, `reset()`, `isAbsoluteMode()`, `getSkew()`
- Simplify `currentTimeMillis()` to return `absoluteTime.get()` directly
- Simplify `nanoTime()` to return `absoluteNanos.get()` directly
- Simplify `advance()` to just increment both atomics
- Replace `setSkew(x)` callers with `setTime(clock.currentTimeMillis() + x)`
- Update documentation

**Acceptance Criteria**:
- [ ] Relative mode fields removed
- [ ] setSkew(), reset(), isAbsoluteMode(), getSkew() removed
- [ ] advance() simplified (single code path)
- [ ] currentTimeMillis() / nanoTime() simplified
- [ ] All 5 dependent files migrated
- [ ] ALL tests pass after migration
- [ ] Documentation updated

**Test Requirements**:
- Verify InjectableClockTest passes with replacement calls
- Verify IntegrationInfrastructureTest passes
- Verify ClockTest updated to test remaining API
- Regression: `mvn test -pl simulation` passes completely

**Files**:
- `simulation/src/test/java/.../TestClock.java` (simplification)
- `simulation/src/test/java/.../InjectableClockTest.java` (migrate setSkew)
- `simulation/src/test/java/.../IntegrationInfrastructureTest.java` (migrate setSkew)
- `simulation/src/test/java/.../ClockTest.java` (update tests)
- `simulation/doc/TESTING_PATTERNS.md` (update docs)
- `lucien/doc/P52_PERFORMANCE_PROFILING_IMPLEMENTATION.md` (update docs)

---

## Risk Assessment Summary

| Bead | Risk | Primary Risk | Mitigation |
|------|------|-------------|------------|
| h86g | LOW | None significant | One-line change, well-understood |
| dtnt | LOW | None significant | Standard immutability pattern |
| bjjn | LOW | Over-validation | Test edge cases, only reject truly invalid |
| 8nbh | LOW | None significant | Standard constructor validation |
| oqh3 | MEDIUM | Config regression | Keep defaults identical, test factory methods |
| 1sa4 | LOW | Over-redaction | Config flag for toggling |
| w1tk | LOW | Rate limit too aggressive | Configurable limits, disabled in tests |
| 7jjx | HIGH | Auth breaks existing | Null apiKey disables, test config unchanged |
| jc5f | HIGH | SSL complexity | Null keystore disables, HTTP fallback |
| bkji | LOW | API confusion | Clear javadoc, backward-compatible default |
| xox5 | LOW | Overly conservative skip | 80% threshold with logging |
| vtet | LOW | Legitimate high-density | Configurable limit, log warnings |
| mauo | MEDIUM | Algorithm correctness | Same comparator, extensive assertion tests |
| sg70 | MEDIUM | Flaky timing | TestClock for determinism, no real waits |
| rp9u | LOW | Stale monitoring data | 1s TTL is acceptable for monitoring |
| ko4u | MEDIUM | Two-map consistency | Separate timestamp map risks desync during eviction |
| 8chv | MEDIUM | setSkew migration | Pre-check all callers, run full test suite |

---

## Effort Summary

| Size | Count | Beads |
|------|-------|-------|
| XS (< 30 min) | 3 | h86g, dtnt, 8nbh |
| S (30 min - 2 hr) | 8 | bjjn, 1sa4, w1tk, bkji, xox5, vtet, rp9u, ko4u |
| M (2-6 hr) | 5 | oqh3, mauo, sg70, 8chv, 7jjx |
| L (6-12 hr) | 1 | jc5f |
| **Total** | **17** | **~35-45 hours** |

---

## Parallelization Opportunities

### Phase 1 (2 parallel tracks):
- Track A: h86g -> dtnt -> bjjn
- Track B: 8nbh -> oqh3

### Phase 2 (2 parallel tracks):
- Track A: 1sa4 -> w1tk
- Track B: 7jjx -> jc5f

### Phase 3 (3 parallel tracks):
- Track A: bkji
- Track B: xox5 -> vtet
- Track C: mauo

### Phase 4 (3 parallel tracks):
- Track A: sg70
- Track B: rp9u
- Track C: ko4u -> 8chv

**With 2 developers, estimated calendar time: 6-7 days**
**With 1 developer, estimated calendar time: 8-9 days**

---

## Phase Completion Checklist

### Phase 1 Complete When:
- [ ] All 5 beads closed (h86g, dtnt, bjjn, 8nbh, oqh3)
- [ ] `mvn test -pl simulation` passes
- [ ] No regressions in any module
- [ ] Config foundation established for subsequent phases

### Phase 2 Complete When:
- [ ] All 4 beads closed (1sa4, w1tk, 7jjx, jc5f)
- [ ] All endpoints authenticated (except /api/health)
- [ ] Rate limiting active on /api/* endpoints
- [ ] Sensitive info redacted from /api/info
- [ ] TLS available (even if optional)
- [ ] Security review pass

### Phase 3 Complete When:
- [ ] All 4 beads closed (bkji, xox5, vtet, mauo)
- [ ] Emergency eviction uses O(n + k log n) algorithm
- [ ] Monitoring calls don't force cleanUp()
- [ ] Backfill respects queue capacity
- [ ] Entity count limit enforced

### Phase 4 Complete When:
- [ ] All 4 beads closed (sg70, rp9u, ko4u, 8chv)
- [ ] Circuit breaker has integration test
- [ ] Response caching reduces monitoring overhead
- [ ] Pinned access is allocation-free
- [ ] TestClock simplified (if safe)
- [ ] Full `mvn test` passes (all modules)

---

## Implementation Notes for Agents

### General Guidelines
- Use sequential thinking (mcp__sequential-thinking__sequentialthinking) for complex changes
- TDD: Write test first, verify it fails, then implement
- Run `mvn test -pl simulation` after each bead
- Update bead status: `bd update <id> --status in_progress` when starting
- Close bead: `bd close <id>` when complete with tests passing

### Component Context
- **RenderingServer.java**: Javalin HTTP server, REST endpoints, lifecycle management
- **RenderingServerConfig.java**: Immutable record, add fields with defaults
- **RegionCache.java**: Caffeine + ConcurrentHashMap hybrid, thread-safe
- **RegionBuilder.java**: Thread pool, priority queue, circuit breaker
- **AdaptiveRegionManager.java**: Entity tracking, region grid, build coordination
- **TestClock.java**: Test infrastructure, used across multiple test files

### Key Patterns in Codebase
- Clock interface injection for deterministic testing
- CopyOnWriteArrayList for thread-safe entity storage
- AtomicBoolean for lifecycle guards
- Caffeine for LRU caching with weight-based eviction
- Records for immutable data (CacheKey, CachedRegion, BuildRequest, etc.)

---

## Plan Audit Results

**Audit Date**: 2026-02-14
**Auditor**: plan-auditor (strategic-planner self-audit due to context recovery)
**Verdict**: GO with 5 conditions (all addressed inline above)

### Issues Addressed in Plan Update

| ID | Severity | Issue | Resolution |
|----|----------|-------|------------|
| C1 | Critical | RenderingServerConfig constructor breaks on field addition | Added explicit call-site search requirement to oqh3 |
| I1 | Important | CircuitBreakerState thread safety | Added NOTE to oqh3 documenting limitation |
| I2 | Important | ko4u risk underestimated | Upgraded from LOW to MEDIUM |
| I3 | Important | 8chv missing method caller search | Added search for reset/isAbsoluteMode/getSkew/getOffset |
| S3 | Minor | 7jjx empty-string apiKey edge case | Added blank-handling requirement |

### Validation Summary

| Area | Result | Notes |
|------|--------|-------|
| Plan completeness | PASS | 17/17 work beads + 1 epic fully specified |
| Dependency correctness | PASS | 6/6 dependencies verified against code |
| Phase sequencing | PASS | 4 phases logically ordered; ko4u/sg70 could move to Phase 3 |
| Risk assessment | PASS | 17/17 beads assessed; 2 ratings adjusted |
| Effort estimates | PASS | 35-45 hours realistic for scope |
| Missing items | PASS | 5 gaps identified and addressed |
| TDD compliance | PASS | All beads specify test-first approach |

### Optimization Opportunity (Optional)

Moving ko4u and sg70 from Phase 4 to Phase 3 could save ~1 calendar day since their
blockers (h86g and dtnt respectively) complete in Phase 1. This is optional since Phase 4
already has manageable scope.
