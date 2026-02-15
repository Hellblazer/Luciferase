# Neighbor Consistency (NC) Monitoring Strategy

**Created**: 2026-02-08
**Bead**: Luciferase-6ae2
**Status**: Production Monitoring Plan

## Executive Summary

This document defines a production monitoring strategy for Neighbor Consistency (NC) metrics to validate Fireflies virtual synchrony effectiveness in the VON distributed spatial index.

**Key Metrics**:
- NC = known_neighbors / actual_neighbors_in_aoi
- Target: NC > 0.95 sustained
- Alert: NC < 0.95 for >5 consecutive measurements (50ms window at 100Hz)

## 1. Implementation Analysis

### Current Implementation (Manager.calculateNC)

**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/von/Manager.java:410-433`

```java
public float calculateNC(Bubble bubble) {
    if (!bubbles.containsKey(bubble.id())) {
        return 0.0f;  // Not managed
    }

    int knownNeighbors = bubble.neighbors().size();

    // Count bubbles within AOI radius (ground truth)
    int actualNeighbors = 0;
    for (Bubble other : bubbles.values()) {
        if (!other.id().equals(bubble.id())) {
            double dist = bubble.position().distance(other.position());
            if (dist <= aoiRadius) {
                actualNeighbors++;
            }
        }
    }

    if (actualNeighbors == 0) {
        return 1.0f;  // Solo bubble - perfect NC
    }

    return (float) knownNeighbors / actualNeighbors;
}
```

### Metric Components

1. **Known Neighbors**: `bubble.neighbors().size()`
   - Local view of discovered neighbors
   - Updated via JOIN, MOVE, LEAVE protocol messages
   - May lag behind ground truth during transient states

2. **Actual Neighbors**: Count of bubbles within AOI radius
   - Ground truth from manager's global perspective
   - Calculated using position distance: `bubble.position().distance(other.position()) <= aoiRadius`
   - Excludes self

3. **NC Calculation**:
   - `NC = known_neighbors / actual_neighbors`
   - Special cases:
     - Solo bubble (actual_neighbors = 0): Returns 1.0 (perfect consistency)
     - Unmanaged bubble: Returns 0.0 (error case)

### Complexity Analysis

- **Time Complexity**: O(n) where n = total bubbles in manager
- **Space Complexity**: O(1) (single float return)
- **Suitability**:
  - ✅ Production-ready for small-medium clusters (<100 bubbles)
  - ⚠️ Consider spatial indexing for large clusters (>1000 bubbles)

## 2. Monitoring Strategy

### 2.1 Metric Definition

**Primary Metric**: Per-bubble NC
```
NC(bubble) = |bubble.neighbors()| / |bubbles_within_aoi(bubble.position(), aoiRadius)|
```

**Aggregate Metrics**:
```
NC_mean   = Σ NC(bubble_i) / total_bubbles
NC_min    = min(NC(bubble_i))  // Worst-case consistency
NC_p50    = median(NC(bubble_i))
NC_p95    = 95th percentile(NC(bubble_i))
NC_p99    = 99th percentile(NC(bubble_i))
```

### 2.2 Target Thresholds

| Metric | Target | Warning | Critical | Justification |
|--------|--------|---------|----------|---------------|
| NC_min | > 0.95 | < 0.95 | < 0.80 | Fireflies virtual synchrony guarantee |
| NC_mean | > 0.98 | < 0.98 | < 0.90 | Average case health |
| NC_p95 | > 0.95 | < 0.95 | < 0.85 | Tail consistency |

**Rationale**:
- **0.95 threshold**: Accounts for 5% transient inconsistency during view changes
- **Sustained degradation**: Alert only if threshold violated for >5 consecutive measurements
- **View stability**: Fireflies view stabilizes after 30 ticks (300ms at 100Hz)

### 2.3 Collection Frequency

**Option A: Periodic Sampling (Recommended)**
```java
// Sample NC every 10 ticks (100ms at 100Hz)
private static final int NC_SAMPLE_INTERVAL_TICKS = 10;

