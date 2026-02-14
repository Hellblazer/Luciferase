# Phase 2: GPU Integration -- Execution Plan

**Date**: 2026-02-13
**Author**: strategic-planner
**Status**: READY FOR AUDIT
**Duration**: 7.5 working days (1.5 weeks)
**Epic Bead**: Luciferase-r47c

**Architecture**: `simulation/doc/plans/PHASE_2_GPU_INTEGRATION_ARCHITECTURE.md`
**Parent Architecture**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE.md`
**Audit Report**: `simulation/doc/plans/GPU_RENDERING_SERVICE_ARCHITECTURE_AUDIT.md`
**ChromaDB**: `decision::architect::phase2-gpu-integration-2026-02-13`

---

## 1. Executive Summary

Phase 2 adds the voxel build pipeline and region cache to the GPU-Accelerated
ESVO/ESVT Rendering Service. It converts entity positions (tracked by Phase 1's
AdaptiveRegionManager) into compact ESVO or ESVT sparse voxel structures using
the proven OctreeBuilder and ESVTBuilder from the render module, then caches the
serialized results for streaming (Phase 3).

**Scope**: 3 new production files, 4 new test files, 2 modified production files.
**Estimated**: ~1,500 LOC production, ~30 tests.

**Phase 1 Baseline**: 9 production files, 6 test files, 2,513 LOC, 43 tests, all passing.

---

## 2. Bead Hierarchy

```
Epic: Luciferase-r47c  Phase 2: GPU Integration
  |
  +-- Luciferase-jbbx  Phase 2A: Core Build Pipeline
  |     |
  |     +-- Luciferase-an6a  SerializationUtils (Day 1)
  |     +-- Luciferase-q8fp  GpuESVOBuilder records + positionsToVoxels (Day 2)
  |     +-- Luciferase-58co  GpuESVOBuilder pipeline + C1 backpressure (Day 3)
  |
  +-- Luciferase-wxu9  Phase 2B: Region Caching
  |     |
  |     +-- Luciferase-f7xd  RegionCache core (Day 4)
  |     +-- Luciferase-lzjb  RegionCache M1 + TTL (Day 5)
  |
  +-- Luciferase-gix5  Phase 2C: Integration and Testing
        |
        +-- Luciferase-7h3l  Integration wiring (Day 6)
        +-- Luciferase-2yfa  Final testing + quality gates (Day 7-7.5)
```

---

## 3. Dependency Graph

```
Luciferase-an6a  SerializationUtils [Day 1]
        |
        v
Luciferase-q8fp  GpuESVOBuilder records [Day 2]
        |                           \
        v                            \
Luciferase-58co  GpuESVOBuilder      Luciferase-f7xd  RegionCache core [Day 4]
  pipeline + C1 [Day 3]                     |
        |                                   v
        |                          Luciferase-lzjb  RegionCache M1 [Day 5]
        |                                   |
        +-----------------------------------+
        |
        v
Luciferase-7h3l  Integration wiring [Day 6]
        |
        v
