# Phase 2: GPU Integration -- Execution Plan V2

**Date**: 2026-02-13
**Author**: strategic-planner
**Status**: READY FOR AUDIT
**Duration**: 9.5 working days (Day 0 benchmark + Days 1-8 + Day 9-9.5 buffer)
**Epic Bead**: Luciferase-r47c

**V2 Architecture**: `simulation/doc/plans/PHASE_2_GPU_INTEGRATION_ARCHITECTURE_V2.md`
**V1 Execution Plan**: `simulation/doc/plans/PHASE_2_EXECUTION_PLAN.md` (superseded)
**Critique**: `simulation/doc/plans/PHASE_2_PLAN_CRITIQUE.md`
**Parent Architecture**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE.md`
**ChromaDB**: `plan::strategic-planner::phase2-gpu-integration-v2-2026-02-13`

---

## 1. Executive Summary

Phase 2 adds the voxel build pipeline and region cache to the GPU-Accelerated
ESVO/ESVT Rendering Service. It converts entity positions (tracked by Phase 1's
AdaptiveRegionManager) into compact ESVO or ESVT sparse voxel structures using
the proven OctreeBuilder and ESVTBuilder from the render module, then caches the
serialized results for streaming (Phase 3).

**V2 Revision**: This plan addresses all 9 critique issues (4 critical, 5 significant)
plus 8 additional improvements identified in `PHASE_2_PLAN_CRITIQUE.md`. Key changes:

- **Caffeine 3.1.8** replaces custom LRU (O(1) amortized instead of O(n))
- **RegionBuilder** replaces misleading "GpuESVOBuilder" naming (CPU-only code)
- **Day 0 benchmark** prerequisite validates build time assumptions before implementation
- **Circuit breaker** prevents retry storms on persistent build failures
- **45 tests** (up from 30) covering failure modes, races, and performance gates
- **9.5 days** (up from 7.5) with 21% schedule buffer

**Scope**: 3 new production files, 5 new test files, 3 modified production files.
**Estimated**: ~1,220 LOC production, 45 tests across ~1,150 LOC test code.

**Phase 1 Baseline**: 9 production files, 6 test files, 2,513 LOC, 43 tests, all passing.

---

## 2. Changes from V1 Execution Plan

| Area | V1 | V2 | Issue |
|------|----|----|-------|
| LRU Cache | ConcurrentHashMap + ConcurrentLinkedDeque (O(n)) | Caffeine 3.1.8 (O(1) amortized) | C2 |
| Builder Name | GpuESVOBuilder | **RegionBuilder** | S1 |
| Config Name | gpuPoolSize, gpuEnabled | **buildPoolSize**, gpuEnabled removed | S1, A1 |
| Schedule | 7.5 days (6.7% buffer) | **9.5 days (21% buffer)** | S5 |
| Test Count | 30 tests | **45 tests** | S4 |
| Day 0 | None | **Benchmark prerequisite** | C1 |
| Build Failures | Silent, no metrics | **Circuit breaker** (3 fails/60s) + failedBuilds metric | C3 |
| Emergency Eviction | No concurrency guard | **AtomicBoolean guard** | C4 |
| Queue Eviction | O(n) linear scan | **O(log n) ConcurrentSkipListSet** | S2 |
| Setter Injection | No backfill | **backfillDirtyRegions()** | S3 |
| Compression | Always GZIP | **Threshold-based** (skip < 200 bytes) | A3 |
| Metrics | Counters only | **/api/metrics endpoint** | M2 |
| Degradation | Undefined | **Documented per-scenario** | M4 |

---

## 3. Bead Hierarchy

```
Epic: Luciferase-r47c  Phase 2: GPU Integration - Build Pipeline and Region Cache
  |
  +-- Luciferase-jbbx  Phase 2A: Core Build Pipeline (Days 0-3)
  |     |
  |     +-- Luciferase-card  Day 0: Benchmark OctreeBuilder [NEW]
  |     +-- Luciferase-an6a  SerializationUtils + compression threshold (Day 1)
  |     +-- Luciferase-q8fp  RegionBuilder records + positionsToVoxels (Day 2)
  |     +-- Luciferase-58co  RegionBuilder pipeline + C1 + C3 + S2 (Day 3)
  |
  +-- Luciferase-wxu9  Phase 2B: Region Caching with Caffeine (Days 4-5)
  |     |
  |     +-- Luciferase-f7xd  RegionCache core with Caffeine (Day 4)
  |     +-- Luciferase-lzjb  RegionCache M1 + C4 emergency guard (Day 5)
  |
  +-- Luciferase-gix5  Phase 2C: Integration, Testing, and Buffer (Days 6-9.5)
        |
        +-- Luciferase-7h3l  Integration wiring + backfill + metrics (Day 6)
        +-- Luciferase-2yfa  Testing + quality gates + buffer (Day 7-9.5)
```

---

## 4. Dependency Graph

```
Luciferase-card  Benchmark OctreeBuilder [Day 0]
        |
        |   Luciferase-an6a  SerializationUtils [Day 1]
        |        |
        +--------+
        |
        v
Luciferase-q8fp  RegionBuilder records [Day 2]
        |                           \
        v                            \
Luciferase-58co  RegionBuilder       Luciferase-f7xd  RegionCache core [Day 4]
  pipeline + C1 + C3 [Day 3]               |
        |                                  v
        |                          Luciferase-lzjb  RegionCache M1 + C4 [Day 5]
        |                                  |
        +----------------------------------+
        |
        v
Luciferase-7h3l  Integration + backfill + metrics [Day 6]
        |
        v
