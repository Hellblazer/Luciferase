# Documentation Archive

This directory contains archived documentation that is no longer current but preserved for historical reference.

## Archive Structure

```
archive/
├── analysis/          # Point-in-time analysis documents
├── benchmarks/        # Historical benchmark reports
├── decisions/         # Superseded or versioned decisions
├── designs/           # Obsolete architecture versions
├── phase2/            # Phase 2 plan evolution (in plans/archive/phase2/)
├── spikes/            # Spike investigation reports
└── technical-decisions/  # One-time fixes and changes
```

## Archival Policy

### When to Archive

**Archive a document if it**:
- Has a version suffix (v1, v2, V2, V3, etc.) and is not the latest version
- Is a point-in-time report (benchmark, spike, analysis)
- Is marked OBSOLETE or superseded by a newer decision
- Contains contradictory or outdated information replaced by current docs

### When to Delete

**Delete a document if it**:
- Is a complete duplicate with no unique content
- Directly contradicts current architecture with no historical value
- Was created in error

### When to Keep as Living Doc

**Keep in doc/ if it**:
- Is current architecture/ADR/runbook
- Is an active plan or roadmap
- Is a living document updated regularly
- Serves as canonical reference for current system

## Subdirectory Purposes

### analysis/
Point-in-time architectural analysis documents that informed decisions but are not current:
- GHOST_LAYER_CONSOLIDATION_ANALYSIS.md
- MOBILE_BUBBLE_ARCHITECTURE_ANALYSIS.md

### benchmarks/
Historical performance benchmark reports:
- DAY_0_BENCHMARK_REPORT.md

### decisions/
Superseded or versioned architectural decisions:
- V3_GHOST_MANAGER_DECISION.md
- ADR_002_OBSOLETE_FIXED_VOLUME.md

### designs/
Obsolete architecture versions that have been replaced:
- DISTRIBUTED_ANIMATION_ARCHITECTURE_v3.0.md
- DISTRIBUTED_ANIMATION_ARCHITECTURE_v4.0.md

### spikes/
Investigation and spike reports from exploratory work:
- PROCESSBUILDER_SPIKE_REPORT.md

### technical-decisions/
One-time fixes and technical changes (not ongoing patterns):
- TECHNICAL_DECISION_CACHE_FIX.md
- TECHNICAL_DECISION_CONCURRENCY_TEST_FIX.md

## Accessing Archived Docs

Archived documents remain accessible via git history and can be referenced by relative path:

```markdown
See archived analysis: [GHOST_LAYER_CONSOLIDATION_ANALYSIS.md](analysis/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md)
```

## Current Documentation

For current architecture, see parent `doc/` directory:
- [ARCHITECTURE_DISTRIBUTED.md](../ARCHITECTURE_DISTRIBUTED.md) - Canonical distributed architecture
- [ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md](../ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md) - Migration and consensus
- [ADR_002_CLOCK_FIXED_NANOTIME.md](../ADR_002_CLOCK_FIXED_NANOTIME.md) - Clock determinism
- [H3_DETERMINISM_EPIC.md](../H3_DETERMINISM_EPIC.md) - Deterministic testing patterns
- [TESTING_PATTERNS.md](../TESTING_PATTERNS.md) - Testing best practices

---

**Last Updated**: 2026-02-14