Luciferase-2yfa  Final testing [Day 7-7.5]
```

**Critical Path**: an6a -> q8fp -> 58co -> 7h3l -> 2yfa (5 sequential steps)
**Parallel Opportunity**: f7xd (Day 4) can start after q8fp (Day 2), overlapping with 58co (Day 3)

---

## 4. Day-by-Day Task Breakdown

### Day 1: SerializationUtils (Luciferase-an6a)

**Objective**: Create the serialization foundation that GpuESVOBuilder depends on.

**Files to Create**:
- `simulation/src/main/java/.../viz/render/SerializationUtils.java` (~200 LOC)
- `simulation/src/test/java/.../viz/render/SerializationUtilsTest.java` (~150 LOC)

**Implementation Details**:

1. **SerializationUtils.java** -- Stateless utility class (final, private constructor):
   - `static byte[] serializeESVO(ESVOOctreeData data)` -- Version header (4 bytes, VERSION=1) + format byte (0x01) + metadata (nodeCount, farPtrCount, maxDepth, leafCount, internalCount as 4-byte ints) + node data via ESVONodeUnified.writeTo(ByteBuffer) + far pointers, then GZIP compress
   - `static ESVOOctreeData deserializeESVO(byte[] compressed)` -- GZIP decompress, parse header, validate version/format, reconstruct ESVOOctreeData via setNode/setFarPointers/setMaxDepth/setLeafCount/setInternalCount
   - `static byte[] serializeESVT(ESVTData data)` -- Same header + ESVT-specific fields (rootType, contourCount, contours, gridResolution)
   - `static ESVTData deserializeESVT(byte[] compressed)` -- Reconstruct using ESVTData canonical constructor
   - `static byte[] gzipCompress(byte[] raw)` -- GZIPOutputStream
   - `static byte[] gzipDecompress(byte[] compressed)` -- GZIPInputStream

2. **Verified API Contracts**:
   - `ESVONodeUnified.SIZE_BYTES = 8`, `writeTo(ByteBuffer)`, `fromByteBuffer(ByteBuffer)` (lines 32, 290, 299)
   - `ESVTNodeUnified.SIZE_BYTES = 8`, `writeTo(ByteBuffer)`, `fromByteBuffer(ByteBuffer)` (lines 54, 373, 382)
   - `ESVOOctreeData`: `getNodeIndices()` returns sorted int[], `getNode(int)`, `getFarPointers()`, `setNode()`, `setFarPointers()`, `setMaxDepth()`, `setLeafCount()`, `setInternalCount()`
   - `ESVTData`: record constructor `(ESVTNodeUnified[], int[], int[], int, int, int, int, int, int[])`, accessor methods `nodes()`, `contours()`, `farPointers()`, `rootType()`, `maxDepth()`, `leafCount()`, `internalCount()`, `gridResolution()`, `leafVoxelCoords()`

3. **Tests** (6 total):
   - `testESVORoundTrip_preservesData` -- Build 10-node ESVOOctreeData via OctreeBuilder, serialize, deserialize, verify all fields match
   - `testESVTRoundTrip_preservesData` -- Build ESVTData via ESVTBuilder, serialize, deserialize, verify nodes/contours/farPointers/rootType/maxDepth
   - `testGZIPCompression_reducesByteSize` -- Compress repetitive 1000-byte array, verify compressed size < original
   - `testGZIPRoundTrip_preservesContent` -- Compress random bytes, decompress, verify identical
   - `testVersionValidation_rejectsWrongVersion` -- Manually create compressed data with version=99, assert IllegalArgumentException on deserialize
   - `testFormatValidation_rejectsWrongFormat` -- Pass ESVT-format data to deserializeESVO(), assert IllegalArgumentException

**Entry Criteria**: Phase 1 complete, `mvn test -pl simulation` passes all 43 tests.
**Exit Criteria**: SerializationUtils compiles, all 6 tests pass.
**Risks**: ESVOOctreeData constructed by OctreeBuilder may have unexpected node structure; mitigate by inspecting actual OctreeBuilder output in test.

---

### Day 2: GpuESVOBuilder Records and Position Conversion (Luciferase-q8fp)

**Objective**: Establish the data types and spatial conversion logic for the build pipeline.

**Files to Create/Modify**:
- `simulation/src/main/java/.../viz/render/GpuESVOBuilder.java` (first ~200 LOC)
- `simulation/src/test/java/.../viz/render/GpuESVOBuilderTest.java` (first 4 tests)

**Implementation Details**:

1. **Inner Records**:
   - `BuildRequest(RegionId, List<EntityPosition>, RegionBounds, int lodLevel, boolean visible, long timestamp) implements Comparable<BuildRequest>` -- visible sorts before invisible, then by timestamp (FIFO within priority class)
   - `BuiltRegion(RegionId, SparseStructureType, byte[], int nodeCount, int leafCount, int lodLevel, long buildTimeNs, long buildVersion)` -- with `estimatedSizeBytes()` returning `serializedData.length + 64`
   - `BuildQueueFullException extends RuntimeException`

2. **Constructor**: Accept `RenderingServerConfig`, extract structureType, gridResolution, maxBuildDepth, poolSize. Create PriorityBlockingQueue, AtomicInteger queueSize, ConcurrentHashMap pendingBuilds, fixed thread pool with daemon threads, volatile Clock.

3. **positionsToVoxels(List<EntityPosition>, RegionBounds)**:
   - Normalize each position to [0,1] within region: `(pos.x() - bounds.minX()) / bounds.size()`
   - Quantize to voxel grid: `(int)(localX * gridResolution)`, clamped to `[0, gridResolution-1]`
   - Return `List<Point3i>`
   - Handle zero-size bounds gracefully (return empty list)

4. **close()**: `shutdown.compareAndSet(false, true)`, `buildPool.shutdownNow()`, cancel all pending futures

5. **Tests** (4):
   - `testBuildESVO_producesNonEmptyResult` -- 10 positions in a region, build, verify serializedData.length > 0 and nodeCount > 0
   - `testBuildEmptyRegion_returnsEmptyResult` -- Empty position list, verify serializedData empty and nodeCount = 0
   - `testPositionsToVoxels_normalizesCorrectly` -- Bounds [100, 200], gridResolution=10, position (150,150,150) should yield Point3i(5,5,5)
   - `testPositionsToVoxels_clampsOutOfBounds` -- Position outside bounds, verify clamped to [0, gridResolution-1]

**Entry Criteria**: SerializationUtils complete (Luciferase-an6a).
**Exit Criteria**: Records compile, position conversion verified, basic ESVO build works.
**Risks**: Point3i import path needs verification (com.hellblazer.luciferase.geometry.Point3i).

---

### Day 3: GpuESVOBuilder Pipeline and C1 Backpressure (Luciferase-58co)

**Objective**: Complete the build pipeline with worker threads and critical backpressure mechanism.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/GpuESVOBuilder.java` (complete to ~400 LOC)
- `simulation/src/test/java/.../viz/render/GpuESVOBuilderTest.java` (complete 10 tests)