Luciferase-2yfa  Testing + quality gates [Day 7-9.5]
```

**Critical Path**: card -> q8fp -> 58co -> 7h3l -> 2yfa (5 sequential steps + Day 0)
**Parallel Opportunities**:
- an6a (Day 1) runs concurrently with card (Day 0) or immediately after
- f7xd (Day 4) can start after q8fp (Day 2), overlapping with 58co (Day 3)

---

## 5. Maven Dependency

Add Caffeine to `simulation/pom.xml` (version managed in root pom.xml `<dependencyManagement>` at line 151):

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

Caffeine 3.1.8 is already declared in root pom.xml. No version tag needed in module pom.xml.
Follow project convention: multiple artifacts from same groupId use version property in root.

---

## 6. Day-by-Day Task Breakdown

### Day 0: Benchmark OctreeBuilder (Luciferase-card) [NEW]

**Objective**: Validate build time assumptions before committing to implementation parameters.

**Files to Create**:
- `simulation/src/test/java/.../viz/render/OctreeBuilderBenchmark.java` (~80 LOC)

**Implementation Details**:

1. **OctreeBuilderBenchmark.java** -- Test class (not JMH) for quick validation:
   - Measure `OctreeBuilder.buildFromVoxels()` for 10/100/1000 voxels
   - Test at resolutions: 16^3, 64^3, 128^3
   - 20 iterations per configuration, report P50 and P99
   - Quality gates: P50 < 50ms, P99 < 200ms at 64^3/100 voxels

2. **Portal Pattern Verification** (A2):
   - Read `portal/.../RenderService.java` position-to-voxel logic
   - Compare normalization and quantization approach
   - Document any differences in benchmark report

3. **Fallback Documentation**:
   - If P50 > 100ms at 64^3: Set `buildPoolSize = max(1, availableProcessors / 2)`
   - If still > 100ms: Reduce `gridResolution` to 32
   - If P99 > 500ms: Consider deferring non-visible builds entirely

**Entry Criteria**: Phase 1 complete, `mvn test -pl simulation` passes all 43 tests.
**Exit Criteria**: Benchmark results documented; P50 < 50ms and P99 < 200ms confirmed; portal pattern verified; fallback strategy documented.
**Risks**: OctreeBuilder may perform differently than estimated; mitigate with fallback plan.

---

### Day 1: SerializationUtils (Luciferase-an6a)

**Objective**: Create the serialization foundation with compression-threshold-aware GZIP.

**Files to Create**:
- `simulation/src/main/java/.../viz/render/SerializationUtils.java` (~220 LOC)
- `simulation/src/test/java/.../viz/render/SerializationUtilsTest.java` (~180 LOC)

**Implementation Details**:

1. **SerializationUtils.java** -- Stateless utility class (final, private constructor):
   - `static byte[] serializeESVO(ESVOOctreeData data)` -- Version header (4 bytes, VERSION=1) + format byte (0x01) + metadata (nodeCount, farPtrCount, maxDepth, leafCount, internalCount as 4-byte ints) + node data via ESVONodeUnified.writeTo(ByteBuffer) + far pointers. **A3: Conditionally GZIP compress only if raw size >= 200 bytes.**
   - `static ESVOOctreeData deserializeESVO(byte[] data)` -- Auto-detect GZIP via magic number (0x1F 0x8B), decompress if needed, parse header, validate version/format, reconstruct ESVOOctreeData
   - `static byte[] serializeESVT(ESVTData data)` -- Same header + ESVT-specific fields (rootType, contourCount, contours, gridResolution). Conditional GZIP.
   - `static ESVTData deserializeESVT(byte[] data)` -- Auto-detect, reconstruct using ESVTData constructor
   - `static boolean isCompressed(byte[] data)` -- Check GZIP magic number
   - `static byte[] gzipCompress(byte[] raw)` -- GZIPOutputStream
   - `static byte[] gzipDecompress(byte[] compressed)` -- GZIPInputStream
   - `static final int COMPRESSION_THRESHOLD_BYTES = 200` -- Skip GZIP below this

2. **Also**: Add Caffeine dependency to `simulation/pom.xml` (prepare for Day 4)

3. **Tests** (7 total):
   - `testESVORoundTrip_preservesData` -- Build 10-node ESVOOctreeData via OctreeBuilder, serialize, deserialize, verify all fields match
   - `testESVTRoundTrip_preservesData` -- Build ESVTData via ESVTBuilder, serialize, deserialize, verify nodes/contours/farPointers/rootType/maxDepth
   - `testGZIPCompression_reducesByteSize` -- Compress repetitive 1000-byte array, verify compressed size < original
   - `testGZIPRoundTrip_preservesContent` -- Compress random bytes, decompress, verify identical
   - `testVersionValidation_rejectsWrongVersion` -- Manually create compressed data with version=99, assert IllegalArgumentException on deserialize
   - `testFormatValidation_rejectsWrongFormat` -- Pass ESVT-format data to deserializeESVO(), assert IllegalArgumentException
   - `testCompressionThreshold_skipsGzipForSmallRegions` **[NEW A3]** -- Small data (< 200 bytes raw) returns uncompressed (isCompressed() == false); large data (>= 200 bytes) returns GZIP-compressed (isCompressed() == true)

**Entry Criteria**: Day 0 benchmark complete (card); Phase 1 tests pass.
**Exit Criteria**: SerializationUtils compiles, all 7 tests pass, Caffeine dependency added.
**Risks**: ESVOOctreeData constructed by OctreeBuilder may have unexpected node structure; mitigate by inspecting actual OctreeBuilder output in test.

---

### Day 2: RegionBuilder Records and Position Conversion (Luciferase-q8fp)

**Objective**: Establish the data types and spatial conversion logic for the build pipeline.

**Files to Create/Modify**:
- `simulation/src/main/java/.../viz/render/RegionBuilder.java` (first ~200 LOC of ~450)
- `simulation/src/test/java/.../viz/render/RegionBuilderTest.java` (first 5 tests)

**Implementation Details**:

1. **Inner Records**:
   - `BuildRequest(RegionId, List<EntityPosition>, RegionBounds, int lodLevel, boolean visible, long timestamp) implements Comparable<BuildRequest>` -- visible sorts before invisible, then by timestamp (FIFO within priority class)
   - `BuiltRegion(RegionId, SparseStructureType, byte[] serializedData, boolean compressed, int nodeCount, int leafCount, int lodLevel, long buildTimeNs, long buildVersion)` -- with `estimatedSizeBytes()` returning `serializedData.length + 72`
   - `BuildQueueFullException extends RuntimeException`
   - `CircuitBreakerOpenException extends RuntimeException` **[NEW C3]**
   - `CircuitBreakerState` inner class scaffold **[NEW C3]** -- AtomicInteger failureCount, AtomicLong circuitOpenUntilMs

2. **Constructor**: Accept `RenderingServerConfig`, extract structureType, gridResolution, maxBuildDepth, poolSize from `config.buildPoolSize()` **(S1: renamed from gpuPoolSize)**. Create PriorityBlockingQueue, AtomicInteger queueSize, ConcurrentHashMap pendingBuilds, ConcurrentHashMap circuitBreakers **(C3)**, ConcurrentSkipListSet invisibleBuilds **(S2: reverseOrder for O(log n) eviction)**, fixed thread pool with daemon threads, volatile Clock.

3. **positionsToVoxels(List<EntityPosition>, RegionBounds)**:
   - Normalize each position to [0,1] within region: `(pos.x() - bounds.minX()) / bounds.size()`
   - Quantize to voxel grid: `(int)(localX * gridResolution)`, clamped to `[0, gridResolution-1]`
   - Return `List<Point3i>`
   - Handle zero-size bounds gracefully (return empty list)

4. **close()**: `shutdown.compareAndSet(false, true)`, `buildPool.shutdownNow()`, cancel all pending futures

5. **Tests** (5):
   - `testBuildESVO_producesNonEmptyResult` -- 10 positions in a region, build, verify serializedData.length > 0 and nodeCount > 0
   - `testBuildESVT_producesNonEmptyResult` -- Same but with SparseStructureType.ESVT config
   - `testBuildEmptyRegion_returnsEmptyResult` -- Empty position list, verify serializedData empty and nodeCount = 0
   - `testPositionsToVoxels_normalizesCorrectly` -- Bounds [100, 200], gridResolution=10, position (150,150,150) should yield Point3i(5,5,5)
   - `testPositionsToVoxels_clampsOutOfBounds` -- Position outside bounds, verify clamped to [0, gridResolution-1]

**Entry Criteria**: SerializationUtils complete (an6a) AND Day 0 benchmark complete (card).
**Exit Criteria**: Records compile, position conversion verified, basic ESVO and ESVT builds work.
**Risks**: Point3i import path needs verification (com.hellblazer.luciferase.geometry.Point3i).

---

### Day 3: RegionBuilder Pipeline, C1 Backpressure, C3 Circuit Breaker (Luciferase-58co)

**Objective**: Complete the build pipeline with worker threads, C1 backpressure, C3 circuit breaker, and S2 O(log n) eviction.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/RegionBuilder.java` (complete to ~450 LOC)
- `simulation/src/test/java/.../viz/render/RegionBuilderTest.java` (complete 15 tests)

