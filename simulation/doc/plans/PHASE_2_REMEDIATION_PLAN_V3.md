# Phase 2 Code Review Remediation Plan (V3)

**Epic**: Luciferase-wbvn (Phase 2 Code Review Remediation Planning)
**Parent Epic**: Luciferase-zq0j (Address all Phase 2 code review findings)
**Date**: 2026-02-14
**Version**: V3 (revised per V2 re-audit findings)
**Status**: PLANNED

## Executive Summary

This plan addresses 18 findings from the comprehensive Phase 2 GPU Integration code review.
The findings span security vulnerabilities (5 P0), correctness/robustness issues (7 P1),
test coverage gaps (1 P2), and optimization suggestions (4 P3). Work is organized into
4 execution phases over approximately 10-11 development days, prioritizing security and
correctness before performance and polish.

**Total Effort**: 42-61 hours (includes 20% buffer over raw estimates of 34.5-50.5 hours)
**Planning Target**: 61 hours (use for scheduling and resource allocation)
**Critical Path**: oqh3 (8-12h) -> jc5f (6-8h) -> 7jjx (4-6h) = 18-26 hours sequential
**Parallelizable**: 75-80% of work can run in parallel within phases
**Calendar Time**: 10-11 days (1 developer), 6-7 days (2 developers)

---

## V3 Change Log

This section documents all changes from V2 based on the V2 re-audit findings.

| ID | Source | Severity | Change | Section |
|----|--------|----------|--------|---------|
| C2-V3 | V2 Re-Audit | Critical | oqh3 now defines ALL 8 SecurityConfig fields upfront (not "empty initially"); effort 4-6h -> 8-12h | oqh3, Config Strategy |
| TLS-V3 | V2 Re-Audit | Critical | SecurityConfig.secure() accepts explicit `boolean tlsEnabled` parameter; secureDefaults() passes `tlsEnabled=true` | Secure Defaults |
| VAL-V3 | V2 Re-Audit | Critical | validate() throws IllegalArgumentException for API key without TLS (was log.warn) | Config Strategy |
| EFF-V3 | V2 Re-Audit | Significant | Line-by-line effort recount: V2 claimed 37-58h raw, actual 30.5-44.5h; V3 corrected to 34.5-50.5h raw (with expanded oqh3) | Effort Summary |
| VER-V3 | V2 Re-Audit | Significant | Added automated grep verification to oqh3 acceptance criteria for constructor call sites | oqh3 |
| ACC-V3 | V2 Re-Audit | Significant | oqh3 acceptance criteria lists all 8 SecurityConfig fields explicitly; Phase 2 beads say "implement using" not "add to" | oqh3, Phase 2 |

---

## V2 Change Log (preserved from V2)

| ID | Source | Severity | Change | Section |
|----|--------|----------|--------|---------|
| C1 | Deep-Critic | Critical | Reversed security dependency: TLS (jc5f) now blocks auth (7jjx) | Dependency Graph, Phase 2 |
| C2 | Deep-Critic | Critical | Added configuration composition strategy (SecurityConfig, CacheConfig, BuildConfig) | Config Strategy, oqh3 |
| C3 | Deep-Critic | Critical | Added 20% buffer to effort estimates; 35-45h -> 45-70h, 8-9d -> 11-12d | Effort Summary |
| S1 | Deep-Critic | Significant | Added secure defaults definition with production-ready config | Secure Defaults |
| S2 | Deep-Critic | Significant | Clarified bjjn 256-char limit as defense-in-depth against memory exhaustion | bjjn |
| S3 | Deep-Critic | Significant | Re-analyzed parallelization: 60% -> 75-80%; xox5/vtet are independent | Parallelization |
| S4 | Deep-Critic | Significant | Specified JMH -prof gc mechanism for ko4u allocation testing | ko4u |
| R1 | Plan-Auditor | Recommendation | Moved ko4u and sg70 from Phase 4 to Phase 3 | Phase 3, Phase 4 |
| R2 | Plan-Auditor | Recommendation | Added line number guidance note | Implementation Notes |
| R3 | Plan-Auditor | Recommendation | Created Luciferase-2qb9 for CircuitBreakerState thread safety | Implementation Notes |
| E1 | Deep-Critic | Escalation | mauo risk escalated MEDIUM -> HIGH | Risk Assessment |
| E2 | Deep-Critic | Clarification | h86g -> bkji/ko4u noted as learning dependency, not code dependency | Implementation Notes |

---

## Configuration Management Strategy (UPDATED V3 - addresses C2, C2-V3, ACC-V3)

### Problem

The V1 plan adds 14 new fields to RenderingServerConfig across 6 beads (oqh3, 1sa4, w1tk,
7jjx, jc5f, vtet), growing it from 9 to 23 parameters. A flat record with 23 positional
parameters is unmaintainable and error-prone.

### Solution: Composition Pattern

Refactor RenderingServerConfig into a composition of domain-specific sub-records during
oqh3 (Phase 1). **All sub-record fields are defined in oqh3 with default/null values.**
Phase 2 beads implement server-side logic (middleware, handlers) that uses these pre-existing
fields. This prevents cascading constructor breaks.

**V3 Clarification**: Java records are immutable value types. Their field set is fixed at
compile time. Therefore, oqh3 MUST define all fields for all three sub-records upfront,
including SecurityConfig's 8 fields. Phase 2 beads do NOT modify record structure; they
implement the server-side behavior (Javalin handlers, SSL configuration, middleware) that
reads from the already-defined SecurityConfig fields.

```java
// BEFORE (V1 - flat record, 23 params):
public record RenderingServerConfig(
    int port, int regionLevel, int regionsPerAxis,
    long maxCacheMemoryBytes, int buildPoolSize, int maxBuildDepth,
    int gridResolution, List<URI> upstreamServers, float worldMin,
    // ... 14 more fields
) { }

// AFTER (V3 - composed record, all fields defined in oqh3):
public record RenderingServerConfig(
    int port,
    int regionLevel,
    int regionsPerAxis,
    float worldMin,
    float worldMax,
    int maxEntitiesPerRegion,
    List<URI> upstreamServers,
    SecurityConfig security,
    CacheConfig cache,
    BuildConfig build
) {
    public static RenderingServerConfig secureDefaults(String apiKey) { ... }
    public static RenderingServerConfig testing() { ... }
    public RenderingServerConfig validate() { ... }
}
```

### Sub-Record Definitions

**SecurityConfig** (ALL 8 fields defined in oqh3; server-side logic implemented by 1sa4, w1tk, 7jjx, jc5f):
```java
public record SecurityConfig(
    String apiKey,                     // 7jjx implements auth middleware using this field
    boolean redactSensitiveInfo,       // 1sa4 implements redaction logic using this field
    boolean tlsEnabled,                // jc5f implements SSL config using this field
    String keystorePath,               // jc5f implements SSL config using this field
    String keystorePassword,           // jc5f implements SSL config using this field
    String keyManagerPassword,         // jc5f implements SSL config using this field
    boolean rateLimitEnabled,          // w1tk implements rate limit middleware using this field
    int rateLimitRequestsPerMinute     // w1tk implements rate limit middleware using this field
) {
    public static SecurityConfig secure(String apiKey, boolean tlsEnabled) { // V3: explicit TLS param
        return new SecurityConfig(
            apiKey,
            true,         // redactSensitiveInfo
            tlsEnabled,   // V3: explicitly controlled, not hardcoded
            null,         // keystorePath (set via withKeystorePath)
            null,         // keystorePassword
            null,         // keyManagerPassword
            true,         // rateLimitEnabled
            100           // rateLimitRequestsPerMinute
        );
    }
    public static SecurityConfig permissive() {  // for testing
        return new SecurityConfig(null, false, false, null, null, null, false, 0);
    }
}
```

**V3 Field Inventory** (8 fields, all defined in oqh3):

| # | Field | Type | Default (permissive) | Default (secure) | Used By |
|---|-------|------|---------------------|-------------------|---------|
| 1 | apiKey | String | null | required param | 7jjx |
| 2 | redactSensitiveInfo | boolean | false | true | 1sa4 |
| 3 | tlsEnabled | boolean | false | true | jc5f |
| 4 | keystorePath | String | null | null (set later) | jc5f |
| 5 | keystorePassword | String | null | null (set later) | jc5f |
| 6 | keyManagerPassword | String | null | null (set later) | jc5f |
| 7 | rateLimitEnabled | boolean | false | true | w1tk |
| 8 | rateLimitRequestsPerMinute | int | 0 | 100 | w1tk |