**Implementation Details**:

1. **buildWorkerLoop()**: Poll from PriorityBlockingQueue with 1-second timeout, decrement queueSize, check if future is cancelled, call doBuild(), complete future with result or exception.

2. **doBuild(BuildRequest)**: positionsToVoxels(), handle empty voxels, dispatch to buildAndSerializeESVO() or buildAndSerializeESVT(), return BuiltRegion.

3. **buildAndSerializeESVO(List<Point3i>)**: `try (var builder = new OctreeBuilder(maxBuildDepth))` -- AutoCloseable! Call `builder.buildFromVoxels(voxels, maxBuildDepth)`, then `SerializationUtils.serializeESVO(octreeData)`.

4. **buildAndSerializeESVT(List<Point3i>)**: `new ESVTBuilder()` -- NOT AutoCloseable. Call `builder.buildFromVoxels(voxels, maxBuildDepth, gridResolution)`, then `SerializationUtils.serializeESVT(esvtData)`.

5. **build() public API with C1 backpressure**:
   - If shutdown, return failed future
   - If `queueSize >= MAX_QUEUE_SIZE`:
     - If NOT visible: return failed future with BuildQueueFullException
     - If visible: call evictLowestPriority(); if no invisible found, return failed future
   - Cancel existing pending build for same regionId
   - Create BuildRequest with `clock.currentTimeMillis()`, offer to queue, increment queueSize
   - Return CompletableFuture stored in pendingBuilds

6. **evictLowestPriority()**: Scan buildQueue for invisible builds, find the one with highest compareTo (lowest priority = newest invisible), remove it from queue, decrement queueSize, increment evictions, complete its future exceptionally.

7. **Metrics**: totalBuilds, totalBuildTimeNs, evictions, avgBuildTimeNs(), queueDepth(), setClock().

8. **Tests** (6 additional, 10 total):
   - `testBuildESVT_producesNonEmptyResult` -- Same as ESVO but with ESVT config
   - `testQueueBackpressure_rejectsInvisibleWhenFull` -- Fill queue to MAX_QUEUE_SIZE with visible builds, submit invisible, verify BuildQueueFullException
   - `testQueueBackpressure_evictsInvisibleForVisible` -- Fill queue with mix of visible/invisible, submit visible when full, verify one invisible evicted
   - `testQueueDepthNeverExceedsMax` -- Rapid-fire 2000 builds, verify queueDepth() <= MAX_QUEUE_SIZE at all times
   - `testConcurrentBuilds_noCorruption` -- 4 threads, 100 builds each, all futures complete without unexpected exceptions
   - `testClose_failsPendingBuilds` -- Submit builds, close(), verify all futures cancelled

**Entry Criteria**: Day 2 complete (Luciferase-q8fp).
**Exit Criteria**: All 10 GpuESVOBuilder tests pass, C1 backpressure verified.
**Risks**: Thread pool timing in tests -- use timeouts on futures (5 seconds); OctreeBuilder may need significant memory for large voxel sets -- use small grid (16^3) in tests.