**Implementation Details**:

1. **buildWorkerLoop()**: Poll from PriorityBlockingQueue with 1-second timeout, decrement queueSize, remove from invisibleBuilds if not visible, check if future is cancelled, call doBuild(), on success: complete future, clear circuit breaker. **On failure (C3): increment failedBuilds, record failure in CircuitBreakerState, complete future exceptionally.**

2. **doBuild(BuildRequest)**: positionsToVoxels(), handle empty voxels, dispatch to buildAndSerializeESVO() or buildAndSerializeESVT(), return BuiltRegion with compressed flag from `SerializationUtils.isCompressed()`.

3. **buildAndSerializeESVO(List<Point3i>)**: `try (var builder = new OctreeBuilder(maxBuildDepth))` -- AutoCloseable! Call `builder.buildFromVoxels(voxels, maxBuildDepth)`, then `SerializationUtils.serializeESVO(octreeData)`.

4. **buildAndSerializeESVT(List<Point3i>)**: `new ESVTBuilder()` -- NOT AutoCloseable. Call `builder.buildFromVoxels(voxels, maxBuildDepth, gridResolution)`, then `SerializationUtils.serializeESVT(esvtData)`.

5. **build() public API with C1 backpressure and C3 circuit breaker**:
   - If shutdown, return failed future
   - **C3: Check circuit breaker** -- if open for this regionId (< cooldown expiry), return failed future with CircuitBreakerOpenException
   - If `queueSize >= MAX_QUEUE_SIZE`:
     - If NOT visible: return failed future with BuildQueueFullException
     - **S2: If visible: call evictLowestPriority() via invisibleBuilds.pollFirst() (O(log n))**; if no invisible found, return failed future
   - Cancel existing pending build for same regionId
   - Create BuildRequest with `clock.currentTimeMillis()`, offer to queue, add to invisibleBuilds if not visible, increment queueSize
   - Return CompletableFuture stored in pendingBuilds

6. **evictLowestPriority() (S2 fix)**: `invisibleBuilds.pollFirst()` returns lowest-priority invisible build in O(log n). Remove from buildQueue, decrement queueSize, increment evictions, complete its future exceptionally.

7. **CircuitBreakerState (C3)**:
   - `recordFailure(long nowMs)` -- increment failureCount; if >= THRESHOLD (3), set circuitOpenUntilMs = now + COOLDOWN (60s)
   - `isOpen(long nowMs)` -- return nowMs < circuitOpenUntilMs
   - `reset()` -- set failureCount=0, circuitOpenUntilMs=0

8. **Metrics**: totalBuilds, totalBuildTimeNs, evictions, **failedBuilds (C3)**, avgBuildTimeNs(), queueDepth(), setClock(), **clearCircuitBreaker()**.

9. **Tests** (10 additional, 15 total):
   - `testQueueBackpressure_rejectsInvisibleWhenFull` -- Fill queue to MAX_QUEUE_SIZE with visible builds, submit invisible, verify BuildQueueFullException
   - `testQueueBackpressure_evictsInvisibleForVisible_OLogN` -- Fill queue with mix of visible/invisible, submit visible when full, verify one invisible evicted
   - `testQueueDepthNeverExceedsMax` -- Rapid-fire 2000 builds, verify queueDepth() <= MAX_QUEUE_SIZE at all times
   - `testBuildFailure_circuitBreakerActivates` **[NEW C3]** -- Configure OctreeBuilder to throw on specific region; 3 consecutive failures opens circuit breaker; subsequent builds fail with CircuitBreakerOpenException; failedBuilds() == 3
   - `testBuildFailure_circuitBreakerResets` **[NEW C3]** -- Circuit breaker open for R1; clearCircuitBreaker(R1) called; subsequent builds accepted
   - `testBuildFailure_metricsTracked` **[NEW C3]** -- Build throws exception; failedBuilds() incremented, totalBuilds() unchanged
   - `testBackpressure_evictsLowestPriorityInvisible_OLogN` **[NEW S2]** -- Queue with 5 invisible builds at timestamps T1-T5; queue full, visible build submitted; newest invisible (T5) evicted
   - `testConcurrentBuilds_noCorruption` -- 4 threads, 100 builds each, all futures complete without unexpected exceptions
   - `testLargeRegionStress_1000Voxels` **[NEW]** -- 1000 entity positions in a region; build at 64^3 resolution; build completes within 5 seconds, result deserializable
   - `testClose_failsPendingBuilds` -- Submit builds, close(), verify all futures cancelled

**Entry Criteria**: Day 2 complete (q8fp).
**Exit Criteria**: All 15 RegionBuilder tests pass, C1 backpressure verified, C3 circuit breaker verified, S2 O(log n) eviction verified.
**Risks**: Thread pool timing in tests -- use timeouts on futures (5 seconds); OctreeBuilder may need significant memory for large voxel sets -- use small grid (16^3) in most tests.