**CacheConfig** (fields from original + rp9u):
```java
public record CacheConfig(
    long maxCacheMemoryBytes           // existing field, moved here
) {
    public static CacheConfig defaults() { ... }
}
```

**BuildConfig** (fields added by oqh3):
```java
public record BuildConfig(
    int buildPoolSize,                 // existing field, moved here
    int maxBuildDepth,                 // existing field, moved here
    int gridResolution,                // existing field, moved here
    int maxQueueDepth,                 // oqh3: default 100
    long circuitBreakerTimeoutMs,      // oqh3: default 60000
    int circuitBreakerFailureThreshold // oqh3: default 3
) {
    public static BuildConfig defaults() { ... }
}
```

### Validation Method (UPDATED V3 - addresses VAL-V3)

```java
public RenderingServerConfig validate() {
    if (security.tlsEnabled() && security.keystorePath() == null)
        throw new IllegalArgumentException("TLS enabled but no keystore path");
    // V3 FIX: THROW, not warn. API key without TLS = cleartext credentials = security violation.
    if (security.apiKey() != null && !security.apiKey().isBlank() && !security.tlsEnabled())
        throw new IllegalArgumentException(
            "API key configured without TLS enabled. Credentials would be transmitted in "
            + "cleartext. Either enable TLS (tlsEnabled=true with valid keystore) or remove "
            + "the API key. Use SecurityConfig.permissive() for testing without security.");
    if (build.buildPoolSize() < 1)
        throw new IllegalArgumentException("buildPoolSize must be >= 1");
    if (build.maxQueueDepth() < 1)
        throw new IllegalArgumentException("maxQueueDepth must be >= 1");
    if (maxEntitiesPerRegion < 1)
        throw new IllegalArgumentException("maxEntitiesPerRegion must be >= 1");
    return this;
}
```

**V3 Rationale for enforcement over warning**: A warning can be ignored. If an operator
configures an API key but forgets TLS, the credentials are transmitted in cleartext on
every request. This is a security violation that should fail fast at startup, not silently
log a warning that may never be read. The exception message provides clear remediation
guidance. Testing configurations use `SecurityConfig.permissive()` which has null apiKey,
so this enforcement does not affect test code.

### Migration Strategy (UPDATED V3)

1. **Phase 1 (oqh3)**: Create ALL three sub-records with ALL their fields defined.
   SecurityConfig gets all 8 fields with default/null values. Move existing fields
   (buildPoolSize, maxBuildDepth, gridResolution, maxCacheMemoryBytes) into their
   sub-records. Define factory methods (secure, permissive, defaults). Add validate()
   method. Update ALL call sites. **oqh3 defines structure; Phase 2 implements behavior.**
2. **Phase 2 (1sa4, w1tk, jc5f, 7jjx)**: Each bead implements server-side logic
   (middleware, handlers, SSL configuration) that READS FROM the pre-existing SecurityConfig
   fields. No changes to record structure. No constructor signature changes.
3. **Phase 3 (vtet)**: maxEntitiesPerRegion added to top-level (simple domain field).

This means Phase 2 beads only add server-side behavior (Javalin handlers, middleware, SSL
setup), not configuration structure. SecurityConfig constructor remains stable after oqh3.

---

## Secure Defaults Definition (UPDATED V3 - addresses TLS-V3)

### Problem

After Phase 2, all security features can be individually disabled. The V1 plan's `defaults()`
factory method has all security features off, allowing insecure production deployments.

### Solution: Tiered Factory Methods

```java
// Production-ready secure configuration (RECOMMENDED)
public static RenderingServerConfig secureDefaults(String apiKey) {
    Objects.requireNonNull(apiKey, "API key required for secure mode");
    if (apiKey.isBlank()) throw new IllegalArgumentException("API key must not be blank");
    return new RenderingServerConfig(
        8080,                                         // port
        4,                                            // regionLevel
        4,                                            // regionsPerAxis
        0.0f,                                         // worldMin
        1024.0f,                                      // worldMax
        10000,                                        // maxEntitiesPerRegion
        List.of(),                                    // upstreamServers
        SecurityConfig.secure(apiKey, true),           // V3: explicit tlsEnabled=true
        CacheConfig.defaults(),
        BuildConfig.defaults()
    ).validate();
}

// Permissive configuration for testing (NO security features)
public static RenderingServerConfig testing() {
    return new RenderingServerConfig(
        0,                             // dynamic port
        4,                             // regionLevel
        2,                             // regionsPerAxis
        0.0f,                          // worldMin
        100.0f,                        // worldMax
        10000,                         // maxEntitiesPerRegion
        List.of(),                     // upstreamServers
        SecurityConfig.permissive(),   // auth OFF, TLS OFF, redact OFF, rate limit OFF
        CacheConfig.defaults(),
        BuildConfig.defaults()
    );
}

// Backward-compatible defaults (DEPRECATED - use secureDefaults for production)
@Deprecated
public static RenderingServerConfig defaults() {
    return new RenderingServerConfig(
        8080, 4, 4, 0.0f, 1024.0f, 10000, List.of(),
        SecurityConfig.permissive(),
        CacheConfig.defaults(),
        BuildConfig.defaults()
    );
}
```

### Security Posture at Phase 2 Completion

| Feature | secureDefaults() | testing() | defaults() (deprecated) |
|---------|-----------------|-----------|------------------------|
| Authentication | ON (API key required) | OFF | OFF |
| TLS/HTTPS | ON (tlsEnabled=true) | OFF | OFF |
| Info Redaction | ON | OFF | OFF |
| Rate Limiting | ON (100 req/min) | OFF | OFF |
| Input Validation | ON (always) | ON (always) | ON (always) |

**V3 Note**: The `secureDefaults()` call to `SecurityConfig.secure(apiKey, true)` explicitly
passes `tlsEnabled=true`. This makes the TLS state transparent in the factory method rather
than hidden inside `SecurityConfig.secure()`. The validate() call at the end enforces that
API key + TLS are consistent (API key without TLS throws IllegalArgumentException).

### Deployment Guidance

Production deployments MUST use `secureDefaults(apiKey)` with a valid keystore:
```java
var config = RenderingServerConfig.secureDefaults("my-api-key")
    .withKeystorePath("/path/to/keystore.jks")
    .withKeystorePassword("changeit");
```

If any security feature must be disabled in production, the operator must explicitly
construct the config, providing an audit trail for the security exception.

---

## Dependency Graph (unchanged from V2)

```
PHASE 1: Foundation & Config Refactoring
  h86g (pin cleanUp)           [XS] ---> bkji (forceAccurate) [Phase 3]
                                    \--> ko4u (pinned access)  [Phase 3]
  dtnt (defensive copy)        [XS] ---> sg70 (circuit test)   [Phase 3]
  bjjn (input validation)      [S]  (independent)
  8nbh (pool validation)       [XS] (independent)
  oqh3 (config composition)    [L-] (independent, creates ALL sub-record fields)

PHASE 2: Security Hardening (CORRECTED SEQUENCE)
  1sa4 (info redaction)        [S]  (independent, implements using SecurityConfig)
  w1tk (rate limiting)         [S]  (independent, implements using SecurityConfig) ---> rp9u [Phase 4]
  jc5f (TLS/HTTPS)             [L]  (independent) ---> 7jjx (auth)        [Phase 2]
  7jjx (authentication)        [M-L] (blocked by jc5f, implements using SecurityConfig)

  NOTE: V1 had 7jjx -> jc5f (auth before TLS). V2 REVERSES this to jc5f -> 7jjx
  (TLS before auth) to prevent cleartext credential transmission. See C1.

PHASE 3: Performance & Robustness (6 beads)
  bkji (forceAccurate)         [S]  (blocked by h86g) ---> rp9u [Phase 4]
  xox5 (queue depth check)     [S]  (independent)
  vtet (entity count limit)    [S]  (independent)
  mauo (min-heap eviction)     [M]  (independent, RISK: HIGH)
  ko4u (pinned access optim.)  [S]  (blocked by h86g)
  sg70 (circuit breaker test)  [M]  (blocked by dtnt)

PHASE 4: Polish & Testing (2 beads)
  rp9u (cache responses)       [S]  (blocked by bkji, w1tk)
  8chv (TestClock simplify)    [M]  (independent but risky)
```