---

### Day 4: RegionCache Core (Luciferase-f7xd)

**Objective**: Implement the LRU cache with pinning and multi-LOD support.

**Files to Create**:
- `simulation/src/main/java/.../viz/render/RegionCache.java` (first ~250 LOC)
- `simulation/src/test/java/.../viz/render/RegionCacheTest.java` (first 6 tests)

**Implementation Details**:

1. **Inner Records**:
   - `CacheKey(RegionId, int lodLevel) implements Comparable<CacheKey>` -- compare by regionId then lodLevel
   - `CachedRegion(BuiltRegion data, long cachedAtMs, AtomicLong lastAccessedMs, long sizeBytes)`
   - `CacheStats(int totalRegions, int pinnedRegions, long memoryUsedBytes, long memoryMaxBytes, long evictions, long emergencyEvictions, long hits, long misses)` with `hitRate()` and `memoryUsagePercent()`

2. **Fields**: `ConcurrentHashMap<CacheKey, CachedRegion> cache`, `ConcurrentLinkedDeque<CacheKey> lruOrder` (head = LRU, tail = MRU), `ConcurrentHashMap.KeySetView<CacheKey, Boolean> pinnedRegions`, `AtomicLong currentMemoryBytes`, `long maxMemoryBytes`, `long regionTtlMs`, hit/miss/eviction counters, `ScheduledExecutorService evictionScheduler` (daemon thread).

3. **get(RegionId, int lodLevel)**: Lookup in cache, if found: update lastAccessedMs, move to tail of LRU (remove + addLast), increment hits. If not found: increment misses, return empty Optional.

4. **put(BuiltRegion)**: Remove existing entry for same key (adjust memory). Create CachedRegion with timestamps. Add to cache and LRU tail. Adjust memory. Trigger emergency eviction if above 90%. Standard eviction while above max.

5. **pin/unpin**: Add/remove CacheKey from pinnedRegions set.

6. **invalidate(RegionId)**: Remove ALL entries matching regionId (any LOD level). Adjust memory for each removed entry.

7. **close()**: Shutdown eviction scheduler, clear all collections.

8. **Tests** (6):
   - `testPutAndGet_basicOperation` -- Put BuiltRegion, get same key, verify Optional present with matching data
   - `testGet_miss_returnsEmpty` -- Get from empty cache, verify empty Optional
   - `testLRUEviction_evictsOldestUnpinned` -- Fill cache to maxMemory with 3 regions, put 4th, verify oldest unpinned evicted
   - `testPinning_preventsEviction` -- Pin region, fill cache, verify pinned survives, unpinned evicted
   - `testMultiLOD_sameRegionDifferentLODs` -- Put R1@LOD0 and R1@LOD2, get both, verify independent entries with different data
   - `testInvalidate_removesAllLODs` -- Cache R1 at LOD 0/1/2, invalidate(R1), verify all 3 removed and memory freed

**Entry Criteria**: GpuESVOBuilder BuiltRegion record exists (Luciferase-q8fp complete).
**Exit Criteria**: Basic cache operations verified, 6 tests pass.
**Risks**: ConcurrentLinkedDeque.remove(Object) is O(n) -- acceptable for expected cache sizes (<3000 entries).

---

### Day 5: RegionCache M1 Emergency Eviction and TTL (Luciferase-lzjb)