---

### Day 4: RegionCache Core with Caffeine (Luciferase-f7xd)

**Objective**: Implement the Caffeine-based hybrid cache with pinning and multi-LOD support.

**Files to Create**:
- `simulation/src/main/java/.../viz/render/RegionCache.java` (first ~200 LOC of ~350)
- `simulation/src/test/java/.../viz/render/RegionCacheTest.java` (first 8 tests)

**Implementation Details**:

1. **Inner Records**:
   - `CacheKey(RegionId, int lodLevel) implements Comparable<CacheKey>` -- compare by regionId then lodLevel
   - `CachedRegion(BuiltRegion data, long cachedAtMs, AtomicLong lastAccessedMs, long sizeBytes)`
   - `CacheStats(int totalRegions, int pinnedRegions, int unpinnedRegions, long memoryUsedBytes, long pinnedMemoryBytes, long memoryMaxBytes, long evictions, long emergencyEvictions, long hits, long misses, double caffeineHitRate)` with `hitRate()` and `memoryUsagePercent()`

2. **Hybrid Cache Architecture (C2 fix)**:
   - `Cache<CacheKey, CachedRegion> cache` -- Caffeine cache for unpinned regions
   - `ConcurrentHashMap<CacheKey, CachedRegion> pinnedCache` -- Pinned regions exempt from Caffeine eviction
   - `AtomicLong pinnedMemoryBytes` -- Track pinned memory separately
   - `AtomicBoolean emergencyEvicting` **(C4)** -- Guard against concurrent emergency eviction

3. **Constructor**: Build Caffeine cache with:
   - `maximumWeight(maxMemory * 0.90)` -- Reserve 10% headroom for pinned
   - `weigher((key, region) -> (int) Math.min(region.sizeBytes(), Integer.MAX_VALUE))`
   - `expireAfterAccess(config.regionTtlMs(), TimeUnit.MILLISECONDS)` -- TTL handled by Caffeine
   - `removalListener(...)` -- Increment evictionCount on eviction
   - `recordStats()` -- Built-in hit/miss tracking

4. **get(RegionId, int lodLevel)**: Check pinnedCache first (O(1)), then Caffeine cache.getIfPresent() (O(1) amortized). Update lastAccessedMs. **No O(n) deque scan (C2 fix).**

5. **put(BuiltRegion)**: Remove existing from both caches. Create CachedRegion. If isPinned(key), store in pinnedCache + adjust pinnedMemoryBytes. Otherwise, store in Caffeine (Weigher handles memory tracking). Check emergency eviction threshold.

6. **pin(RegionId, int lodLevel)**: Move from Caffeine to pinnedCache. Invalidate in Caffeine, put in pinnedCache, adjust pinnedMemoryBytes.

7. **unpin(RegionId, int lodLevel)**: Move from pinnedCache to Caffeine. Remove from pinnedCache, put in Caffeine, adjust pinnedMemoryBytes.

8. **invalidate(RegionId)**: Remove ALL entries matching regionId from both caches. Adjust pinnedMemoryBytes for each removed pinned entry.

9. **totalMemoryUsageBytes()**: `cache.policy().eviction().weightedSize() + pinnedMemoryBytes`

10. **close()**: `cache.invalidateAll()`, `pinnedCache.clear()`, reset pinnedMemoryBytes.

11. **Tests** (8):
   - `testPutAndGet_basicOperation` -- Put BuiltRegion, get same key, verify Optional present with matching data
   - `testGet_miss_returnsEmpty` -- Get from empty cache, verify empty Optional
   - `testMemoryTracking_accurate` -- Empty: 0 bytes. Put regions. Invalidate. Verify tracking.
   - `testLRUEviction_caffeineEvictsOldestUnpinned` -- Fill cache to maxWeight, put more, verify Caffeine auto-evicts
   - `testTTLEviction_caffeineRemovesExpiredEntries` -- TTL=5s, put region, wait/advance, verify expired
   - `testPinning_preventsEviction` -- Pin region, fill cache, verify pinned survives in pinnedCache
   - `testMultiLOD_sameRegionDifferentLODs` -- Put R1@LOD0 and R1@LOD2, get both, verify independent
   - `testPin_movesBetweenCaches` **[NEW]** -- Region in Caffeine; pin(); verify moved to pinnedCache, totalMemoryUsageBytes unchanged

**Entry Criteria**: RegionBuilder BuiltRegion record exists (q8fp complete).
**Exit Criteria**: Basic Caffeine cache operations verified, pinning prevents eviction, 8 tests pass.
**Risks**: Caffeine Weigher returns int (max 2GB per entry) -- mitigate with Math.min(sizeBytes, Integer.MAX_VALUE).

---

### Day 5: RegionCache M1 Emergency Eviction and C4 Guard (Luciferase-lzjb)