### Critical Path (UPDATED V3)

```
Phase 1: oqh3 (8-12h, longest Phase 1 bead)  <== V3: expanded from 4-6h
    |
    v
Phase 2: jc5f (6-8h) -----> 7jjx (4-6h)    <== CRITICAL PATH: 10-14h sequential
    |                            |
    v                            v
Phase 3: all 6 beads parallel (longest: mauo at 2-3h)
    |
    v
Phase 4: rp9u + 8chv parallel (longest: 8chv at 2-3h)
```

**Total critical path**: ~22-33 hours sequential (oqh3 -> jc5f -> 7jjx -> mauo -> 8chv)
**With parallelization**: Most non-critical-path work runs alongside Phases 2-4.

---

## Phase 1: Foundation & Config Refactoring (Days 1-4)

**Goal**: Fix correctness bugs, establish configuration composition foundation, build momentum.
**Completion Criteria**: All 5 beads closed, `mvn test -pl simulation` passes.
**V3 Change**: oqh3 expanded to define all 15 sub-record fields upfront (8 SecurityConfig +
1 CacheConfig + 6 BuildConfig). Effort increased from 4-6h to 8-12h.

### 1. Luciferase-h86g: Add cleanUp() to RegionCache.pin() [XS - 30 min]

**Priority**: P1 (Bug Fix)
**Component**: RegionCache.java
**Risk**: LOW - single line addition with clear semantics

**Problem**: `pin()` calls `unpinnedCache.invalidate()` but not `cleanUp()`, so Caffeine's
`weightedSize` is stale until the next async maintenance cycle. This causes temporary
double-counting when `getTotalMemoryBytes()` is called immediately after `pin()`.

**Implementation**:
- Add `unpinnedCache.cleanUp()` after `invalidate()` in the pin() method
- This forces Caffeine to update its internal weight tracking immediately

**NOTE**: This is a "learning dependency" for bkji and ko4u - understanding cleanUp
semantics here informs the forceAccurate parameter and pinned access optimization.
There is no code dependency; bkji and ko4u can technically proceed without h86g's
code changes, but the knowledge gained here is valuable context.

**Acceptance Criteria**:
- [ ] `unpinnedCache.cleanUp()` called after `invalidate()` in `pin()` method
- [ ] New test in RegionCacheTest: pin a region, immediately call getTotalMemoryBytes(), verify no double-counting
- [ ] Existing RegionCacheTest tests still pass
- [ ] Javadoc updated to document the cleanUp() call

**Test Requirements**:
- Unit test: `testPinImmediateMemoryAccuracy()` - put region, pin it, verify totalMemoryBytes equals region size (not 2x)
- Regression: Run full RegionCacheTest suite

**Files**:
- `simulation/src/main/java/.../RegionCache.java`
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
- `simulation/src/main/java/.../RegionBuilder.java` (BuildRequest record)
- `simulation/src/test/java/.../BuildIntegrationTest.java` (verify no list reuse issues)

---

### 3. Luciferase-bjjn: Add input validation for entity updates [S - 1-2 hours]

**Priority**: P0 (Security)
**Component**: AdaptiveRegionManager.java
**Risk**: LOW - additive validation, no existing logic changed

**Problem**: `updateEntity()` accepts any float values including NaN, Infinity, and negative
coordinates. Malicious or buggy upstream data could poison the region grid. Additionally,
unbounded string inputs (entityId, type) could exhaust memory via very long strings.

**V2 Clarification (S2)**: The 256-character entity ID limit is defense-in-depth against
memory exhaustion attacks, separate from coordinate poisoning. A malicious client could
send millions of entities with megabyte-length IDs to exhaust heap. 256 characters is
generous for any reasonable UUID (36 chars), name, or composite identifier scheme. The
validation addresses two distinct threat vectors:
1. **Coordinate poisoning**: NaN/Infinity values corrupt spatial indexing
2. **Memory exhaustion**: Unbounded string lengths in entityId/type

**Implementation**:
- Add validation at start of `updateEntity()`:
  - Reject NaN/Inf coordinates: `Float.isNaN(x) || Float.isInfinite(x)` -> IllegalArgumentException
  - Validate entity ID: non-null, non-empty, max 256 characters
  - Validate entity type: non-null, non-empty
- Clamp coordinates to world bounds (already done by regionForPosition, but validate early)
- Negative coordinates are valid (world bounds may span negative space); only NaN/Inf rejected

**Acceptance Criteria**:
- [ ] NaN coordinates rejected with IllegalArgumentException
- [ ] Infinite coordinates rejected with IllegalArgumentException
- [ ] Null/empty entityId rejected with IllegalArgumentException
- [ ] EntityId > 256 chars rejected with IllegalArgumentException
- [ ] Null/empty type rejected with IllegalArgumentException
- [ ] Negative coordinates accepted (valid spatial values)
- [ ] Valid coordinates still work (regression)
- [ ] Javadoc documents validation rules and rationale

**Test Requirements**:
- `testUpdateEntityNanCoordinates()` - verify IllegalArgumentException
- `testUpdateEntityInfiniteCoordinates()` - verify IllegalArgumentException
- `testUpdateEntityNullId()` - verify IllegalArgumentException
- `testUpdateEntityEmptyId()` - verify IllegalArgumentException
- `testUpdateEntityLongId()` - verify IllegalArgumentException at 257 chars
- `testUpdateEntityNullType()` - verify IllegalArgumentException
- `testUpdateEntityNegativeCoordinates()` - verify acceptance (valid input)
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

**V2 Note**: After oqh3 moves these fields to BuildConfig, this validation moves to
BuildConfig.validate() or the BuildConfig compact constructor. Coordinate with oqh3.

**Implementation**:
- Add validation at start of constructor (or BuildConfig compact constructor):
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
- `simulation/src/main/java/.../RegionBuilder.java` (constructor)
- `simulation/src/test/java/.../RegionBuilderTest.java` (new or extended)

---

### 5. Luciferase-oqh3: Refactor RenderingServerConfig to composition pattern [L- 8-12 hours]

**Priority**: P1 (Chore)
**Component**: RenderingServerConfig.java, AdaptiveRegionManager.java, RenderingServer.java, RegionBuilder.java
**Risk**: MEDIUM - touches 4+ files, introduces sub-records with 15 total fields, must maintain backward compatibility
**V2 Change**: Expanded from simple field addition (3-4h) to full composition refactoring (4-6h) per C2.
**V3 Change**: Further expanded to define ALL SecurityConfig fields upfront (8-12h) per C2-V3/ACC-V3.

**Problem (V1)**: Several values are hardcoded across multiple files.

**Problem (V2 - expanded)**: Adding these values as flat fields creates a 23-parameter
constructor. The V2 approach creates domain-specific sub-records to keep the config
manageable as subsequent phases add more fields.

**Problem (V3 - corrected)**: V2 described SecurityConfig as "initially empty, Phase 2
adds fields". Java records are immutable; fields cannot be added incrementally. ALL
SecurityConfig fields must be defined in this bead with default/null values. Phase 2
beads then implement server-side logic that reads from these pre-existing fields.

**Implementation** (V3 - composition with all fields upfront):
1. Create `SecurityConfig` record with ALL 8 fields defined:
   - `String apiKey` (default: null = no auth)
   - `boolean redactSensitiveInfo` (default: false in permissive, true in secure)
   - `boolean tlsEnabled` (default: false in permissive, explicit param in secure)
   - `String keystorePath` (default: null)
   - `String keystorePassword` (default: null)
   - `String keyManagerPassword` (default: null)
   - `boolean rateLimitEnabled` (default: false in permissive, true in secure)
   - `int rateLimitRequestsPerMinute` (default: 0 in permissive, 100 in secure)