**Objective**: Complete the cache with emergency eviction, TTL sweep, and concurrency verification.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/RegionCache.java` (complete to ~400 LOC)
- `simulation/src/test/java/.../viz/render/RegionCacheTest.java` (complete 10 tests)

**Implementation Details**:

1. **emergencyEvict()**: When `currentMemoryBytes > maxMemoryBytes * 0.90`, evict unpinned regions in LRU order until `currentMemoryBytes <= maxMemoryBytes * 0.75`. Increment emergencyEvictionCount.

2. **evictionSweep()**: Scheduled every 10 seconds. Iterate cache entries, collect unpinned entries where `(now - lastAccessedMs) > regionTtlMs`, remove all expired entries.

3. **evictLeastRecentlyUsed()**: Walk `lruOrder` iterator from head (LRU). Skip pinned entries. Remove first unpinned entry from cache and LRU, decrement memory, increment eviction count. Return true if evicted, false if all pinned.

4. **Tests** (4 additional, 10 total):
   - `testEmergencyEviction_triggersAbove90Percent` -- maxMemory=1000 bytes, put entries totaling >900 bytes, verify emergency eviction reduces to ~750 bytes, emergencyEvictionCount incremented
   - `testTTLEviction_removesExpiredEntries` -- TTL=5s, put region, advance TestClock by 10s, trigger eviction sweep, verify region evicted
   - `testMemoryTracking_accurate` -- Empty cache: 0 bytes. Put 100-byte region: ~164 bytes. Put 200-byte region: ~364 bytes. Invalidate first: verify second remains.
   - `testConcurrentAccess_noCorruption` -- 4 threads doing 1000 random put/get/invalidate operations each. Verify: no exceptions thrown, memory tracking non-negative, cache size consistent.

**Entry Criteria**: Day 4 complete (Luciferase-f7xd).
**Exit Criteria**: All 10 RegionCache tests pass, M1 emergency eviction verified.
**Risks**: Scheduled eviction sweep timing in tests -- use TestClock and manually invoke evictionSweep() instead of waiting for scheduler.

---

### Day 6: Integration Wiring (Luciferase-7h3l)

**Objective**: Wire Phase 2 components into Phase 1 via setter injection pattern.

**Files to Modify**:
- `simulation/src/main/java/.../viz/render/AdaptiveRegionManager.java` (add ~80 LOC)
- `simulation/src/main/java/.../viz/render/RenderingServer.java` (add ~40 LOC)

**Files to Create**:
- `simulation/src/test/java/.../viz/render/BuildIntegrationTest.java` (first 2 of 4 tests)

**Implementation Details**:

1. **AdaptiveRegionManager Extensions**:
   ```java
   private volatile GpuESVOBuilder builder;  // null until Phase 2 wiring
   private volatile RegionCache cache;       // null until Phase 2 wiring

   public void setBuilder(GpuESVOBuilder builder) { this.builder = builder; }
   public void setCache(RegionCache cache) { this.cache = cache; }

   public CompletableFuture<GpuESVOBuilder.BuiltRegion> scheduleBuild(
           RegionId region, int lodLevel, boolean visible) {
       if (builder == null) return CompletableFuture.completedFuture(null);

       // Check cache first
       if (cache != null) {
           var cached = cache.get(region, lodLevel);
           if (cached.isPresent()) {
               var state = regions.get(region);
               if (state != null && !state.dirty().get()) {
                   return CompletableFuture.completedFuture(cached.get().data());
               }
               cache.invalidate(region);
           }
       }

       var state = regions.get(region);
       if (state == null || state.entities().isEmpty())
           return CompletableFuture.completedFuture(null);

       var bounds = boundsForRegion(region);
       return builder.build(region, List.copyOf(state.entities()),
                            bounds, lodLevel, visible)
                     .thenApply(built -> {
                         if (cache != null && built != null) cache.put(built);
                         state.dirty().set(false);
                         state.buildVersion().incrementAndGet();
                         return built;
                     });
   }
   ```

2. **RenderingServer Extensions**:
   ```java
   private GpuESVOBuilder gpuBuilder;
   private RegionCache regionCache;

   // In start():
   gpuBuilder = new GpuESVOBuilder(config);
   regionCache = new RegionCache(config);
   regionManager.setBuilder(gpuBuilder);
   regionManager.setCache(regionCache);

   // In stop():
   if (gpuBuilder != null) gpuBuilder.close();
   if (regionCache != null) regionCache.close();

   // Extended setClock():
   if (gpuBuilder != null) gpuBuilder.setClock(clock);
   if (regionCache != null) regionCache.setClock(clock);

   // Accessors:
   public GpuESVOBuilder getGpuBuilder() { return gpuBuilder; }
   public RegionCache getRegionCache() { return regionCache; }
   ```

3. **Tests** (2):
   - `testEntityUpdateTriggersScheduleBuild` -- Create server with testing config, start, update entity, call scheduleBuild on its region, verify BuiltRegion returned with non-empty data, verify cache populated
   - `testCacheHitSkipsBuild` -- Build once, clear dirty flag, scheduleBuild again, verify cached result returned (no re-build), verify builder.totalBuilds() did not increment

**Entry Criteria**: GpuESVOBuilder complete (Luciferase-58co) AND RegionCache complete (Luciferase-lzjb).
**Exit Criteria**: Integration wiring works, 2 integration tests pass, all Phase 1 tests still pass.
**Risks**: Setter injection timing -- ensure start() creates builder/cache before entityConsumer starts sending updates.

---

### Day 7-7.5: Final Testing and Quality Gates (Luciferase-2yfa)

**Objective**: Complete integration test suite, verify all quality gates, ensure production readiness.

**Files to Modify**:
- `simulation/src/test/java/.../viz/render/BuildIntegrationTest.java` (complete 4 tests)

**Implementation Details**:

1. **Additional Integration Tests** (2):
   - `testDirtyRegionInvalidatesCacheAndRebuilds` -- Build and cache region, update entity in that region (dirty=true), scheduleBuild(), verify cache invalidated and new build executed with updated entity positions
   - `testFullPipeline_multipleEntitiesMultipleRegions` -- Create 50 entities across 5 regions, scheduleBuild for each dirty region, verify 5 BuiltRegion results all cached, verify each is deserializable via SerializationUtils roundtrip

2. **Regression Testing**:
   - Run `mvn test -pl simulation` -- expect ~73 tests (43 Phase 1 + 30 Phase 2)
   - Verify all Phase 1 tests pass unchanged
   - Run with `-Dsurefire.rerunFailingTestsCount=0` to catch flaky tests

3. **Quality Gate Checklist**:
   - [ ] No `synchronized` blocks in any Phase 2 code
   - [ ] Clock injection via setClock() on GpuESVOBuilder and RegionCache
   - [ ] Dynamic port (port 0) in all tests via `RenderingServerConfig.testing()`
   - [ ] SLF4J logging with `{}` placeholders throughout
   - [ ] All Phase 1 tests pass (no regressions)
   - [ ] All Phase 2 tests pass
   - [ ] OctreeBuilder used with try-with-resources (AutoCloseable)
   - [ ] No external dependencies added (no Caffeine, etc.)
   - [ ] Thread pools use daemon threads
   - [ ] CompletableFuture used for async results (no blocking in hot path)

4. **Buffer Time** (0.5 day): Fix any issues found during quality gate verification.

**Entry Criteria**: Integration wiring complete (Luciferase-7h3l).
**Exit Criteria**: All ~73 tests pass, all quality gates met, Phase 2 complete.
**Risks**: Possible flaky tests from thread timing; mitigate with appropriate timeouts and TestClock usage.

---

## 5. Test Strategy

### 5.1 Test Distribution

| Test Class | Tests | Day | Sub-Phase | Focus |
|------------|-------|-----|-----------|-------|
| SerializationUtilsTest | 6 | 1 | 2A | Round-trip, compression, validation |
| GpuESVOBuilderTest | 10 | 2-3 | 2A | Build pipeline, C1, position conversion |
| RegionCacheTest | 10 | 4-5 | 2B | LRU, pinning, M1, TTL, concurrency |
| BuildIntegrationTest | 4 | 6-7 | 2C | Full pipeline end-to-end |
| **Total Phase 2** | **30** | | | |
| Phase 1 (unchanged) | 43 | | | Regression verification |
| **Grand Total** | **73** | | | |

### 5.2 Critical Behavior Tests

| Behavior | Test | Why Critical |
|----------|------|-------------|
| C1 backpressure | testQueueBackpressure_rejectsInvisibleWhenFull | Prevents OOM under entity flood |
| C1 backpressure | testQueueBackpressure_evictsInvisibleForVisible | Visible builds always prioritized |
| C1 queue bound | testQueueDepthNeverExceedsMax | Queue memory bounded |
| M1 emergency eviction | testEmergencyEviction_triggersAbove90Percent | Prevents cache OOM |
| Pinning safety | testPinning_preventsEviction | Visible regions never lost |
| Serialization fidelity | testESVORoundTrip_preservesData | Data integrity across serialize/deserialize |
| Cache-build integration | testDirtyRegionInvalidatesCacheAndRebuilds | Stale data never served |

### 5.3 Test Infrastructure

- **TestClock**: Used for TTL eviction tests and deterministic timing
- **RenderingServerConfig.testing()**: Port 0, 16^3 grid, 4 depth, 16MB cache, CPU only
- **Small voxel sets**: 10-50 entities per test to keep build times <100ms
- **Future timeouts**: 5-second timeout on all CompletableFuture.get() calls in tests
- **No GPU tests**: Phase 2 is CPU-only; GPU tests deferred to Phase 4

---

## 6. File Layout (After Phase 2)

```
simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - unchanged]
    RenderingServerConfig.java
    EntityStreamConsumer.java
    EntityPosition.java
    RegionId.java
    RegionBounds.java
    UpstreamConfig.java
    SparseStructureType.java

    [Phase 1 - modified]
    RenderingServer.java              +40 LOC (builder/cache wiring)
    AdaptiveRegionManager.java        +80 LOC (scheduleBuild, setBuilder, setCache)

    [Phase 2 - new]
    GpuESVOBuilder.java               ~400 LOC (build pipeline, C1)
    RegionCache.java                  ~400 LOC (LRU, M1, TTL)
    SerializationUtils.java           ~200 LOC (GZIP, ESVO/ESVT)