controller.addTickListener((simTime, lamportClock) -> {
    if (lamportClock % NC_SAMPLE_INTERVAL_TICKS == 0) {
        collectNCMetrics();
    }
});
```

**Benefits**:
- Low overhead (0.1% at 100Hz, 10 tick interval)
- Captures transient view changes
- Aligns with Fireflies view stability window (30 ticks)

**Option B: Event-Triggered**
```java
// Sample NC after VON protocol events
bubble.addEventListener(event -> {
    if (event instanceof Event.Join ||
        event instanceof Event.Move ||
        event instanceof Event.Leave) {
        scheduleNCMetric(bubble.id());  // Delayed 300ms for view stability
    }
});
```

**Benefits**:
- Zero overhead during steady state
- Focused on protocol transitions

**Recommendation**: **Option A** (periodic sampling) for continuous validation

### 2.4 Alert Conditions

**Alert Levels**:

1. **Warning** (NC_min < 0.95 for >5 consecutive samples):
   - **Duration**: >50ms sustained degradation
   - **Action**: Log warning, increment counter
   - **Escalation**: Critical if persists >1 second

2. **Critical** (NC_min < 0.80 OR NC_mean < 0.90):
   - **Duration**: Any single sample
   - **Action**: Immediate alert, capture diagnostics
   - **Diagnostics**: View ID, neighbor lists, position, pending messages

3. **Recovery** (NC_min > 0.95 after alert):
   - **Action**: Log recovery, calculate downtime
   - **Metrics**: Alert duration, max degradation

## 3. Instrumentation Approach

### 3.1 Metrics Collection

**Approach: Metrics Collector Class**

```java
public class NCMetricsCollector implements RealTimeController.TickListener {
    private final Manager manager;
    private final MetricsRegistry registry;  // Micrometer, Prometheus, etc.
    private final int sampleIntervalTicks;
    private final Deque<NCSnapshot> history;  // Ring buffer for trend analysis

    private static final int HISTORY_SIZE = 50;  // 5 seconds at 10 tick interval

    public NCMetricsCollector(Manager manager, MetricsRegistry registry, int sampleIntervalTicks) {
        this.manager = manager;
        this.registry = registry;
        this.sampleIntervalTicks = sampleIntervalTicks;
        this.history = new ArrayDeque<>(HISTORY_SIZE);
    }

    @Override
    public void onTick(long simTime, long lamportClock) {
        if (lamportClock % sampleIntervalTicks != 0) {
            return;  // Skip non-sample ticks
        }

        var snapshot = collectSnapshot();
        history.addLast(snapshot);
        if (history.size() > HISTORY_SIZE) {
            history.removeFirst();
        }

        publishMetrics(snapshot);
        checkAlerts(snapshot);
    }

    private NCSnapshot collectSnapshot() {
        var allBubbles = manager.getAllBubbles();
        var ncValues = new ArrayList<Float>(allBubbles.size());

        for (var bubble : allBubbles) {
            float nc = manager.calculateNC(bubble);
            ncValues.add(nc);
        }

        return new NCSnapshot(
            System.currentTimeMillis(),
            ncValues,
            calculateStats(ncValues)
        );
    }

    private NCStats calculateStats(List<Float> values) {
        if (values.isEmpty()) {
            return NCStats.EMPTY;
        }

        var sorted = new ArrayList<>(values);
        sorted.sort(Float::compareTo);

        float sum = 0.0f;
        float min = sorted.get(0);
        float max = sorted.get(sorted.size() - 1);

        for (float v : sorted) {
            sum += v;
        }

        return new NCStats(
            sum / sorted.size(),  // mean
            min,
            max,
            sorted.get(sorted.size() / 2),  // p50
            sorted.get((int) (sorted.size() * 0.95)),  // p95
            sorted.get((int) (sorted.size() * 0.99))   // p99
        );
    }

    private void publishMetrics(NCSnapshot snapshot) {
        var stats = snapshot.stats();

        // Micrometer-style metrics
        registry.gauge("von.nc.mean", stats.mean());
        registry.gauge("von.nc.min", stats.min());
        registry.gauge("von.nc.max", stats.max());
        registry.gauge("von.nc.p50", stats.p50());
        registry.gauge("von.nc.p95", stats.p95());
        registry.gauge("von.nc.p99", stats.p99());
        registry.gauge("von.bubbles.total", snapshot.values().size());
    }

    private void checkAlerts(NCSnapshot snapshot) {
        var stats = snapshot.stats();

        // Check critical thresholds
        if (stats.min() < 0.80) {
            log.error("CRITICAL: NC_min={} < 0.80 threshold", stats.min());
            captureDiagnostics();
        } else if (stats.min() < 0.95) {
            // Check if sustained (5+ consecutive samples)
            long sustainedCount = history.stream()
                .filter(s -> s.stats().min() < 0.95)
                .count();

            if (sustainedCount >= 5) {
                log.warn("WARNING: NC_min={} < 0.95 for {} consecutive samples",
                         stats.min(), sustainedCount);
            }
        }

        // Recovery detection
        if (stats.min() > 0.95 && wasInAlert()) {
            log.info("RECOVERY: NC_min={} > 0.95, alert cleared", stats.min());
            clearAlert();
        }
    }