**Objective**: Complete the cache with emergency eviction, C4 concurrency guard, and full test coverage.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/RegionCache.java` (complete to ~350 LOC)
- `simulation/src/test/java/.../viz/render/RegionCacheTest.java` (complete 15 tests)

**Implementation Details**:

1. **M1 emergencyEvict() with C4 guard**: When `totalMemoryUsageBytes() > maxMemoryBytes * 0.90`:
   ```
   if (emergencyEvicting.compareAndSet(false, true)) {
       try {
           // Step 1: Force Caffeine cleanup
           cache.cleanUp();
           // Step 2: Evict oldest pinned entries until below 75% target
           while (totalMemoryUsageBytes() > target) {
               evict oldest from pinnedCache by lastAccessedMs
           }
       } finally {
           emergencyEvicting.set(false);
       }
   }
   ```
   **C4 fix**: AtomicBoolean prevents multiple threads from concurrently entering emergency eviction, preventing over-eviction.

2. **TTL**: Handled automatically by Caffeine's `expireAfterAccess()` -- no manual evictionSweep() needed (simplification from V1).

3. **Tests** (7 additional, 15 total):
   - `testUnpin_movesBetweenCaches` **[NEW]** -- Region in pinnedCache; unpin(); verify moved to Caffeine
   - `testInvalidate_removesAllLODs` -- Cache R1 at LOD 0/1/2 (mix pinned+unpinned), invalidate(R1), verify all removed, memory freed
   - `testCacheInvalidationOnDirty` **[NEW]** -- Region cached, entity moves (dirty), scheduleBuild checks dirty flag, cache invalidated before rebuild
   - `testEmergencyEviction_triggersAbove90Percent` -- maxMemory=1000 bytes, put entries via pinned cache totaling >900 bytes, verify emergency eviction reduces to ~750 bytes, emergencyEvictionCount incremented
   - `testConcurrentEmergencyEviction_onlyOneThreadEvicts` **[NEW C4]** -- Cache near 90%, 4 threads simultaneously put() exceeding threshold, verify emergencyEvictionCount == 1 (not 4), AtomicBoolean guard working
   - `testCaffeineStats_hitMissTracking` **[NEW]** -- 5 hits + 3 misses via Caffeine, verify stats().hits == 5, stats().misses == 3, caffeineHitRate approx 0.625
   - `testConcurrentAccess_noCorruption` -- 4 threads doing 1000 random put/get/pin/unpin/invalidate operations each. Verify: no exceptions, memory tracking non-negative.

**Entry Criteria**: Day 4 complete (f7xd).
**Exit Criteria**: All 15 RegionCache tests pass, M1 emergency eviction verified, C4 guard verified, Caffeine stats working.
**Risks**: Caffeine's asynchronous eviction may cause timing issues in tests -- use `cache.cleanUp()` to force synchronous processing.

---

### Day 6: Integration Wiring + Backfill + Metrics (Luciferase-7h3l)

**Objective**: Wire Phase 2 components into Phase 1, add dirty-region backfill and metrics endpoint.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/RenderingServerConfig.java` (modify record)
- `simulation/src/main/java/.../viz/render/AdaptiveRegionManager.java` (add ~100 LOC)
- `simulation/src/main/java/.../viz/render/RenderingServer.java` (add ~60 LOC)

**Files to Create**:
- `simulation/src/test/java/.../viz/render/BuildIntegrationTest.java` (first 4 of 8 tests)

**Implementation Details**:

1. **RenderingServerConfig Changes (S1, A1)**:
   - **Remove `gpuEnabled`** from record (A1: not used in Phase 2)
   - **Rename `gpuPoolSize` to `buildPoolSize`** (S1: accurate for CPU threads)
   - Update `defaults()` and `testing()` factory methods

2. **AdaptiveRegionManager Extensions**:
   ```java
   private volatile RegionBuilder builder;  // null until Phase 2 wiring
   private volatile RegionCache cache;       // null until Phase 2 wiring

   public void setBuilder(RegionBuilder builder) { this.builder = builder; }
   public void setCache(RegionCache cache) { this.cache = cache; }
   ```

3. **scheduleBuild(RegionId, int lodLevel, boolean visible)**: If builder == null, return completedFuture(null). Check cache first; if hit and not dirty, return cached. If dirty, invalidate cache. Submit build to builder. On completion: cache result, clear dirty flag, increment build version.

4. **backfillDirtyRegions() (S3 fix)**:
   ```java
   public void backfillDirtyRegions() {
       if (builder == null) return;
       dirtyRegions().forEach(region -> {
           var state = regions.get(region);
           if (state != null && !state.entities().isEmpty()) {
               scheduleBuild(region, 0, false);  // Invisible, low priority
           }
       });
   }
   ```
   Called from RenderingServer.start() after setBuilder/setCache.

5. **RenderingServer Extensions**:
   ```java
   private RegionBuilder regionBuilder;      // S1: renamed from gpuBuilder
   private RegionCache regionCache;

   // In start():
   regionBuilder = new RegionBuilder(config);
   regionCache = new RegionCache(config);
   regionManager.setBuilder(regionBuilder);
   regionManager.setCache(regionCache);
   regionManager.backfillDirtyRegions();     // S3: backfill after wiring

   // In stop():
   if (regionBuilder != null) regionBuilder.close();
   if (regionCache != null) regionCache.close();

   // M2: Metrics endpoint
   app.get("/api/metrics", ctx -> {
       var builderStats = Map.of(
           "totalBuilds", regionBuilder.totalBuilds(),
           "failedBuilds", regionBuilder.failedBuilds(),
           "avgBuildTimeNs", regionBuilder.avgBuildTimeNs(),
           "queueDepth", regionBuilder.queueDepth(),
           "evictions", regionBuilder.evictions()
       );
       var cacheStats = regionCache.stats();
       ctx.json(Map.of("builder", builderStats, "cache", cacheStats));
   });
   ```

6. **Tests** (4):
   - `testEntityUpdateTriggersScheduleBuild` -- Create server, start, update entity, scheduleBuild on region, verify BuiltRegion returned, cache populated
   - `testCacheHitSkipsBuild` -- Build once, clear dirty flag, scheduleBuild again, verify cached result returned (no re-build)
   - `testDirtyRegionInvalidatesCacheAndRebuilds` -- Build and cache region, update entity (dirty=true), scheduleBuild(), verify cache invalidated and new build with updated positions
   - `testSetBuilder_backfillsDirtyRegions` **[NEW S3]** -- AdaptiveRegionManager with 3 dirty regions (builder==null), setBuilder/setCache/backfillDirtyRegions(), verify all 3 have builds scheduled and eventually cached

**Entry Criteria**: RegionBuilder complete (58co) AND RegionCache complete (lzjb).
**Exit Criteria**: Integration wiring works, backfill verified, 4 integration tests pass, all Phase 1 tests still pass.
**Risks**: Setter injection timing -- ensure start() creates builder/cache before entityConsumer starts sending updates; RenderingServerConfig record change requires updating all call sites.

---

### Day 7-8: Testing and Quality Gates (Luciferase-2yfa)

**Objective**: Complete integration test suite, verify all V2 quality gates, ensure production readiness.

**Files to Modify**:
- `simulation/src/test/java/.../viz/render/BuildIntegrationTest.java` (complete 8 tests)

**Implementation Details**:

1. **Additional Integration Tests** (4):
   - `testFullPipeline_multipleEntitiesMultipleRegions` -- 50 entities across 5 regions, scheduleBuild for each dirty region, verify 5 BuiltRegion results all cached, verify each deserializable via SerializationUtils roundtrip
   - `testPerformanceGate_buildLatencyP50Under50ms` **[NEW C1]** -- 20 builds of 100-voxel regions at 64^3, measure build times, assert P50 < 50ms
   - `testPerformanceGate_buildLatencyP99Under200ms` **[NEW C1]** -- 100 builds of 100-voxel regions at 64^3, measure build times, assert P99 < 200ms
   - `testGracefulDegradation_queueSaturation` **[NEW M4]** -- Builder with slow builds (simulated), flood with invisible builds until saturated, verify invisible rejected, visible still accepted, no deadlocks, system recovers