simulation/src/test/java/com/hellblazer/luciferase/simulation/viz/render/
    [Phase 1 - unchanged]
    RenderingServerTest.java
    EntityStreamConsumerTest.java
    AdaptiveRegionManagerTest.java
    RegionIdTest.java
    RegionBoundsTest.java
    RenderingServerIntegrationTest.java

    [Phase 2 - new]
    GpuESVOBuilderTest.java           ~300 LOC (10 tests)
    RegionCacheTest.java              ~300 LOC (10 tests)
    SerializationUtilsTest.java       ~150 LOC (6 tests)
    BuildIntegrationTest.java         ~200 LOC (4 tests)
```

**Total Phase 2 Production**: ~1,120 LOC new + ~120 LOC modified = ~1,240 LOC
**Total Phase 2 Tests**: ~950 LOC across 30 tests
**Total Phase 2**: ~2,190 LOC (within 1,500 LOC estimate when counting production only)

---

## 7. Risk Register

| # | Risk | Severity | Likelihood | Mitigation | Day |
|---|------|----------|------------|------------|-----|
| R1 | ESVOOctreeData node structure varies from doc | Medium | Medium | Verify actual OctreeBuilder output in SerializationUtilsTest day 1 | 1 |
| R2 | OctreeBuilder resource leak | High | Low | Always try-with-resources; doc mandates this | 3 |
| R3 | Build thread pool race conditions | Medium | Medium | Use concurrent primitives, test with timeouts, small thread counts | 3 |
| R4 | ConcurrentLinkedDeque O(n) remove | Low | High | Acceptable for <3000 entries; microsecond latency | 4 |
| R5 | ESVTData constructor mismatch | Low | Medium | Verified: 9-param canonical + convenience constructors available | 1 |
| R6 | Phase 1 regression from setter injection | Medium | Low | Additive changes only; null defaults; run full regression | 6 |
| R7 | Flaky concurrent tests | Low | Medium | Use TestClock, appropriate timeouts, small operation counts | 3,5 |
| R8 | GZIP expansion for tiny regions | Low | High | Acceptable; consistency > micro-optimization | 1 |
| R9 | Scheduled eviction timing in tests | Low | Medium | Call evictionSweep() directly in tests; don't rely on scheduler | 5 |

---

## 8. Code Reuse Verification

All Phase 2 render module dependencies have been verified against the actual codebase:

| Class | Module | File | API Verified |
|-------|--------|------|-------------|
| OctreeBuilder | render | esvo/core/OctreeBuilder.java:28 | buildFromVoxels(List<Point3i>, int):80, AutoCloseable |
| ESVTBuilder | render | esvt/builder/ESVTBuilder.java:57 | buildFromVoxels(List<Point3i>, int, int):150 |
| ESVOOctreeData | render | esvo/core/ESVOOctreeData.java:25 | getNodeIndices, getNode, getFarPointers, setters |
| ESVTData | render | esvt/core/ESVTData.java:40 | record: nodes, contours, farPointers, rootType, etc. |
| ESVONodeUnified | render | esvo/core/ESVONodeUnified.java | SIZE_BYTES=8:32, writeTo:290, fromByteBuffer:299 |
| ESVTNodeUnified | render | esvt/core/ESVTNodeUnified.java | SIZE_BYTES=8:54, writeTo:373, fromByteBuffer:382 |
| Point3i | common | geometry/Point3i.java | Already used by Phase 1 (via MortonCurve) |
| MortonCurve | common | geometry/MortonCurve.java | Already used by AdaptiveRegionManager |
| Clock | simulation | distributed/integration/Clock.java | Already used by all Phase 1 components |

---

## 9. Parallel Execution Guidance

For each task bead, the java-developer agent should:

1. **Use sequential thinking** (mcp__sequential-thinking__sequentialthinking) for analysis of render module API compatibility before writing code
2. **Write tests FIRST** (TDD) -- test compiles with stubs, then implement to pass
3. **Spawn sub-agents** for independent test verification if working on multiple test classes
4. **Check compilation frequently** -- `mvn compile -pl simulation` between logical units
5. **Run tests incrementally** -- `mvn test -pl simulation -Dtest=ClassName` for current class

---

## 10. Integration Points with Phase 3

Phase 2 exit criteria establish the foundation for Phase 3 (Viewport Tracking and Region Streaming):

1. **RegionCache.get(RegionId, lodLevel)** -- Phase 3's ViewportTracker queries cache for visible regions
2. **RegionCache.pin/unpin** -- Phase 3's ViewportTracker pins visible regions
3. **BuiltRegion.serializedData** -- Phase 3's RegionStreamer sends this as binary WS frames
4. **AdaptiveRegionManager.scheduleBuild** -- Phase 3 triggers builds based on viewport changes
5. **GpuESVOBuilder.queueDepth()** -- Phase 3's metrics endpoint reports build queue status

---

## 11. Quality Gates (Phase 2 Complete)

All must pass before proceeding to Phase 3:

- [ ] GpuESVOBuilder builds correct ESVO from entity positions (round-trip verified)
- [ ] GpuESVOBuilder builds correct ESVT from entity positions (round-trip verified)
- [ ] CPU build path works (no GPU/OpenCL required)
- [ ] C1: Build queue never exceeds MAX_QUEUE_SIZE under entity flood
- [ ] RegionCache LRU eviction works correctly under memory pressure
- [ ] RegionCache pinning prevents eviction of pinned regions
- [ ] M1: Emergency eviction triggers above 90% and reduces to 75%
- [ ] Multi-LOD: Same region cached at different LOD levels independently
- [ ] Serialization round-trip preserves all ESVO/ESVT data fields
- [ ] GZIP compression reduces serialized data size
- [ ] All Phase 1 tests continue to pass (no regressions)
- [ ] All Phase 2 tests pass (`mvn test -pl simulation`)
- [ ] No `synchronized` blocks (concurrent collections only)
- [ ] Clock injection for all time-dependent code
- [ ] Dynamic ports in all tests (port 0)
- [ ] SLF4J logging with `{}` placeholders

---

## Appendix A: Bead Quick Reference

| Bead ID | Title | Day | Depends On |
|---------|-------|-----|------------|
| Luciferase-r47c | Epic: Phase 2 GPU Integration | - | (parent) |
| Luciferase-jbbx | Phase 2A: Core Build Pipeline | 1-3 | r47c |
| Luciferase-wxu9 | Phase 2B: Region Caching | 4-5 | jbbx |
| Luciferase-gix5 | Phase 2C: Integration and Testing | 6-7.5 | wxu9 |
| Luciferase-an6a | SerializationUtils | 1 | (none - first task) |
| Luciferase-q8fp | GpuESVOBuilder records + positions | 2 | an6a |
| Luciferase-58co | GpuESVOBuilder pipeline + C1 | 3 | q8fp |
| Luciferase-f7xd | RegionCache core | 4 | q8fp |
| Luciferase-lzjb | RegionCache M1 + TTL | 5 | f7xd |
| Luciferase-7h3l | Integration wiring | 6 | 58co, lzjb |
| Luciferase-2yfa | Final testing + quality gates | 7-7.5 | 7h3l |

## Appendix B: Command Reference

```bash
# Start Phase 2
bd update Luciferase-r47c --status in_progress

# Start Day 1
bd update Luciferase-an6a --status in_progress

# Complete a task
bd close Luciferase-an6a --reason "SerializationUtils implemented, 6 tests pass"

# Check what's ready to work on
bd ready

# Run Phase 2 tests
mvn test -pl simulation -Dtest="SerializationUtilsTest,GpuESVOBuilderTest,RegionCacheTest,BuildIntegrationTest"

# Run all tests (including Phase 1 regression)
mvn test -pl simulation

# Verify no synchronized blocks
grep -rn "synchronized" simulation/src/main/java/.../viz/render/
```
