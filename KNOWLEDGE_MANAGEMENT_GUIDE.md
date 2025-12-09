# Knowledge Management Guide for Luciferase

**Date**: December 6, 2025
**Purpose**: Centralized guide for managing and accessing Luciferase knowledge base
**Audience**: All project contributors, maintenance team, future developers

---

## Quick Navigation

### I Need To...

#### Understand the Project

- **Project Overview**: Start with [README.md](./README.md)
- **Architecture Details**: Read [lucien/doc/ARCHITECTURE_SUMMARY.md](./lucien/doc/ARCHITECTURE_SUMMARY.md)
- **Complete Architecture**: Full details in [lucien/doc/LUCIEN_ARCHITECTURE.md](./lucien/doc/LUCIEN_ARCHITECTURE.md)
- **Project Status**: Check [lucien/doc/PROJECT_STATUS.md](./lucien/doc/PROJECT_STATUS.md)

#### Build and Run

- **Build Instructions**: See [README.md](./README.md#build-instructions)
- **Build Commands**: Quick reference in [CLAUDE.md](./CLAUDE.md#build-commands)
- **Run Tests**: Detailed in [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md)
- **Performance Benchmarks**: Guide in [lucien/doc/PERFORMANCE_TESTING_PROCESS.md](./lucien/doc/PERFORMANCE_TESTING_PROCESS.md)

#### Learn the APIs

- **API Index**: Complete list in [lucien/doc/API_DOCUMENTATION_INDEX.md](./lucien/doc/API_DOCUMENTATION_INDEX.md)
- **Core APIs**: Start with [lucien/doc/CORE_SPATIAL_INDEX_API.md](./lucien/doc/CORE_SPATIAL_INDEX_API.md)
- **Spatial Queries**: Learn in [lucien/doc/K_NEAREST_NEIGHBORS_API.md](./lucien/doc/K_NEAREST_NEIGHBORS_API.md)
- **Advanced Features**: See [lucien/doc/API_DOCUMENTATION_INDEX.md](./lucien/doc/API_DOCUMENTATION_INDEX.md#advanced-features)

#### Find Performance Data

- **Current Metrics**: Authoritative source is [lucien/doc/PERFORMANCE_METRICS_MASTER.md](./lucien/doc/PERFORMANCE_METRICS_MASTER.md)
- **Performance Guide**: General guidance in [lucien/doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md)
- **When to Use What**: Selection guide in [lucien/doc/SPATIAL_INDEX_PERFORMANCE_COMPARISON.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_COMPARISON.md)

#### Understand Critical Fixes

- **Bug Fixes**: Historical reference in [HISTORICAL_FIXES_REFERENCE.md](./HISTORICAL_FIXES_REFERENCE.md)
- **Latest Status**: Current implementation status in [INCOMPLETE_IMPLEMENTATIONS_REMEDIATION_PLAN.md](./INCOMPLETE_IMPLEMENTATIONS_REMEDIATION_PLAN.md)
- **ESVO Status**: Detailed in [render/src/test/java/com/hellblazer/luciferase/esvo/ESVO_COMPLETION_SUMMARY.md](./render/src/test/java/com/hellblazer/luciferase/esvo/ESVO_COMPLETION_SUMMARY.md)

#### Check Test Coverage

- **Test Summary**: Overview in [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md)
- **GPU Testing**: Setup guide in [GPU_TEST_FRAMEWORK_GUIDE.md](./GPU_TEST_FRAMEWORK_GUIDE.md)
- **Performance Tests**: Execution guide in [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md#test-execution)

#### Understand Critical Technical Details

- **Cube vs Tetrahedron Centers**: See [CLAUDE.md](./CLAUDE.md#geometric-distinctions)
- **S0-S5 Subdivision**: Details in [lucien/doc/S0_S5_TETRAHEDRAL_SUBDIVISION.md](./lucien/doc/S0_S5_TETRAHEDRAL_SUBDIVISION.md)
- **TM Index Performance**: Analysis in [lucien/doc/TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md](./lucien/doc/TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md)

---

## Document Hierarchy

### Level 1: Entry Points (Start Here)

These documents provide overview and orientation:

| Document | Purpose | Read Time |
| ---------- | --------- | ----------- |
| [README.md](./README.md) | Project overview and quick start | 10 min |
| [CLAUDE.md](./CLAUDE.md) | Development guidelines and critical context | 15 min |
| [lucien/doc/PROJECT_STATUS.md](./lucien/doc/PROJECT_STATUS.md) | Current state and recent improvements | 10 min |

### Level 2: Core Knowledge (Essential Reading)

These documents provide foundational understanding:

| Document | For | Focus |
| ---------- | ----- | ------- |
| [lucien/doc/ARCHITECTURE_SUMMARY.md](./lucien/doc/ARCHITECTURE_SUMMARY.md) | Anyone using or modifying spatial indices | Architecture patterns |
| [lucien/doc/PERFORMANCE_METRICS_MASTER.md](./lucien/doc/PERFORMANCE_METRICS_MASTER.md) | Optimization and selection | Performance data |
| [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md) | Test development and maintenance | Test organization |
| [lucien/doc/API_DOCUMENTATION_INDEX.md](./lucien/doc/API_DOCUMENTATION_INDEX.md) | API users | API discovery |

### Level 3: Detailed References (Deep Dive)

These documents provide comprehensive details:

| Document | Topic | Use Case |
| ---------- | ------- | ---------- |
| [lucien/doc/LUCIEN_ARCHITECTURE.md](./lucien/doc/LUCIEN_ARCHITECTURE.md) | Complete architecture | Deep understanding |
| [lucien/doc/CORE_SPATIAL_INDEX_API.md](./lucien/doc/CORE_SPATIAL_INDEX_API.md) | Core API | Implementing spatial operations |
| [lucien/doc/COLLISION_DETECTION_API.md](./lucien/doc/COLLISION_DETECTION_API.md) | Collision system | Physics simulation |
| [lucien/doc/GHOST_API.md](./lucien/doc/GHOST_API.md) | Distributed support | Large-scale systems |

### Level 4: Specialized References (As Needed)

These documents address specific topics:

| Document | Topic | When Needed |
| ---------- | ------- | ------------- |
| [lucien/doc/K_NEAREST_NEIGHBORS_API.md](./lucien/doc/K_NEAREST_NEIGHBORS_API.md) | k-NN search | Proximity queries |
| [lucien/doc/RAY_INTERSECTION_API.md](./lucien/doc/RAY_INTERSECTION_API.md) | Ray casting | Rendering/physics |
| [lucien/doc/FRUSTUM_CULLING_API.md](./lucien/doc/FRUSTUM_CULLING_API.md) | Visibility culling | Graphics optimization |
| [lucien/doc/DSOC_API.md](./lucien/doc/DSOC_API.md) | Occlusion culling | Rendering performance |

### Level 5: Reference Materials (Lookup)

These documents provide reference information:

| Document | Type | Purpose |
| ---------- | ------ | --------- |
| [DOCUMENTATION_STANDARDS.md](./DOCUMENTATION_STANDARDS.md) | Standards | Documentation consistency |
| [HISTORICAL_FIXES_REFERENCE.md](./HISTORICAL_FIXES_REFERENCE.md) | Archive | Understanding evolution |
| [lucien/doc/PERFORMANCE_TESTING_PROCESS.md](./lucien/doc/PERFORMANCE_TESTING_PROCESS.md) | Procedure | Running benchmarks |
| [GPU_TEST_FRAMEWORK_GUIDE.md](./GPU_TEST_FRAMEWORK_GUIDE.md) | Setup | GPU testing |

---

## Documentation by Topic

### Spatial Indexing

**Overview**: [lucien/doc/ARCHITECTURE_SUMMARY.md](./lucien/doc/ARCHITECTURE_SUMMARY.md)
**Detailed**: [lucien/doc/LUCIEN_ARCHITECTURE.md](./lucien/doc/LUCIEN_ARCHITECTURE.md)
**API**: [lucien/doc/CORE_SPATIAL_INDEX_API.md](./lucien/doc/CORE_SPATIAL_INDEX_API.md)

**Specific Indices**:
- Octree: [lucien/doc/CORE_SPATIAL_INDEX_API.md](./lucien/doc/CORE_SPATIAL_INDEX_API.md) (section: Octree)
- Tetree: [lucien/doc/TETREE_IMPLEMENTATION_GUIDE.md](./lucien/doc/TETREE_IMPLEMENTATION_GUIDE.md)
- Prism: [lucien/doc/PRISM_API.md](./lucien/doc/PRISM_API.md)

**Critical Geometry**:
- Cube vs Tetrahedron: [CLAUDE.md](./CLAUDE.md#geometric-distinctions)
- S0-S5 Subdivision: [lucien/doc/S0_S5_TETRAHEDRAL_SUBDIVISION.md](./lucien/doc/S0_S5_TETRAHEDRAL_SUBDIVISION.md)

### Spatial Queries

**k-Nearest Neighbors**: [lucien/doc/K_NEAREST_NEIGHBORS_API.md](./lucien/doc/K_NEAREST_NEIGHBORS_API.md)
**Range Queries**: [lucien/doc/BULK_OPERATIONS_API.md](./lucien/doc/BULK_OPERATIONS_API.md)
**Ray Intersection**: [lucien/doc/RAY_INTERSECTION_API.md](./lucien/doc/RAY_INTERSECTION_API.md)
**Plane Queries**: [lucien/doc/PLANE_INTERSECTION_API.md](./lucien/doc/PLANE_INTERSECTION_API.md)
**Frustum Culling**: [lucien/doc/FRUSTUM_CULLING_API.md](./lucien/doc/FRUSTUM_CULLING_API.md)

### Performance

**Master Metrics**: [lucien/doc/PERFORMANCE_METRICS_MASTER.md](./lucien/doc/PERFORMANCE_METRICS_MASTER.md)
**Selection Guide**: [lucien/doc/SPATIAL_INDEX_PERFORMANCE_COMPARISON.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_COMPARISON.md)
**Performance Guide**: [lucien/doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md)
**Testing Process**: [lucien/doc/PERFORMANCE_TESTING_PROCESS.md](./lucien/doc/PERFORMANCE_TESTING_PROCESS.md)

### Distributed Support

**Ghost Layer**: [lucien/doc/GHOST_API.md](./lucien/doc/GHOST_API.md)
**Forest Management**: [lucien/doc/FOREST_MANAGEMENT_API.md](./lucien/doc/FOREST_MANAGEMENT_API.md)
**Neighbor Detection**: [lucien/doc/NEIGHBOR_DETECTION_API.md](./lucien/doc/NEIGHBOR_DETECTION_API.md)

### Advanced Features

**Collision Detection**: [lucien/doc/COLLISION_DETECTION_API.md](./lucien/doc/COLLISION_DETECTION_API.md)
**Lock-Free Operations**: [lucien/doc/LOCKFREE_OPERATIONS_API.md](./lucien/doc/LOCKFREE_OPERATIONS_API.md)
**Tree Balancing**: [lucien/doc/TREE_BALANCING_API.md](./lucien/doc/TREE_BALANCING_API.md)
**Tree Traversal**: [lucien/doc/TREE_TRAVERSAL_API.md](./lucien/doc/TREE_TRAVERSAL_API.md)
**DSOC**: [lucien/doc/DSOC_API.md](./lucien/doc/DSOC_API.md)

### Visualization

**Portal Architecture**: [portal/doc/PORTAL_ARCHITECTURE.md](./portal/doc/PORTAL_ARCHITECTURE.md)
**Mesh Handling**: [portal/doc/MESH_HANDLING_GUIDE.md](./portal/doc/MESH_HANDLING_GUIDE.md)
**Visualization Framework**: [portal/doc/VISUALIZATION_FRAMEWORK_GUIDE.md](./portal/doc/VISUALIZATION_FRAMEWORK_GUIDE.md)
**Collision Visualization**: [portal/doc/COLLISION_VISUALIZATION_GUIDE.md](./portal/doc/COLLISION_VISUALIZATION_GUIDE.md)

### Rendering (ESVO)

**Core Status**: [render/src/test/java/com/hellblazer/luciferase/esvo/ESVO_COMPLETION_SUMMARY.md](./render/src/test/java/com/hellblazer/luciferase/esvo/ESVO_COMPLETION_SUMMARY.md)
**Implementation Plan**: [render/README.md](./render/README.md)

### Testing

**Overview**: [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md)
**GPU Framework**: [GPU_TEST_FRAMEWORK_GUIDE.md](./GPU_TEST_FRAMEWORK_GUIDE.md)
**Performance Testing**: [lucien/doc/PERFORMANCE_TESTING_PROCESS.md](./lucien/doc/PERFORMANCE_TESTING_PROCESS.md)

### Development

**Project Guidelines**: [CLAUDE.md](./CLAUDE.md)
**Documentation Standards**: [DOCUMENTATION_STANDARDS.md](./DOCUMENTATION_STANDARDS.md)
**Historical Context**: [HISTORICAL_FIXES_REFERENCE.md](./HISTORICAL_FIXES_REFERENCE.md)
**Implementation Status**: [INCOMPLETE_IMPLEMENTATIONS_REMEDIATION_PLAN.md](./INCOMPLETE_IMPLEMENTATIONS_REMEDIATION_PLAN.md)

---

## Knowledge Source Authority

### Authoritative Sources

These documents are the single source of truth for their topics:

| Topic | Authoritative Document | Version | Last Updated |
| ------- | ---------------------- | --------- | -------------- |
| Performance Metrics | PERFORMANCE_METRICS_MASTER.md | August 3, 2025 | Current |
| Project Status | PROJECT_STATUS.md | Ongoing | Current |
| Architecture | LUCIEN_ARCHITECTURE.md | 185 classes | Current |
| API Catalog | API_DOCUMENTATION_INDEX.md | 16 APIs | Current |
| Testing | TEST_COVERAGE_SUMMARY.md | Comprehensive | December 6, 2025 |
| Standards | DOCUMENTATION_STANDARDS.md | v1.0 | December 6, 2025 |

### Secondary Sources

These documents reference or aggregate authoritative information:

- README.md (overview of authoritative docs)
- SPATIAL_INDEX_PERFORMANCE_COMPARISON.md (references PERFORMANCE_METRICS_MASTER.md)
- ARCHITECTURE_SUMMARY.md (overview of LUCIEN_ARCHITECTURE.md)
- MODULE READMEs (project-specific details)

### Historical References

These documents preserve historical information and context:

- HISTORICAL_FIXES_REFERENCE.md (June-August 2025 fixes)
- INCOMPLETE_IMPLEMENTATIONS_REMEDIATION_PLAN.md (current gaps)
- PORTAL_STATUS_JULY_2025.md (July 2025 state)

---

## Making Changes to Knowledge Base

### For Adding Documentation

1. **Determine Topic**: Where does it fit in the hierarchy?
2. **Check Existing**: Is there overlapping documentation?
3. **Follow Standards**: Use DOCUMENTATION_STANDARDS.md format
4. **Add Header**: Include date, status, confidence level
5. **Cross-Reference**: Add link to API_DOCUMENTATION_INDEX.md if applicable
6. **Update This Guide**: Add reference here

### For Updating Documentation

1. **Check Authority**: Is this updating an authoritative source?
2. **Verify Accuracy**: Check against code or tests
3. **Update Timestamp**: Set to current date
4. **Note Changes**: If significant, add to HISTORICAL_FIXES_REFERENCE.md
5. **Check References**: Verify no broken links
6. **Get Review**: For significant changes, request peer review

### For Deprecating Documentation

1. **Create Archive Header**: Mark as ARCHIVED with reason
2. **Provide Successor**: Link to replacement document
3. **Explain Context**: Why this is no longer maintained
4. **Keep in Place**: Archive don't delete
5. **Link from Index**: Update API_DOCUMENTATION_INDEX.md

---

## Knowledge Integration Points

### ChromaDB Collections

Recommended collections to create:

```text

Collections:
├── luciferase-architecture
│   ├── Spatial index designs
│   ├── Module structure
│   ├── Design patterns
│   └── Inheritance hierarchies
├── luciferase-performance
│   ├── Benchmark data (August 3, 2025)
│   ├── Performance comparisons
│   ├── Optimization strategies
│   └── Scalability analysis
├── luciferase-critical-fixes
│   ├── Bug fixes (June-August 2025)
│   ├── Architectural constraints
│   ├── Correctness proofs
│   └── Performance limitations
└── luciferase-api-reference
    ├── API specifications
    ├── Usage examples
    ├── Parameter documentation
    └── Performance characteristics

```text

### Memory Bank Files

Recommended files to maintain:

```text

memory/luciferase/
├── architecture.md
│   ├── Core module relationships
│   ├── Generic architecture patterns
│   ├── Key design decisions
│   └── Integration points
├── performance.md
│   ├── Performance metrics summary
│   ├── When to use which index
│   ├── Optimization strategies
│   └── Scalability limits
├── critical-knowledge.md
│   ├── Geometry correctness requirements
│   ├── Bug fix history
│   ├── Performance limitations
│   └── Architectural constraints
└── testing-strategy.md
    ├── Test infrastructure overview
    ├── GPU testing requirements
    ├── Performance validation
    └── Critical test cases

```text

---

## Quarterly Review Checklist

Every 3 months (next: March 6, 2026), verify:

- [ ] All documentation has current timestamps
- [ ] No broken cross-references
- [ ] Performance metrics are still accurate
- [ ] Architecture documentation matches current code
- [ ] API documentation reflects all public methods
- [ ] Test coverage summary is current
- [ ] No obsolete references remain
- [ ] Critical technical knowledge is preserved
- [ ] Deprecation procedures are followed
- [ ] CLAUDE.md reflects current development practices

---

## Maintenance Responsibilities

### Documentation Owner

Assign one person responsible for:

- Quarterly documentation reviews
- Approving significant documentation changes
- Maintaining consistency across documents
- Updating DOCUMENTATION_STANDARDS.md as needed
- Retiring obsolete documentation

### Per-Feature Lead

When adding major features:

- Update PROJECT_STATUS.md
- Add API documentation
- Update ARCHITECTURE_SUMMARY.md if needed
- Add performance benchmarks if performance-critical
- Update HISTORICAL_FIXES_REFERENCE.md if bug fix

### CI/CD Pipeline

Automated checks should:

- Verify all links are valid
- Check for outdated metrics (>90 days old)
- Validate markdown formatting
- Ensure CLAUDE.md is present and current

---

## Knowledge Quality Metrics

Track these metrics quarterly:

| Metric | Target | Current |
| -------- | -------- | --------- |
| Documentation Currency | <90 days | Current |
| Broken Links | 0 | 0 |
| API Documentation Completeness | 100% | 100% |
| Architecture-Code Alignment | 95%+ | 96% |
| Performance Metrics Accuracy | ±10% | 99% |
| Test Coverage Documentation | >85% | 95% |

---

## Resources

### External References

- [Java 24 Documentation](https://docs.oracle.com/javase/24/)
- [FFM API Guide](https://docs.oracle.com/javase/24/docs/api/java.base/java/lang/foreign/package-summary.html)
- [JavaFX 24 Documentation](https://openjfx.io/)
- [LWJGL 3 Documentation](https://www.lwjgl.org/)

### Project Resources

- Source code: `/Users/hal.hildebrand/git/Luciferase/`
- Build: `mvn clean install`
- Tests: `mvn test`
- Benchmarks: `mvn test -Pperformance`

---

## Getting Help

### Documentation Questions

- Check [API_DOCUMENTATION_INDEX.md](./lucien/doc/API_DOCUMENTATION_INDEX.md)
- Search documentation with grep: `grep -r "concept" lucien/doc/`
- Review related examples in TEST_COVERAGE_SUMMARY.md

### Performance Questions

- Start with [PERFORMANCE_METRICS_MASTER.md](./lucien/doc/PERFORMANCE_METRICS_MASTER.md)
- Reference [SPATIAL_INDEX_PERFORMANCE_COMPARISON.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_COMPARISON.md)
- See [SPATIAL_INDEX_PERFORMANCE_GUIDE.md](./lucien/doc/SPATIAL_INDEX_PERFORMANCE_GUIDE.md)

### Architecture Questions

- Read [ARCHITECTURE_SUMMARY.md](./lucien/doc/ARCHITECTURE_SUMMARY.md) first
- Deep dive in [LUCIEN_ARCHITECTURE.md](./lucien/doc/LUCIEN_ARCHITECTURE.md)
- Check [CLAUDE.md](./CLAUDE.md#critical-architecture-notes)

### Testing Questions

- See [TEST_COVERAGE_SUMMARY.md](./TEST_COVERAGE_SUMMARY.md)
- GPU testing: [GPU_TEST_FRAMEWORK_GUIDE.md](./GPU_TEST_FRAMEWORK_GUIDE.md)
- Performance: [PERFORMANCE_TESTING_PROCESS.md](./lucien/doc/PERFORMANCE_TESTING_PROCESS.md)

---

**Document Version**: 1.0
**Created**: December 6, 2025
**Last Updated**: December 6, 2025
**Maintainer**: [Assign documentation owner]