2. Create factory methods: `SecurityConfig.secure(apiKey, tlsEnabled)`, `SecurityConfig.permissive()`
3. Create `CacheConfig` record (move `maxCacheMemoryBytes` here)
4. Create `BuildConfig` record:
   - Move existing: `buildPoolSize`, `maxBuildDepth`, `gridResolution`
   - Add new: `maxQueueDepth` (default: 100), `circuitBreakerTimeoutMs` (default: 60000),
     `circuitBreakerFailureThreshold` (default: 3)
5. Refactor RenderingServerConfig:
   - Keep top-level: `port`, `regionLevel`, `regionsPerAxis`, `worldMin`, `worldMax`,
     `maxEntitiesPerRegion`, `upstreamServers`
   - Add composed: `SecurityConfig security`, `CacheConfig cache`, `BuildConfig build`
6. Add `validate()` method for cross-field consistency (V3: throws on API key without TLS)
7. Create `secureDefaults(apiKey)`, update `testing()`, deprecate `defaults()`
8. Update ALL direct constructor call sites (CRITICAL - verify with grep)
9. Update AdaptiveRegionManager to use `config.worldMin()`/`config.worldMax()`
10. Update RenderingServer to pass `config.build().maxQueueDepth()`
11. Update RegionBuilder to accept BuildConfig for circuit breaker params
12. **V3 NEW**: Run automated verification: `grep -rn "new SecurityConfig(" simulation/src/`
    to confirm no direct constructor calls outside factory methods

**IMPORTANT - Constructor Compatibility**: This refactoring changes the record signature.
ALL code that calls `new RenderingServerConfig(...)` directly must be updated. Search for
ALL call sites: `grep -rn "new RenderingServerConfig(" simulation/src/`

**IMPORTANT - Constructor Call Site Verification (V3 - VER-V3)**: After refactoring, run:
```bash
# Verify no direct RenderingServerConfig constructor calls remain outside factory methods
grep -rn "new RenderingServerConfig(" simulation/src/ | grep -v "secureDefaults\|testing()\|defaults()"

# Verify no direct SecurityConfig constructor calls remain outside factory methods
grep -rn "new SecurityConfig(" simulation/src/ | grep -v "secure(\|permissive()"
```
Both commands should return zero results. If any results appear, update those call sites
to use factory methods.

**NOTE - CircuitBreakerState Thread Safety**: The existing CircuitBreakerState has
unsynchronized fields (consecutiveFailures, lastFailureTime) but is stored in a
ConcurrentHashMap. Current usage relies on map-level atomicity from compute operations.
Do NOT change the concurrency model in this bead - just parameterize the constants.
Thread safety is tracked separately in Luciferase-2qb9.

**Acceptance Criteria** (UPDATED V3 - addresses ACC-V3):
- [ ] SecurityConfig record created with ALL 8 fields defined:
  - `String apiKey`
  - `boolean redactSensitiveInfo`
  - `boolean tlsEnabled`
  - `String keystorePath`
  - `String keystorePassword`
  - `String keyManagerPassword`
  - `boolean rateLimitEnabled`
  - `int rateLimitRequestsPerMinute`
- [ ] SecurityConfig.secure(String apiKey, boolean tlsEnabled) factory method created
- [ ] SecurityConfig.permissive() factory method created (all security OFF)
- [ ] CacheConfig record created with maxCacheMemoryBytes
- [ ] BuildConfig record created with 6 fields (3 existing + 3 new)
- [ ] RenderingServerConfig uses composition (security, cache, build)
- [ ] validate() method checks cross-field consistency (V3: throws on apiKey without TLS)
- [ ] secureDefaults(apiKey) factory method created, calls SecurityConfig.secure(apiKey, true)
- [ ] testing() factory method updated for composed config
- [ ] defaults() deprecated with @Deprecated annotation
- [ ] AdaptiveRegionManager uses config.worldMin()/worldMax()
- [ ] RenderingServer passes config.build().maxQueueDepth() to RegionBuilder
- [ ] CircuitBreakerState uses config.build().circuitBreakerTimeoutMs() etc.
- [ ] ALL direct RenderingServerConfig constructor call sites updated
- [ ] **V3**: Automated verification confirms no direct constructor calls (grep returns 0 results)
- [ ] All existing tests pass without modification (backward compatible behavior)
- [ ] New tests verify non-default config values are honored
- [ ] New tests verify validate() catches invalid combinations
- [ ] **V3**: New test verifies validate() throws IllegalArgumentException for apiKey without TLS

**Test Requirements**:
- `testCustomWorldBounds()` - verify non-default bounds work
- `testCustomQueueDepth()` - verify builder gets configured depth
- `testCustomCircuitBreakerConfig()` - verify timeout/threshold honored
- `testConfigValidation()` - verify validate() catches TLS without keystore
- `testConfigValidationApiKeyWithoutTls()` - V3: verify validate() throws for apiKey without TLS
- `testSecureDefaults()` - verify secure factory method requires apiKey
- `testTestingConfig()` - verify permissive test factory method
- `testSecurityConfigFields()` - V3: verify all 8 SecurityConfig fields accessible
- Regression: ALL simulation module tests pass

**Files**:
- `simulation/src/main/java/.../RenderingServerConfig.java` (refactored)
- `simulation/src/main/java/.../SecurityConfig.java` (new - ALL 8 fields)
- `simulation/src/main/java/.../CacheConfig.java` (new)
- `simulation/src/main/java/.../BuildConfig.java` (new)
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (use config)
- `simulation/src/main/java/.../RenderingServer.java` (pass config values)
- `simulation/src/main/java/.../RegionBuilder.java` (accept BuildConfig)
- All test files (regression)

---

## Phase 2: Security Hardening (Days 5-8)

**Goal**: Close all P0 security vulnerabilities.
**Completion Criteria**: All 4 security beads closed, no unauthenticated access to sensitive
endpoints, all traffic encryptable.
**V2 Change**: Corrected sequence - TLS (jc5f) implemented BEFORE auth (7jjx) per C1.
**V3 Change**: Phase 2 beads implement server-side logic using pre-existing SecurityConfig
fields. They do NOT modify SecurityConfig record structure (all fields defined in oqh3).
Bead descriptions updated from "add field X" to "implement logic using field X".

### 6. Luciferase-1sa4: Implement info redaction using SecurityConfig [S - 1 hour]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: LOW - output filtering only
**V3 Change**: Bead description updated. Does NOT add fields to SecurityConfig (already
defined in oqh3). Implements the redaction logic in the /api/info handler that reads
from `config.security().redactSensitiveInfo()`.

**Problem**: `/api/info` exposes upstream URIs, memory limits, and internal configuration
details. This enables reconnaissance for targeted attacks.

**Implementation**:
- Implement redaction logic in RenderingServer's handleInfo() method
- Read `config.security().redactSensitiveInfo()` (field already defined in oqh3)
- When redacted:
  - Replace upstream URIs with count only: `"upstreamCount": 3`
  - Replace maxCacheMemoryBytes with percentage: `"cacheUtilization": 45.2`
  - Remove build config details (buildPoolSize, maxBuildDepth, gridResolution)
- Keep version, port, regionLevel, regionsPerAxis (non-sensitive)

**Acceptance Criteria**:
- [ ] Redaction logic implemented in handleInfo() using SecurityConfig.redactSensitiveInfo()
- [ ] Upstream URIs hidden when redacted (count shown instead)
- [ ] Memory limits hidden when redacted (percentage shown instead)
- [ ] Internal config details hidden when redacted
- [ ] Testing config has redact=false (SecurityConfig.permissive()) for backward-compatible tests
- [ ] New test verifies redaction works correctly

**Test Requirements**:
- `testInfoEndpointRedacted()` - verify sensitive fields absent, safe fields present
- `testInfoEndpointUnredacted()` - verify all fields present (test mode)
- Regression: existing RenderingServerTest passes

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (handleInfo method)
- `simulation/src/test/java/.../RenderingServerTest.java` (new + updated tests)

---

### 7. Luciferase-w1tk: Implement rate limiting using SecurityConfig [S - 2 hours]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: LOW - Javalin has built-in rate limiting support
**V3 Change**: Bead description updated. Does NOT add fields to SecurityConfig (already
defined in oqh3). Implements rate limiting middleware that reads from
`config.security().rateLimitEnabled()` and `config.security().rateLimitRequestsPerMinute()`.