2. **Regression Testing**:
   - Run `mvn test -pl simulation` -- expect 88 tests (43 Phase 1 + 45 Phase 2)
   - Verify all Phase 1 tests pass unchanged
   - Run with `-Dsurefire.rerunFailingTestsCount=0` to catch flaky tests

3. **Quality Gate Checklist** (see Section 10 for complete gates)

### Day 9-9.5: Buffer

**Buffer time** (2 days, 21% of schedule) for:
- Integration issues between Caffeine and existing components
- Caffeine configuration tuning (weigher, TTL, maxWeight)
- Performance gate failures requiring parameter adjustment (buildPoolSize, gridResolution)
- Unexpected API mismatches with render module builders
- CI/CD test distribution setup for new test classes

**Entry Criteria**: Integration wiring complete (7h3l).
**Exit Criteria**: All 88 tests pass, all quality gates met, Phase 2 complete.
**Risks**: Possible flaky tests from thread timing; mitigate with appropriate timeouts and TestClock usage.

---

## 7. Test Strategy

### 7.1 Test Distribution

| Test Class | Tests | Day | Sub-Phase | Focus |
|------------|-------|-----|-----------|-------|
| OctreeBuilderBenchmark | (benchmark) | 0 | 2A | Build performance validation |
| SerializationUtilsTest | 7 | 1 | 2A | Round-trip, compression threshold, validation |
| RegionBuilderTest | 15 | 2-3 | 2A | Build pipeline, C1 backpressure, C3 circuit breaker, S2 O(log n) |
| RegionCacheTest | 15 | 4-5 | 2B | Caffeine LRU, pinning, C4 guard, multi-LOD, stats |
| BuildIntegrationTest | 8 | 6-8 | 2C | Full pipeline, backfill, performance gates, degradation |
| **Total Phase 2** | **45** | | | |
| Phase 1 (unchanged) | 43 | | | Regression verification |
| **Grand Total** | **88** | | | |

### 7.2 New Tests in V2 (15 additions)

| Test | Class | Issue | Why Added |
|------|-------|-------|-----------|
| testCompressionThreshold_skipsGzipForSmallRegions | SerializationUtilsTest | A3 | Verify small data not expanded by GZIP |
| testBuildFailure_circuitBreakerActivates | RegionBuilderTest | C3 | Verify 3-failure threshold opens circuit |
| testBuildFailure_circuitBreakerResets | RegionBuilderTest | C3 | Verify reset on clean region |
| testBuildFailure_metricsTracked | RegionBuilderTest | C3 | Verify failedBuilds metric |
| testBackpressure_evictsLowestPriorityInvisible_OLogN | RegionBuilderTest | S2 | Verify O(log n) eviction |
| testLargeRegionStress_1000Voxels | RegionBuilderTest | S4 | Stress test large inputs |
| testPin_movesBetweenCaches | RegionCacheTest | C2 | Verify Caffeine-to-pinned movement |
| testUnpin_movesBetweenCaches | RegionCacheTest | C2 | Verify pinned-to-Caffeine movement |
| testCacheInvalidationOnDirty | RegionCacheTest | S4 | Verify dirty-flag cache interaction |
| testConcurrentEmergencyEviction_onlyOneThreadEvicts | RegionCacheTest | C4 | Verify AtomicBoolean guard |
| testCaffeineStats_hitMissTracking | RegionCacheTest | C2 | Verify Caffeine recordStats |
| testSetBuilder_backfillsDirtyRegions | BuildIntegrationTest | S3 | Verify backfill on dynamic wiring |
| testPerformanceGate_buildLatencyP50Under50ms | BuildIntegrationTest | C1 | Performance quality gate |
| testPerformanceGate_buildLatencyP99Under200ms | BuildIntegrationTest | C1 | Performance quality gate |
| testGracefulDegradation_queueSaturation | BuildIntegrationTest | M4 | Degradation behavior |

### 7.3 Critical Behavior Tests

| Behavior | Test | Why Critical |
|----------|------|-------------|
| C1 backpressure | testQueueBackpressure_rejectsInvisibleWhenFull | Prevents OOM under entity flood |
| C1 backpressure | testQueueBackpressure_evictsInvisibleForVisible_OLogN | Visible builds always prioritized |
| C1 queue bound | testQueueDepthNeverExceedsMax | Queue memory bounded |
| C2 Caffeine LRU | testLRUEviction_caffeineEvictsOldestUnpinned | O(1) amortized eviction |
| C3 circuit breaker | testBuildFailure_circuitBreakerActivates | Prevents retry storms |
| C4 eviction guard | testConcurrentEmergencyEviction_onlyOneThreadEvicts | Prevents over-eviction |
| M1 emergency eviction | testEmergencyEviction_triggersAbove90Percent | Prevents cache OOM |
| S2 O(log n) | testBackpressure_evictsLowestPriorityInvisible_OLogN | No hot-path degradation |
| S3 backfill | testSetBuilder_backfillsDirtyRegions | No lost dirty regions |
| Pinning safety | testPinning_preventsEviction | Visible regions never lost |
| Serialization fidelity | testESVORoundTrip_preservesData | Data integrity |
| Cache-build integration | testDirtyRegionInvalidatesCacheAndRebuilds | Stale data never served |

### 7.4 Test Infrastructure

- **TestClock**: Used for TTL eviction tests, circuit breaker tests, and deterministic timing
- **RenderingServerConfig.testing()**: Port 0, 16^3 grid, 4 depth, 16MB cache, CPU only, buildPoolSize=1
- **Small voxel sets**: 10-50 entities per test to keep build times <100ms
- **Future timeouts**: 5-second timeout on all CompletableFuture.get() calls in tests
- **No GPU tests**: Phase 2 is CPU-only; GPU tests deferred to Phase 4
- **Caffeine synchronization**: Use `cache.cleanUp()` in tests to force synchronous eviction processing

---

## 8. File Layout (After Phase 2)

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - unchanged]
    EntityStreamConsumer.java
    EntityPosition.java
    RegionId.java
    RegionBounds.java
    UpstreamConfig.java
    SparseStructureType.java

    [Phase 1 - modified]
    RenderingServerConfig.java        MODIFIED: gpuEnabled removed, buildPoolSize (S1, A1)
    RenderingServer.java              +60 LOC: wires Phase 2, /api/metrics (M2)
    AdaptiveRegionManager.java        +100 LOC: scheduleBuild, backfillDirtyRegions (S3)

    [Phase 2 - new]
    RegionBuilder.java                ~450 LOC (build pipeline, C1, C3, S2)
    RegionCache.java                  ~350 LOC (Caffeine LRU, pinning, M1, C4)
    SerializationUtils.java           ~220 LOC (GZIP, ESVO/ESVT, A3 threshold)

simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - unchanged]
    RenderingServerTest.java
    EntityStreamConsumerTest.java
    AdaptiveRegionManagerTest.java
    RegionIdTest.java
    RegionBoundsTest.java
    RenderingServerIntegrationTest.java

    [Phase 2 - new]
    OctreeBuilderBenchmark.java       ~80 LOC (Day 0 benchmark)
    RegionBuilderTest.java            ~450 LOC (15 tests)
    RegionCacheTest.java              ~400 LOC (15 tests)
    SerializationUtilsTest.java       ~180 LOC (7 tests)
    BuildIntegrationTest.java         ~300 LOC (8 tests)
```

**Total Phase 2 Production**: ~1,020 LOC new + ~160 LOC modified = ~1,180 LOC
**Total Phase 2 Tests**: ~1,410 LOC across 45 tests + benchmark
**Total Phase 2**: ~2,590 LOC

---

## 9. Risk Register

| # | Risk | Severity | Likelihood | Mitigation | Status |
|---|------|----------|------------|------------|--------|
| R1 | ESVOOctreeData node structure varies from doc | Medium | Medium | Day 0 benchmark validates OctreeBuilder output; Day 1 test verifies | MITIGATED |
| R2 | OctreeBuilder resource leak | High | Low | Always try-with-resources; enforced by code review | MITIGATED |
| R3 | Build thread pool race conditions | Medium | Medium | Concurrent primitives, TestClock, small thread counts | MITIGATED |
| R4 | ConcurrentLinkedDeque O(n) remove | **N/A** | **N/A** | **ELIMINATED: Caffeine replaces deque (C2)** | **RESOLVED** |
| R5 | ESVTData constructor mismatch | Low | Medium | Verified: 9-param canonical + convenience constructors | MITIGATED |
| R6 | Phase 1 regression from setter injection | Medium | Low | Additive changes only; null defaults; full regression | MITIGATED |
| R7 | Flaky concurrent tests | Low | Medium | TestClock, timeouts, small operation counts, cache.cleanUp() | MITIGATED |
| R8 | GZIP expansion for tiny regions | **N/A** | **N/A** | **ELIMINATED: A3 compression threshold (< 200 bytes)** | **RESOLVED** |
| R9 | Scheduled eviction timing in tests | **N/A** | **N/A** | **ELIMINATED: Caffeine handles TTL internally** | **RESOLVED** |
| R10 | Build failure retry storms | **N/A** | **N/A** | **ELIMINATED: C3 circuit breaker (3 fails/60s)** | **RESOLVED** |
| R11 | Concurrent emergency over-eviction | **N/A** | **N/A** | **ELIMINATED: C4 AtomicBoolean guard** | **RESOLVED** |
| R12 | OctreeBuilder build time exceeds assumptions | High | Medium | Day 0 benchmark validates; fallback: increase poolSize or reduce resolution | MITIGATED |
| R13 | Caffeine Weigher overflow for >2GB regions | Low | Low | Math.min(sizeBytes, Integer.MAX_VALUE) | MITIGATED |
| R14 | RenderingServerConfig record change breaks callers | Medium | Medium | Compile-time errors; fix all call sites on Day 6 | MITIGATED |
| R15 | Dirty regions lost on dynamic wiring | **N/A** | **N/A** | **ELIMINATED: S3 backfillDirtyRegions()** | **RESOLVED** |

---

## 10. Quality Gates (Phase 2 Complete)

All must pass before proceeding to Phase 3:

### Correctness
- [ ] RegionBuilder builds correct ESVO from entity positions (verified via serialization round-trip with nodeCount, leafCount, farPointers match)
- [ ] RegionBuilder builds correct ESVT from entity positions (verified via round-trip)
- [ ] CPU build path works (no GPU/OpenCL required)
- [ ] C1: Build queue never exceeds MAX_QUEUE_SIZE under entity flood
- [ ] RegionCache Caffeine LRU eviction works correctly under memory pressure
- [ ] RegionCache pinning prevents eviction of pinned regions
- [ ] M1: Emergency eviction triggers above 90% and reduces to 75%
- [ ] Multi-LOD: Same region cached at different LOD levels independently
- [ ] Serialization round-trip preserves all ESVO/ESVT data fields
- [ ] Compression threshold: small regions (< 200 bytes) stored uncompressed (A3)
- [ ] All Phase 1 tests continue to pass (no regressions)
- [ ] All 45 Phase 2 tests pass (`mvn test -pl simulation`)

### Performance (C1)
- [ ] Build latency P50 < 50ms for 100-voxel region at 64^3 resolution
- [ ] Build latency P99 < 200ms for 100-voxel region at 64^3 resolution
- [ ] Cache hit latency P99 < 5ms with 1000 cached regions
- [ ] Queue depth stays < 500 under sustained 50 regions/sec load for 60 seconds

### Resilience (C3, C4)
- [ ] Circuit breaker activates after 3 failures, blocks retries for 60s
- [ ] Circuit breaker resets on clean region or cooldown expiry
- [ ] Concurrent emergency eviction: only one thread enters (C4 guard)
- [ ] failedBuilds metric accurately counts all build exceptions

### Observability (M2)
- [ ] /api/metrics endpoint returns builder + cache stats in < 50ms
- [ ] All time-dependent code uses injected Clock (zero System.currentTimeMillis() in production)
- [ ] All tests use TestClock for determinism

### Code Standards
- [ ] No `synchronized` blocks in any Phase 2 code
- [ ] Clock injection via setClock() on RegionBuilder and RegionCache
- [ ] Dynamic port (port 0) in all tests via `RenderingServerConfig.testing()`
- [ ] SLF4J logging with `{}` placeholders throughout
- [ ] All Phase 1 tests pass (no regressions)
- [ ] Caffeine dependency added to simulation/pom.xml (no version, managed by root)
- [ ] Thread pools use daemon threads
- [ ] CompletableFuture used for async results (no blocking in hot path)
- [ ] All classes named correctly: RegionBuilder (not GpuESVOBuilder), buildPoolSize (not gpuPoolSize)
- [ ] gpuEnabled removed from Phase 2 config

---

## 11. Code Reuse Verification

All Phase 2 render module dependencies have been verified:

| Class | Module | API Verified |
|-------|--------|-------------|
| OctreeBuilder | render | buildFromVoxels(List<Point3i>, int):80, AutoCloseable:28 |
| ESVTBuilder | render | buildFromVoxels(List<Point3i>, int, int):150, NOT AutoCloseable |
| ESVOOctreeData | render | getNodeIndices, getNode, getFarPointers, setters |
| ESVTData | render | record: nodes, contours, farPointers, rootType, maxDepth, etc. |
| ESVONodeUnified | render | SIZE_BYTES=8:32, writeTo:290, fromByteBuffer:299 |
| ESVTNodeUnified | render | SIZE_BYTES=8:54, writeTo:373, fromByteBuffer:382 |
| Point3i | common | Already used by Phase 1 |
| Clock | simulation | Already used by all Phase 1 components |
| Caffeine | com.github.ben-manes | Already in root pom.xml dependencyManagement |

---

## 12. Parallel Execution Guidance

For each task bead, the java-developer agent should:

1. **Use sequential thinking** (mcp__sequential-thinking__sequentialthinking) for analysis of render module API compatibility before writing code
2. **Write tests FIRST** (TDD) -- test compiles with stubs, then implement to pass
3. **Spawn sub-agents** for independent test verification if working on multiple test classes
4. **Check compilation frequently** -- `mvn compile -pl simulation` between logical units
5. **Run tests incrementally** -- `mvn test -pl simulation -Dtest=ClassName` for current class
6. **Use cache.cleanUp()** in RegionCache tests to force synchronous Caffeine processing

---

## 13. Integration Points with Phase 3

Phase 2 exit criteria establish the foundation for Phase 3 (Viewport Tracking and Region Streaming):

1. **RegionCache.get(RegionId, lodLevel)** -- Phase 3's ViewportTracker queries cache for visible regions
2. **RegionCache.pin/unpin** -- Phase 3's ViewportTracker pins visible regions
3. **BuiltRegion.serializedData + compressed flag** -- Phase 3's RegionStreamer sends binary WS frames
4. **AdaptiveRegionManager.scheduleBuild** -- Phase 3 triggers builds based on viewport changes
5. **RegionBuilder.queueDepth()** -- Phase 3's metrics endpoint reports build queue status
6. **/api/metrics** -- Phase 3 extends with viewport and streaming metrics

---

## Appendix A: Bead Quick Reference

| Bead ID | Title | Day | Depends On |
|---------|-------|-----|------------|
| Luciferase-r47c | Epic: Phase 2 GPU Integration | - | (parent) |
| Luciferase-jbbx | Phase 2A: Core Build Pipeline | 0-3 | r47c |
| Luciferase-wxu9 | Phase 2B: Region Caching with Caffeine | 4-5 | jbbx |
| Luciferase-gix5 | Phase 2C: Integration, Testing, Buffer | 6-9.5 | wxu9 |
| **Luciferase-card** | **Day 0: Benchmark OctreeBuilder** [NEW] | **0** | **(none - first task)** |
| Luciferase-an6a | SerializationUtils + compression threshold | 1 | (none - parallel with card) |
| Luciferase-q8fp | RegionBuilder records + positions | 2 | card, an6a |
| Luciferase-58co | RegionBuilder pipeline + C1 + C3 + S2 | 3 | q8fp |
| Luciferase-f7xd | RegionCache core with Caffeine | 4 | q8fp |
| Luciferase-lzjb | RegionCache M1 + C4 guard | 5 | f7xd |
| Luciferase-7h3l | Integration + backfill + metrics | 6 | 58co, lzjb |
| Luciferase-2yfa | Testing + quality gates + buffer | 7-9.5 | 7h3l |

## Appendix B: Command Reference

```bash
# Start Phase 2
bd update Luciferase-r47c --status in_progress