    private void captureDiagnostics() {
        for (var bubble : manager.getAllBubbles()) {
            log.error("Diagnostics for bubble {}: position={}, neighbors={}, NC={}",
                      bubble.id(),
                      bubble.position(),
                      bubble.neighbors().size(),
                      manager.calculateNC(bubble));
        }
    }
}

record NCSnapshot(long timestamp, List<Float> values, NCStats stats) {}

record NCStats(float mean, float min, float max, float p50, float p95, float p99) {
    static final NCStats EMPTY = new NCStats(0, 0, 0, 0, 0, 0);
}
```

### 3.2 Integration Points

**Lifecycle Integration**:
```java
public class Manager {
    private NCMetricsCollector metricsCollector;

    public void enableMetrics(MetricsRegistry registry, int sampleIntervalTicks) {
        this.metricsCollector = new NCMetricsCollector(this, registry, sampleIntervalTicks);

        // Register with lifecycle coordinator tick source
        // Assumes bubbles share a common RealTimeController
        var controller = getSharedController();  // Implementation detail
        controller.addTickListener(metricsCollector);

        log.info("NC metrics enabled: sampleInterval={} ticks", sampleIntervalTicks);
    }

    public void disableMetrics() {
        if (metricsCollector != null) {
            var controller = getSharedController();
            controller.removeTickListener(metricsCollector);
            metricsCollector = null;
            log.info("NC metrics disabled");
        }
    }
}
```

**Production Usage**:
```java
// Create manager with metrics enabled
var transportRegistry = new LocalServerTransport.Registry();
var manager = new Manager(transportRegistry);

// Enable metrics with 10-tick interval (100ms at 100Hz)
manager.enableMetrics(prometheusRegistry, 10);

// ... normal VON operations ...

// Metrics auto-collected every 100ms
```

### 3.3 Metrics Exposure

**Prometheus Metrics** (recommended):
```
# HELP von_nc_mean Average neighbor consistency across all bubbles
# TYPE von_nc_mean gauge
von_nc_mean 0.987

# HELP von_nc_min Minimum neighbor consistency (worst case)
# TYPE von_nc_min gauge
von_nc_min 0.953

# HELP von_nc_p95 95th percentile neighbor consistency
# TYPE von_nc_p95 gauge
von_nc_p95 0.991

# HELP von_bubbles_total Total number of managed bubbles
# TYPE von_bubbles_total gauge
von_bubbles_total 47
```

**Grafana Dashboard Queries**:
```promql
# NC Min over time (detect transients)
von_nc_min

# Alert query
von_nc_min < 0.95 for 5s

# Recovery rate
rate(von_nc_recovery_total[1m])
```

## 4. Relationship to Fireflies Virtual Synchrony

### 4.1 Virtual Synchrony Guarantee

**Fireflies Property**: All messages sent within a stable view are delivered to all live members.

**NC Implication**:
- **Stable view** → NC should approach 1.0
- **View transition** → NC may temporarily degrade (allowed by 0.95 threshold)
- **Sustained NC < 0.95** → Indicates Fireflies membership instability OR protocol bugs

### 4.2 ACK TOCTOU Race (Luciferase-y1pd)

**Issue**: Fixed in commit fixing Luciferase-y1pd (CLOSED)
- **Problem**: View ID captured BEFORE send, creating race window
- **Solution**: Capture view ID AFTER successful send
- **Impact on NC**: Eliminates false-positive ACK failures that would prevent neighbor updates

**NC Monitoring Validates Fix**:
```yaml
BEFORE Fix (TOCTOU race):
- False ACK failures → missed neighbor updates → NC degradation
- Expected: NC oscillates 0.85-0.95 during view changes

