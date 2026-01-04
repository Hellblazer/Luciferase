# Simulation Bubbles: Distributed Animation

**Status**: Design Complete - See Implementation Plan
**Implementation Plan**: [`.pm/SIMULATION_BUBBLES_PLAN.md`](../../.pm/SIMULATION_BUBBLES_PLAN.md)

---

## Overview

Bubbles are **emergent**, not computed. No node knows "the bubble". Each node only knows:

- Entities it simulates (local)
- Entities nearby that might interact (ghosts from peers)

**Bubble = connected component of interacting entities across nodes.**
No one computes this. It emerges from pairwise peer coordination.

## Consistency Model

This design uses **Causal Consistency** (not global determinism):

- Causal ordering within interaction bubbles
- Eventual consistency across bubbles
- Bounded rollback window (100-200ms, GGPO-style)
- Divergence beyond rollback window is accepted as physically correct

**Key Insight**: Interest management (AOI) IS causality - entities outside AOI are spacelike separated.

## Architecture

```text
Layer 4: SIMULATION BUBBLES (simulation module)
  SimulationNode, BucketScheduler, CausalRollback, MigrationManager
         | uses
Layer 3: GHOST LAYER (lucien module)
  GhostZoneManager, GhostLayer, GhostCommunicationManager
         | uses
Layer 2: SPATIAL INDEX (lucien module)
  Tetree, TetreeKey, Entity<Key,Content>, EntityID
         | uses
Layer 1: FOREST (lucien module)
  Forest, TreeNode
```

## Implementation Phases

| Phase | Focus | Status |
|-------|-------|--------|
| 0 | Validation Sprint | Ready |
| 1 | Entity Ownership | Blocked by Phase 0 |
| 2 | Causal Synchronization | Blocked by Phase 1 |
| 3 | Ghost Layer Boundaries | Blocked by Phase 1 |
| 4 | Entity Migration | Blocked by Phases 2 & 3 |
| 5 | Dead Reckoning (Optional) | Blocked by Phases 2 & 3 |

## Research Foundation

- Lamport, "Time, Clocks, and the Ordering of Events" (1978)
- GGPO rollback netcode (fighting games)
- Jefferson, "Virtual Time" (TimeWarp)
- VON papers (Voronoi Overlay Network)

See ChromaDB:

- `research::distributed-consistency::causal-consistency-games`
- `research::distributed-consistency::implementation-recommendations`
- `research::von::*`

## Full Documentation

For complete implementation details, code samples, and test strategies:

**[`.pm/SIMULATION_BUBBLES_PLAN.md`](../../.pm/SIMULATION_BUBBLES_PLAN.md)**