# Start Day 0
bd update Luciferase-card --status in_progress

# Complete a task
bd close Luciferase-card --reason "Benchmark complete: P50=12ms, P99=45ms at 64^3/100 voxels"

# Check what's ready to work on
bd ready

# Run Phase 2 tests
mvn test -pl simulation -Dtest="SerializationUtilsTest,RegionBuilderTest,RegionCacheTest,BuildIntegrationTest"

# Run all tests (including Phase 1 regression)
mvn test -pl simulation

# Verify no synchronized blocks
grep -rn "synchronized" simulation/src/main/java/.../viz/render/

# Run benchmark
mvn test -pl simulation -Dtest=OctreeBuilderBenchmark
```

## Appendix C: Remediation Traceability

Every critique issue maps to a specific bead and test:

| Issue | Fix Location | Bead | Verification Test |
|-------|-------------|------|-------------------|
| C1 Performance baseline | OctreeBuilderBenchmark | card | testPerformanceGate_buildLatencyP50/P99 |
| C2 O(n) LRU | Caffeine Cache in RegionCache | f7xd | testLRUEviction_caffeineEvictsOldestUnpinned |
| C3 Build failure | CircuitBreakerState in RegionBuilder | 58co | testBuildFailure_circuitBreakerActivates/Resets |
| C4 Concurrent eviction | AtomicBoolean in RegionCache | lzjb | testConcurrentEmergencyEviction_onlyOneThreadEvicts |
| S1 Naming | RegionBuilder, buildPoolSize | q8fp | All RegionBuilder tests use correct name |
| S2 O(n) eviction | ConcurrentSkipListSet | 58co | testBackpressure_evictsLowestPriorityInvisible_OLogN |
| S3 No backfill | backfillDirtyRegions() | 7h3l | testSetBuilder_backfillsDirtyRegions |
| S4 Test coverage | 15 new tests | all | 45 total tests |
| S5 Schedule buffer | 9.5 days (21%) | 2yfa | Buffer in Day 9-9.5 |
| A1 GPU config | gpuEnabled removed | 7h3l | Compile-time verification |
| A2 Portal pattern | Day 0 verification | card | Benchmark validates pattern |
| A3 GZIP expansion | COMPRESSION_THRESHOLD_BYTES | an6a | testCompressionThreshold_skipsGzipForSmallRegions |
| A4 Version header | Retained for forward-compat | an6a | testVersionValidation_rejectsWrongVersion |
| M1 Benchmarks | OctreeBuilderBenchmark | card | Day 0 benchmark |
| M2 Metrics | /api/metrics endpoint | 7h3l | Integration tests verify endpoint |
| M3 Profiling | JFR + async-profiler documented | - | Architecture §10.2 |
| M4 Degradation | Documented per-scenario | 2yfa | testGracefulDegradation_queueSaturation |