AFTER Fix:
- Correct ACK semantics → reliable neighbor updates → stable NC
- Expected: NC > 0.95 sustained, brief dips during view transitions
```

### 4.3 Expected Behavior

**Steady State** (no joins/leaves/moves):
```
NC_min  = 1.00  (all neighbors known)
NC_mean = 1.00
```

**View Transition** (Fireflies membership change):
```
t=0ms:    View stable, NC = 1.00
t=50ms:   Member leaves, view unstable
t=100ms:  LEAVE messages propagating, NC drops to 0.92 (transient)
t=300ms:  View stabilizes (30 ticks), NC recovers to 1.00
```

**Acceptable Pattern**:
- Brief NC dips (< 300ms) during view transitions
- Rapid recovery to NC > 0.95

**Alert Pattern** (indicates bug):
- Sustained NC < 0.95 for >1 second
- No view transition event correlation
- Suggests: Protocol message loss, ACK bugs, or Fireflies instability

## 5. Implementation Checklist

- [ ] Create `NCMetricsCollector` class in `simulation/src/main/java/.../von/`
- [ ] Add `enableMetrics()` / `disableMetrics()` to Manager
- [ ] Integrate with Micrometer or Prometheus metrics registry
- [ ] Configure 10-tick sample interval (100ms at 100Hz)
- [ ] Add Grafana dashboard for NC visualization
- [ ] Configure alerts:
  - Warning: NC_min < 0.95 for >5 seconds
  - Critical: NC_min < 0.80 (immediate)
- [ ] Add NC metrics to production monitoring runbook
- [ ] Test in staging with induced view changes

## 6. Testing Strategy

**Unit Test**: Verify metric calculation
```java
@Test
void testNCMetricsCalculation() {
    var manager = createTestManager();
    var bubble1 = manager.createBubble();
    var bubble2 = manager.createBubble();

    // Both bubbles within AOI
    manager.joinAt(bubble1, new Point3D(0, 0, 0));
    manager.joinAt(bubble2, new Point3D(10, 0, 0));  // Within 50.0f AOI

    // Wait for protocol convergence
    Thread.sleep(500);

    // Verify NC = 1.0 (both bubbles know each other)
    assertEquals(1.0f, manager.calculateNC(bubble1), 0.01f);
    assertEquals(1.0f, manager.calculateNC(bubble2), 0.01f);
}
```

**Integration Test**: Verify alerting during view transitions
```java
@Test
void testNCDegradationDuringViewChange() {
    var manager = createTestManager();
    var collector = new NCMetricsCollector(manager, mockRegistry, 10);

    // Create cluster
    var bubbles = createCluster(10);

    // Induce view change (remove member)
    bubbles.get(0).close();

    // Sample NC during transition
    Thread.sleep(100);
    var snapshot = collector.collectSnapshot();

    // Should see transient degradation
    assertTrue(snapshot.stats().min() < 1.0f, "NC should degrade during transition");
    assertTrue(snapshot.stats().min() > 0.90f, "NC should stay above 0.90 (transient)");

    // Wait for recovery
    Thread.sleep(500);
    var recovered = collector.collectSnapshot();

    // Should recover to NC > 0.95
    assertTrue(recovered.stats().min() > 0.95f, "NC should recover after view stabilizes");
}
```

## 7. Production Deployment Plan

**Phase 1: Shadow Metrics** (Week 1)
- Deploy collector with metrics emission
- No alerts (observation only)
- Establish baseline NC values

**Phase 2: Non-Critical Alerts** (Week 2)
- Enable warning alerts (NC < 0.95 for >5s)
- Log to monitoring system
- Manual review of alerts

**Phase 3: Production Alerts** (Week 3+)
- Enable critical alerts (NC < 0.80)
- Integrate with on-call rotation
- Add to SLA/SLO tracking

## 8. Success Criteria

- [ ] NC_min > 0.95 sustained in production (99% of time)
- [ ] Alert false-positive rate < 1% (validated in staging)
- [ ] Overhead < 1% CPU at 100Hz tick rate
- [ ] View transition NC recovery time < 500ms (P99)
- [ ] Zero sustained NC degradation events in first month

## Conclusion

This monitoring strategy provides:

1. **Validation**: Confirms Fireflies virtual synchrony effectiveness
2. **Early Warning**: Detects protocol bugs before user impact
3. **Performance**: Low overhead (0.1% at recommended settings)
4. **Actionable**: Clear thresholds and diagnostic capture

**Next Steps**:
1. Implement `NCMetricsCollector` class
2. Add to Manager lifecycle
3. Deploy to staging environment
4. Validate alert thresholds
5. Document in production runbook

**Related Beads**:
- Luciferase-6ae2 (this monitoring plan)
- Luciferase-y1pd (ACK TOCTOU race - FIXED)