**Problem**: Repeated `/api/metrics` calls force expensive `cleanUp()` operations. Without
rate limiting, a single client can DoS the server.

**Implementation**:
- Add Javalin rate limiting middleware (javalin-rate-limiter or custom)
- Read rate limit configuration from SecurityConfig (fields already defined in oqh3):
  - `config.security().rateLimitEnabled()`
  - `config.security().rateLimitRequestsPerMinute()`
- Apply rate limiting to all `/api/*` endpoints
- Return HTTP 429 Too Many Requests when exceeded
- SecurityConfig.permissive() has rateLimitEnabled=false (disabled for testing)

**Acceptance Criteria**:
- [ ] Rate limiting middleware applied to /api/* endpoints
- [ ] Returns 429 when limit exceeded
- [ ] Limit read from SecurityConfig fields (already defined in oqh3)
- [ ] Disabled when SecurityConfig.permissive() used (rateLimitEnabled=false)
- [ ] Rate limit headers included in response (X-RateLimit-Remaining, X-RateLimit-Reset)
- [ ] Test validates rate limiting triggers at configured threshold

**Test Requirements**:
- `testRateLimitTriggered()` - send 101 requests, verify 429 on last
- `testRateLimitHeaders()` - verify X-RateLimit-* headers present
- `testRateLimitDisabled()` - verify no 429 when disabled
- Regression: existing tests pass (rate limiting disabled in test config)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (middleware setup)
- `simulation/src/test/java/.../RenderingServerTest.java` (new tests)
- `simulation/pom.xml` (if new dependency needed)

---

### 8. Luciferase-jc5f: Implement TLS/HTTPS using SecurityConfig [L - 6-8 hours]

**Priority**: P0 (Security)
**Component**: RenderingServer.java
**Risk**: HIGH - SSL configuration complexity, cert management, test infrastructure
**V2 Change**: No longer depends on 7jjx. This bead is now FIRST in the security chain.
7jjx (auth) depends on this bead. TLS must be operational before credentials are transmitted.
**V3 Change**: Bead description updated. Does NOT add fields to SecurityConfig (already
defined in oqh3). Implements SSL/TLS configuration in RenderingServer that reads from
`config.security().tlsEnabled()`, `config.security().keystorePath()`, etc.

**Problem**: All REST and WebSocket traffic is unencrypted clear text. Sensitive data
(entity positions, server configuration) can be intercepted.

**Implementation**:
- Read TLS configuration from SecurityConfig (fields already defined in oqh3):
  - `config.security().tlsEnabled()`
  - `config.security().keystorePath()`
  - `config.security().keystorePassword()`
  - `config.security().keyManagerPassword()`
- Configure Javalin's embedded Jetty with SSL:
  - `SslContextFactory.Server` for HTTPS
  - Optional HTTP->HTTPS redirect
- Self-signed certificate generation utility for development
- WebSocket secure (wss://) support

**Acceptance Criteria**:
- [ ] TLS logic reads from SecurityConfig fields (already defined in oqh3)
- [ ] HTTPS endpoints work with valid keystore
- [ ] WSS (secure WebSocket) works
- [ ] HTTP fallback when TLS not configured (tlsEnabled=false)
- [ ] Self-signed cert utility for development/testing
- [ ] Test with self-signed certificate validates TLS works
- [ ] Null keystorePath with tlsEnabled=false disables TLS (backward compatible)
- [ ] Javadoc documents TLS setup procedure
- [ ] validate() enforcement: apiKey without TLS throws (defined in oqh3, verified here)

**Test Requirements**:
- `testTlsEndpoints()` - HTTPS health check with self-signed cert
- `testWssWebSocket()` - secure WebSocket connection
- `testHttpFallback()` - verify HTTP still works when TLS disabled
- `testTlsHandshakeFailure()` - verify rejection of invalid certs
- Regression: all existing tests pass (TLS disabled by default)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (SSL config)
- `simulation/src/test/java/.../RenderingServerTest.java` (TLS tests)
- `simulation/src/test/resources/test-keystore.jks` (test certificate)

---

### 9. Luciferase-7jjx: Implement authentication using SecurityConfig [M-L - 4-6 hours]

**Priority**: P0 (Security)
**Dependency**: Luciferase-jc5f (TLS MUST be in place first - see C1)
**Component**: RenderingServer.java
**Risk**: HIGH - authentication middleware must not break existing functionality
**V2 Change**: Now BLOCKED BY jc5f (was blocker of jc5f in V1). Credentials are never
transmitted in cleartext because TLS is operational before auth is configured.
**V3 Change**: Bead description updated. Does NOT add fields to SecurityConfig (already
defined in oqh3). Implements authentication middleware that reads from
`config.security().apiKey()`.

**Problem**: All REST endpoints are open with no authentication. Anyone with network access
can query server status, metrics, and eventually stream rendered data.

**Implementation**:
- Phase A: API key authentication (simpler, immediate value)
  - Read `config.security().apiKey()` (field already defined in oqh3)
  - Javalin before() handler checks Authorization header
  - `Authorization: Bearer <api-key>` format
  - Return 401 Unauthorized if key missing/invalid
- Phase B (future): JWT token support (separate bead if needed)
- Exempt /api/health from auth (load balancer health checks)
- **Edge case**: Empty string apiKey should be treated same as null (no auth).
  Validate with: `apiKey == null || apiKey.isBlank()` -> auth disabled
- **V3**: validate() throws IllegalArgumentException if apiKey set without TLS (enforcement, not warning)

**Acceptance Criteria**:
- [ ] Auth middleware reads apiKey from SecurityConfig (already defined in oqh3)
- [ ] /api/info requires valid API key (401 without)
- [ ] /api/metrics requires valid API key (401 without)
- [ ] /api/health exempt from auth (for load balancers)
- [ ] /ws/render requires valid API key in query param or header
- [ ] Null or blank apiKey in SecurityConfig disables auth (backward compatible)
- [ ] Testing SecurityConfig (permissive) has null apiKey (no auth for tests)
- [ ] secureDefaults() requires non-null, non-blank apiKey
- [ ] **V3**: validate() throws IllegalArgumentException if auth enabled without TLS

**Test Requirements**:
- `testAuthRequiredForInfo()` - 401 without key
- `testAuthRequiredForMetrics()` - 401 without key
- `testHealthExemptFromAuth()` - 200 without key
- `testAuthAccepted()` - 200 with valid key
- `testAuthRejected()` - 401 with invalid key
- `testAuthWithoutTlsThrows()` - V3: validate() throws IllegalArgumentException
- Regression: existing tests pass (auth disabled in test config)

**Files**:
- `simulation/src/main/java/.../RenderingServer.java` (auth middleware)
- `simulation/src/test/java/.../RenderingServerTest.java` (auth tests)
- `simulation/src/test/java/.../RenderingServerIntegrationTest.java` (update if needed)

---

## Phase 3: Performance & Robustness (Days 9-10)

**Goal**: Fix performance bottlenecks, add robustness guards, close test gaps.
**Completion Criteria**: All 6 beads closed, emergency eviction O(n+k log n), queue protected,
circuit breaker tested end-to-end.
**V2 Change**: Expanded from 4 to 6 beads (ko4u and sg70 moved from Phase 4 per R1).
Phase 3 is fully parallelizable - all 6 beads can run concurrently.

### 10. Luciferase-bkji: Add forceAccurate parameter to getUnpinnedMemoryBytes() [S - 1-2 hours]

**Priority**: P1 (Performance)
**Dependency**: Luciferase-h86g (learning dependency - understand cleanUp semantics first)
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
- `maxEntitiesPerRegion` is a top-level field on RenderingServerConfig (default: 10000)
- Pass to AdaptiveRegionManager constructor
- Check in `updateEntity()` before adding to region
- Log warning when limit approached (>90%), reject at limit

**Acceptance Criteria**:
- [ ] `maxEntitiesPerRegion` field on RenderingServerConfig (default: 10000)
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
- `simulation/src/main/java/.../RenderingServerConfig.java` (top-level field)
- `simulation/src/main/java/.../AdaptiveRegionManager.java` (limit check)
- `simulation/src/test/java/.../AdaptiveRegionManagerTest.java` (new tests)

---

### 13. Luciferase-mauo: Optimize emergency eviction with min-heap [M - 2-3 hours]

**Priority**: P1 (Performance)
**Component**: RegionCache.java
**Risk**: HIGH (escalated from MEDIUM per E1)
**V2 Change**: Risk escalated to HIGH because this is a critical OOM-prevention path
and the algorithm change involves concurrent data structure modification.

**Risk Escalation Rationale**:
- Emergency eviction is the last line of defense against OutOfMemoryError
- Algorithm change in concurrent code path (other threads may access pinnedCache)
- PriorityQueue is NOT thread-safe; must ensure no concurrent modification during eviction
- If eviction fails or produces wrong results, the server crashes with OOM

**Problem**: `emergencyEvict()` uses `stream().sorted().collect()` which is O(n log n)
on all pinned entries. For large pinned sets, this creates GC pressure and latency.

**Implementation**:
- Replace sorted stream with `PriorityQueue<Map.Entry<CacheKey, CachedRegion>>`
- Comparator: `Comparator.comparingLong(e -> e.getValue().lastAccessedMs())`
- Add all entries to PriorityQueue (O(n))
- Poll entries until `bytesEvicted >= bytesToEvict` (O(k log n))
- Total: O(n + k log n) vs O(n log n), where k << n typically
- CRITICAL: Ensure snapshot of pinnedCache entries used (not live iteration)

**Acceptance Criteria**:
- [ ] PriorityQueue replaces stream().sorted().collect()
- [ ] Same eviction order (oldest lastAccessedMs first)
- [ ] Same bytesToEvict calculation
- [ ] Same C4 atomic remove behavior preserved
- [ ] Snapshot of entries used (no ConcurrentModificationException risk)
- [ ] Performance test shows improvement for large pinned sets (>1000 entries)
- [ ] Existing emergency eviction tests pass with identical results

**Test Requirements**:
- `testMinHeapEvictionOrder()` - verify oldest evicted first
- `testMinHeapEvictionAmount()` - verify correct bytes evicted
- `testMinHeapConcurrency()` - verify C4 guard still works
- `testMinHeapSnapshotIsolation()` - verify eviction works while other threads access cache
- Performance: benchmark with 1000+ pinned entries, compare stream vs PQ
- Regression: all emergency eviction tests pass

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (emergencyEvict method)
- `simulation/src/test/java/.../RegionCacheTest.java` (updated + new tests)

---

### 14. Luciferase-ko4u: Optimize pinned cache access overhead [S - 1-2 hours]

**Priority**: P3 (Suggestion)
**Dependency**: Luciferase-h86g (learning dependency - understand pin cleanUp interaction)
**Component**: RegionCache.java
**Risk**: MEDIUM - Option A introduces two-map consistency concern during emergency eviction
**V2 Change**: Moved from Phase 4 to Phase 3 (blocker h86g completes in Phase 1). Added
JMH test mechanism specification per S4.

**Problem**: `RegionCache.get()` for pinned entries calls `computeIfPresent()` which
creates a new `CachedRegion` record on EVERY access. This generates garbage for
high-frequency reads.

**Implementation**:
- Option A (recommended): Track access time in separate `ConcurrentHashMap<CacheKey, AtomicLong>`
  - Read from pinnedCache, update timestamp in separate map
  - Emergency eviction reads timestamps from separate map
  - Zero allocation on get()
- Option B: Update timestamp only every N accesses (sampling)
- Option C: Use volatile long field in mutable wrapper

**Test Mechanism (V2 - addresses S4)**:
The "no allocation" acceptance criterion is verified using JMH with `-prof gc`:
```bash
mvn test -pl simulation -Dtest=RegionCacheBenchmark -Djmh.prof=gc
```
The benchmark measures `gc.alloc.rate.norm` (bytes allocated per operation). The target
is 0 bytes per `get()` call for pinned entries. If JMH infrastructure is too heavy for
this single test, an alternative functional test verifies the timestamp map is used:
- Call `get()` 1000 times
- Verify `pinnedCache.computeIfPresent()` NOT called (mock or spy)
- Verify `accessTimeMap.put()` called instead

**Acceptance Criteria**:
- [ ] No new object allocation per `get()` call for pinned entries
- [ ] Access timestamp still tracked (for emergency eviction ordering)
- [ ] Emergency eviction still evicts oldest-accessed entries first
- [ ] Benchmark shows gc.alloc.rate.norm = 0 for pinned get() (JMH -prof gc)
- [ ] Existing tests pass with identical behavior

**Test Requirements**:
- `testPinnedGetNoAllocation()` - functional test verifying timestamp map used, not computeIfPresent
- `testEmergencyEvictionStillOrdered()` - verify oldest access evicted first
- Performance: JMH benchmark with `-prof gc` measuring allocation per get()
- Regression: all RegionCache tests pass

**Files**:
- `simulation/src/main/java/.../RegionCache.java` (get method + emergency eviction)
- `simulation/src/test/java/.../RegionCacheTest.java` (new + updated tests)

---

### 15. Luciferase-sg70: Add circuit breaker integration test [M - 2-3 hours]

**Priority**: P2 (Test Gap)
**Dependency**: Luciferase-dtnt (defensive copy should be in place)
**Component**: BuildIntegrationTest.java
**Risk**: MEDIUM - test must reliably trigger failure and recovery
**V2 Change**: Moved from Phase 4 to Phase 3 (blocker dtnt completes in Phase 1 per R1).

**Problem**: Current circuit breaker tests only validate wiring, not actual open/close
state transitions. Need end-to-end test of failure -> open -> timeout -> close cycle.

**Implementation**:
- Create test that injects build failures (mock or use invalid data)
- Trigger 3 consecutive failures for same regionId (or config.build().circuitBreakerFailureThreshold())
- Verify next build rejected with CircuitBreakerOpenException
- Advance clock past timeout (config.build().circuitBreakerTimeoutMs())
- Verify next build succeeds
- Verify circuit breaker cleared on success

**Acceptance Criteria**:
- [ ] Test forces N consecutive failures for same region (N = failure threshold from config)
- [ ] Next build attempt throws CircuitBreakerOpenException
- [ ] After timeout, next build attempt succeeds
- [ ] Successful build clears circuit breaker state
- [ ] Test uses TestClock for deterministic timeout control
- [ ] Test completes in < 5 seconds (no real timeout wait)

**Test Requirements**:
- `testCircuitBreakerFullLifecycle()`:
  1. Submit N failing builds -> verify failures recorded
  2. Submit (N+1)th build -> verify CircuitBreakerOpenException
  3. Advance TestClock by timeout + 1 second
  4. Submit next build -> verify acceptance and success
  5. Verify circuit breaker cleared

**Files**:
- `simulation/src/test/java/.../BuildIntegrationTest.java` (new test method)

---

## Phase 4: Polish & Testing (Days 10-11)

**Goal**: Close remaining items, apply final optimizations.
**Completion Criteria**: All 2 beads closed, full test suite passes.
**V2 Change**: Reduced from 4 beads to 2 (ko4u and sg70 moved to Phase 3).

### 16. Luciferase-rp9u: Cache health/metrics responses with 1s TTL [S - 1-2 hours]

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

## Risk Assessment Summary (unchanged from V2)

| Bead | Risk | V1 Risk | Primary Risk | Mitigation |
|------|------|---------|-------------|------------|
| h86g | LOW | LOW | None significant | One-line change, well-understood |
| dtnt | LOW | LOW | None significant | Standard immutability pattern |
| bjjn | LOW | LOW | Over-validation | Test edge cases, only reject truly invalid |
| 8nbh | LOW | LOW | None significant | Standard constructor validation |
| oqh3 | MEDIUM | MEDIUM | Config regression + composition complexity | Keep defaults identical, test factory methods, validate() |
| 1sa4 | LOW | LOW | Over-redaction | Config flag for toggling |
| w1tk | LOW | LOW | Rate limit too aggressive | Configurable limits, disabled in tests |
| jc5f | HIGH | HIGH | SSL complexity | Null keystore disables, HTTP fallback |
| 7jjx | HIGH | HIGH | Auth breaks existing | Null apiKey disables, test config unchanged |
| bkji | LOW | LOW | API confusion | Clear javadoc, backward-compatible default |
| xox5 | LOW | LOW | Overly conservative skip | 80% threshold with logging |
| vtet | LOW | LOW | Legitimate high-density | Configurable limit, log warnings |
| **mauo** | **HIGH** | MEDIUM | **Algorithm correctness in OOM-prevention path + concurrency** | Snapshot isolation, same comparator, extensive assertion tests |
| sg70 | MEDIUM | MEDIUM | Flaky timing | TestClock for determinism, no real waits |
| ko4u | MEDIUM | MEDIUM | Two-map consistency | Separate timestamp map risks desync during eviction |
| rp9u | LOW | LOW | Stale monitoring data | 1s TTL is acceptable for monitoring |
| 8chv | MEDIUM | MEDIUM | setSkew migration | Pre-check all callers, run full test suite |

**Risk Distribution**: 3 HIGH, 4 MEDIUM, 10 LOW (V1 was 2 HIGH, 5 MEDIUM, 10 LOW)

---

## Effort Summary (UPDATED V3 - addresses EFF-V3)

### Recount Methodology (NEW V3)

V2 claimed "Raw component sum: 37-58 hours" but this did not match the actual bead-by-bead
estimates. V3 performs a line-by-line recount of all 17 beads, using the effort estimates
stated in each bead's header.

### Line-by-Line Bead Recount

| # | Bead | Size | Low (h) | High (h) | Source |
|---|------|------|---------|----------|--------|
| 1 | h86g | XS | 0.5 | 0.5 | "XS - 30 min" |
| 2 | dtnt | XS | 0.5 | 0.5 | "XS - 30 min" |
| 3 | bjjn | S | 1.0 | 2.0 | "S - 1-2 hours" |
| 4 | 8nbh | XS | 0.5 | 0.5 | "XS - 30 min" |
| 5 | oqh3 | L- | **8.0** | **12.0** | "L- 8-12 hours" (V3: expanded from 4-6h) |
| 6 | 1sa4 | S | 1.0 | 1.0 | "S - 1 hour" |
| 7 | w1tk | S | 2.0 | 2.0 | "S - 2 hours" |
| 8 | jc5f | L | 6.0 | 8.0 | "L - 6-8 hours" |
| 9 | 7jjx | M-L | 4.0 | 6.0 | "M-L - 4-6 hours" |
| 10 | bkji | S | 1.0 | 2.0 | "S - 1-2 hours" |
| 11 | xox5 | S | 1.0 | 1.0 | "S - 1 hour" |
| 12 | vtet | S | 1.0 | 2.0 | "S - 1-2 hours" |
| 13 | mauo | M | 2.0 | 3.0 | "M - 2-3 hours" |
| 14 | ko4u | S | 1.0 | 2.0 | "S - 1-2 hours" |
| 15 | sg70 | M | 2.0 | 3.0 | "M - 2-3 hours" |
| 16 | rp9u | S | 1.0 | 2.0 | "S - 1-2 hours" |
| 17 | 8chv | M | 2.0 | 3.0 | "M - 2-3 hours" |
| | **TOTAL** | | **34.5** | **50.5** | |

### V2 vs V3 Comparison

| Metric | V2 Claimed | V2 Actual (recount) | V3 (corrected) |
|--------|-----------|---------------------|----------------|
| Raw low (hours) | 37 | 30.5 | 34.5 |
| Raw high (hours) | 58 | 44.5 | 50.5 |
| Discrepancy | - | 6.5-13.5h overcounted | 0 (verified) |
| oqh3 effort | 4-6h | 4-6h | 8-12h (expanded) |

**V2 Arithmetic Error**: V2 claimed "37-58 hours raw" but the actual sum of individual
bead estimates was 30.5-44.5 hours (a 6.5-13.5 hour overcounting). V3 corrects this with
the verified line-by-line recount above. The V3 total of 34.5-50.5h reflects the expanded
oqh3 (8-12h vs 4-6h) applied to the corrected base.

### Buffered Estimates (V3)

| Estimate Type | Hours | Rationale |
|---------------|-------|-----------|
| Optimistic (raw) | 34.5 | All beads at low end, no surprises |
| Realistic (raw) | 42.5 | Mid-range estimates, some complexity |
| Pessimistic (raw) | 50.5 | All beads at high end |
| **Planning target (20% buffer)** | **61** | Pessimistic + 20% for unknowns, integration issues |

**Buffer rationale**: 20% covers discovery work during implementation (e.g., finding
additional constructor call sites for oqh3, SSL compatibility issues for jc5f, migration
edge cases for 8chv). This is standard engineering practice for plans with HIGH-risk items.

### Calendar Estimates (V3)

| Scenario | Daily Hours | Calendar Days | Notes |
|----------|-------------|---------------|-------|
| 1 developer, realistic | 6h/day | 7-8 days | Raw 42.5h midpoint estimate |
| 1 developer, with buffer | 6h/day | 10-11 days | Planning target 61h |
| 2 developers, with buffer | 6h/day each | 6-7 days | 75-80% parallelizable |

---

## Parallelization Analysis (unchanged from V2)

### Phase 1 (5 independent beads - fully parallel):
- Track A: h86g [XS]
- Track B: dtnt [XS]
- Track C: bjjn [S]
- Track D: 8nbh [XS]
- Track E: oqh3 [L-] (longest - determines phase duration)
- **Phase 1 wall-clock**: 8-12 hours (V3: limited by expanded oqh3)

### Phase 2 (3 parallel + 1 sequential):
- Track A: 1sa4 [S] (independent)
- Track B: w1tk [S] (independent)
- Track C: jc5f [L] -> 7jjx [M-L] (SEQUENTIAL - critical path)
- **Phase 2 wall-clock**: 10-14 hours (limited by jc5f -> 7jjx chain)

### Phase 3 (6 independent beads - fully parallel):
- Track A: bkji [S] (blocked by h86g, satisfied in Phase 1)
- Track B: xox5 [S] (independent)
- Track C: vtet [S] (independent)
- Track D: mauo [M] (independent)
- Track E: ko4u [S] (blocked by h86g, satisfied in Phase 1)
- Track F: sg70 [M] (blocked by dtnt, satisfied in Phase 1)
- **Phase 3 wall-clock**: 2-3 hours (limited by mauo or sg70)

### Phase 4 (2 independent beads - fully parallel):
- Track A: rp9u [S] (blocked by bkji+w1tk, satisfied in Phases 2-3)
- Track B: 8chv [M] (independent)
- **Phase 4 wall-clock**: 2-3 hours (limited by 8chv)

### Parallelization Summary

**Total work**: 42-61 hours (buffered)
**Critical path**: oqh3 (8-12h) -> jc5f (6-8h) -> 7jjx (4-6h) = 18-26h sequential
**Non-critical-path work**: runs alongside critical path in all phases

**Parallelization ratio**: 75-80%
- 17 beads total
- Only 1 forced sequential pair within a phase: jc5f -> 7jjx
- All other beads can run in parallel within their phase
- Cross-phase dependencies satisfied by phase boundaries

---

## Phase Completion Checklists (UPDATED V3)

### Phase 1 Complete When:
- [ ] All 5 beads closed (h86g, dtnt, bjjn, 8nbh, oqh3)
- [ ] `mvn test -pl simulation` passes
- [ ] No regressions in any module
- [ ] Config composition foundation established (SecurityConfig, CacheConfig, BuildConfig)
- [ ] **V3**: ALL 8 SecurityConfig fields defined with factory methods
- [ ] validate() method working for cross-field checks
- [ ] **V3**: validate() throws IllegalArgumentException for apiKey without TLS
- [ ] secureDefaults() and testing() factory methods available
- [ ] **V3**: Automated grep verification shows no direct constructor calls

### Phase 2 Complete When:
- [ ] All 4 beads closed (1sa4, w1tk, jc5f, 7jjx)
- [ ] TLS operational BEFORE authentication configured (C1 verified)
- [ ] All endpoints authenticated (except /api/health)
- [ ] Rate limiting active on /api/* endpoints
- [ ] Sensitive info redacted from /api/info
- [ ] TLS available (enabled by default in secureDefaults)
- [ ] SecurityConfig fields used by server-side logic (no record structure changes in Phase 2)
- [ ] Security review pass

### Phase 3 Complete When:
- [ ] All 6 beads closed (bkji, xox5, vtet, mauo, ko4u, sg70)
- [ ] Emergency eviction uses O(n + k log n) algorithm with snapshot isolation
- [ ] Monitoring calls don't force cleanUp()
- [ ] Backfill respects queue capacity
- [ ] Entity count limit enforced
- [ ] Pinned get() allocation-free (verified with JMH or functional test)
- [ ] Circuit breaker has end-to-end integration test

### Phase 4 Complete When:
- [ ] All 2 beads closed (rp9u, 8chv)
- [ ] Response caching reduces monitoring overhead
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

### Line Number Guidance (R2)

**Line numbers in this plan are approximate.** The codebase may have evolved since the
code review was conducted. When implementing beads:
1. Use the **problem description** and **component name** to locate the actual code
2. Search for method names or patterns mentioned (e.g., `grep -n "emergencyEvict" RegionCache.java`)
3. Do NOT assume line numbers are exact - verify before editing

### Learning vs Code Dependencies (E2)

Some dependencies in this plan are "learning dependencies" rather than strict code dependencies:
- **h86g -> bkji**: Understanding cleanUp() semantics in h86g informs the forceAccurate
  parameter design in bkji. No code from h86g is imported/used by bkji.
- **h86g -> ko4u**: Understanding pin/cleanUp interaction informs pinned access optimization.
  No code dependency.
- **dtnt -> sg70**: Defensive copy ensures test data integrity. Weak code dependency
  (sg70 could work without dtnt, but test reliability improves with it).

**Impact**: Learning dependencies can be relaxed if the developer already understands the
domain. Strict code dependencies (jc5f -> 7jjx, w1tk -> rp9u, bkji -> rp9u) cannot.

### CircuitBreakerState Thread Safety (R3)

CircuitBreakerState thread safety analysis is tracked in **Luciferase-2qb9** (separate
from this plan). The current implementation relies on ConcurrentHashMap.compute() for
atomicity. This plan (oqh3) parameterizes the circuit breaker constants but does NOT
change the concurrency model.

### V3 Key Principle: Structure vs Behavior Separation

**oqh3 defines structure; Phase 2 implements behavior.**

- **oqh3 (Phase 1)**: Defines SecurityConfig with all 8 fields, factory methods (secure,
  permissive), and validation. No server-side security logic is implemented.
- **1sa4, w1tk, jc5f, 7jjx (Phase 2)**: Implement server-side security features (redaction
  handlers, rate limiting middleware, SSL configuration, auth middleware) that READ FROM
  the pre-existing SecurityConfig fields. No record structure changes.

This separation means:
1. SecurityConfig constructor is stable after Phase 1
2. Phase 2 beads are truly independent (no cascading constructor breaks)
3. Phase 2 beads can be developed and tested in parallel (except jc5f -> 7jjx)

### Component Context
- **RenderingServer.java**: Javalin HTTP server, REST endpoints, lifecycle management
- **RenderingServerConfig.java**: Immutable record -> V2: composed of sub-records
- **SecurityConfig.java**: V3: ALL 8 security fields defined in oqh3 with factory methods
- **CacheConfig.java**: V2 NEW - cache memory configuration
- **BuildConfig.java**: V2 NEW - build pool, queue, circuit breaker configuration
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
- **V2 NEW**: Composed records for configuration management
- **V3 NEW**: Structure/behavior separation for incremental security feature delivery

---

## Self-Audit Results (V3)

**V1 Audit Date**: 2026-02-14
**V1 Auditor**: plan-auditor (self-audit)
**V1 Verdict**: GO with 5 conditions (all addressed)

**V2 Audit Date**: 2026-02-14
**V2 Sources**: plan-auditor formal review + deep-critic analysis
**V2 Verdict**: All findings addressed (3 Critical, 4 Significant, 3 Recommendations, 2 Escalations)

**V2 Re-Audit Date**: 2026-02-14
**V2 Re-Audit Source**: plan-auditor re-audit of V2
**V2 Re-Audit Verdict**: CONDITIONAL GO (requires C2-V3 fix + 5 additional issues)

**V3 Audit Date**: 2026-02-14
**V3 Sources**: V2 re-audit findings (6 issues: 3 critical, 3 significant)
**V3 Verdict**: All 6 findings addressed (self-audit pending external re-audit)

### V1 Issues (addressed in V1)

| ID | Severity | Issue | Resolution |
|----|----------|-------|------------|
| C1 | Critical | RenderingServerConfig constructor breaks on field addition | Addressed in V1 oqh3 |
| I1 | Important | CircuitBreakerState thread safety | Documented in V1 oqh3 NOTE |
| I2 | Important | ko4u risk underestimated | Upgraded from LOW to MEDIUM in V1 |
| I3 | Important | 8chv missing method caller search | Expanded search scope in V1 |
| S3 | Minor | 7jjx empty-string apiKey edge case | Added blank-handling in V1 |

### V2 Issues (addressed in V2)

| ID | Source | Severity | Issue | Resolution |
|----|--------|----------|-------|------------|
| C1 | Deep-Critic | Critical | Security sequence backwards (auth before TLS) | Reversed: jc5f blocks 7jjx |
| C2 | Deep-Critic | Critical | Configuration explosion (9->23 params) | Added composition strategy, expanded oqh3 |
| C3 | Deep-Critic | Critical | Effort estimates lack buffer | Added 20% buffer |
| S1 | Deep-Critic | Significant | Secure defaults undefined | Added secureDefaults() definition |
| S2 | Deep-Critic | Significant | Validation scope mismatch (bjjn) | Clarified 256-char as defense-in-depth |
| S3 | Deep-Critic | Significant | Parallelization undercounted | Re-analyzed to 75-80% |
| S4 | Deep-Critic | Significant | Test mechanism missing (ko4u) | Specified JMH -prof gc mechanism |
| R1 | Plan-Auditor | Recommendation | Phase rebalancing | ko4u/sg70 moved to Phase 3 |
| R2 | Plan-Auditor | Recommendation | Line number guidance | Added implementation note |
| R3 | Plan-Auditor | Recommendation | CircuitBreakerState tracking | Created Luciferase-2qb9 |
| E1 | Deep-Critic | Escalation | mauo risk too low | MEDIUM -> HIGH |
| E2 | Deep-Critic | Clarification | Learning vs code dependencies | Added implementation note |

### V3 Issues (addressed in this revision)

| ID | Source | Severity | Issue | Resolution |
|----|--------|----------|-------|------------|
| C2-V3 | V2 Re-Audit | Critical | SecurityConfig "empty initially" impossible (Java records immutable) | oqh3 defines ALL 8 SecurityConfig fields upfront; effort 4-6h -> 8-12h |
| TLS-V3 | V2 Re-Audit | Critical | TLS enabled state hidden inside secure() factory | secure() now takes explicit `boolean tlsEnabled` parameter |
| VAL-V3 | V2 Re-Audit | Critical | validate() only warns for apiKey without TLS | validate() throws IllegalArgumentException (enforcement, not warning) |
| EFF-V3 | V2 Re-Audit | Significant | Effort arithmetic: claimed 37-58h, actual 30.5-44.5h | Line-by-line recount: 34.5-50.5h raw, 42-61h buffered, 61h planning target |
| VER-V3 | V2 Re-Audit | Significant | No verification for constructor call sites | Added automated grep verification to oqh3 acceptance criteria |
| ACC-V3 | V2 Re-Audit | Significant | oqh3 acceptance criteria says "empty initially" | Lists all 8 fields explicitly; Phase 2 says "implement using" not "add to" |

### Quality Criteria Verification (V3)

- [x] All 3 critical issues (C2-V3, TLS-V3, VAL-V3) resolved with code changes
- [x] All 3 significant issues (EFF-V3, VER-V3, ACC-V3) resolved
- [x] Line-by-line effort recount completed and documented (17 beads, totals verified)
- [x] All Phase 2 bead descriptions consistently use "implement using" language
- [x] SecurityConfig field list explicitly documented (8 fields in inventory table)
- [x] Automated grep verification added to oqh3 acceptance criteria
- [x] validate() enforcement mechanism specified (throws IllegalArgumentException)
- [x] V3 Change Log tracks all 6 fixes with IDs and section references
- [x] No new contradictions introduced (self-checked: "empty initially" language removed throughout)
