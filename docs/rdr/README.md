# Recommendation Decisioning Records (RDRs)

**Last Updated**: 2026-05-29
**Status**: Current

RDRs are specification prompts built through iterative research and refinement.

See the [RDR process documentation](https://github.com/cwensel/rdr) for the full workflow.

## Index

| ID | Title | Status | Type | Priority |
|----|-------|--------|------|----------|
| [RDR-001](RDR-001-axis-aligned-bounding-tetrahedra.md) | Axis-Aligned Bounding Tetrahedra (AABT) for Tetree Spatial Index | closed | Architecture | medium |
| [RDR-002](RDR-002-12dop-exact-containment.md) | 12-DOP Exact Containment for Kuhn Tetrahedra — Replace containsUltraFast | closed | Architecture | high |
| [RDR-003](RDR-003-fcc-aligned-spatial-indexing.md) | FCC-Aligned Spatial Indexing for Luciferase — VoN Spatialization, RD Overlay, and Optional TetOctree | implemented | Architecture | medium |
| [RDR-004](RDR-004-von-socketserver-deserialization-hardening.md) | Harden Network Deserialization on the VoN SocketServer | accepted | Security | high |
| [RDR-005](RDR-005-grpc-tls-auth-model.md) | gRPC TLS + Authentication Model for Ghost and Balancing Services | accepted | Security | high |
| [RDR-006](RDR-006-break-simulation-portal-coupling.md) | Break the simulation→portal Coupling (BubbleBounds JavaFX Pull-In) | closed | Architecture | medium |
| [RDR-007](RDR-007-extract-lucien-distributed-module.md) | Extract a lucien-distributed Module (gRPC Clients out of lucien) | closed | Architecture | medium |
| [RDR-008](RDR-008-decompose-abstractspatialindex.md) | Decompose the AbstractSpatialIndex God-Class | closed | Architecture | medium |
| [RDR-009](RDR-009-prism-full-cube-coverage.md) | Prism Full-Cube Coverage via Two-Prism Cover | closed | Architecture | medium |
| [RDR-010](RDR-010-pyramid-spatial-index.md) | Pyramid Spatial Index — Close the Hybrid Hex↔Tet Partition Gap | accepted | Architecture | medium |
| [RDR-011](RDR-011-pyramid-sfc-linear-id.md) | PyramidIndex SFC Linear-ID Primitive — Port t8code linear_id, or Accept the Morton-Key Divergence | draft | Architecture | low |
| [RDR-012](RDR-012-pyramid-deep-cross-shape-productionization.md) | PyramidIndex Domain Contract & Deep Cross-Shape — Define the Reachable-SFC Set, Productionize the Deep-Tet Path or Fence It | accepted | Architecture | medium |
| [RDR-013](RDR-013-grpc-server-dos-hardening.md) | gRPC Server DoS Hardening — Explicit Inbound Message-Size Bounds for Ghost/Balance | accepted | Security | low |
| [RDR-014](RDR-014-cross-level-tet-edge-vertex-neighbors.md) | Cross-Level Tetrahedral Edge/Vertex Neighbor Traversal for TetreeNeighborFinder | accepted | Correctness | medium |
| [RDR-015](RDR-015-simulation-bubble-grid-coordinate-space.md) | Reconcile Simulation Bubble-Grid Coordinate Space — Revive the Dead Migration Path | accepted | Architecture | medium |
| [RDR-016](RDR-016-persistence-productionization.md) | Productionize WAL Persistence and Recovery in the Simulation Node Lifecycle | closed | Architecture | medium |
| [RDR-017](RDR-017-production-node-bootstrap.md) | Production Node Bootstrap — Compose the Distributed Simulation Node (Lifecycle, WAL, Recovery, Migration) | accepted | Architecture | medium |
| [RDR-018](RDR-018-dynamic-topology-vs-single-level-partition.md) | Dynamic Topology (Split/Merge) vs the RDR-015 Single-Level Migration Partition | accepted | Architecture | medium |

## Post-Mortems

Closed/implemented RDRs carry a post-mortem under [`post-mortem/`](post-mortem/) capturing realized-vs-predicted outcomes.

| RDR | Post-Mortem |
|-----|-------------|
| RDR-001 | [post-mortem/001-axis-aligned-bounding-tetrahedra.md](post-mortem/001-axis-aligned-bounding-tetrahedra.md) |
| RDR-002 | [post-mortem/002-12dop-exact-containment.md](post-mortem/002-12dop-exact-containment.md) |
| RDR-003 | [post-mortem/003-fcc-aligned-spatial-indexing.md](post-mortem/003-fcc-aligned-spatial-indexing.md) |
| RDR-006 | [post-mortem/006-break-simulation-portal-coupling.md](post-mortem/006-break-simulation-portal-coupling.md) |
| RDR-007 | [post-mortem/007-extract-lucien-distributed-module.md](post-mortem/007-extract-lucien-distributed-module.md) |
| RDR-008 | [post-mortem/008-decompose-abstractspatialindex.md](post-mortem/008-decompose-abstractspatialindex.md) |
| RDR-009 | [post-mortem/009-prism-full-cube-coverage.md](post-mortem/009-prism-full-cube-coverage.md) |
