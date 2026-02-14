# GPU-Accelerated ESVO/ESVT Rendering Service Design

**Date**: 2026-02-13
**Status**: Architecture Design Phase
**Scope**: Bring GPU-accelerated rendering from portal to simulation servers

## Vision

Extend the simulation visualization infrastructure with GPU-accelerated ESVO/ESVT streaming, enabling efficient visualization of large-scale distributed simulations through adaptive octree region streaming with progressive client-side rendering.

## Context

### Existing Infrastructure

**Portal Module - SpatialInspectorServer**:
- Javalin REST API (port 7071)
- GPU-accelerated ESVO/ESVT rendering via OpenCL
- Session-based spatial index operations
- Proven GPU rendering pipeline (GpuService, RenderService)

**Simulation Module - Real-time Streaming**:
- EntityVisualizationServer (port 7080) - Single bubble WebSocket streaming
- MultiBubbleVisualizationServer (port 7081) - Multi-bubble with topology events
- Three.js clients rendering raw entity positions
- Real-time entity updates (30-60 FPS)

### Goals

1. **GPU-Accelerated Conversion**: Use GPU to build compact ESVO/ESVT structures from entity data
2. **Adaptive Region Streaming**: Hierarchical octree regions with viewport-based LOD
3. **Progressive Client Rendering**: WebGPU > WebGL > CPU fallback with unified interface
4. **Backward Compatibility**: Existing entity streaming clients continue working
5. **Independent Scaling**: Rendering service scales separately from simulation

## Design Decisions

### Key Choices Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Rendering Model | GPU-Accelerated ESVO/ESVT Streaming | Compact representation, client-side raytracing, efficient bandwidth |
| Update Model | Region-Based Streaming with viewport tracking | Clients only receive visible regions, scales to huge worlds |
| Spatial Organization | Adaptive Quadtree/Octree Regions | Optimal bandwidth, natural LOD, handles variable density |
| Client Rendering | Progressive Enhancement (WebGPU > WebGL > CPU) | Works everywhere, uses best available tech, future-proof |
| Architecture | Hybrid: Separate RenderingServer + Backward Compatible | Clean separation, reuses existing infrastructure, optional GPU path |

## High-Level Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                     Simulation Layer                         │
│  ┌──────────────────┐    ┌──────────────────┐              │
│  │ EntityViz Server │    │ MultiBubble      │              │
│  │ (port 7080)      │    │ Server (7081)    │              │
│  └────────┬─────────┘    └────────┬─────────┘              │
│           │ WebSocket             │ WebSocket               │
│           │ (entity positions)    │ (entities + topology)   │
└───────────┼───────────────────────┼─────────────────────────┘
            │                       │
            ├───────────────────────┤
            │                       │
┌───────────▼───────────────────────▼─────────────────────────┐
│                  RenderingServer (NEW)                       │
│                     (port 7090)                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Entity Stream Consumer (subscribes to sim servers)    │ │
│  └────────────────┬───────────────────────────────────────┘ │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │ Adaptive Octree Region Manager                         │ │
│  │  - Builds spatial octree from entity data             │ │
│  │  - Tracks client viewports                            │ │
│  │  - Determines visible regions per client              │ │
│  └────────────────┬───────────────────────────────────────┘ │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │ GPU ESVO/ESVT Builder (parallel per region)           │ │
│  │  - GpuService from portal                             │ │
│  │  - Builds octree/tetree structures                    │ │
│  │  - OpenCL acceleration                                │ │
│  └────────────────┬───────────────────────────────────────┘ │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │ WebSocket ESVO/ESVT Streamer                          │ │
│  │  - Streams compact voxel/tetrahedral data            │ │
│  │  - Delta compression                                  │ │
│  │  - Region-based protocol                              │ │
│  └────────────────┬───────────────────────────────────────┘ │
└───────────────────┼─────────────────────────────────────────┘
                    │ WebSocket (ESVO/ESVT regions)
         ┌──────────┼──────────┐
         │          │          │
┌────────▼────┐ ┌──▼──────┐ ┌─▼─────────┐
│ Client A    │ │Client B │ │ Client C  │
│ (WebGPU)    │ │(WebGL)  │ │ (CPU)     │
│ Viewport 1  │ │Viewport2│ │ Viewport3 │
└─────────────┘ └─────────┘ └───────────┘
```

### Design Principles

1. **Backward Compatibility**: Existing simulation servers unchanged, continue serving raw entity streams
2. **Opt-In Rendering**: Clients choose entity streaming OR ESVO/ESVT rendering at connection time
3. **Separation of Concerns**: RenderingServer is stateless w.r.t simulation logic, purely transforms entity→ESVO
4. **Independent Scaling**: Deploy RenderingServer on GPU-heavy machines, scale separately from simulation
5. **Progressive Enhancement**: Client renderer adapts to browser capabilities (WebGPU > WebGL > CPU)

### Deployment Modes

**Development**: Single machine runs simulation + RenderingServer (different ports)
**Production**: Separate machines, RenderingServer cluster behind load balancer
**Hybrid**: RenderingServer optional, clients fall back to entity streaming if unavailable

## Next Steps

1. **Java Architecture Design** (java-architect-planner):
   - Detailed component design (RenderingServer, RegionManager, GpuBuilder, etc.)
   - Code reuse strategy from portal (GpuService, RenderService)
   - Generic renderer pattern (like SpatialIndex<Key>)
   - Phase breakdown with dependencies

2. **Plan Validation** (plan-auditor):
   - Verify architecture soundness
   - Identify risks and gaps
   - Validate integration points with existing code

3. **Implementation Planning** (strategic-planner):
   - Task breakdown with time estimates
   - Bead creation for tracking
   - Testing strategy

## Open Questions

- Region cache strategy (how long to keep inactive regions in memory?)
- Client reconnection handling (resume from cached regions?)
- Multi-simulation support (one RenderingServer for multiple simulations?)
- GPU resource management (how to share GPU across regions?)
- Protocol versioning (compatibility between client/server versions?)

## References

- Portal: `portal/src/main/java/.../web/SpatialInspectorServer.java`
- Simulation: `simulation/src/main/java/.../viz/MultiBubbleVisualizationServer.java`
- Architecture: `simulation/doc/ARCHITECTURE_DISTRIBUTED.md`
- Lucien Docs: `lucien/doc/LUCIEN_ARCHITECTURE.md`
