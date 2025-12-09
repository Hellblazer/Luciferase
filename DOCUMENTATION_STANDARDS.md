# Documentation Standards for Luciferase

**Effective Date**: December 6, 2025
**Applies To**: All markdown documentation and inline code documentation

---

## Purpose

These standards ensure consistency, accuracy, and maintainability of all knowledge documentation in the Luciferase project. They provide guidelines for creating, updating, and retiring documentation.

---

## Document Header Requirements

All primary documentation files (not included: code comments, inline documentation) must include a header with the following information:

### Minimal Header

```markdown

# Document Title

**Last Updated**: YYYY-MM-DD
**Status**: Current | Archived | Draft

```text

### Recommended Header (for significant documents)

```markdown

# Document Title

**Last Updated**: December 6, 2025
**Status**: Current (Production Ready)
**Author/Owner**: [Optional: Name or role]
**Applies To**: [Module names or components]
**Related Documents**: [List of cross-references]
**Confidence Level**: [95%+ recommended, less for in-progress items]

```text

### Archive Header (for deprecated documents)

```markdown

# Document Title [ARCHIVED]

**Deprecated**: December 1, 2025
**Reason**: [Reason for archiving]
**Successor**: [Link to replacement document or none]
**Last Updated**: [Date when deprecated]
**Historical Context**: [Brief explanation of what this document covers and why it's historical]

```text

---

## Content Standards

### 1. Accuracy Requirements

- **Verifiable Facts**: All factual claims must be verifiable (either by code inspection or recent testing)
- **Performance Data**: Must include date of measurement and test environment
- **Technical Specifications**: Must reference code location where implemented
- **Examples**: Code examples must compile and work with current implementation

### 2. Cross-Reference Standards

- **Link Format**: Use relative paths for internal documents: `[API Guide](./lucien/doc/CORE_SPATIAL_INDEX_API.md)`
- **External Links**: Use absolute URLs with full path
- **Broken References**: Document review process must catch broken links
- **Mandatory Cross-References**:
  - Architecture docs must reference implementation modules
  - API docs must link to usage examples
  - Performance claims must link to PERFORMANCE_METRICS_MASTER.md

### 3. Terminology Standards

Use these standard terms consistently across all documentation:

| Concept | Standard Term | Avoid |
| --------- | --------------- | ------- |
| Distributed spatial indexing support | Distributed support / Ghost layer | Remote trees, distributed trees |
| Multi-tree management | Forest management | Tree forest, forest coordination |
| Tree depth optimization | Tree balancing | Rebalancing, reorganization |
| Spatial location | Node | Cell, bin, partition |
| Data entity in index | Entity | Object, item, element (except ghost elements) |
| Index subdivision level | Level | Depth, tier, layer |
| Geometry division types | Octree, Tetree, Prism | Cubic, tetrahedral, prismatic (as adjectives only) |

### 4. Performance Claims Standards

All performance-related statements must include:

- **Measurement Date**: When the benchmark was run
- **Environment**: Platform, JVM version, CPU details
- **Scale**: Entity count tested
- **Conditions**: What operation(s) were measured
- **Source Document**: Reference to PERFORMANCE_METRICS_MASTER.md

**Example Format**:

```text

Tetree performs 5.7x faster than Octree for insertions with 1,000 entities
(OctreeVsTetreeVsPrismBenchmark, August 3, 2025, Mac OS X aarch64, Java 24)
See PERFORMANCE_METRICS_MASTER.md for complete results.

```text

### 5. Code Example Standards

All code examples must:

- **Be Syntactically Correct**: Must compile with current Java/module versions
- **Be Runnable**: Examples should be executable (even if simplified)
- **Include Imports**: Show necessary import statements
- **Be Minimal**: Show only essential code, not boilerplate
- **Be Commented**: Explain non-obvious parts

**Poor Example**:

```java

octree.insert(id, pos);

```text

**Good Example**:

```java

// Insert an entity at a specific position
var octree = new Octree<>(bounds, maxLevel);
var entityId = new LongEntityID(42L);
var position = new Point3f(10, 20, 30);
octree.insert(entityId, position);

```text

---

## Update Process

### When to Update Documentation

**Immediately** (within 1 commit):
- Bug fixes that change documented behavior
- Security-related changes
- Critical API changes

**Within 1 sprint** (2 weeks):
- Feature additions with new APIs
- Performance improvements (>10% difference)
- Architecture changes

**Quarterly** (every 3 months):
- Review all documentation for accuracy
- Update metrics if assumptions changed
- Fix broken cross-references

### Update Checklist for Major Features

When merging a major feature, update these documents in order:

```markdown

## Documentation Update Checklist

When merging a feature branch with significant changes:

- [ ] Update PROJECT_STATUS.md with new features/completion status
- [ ] Update module README.md with feature description
- [ ] Update relevant API documentation (if new APIs)
- [ ] Run performance benchmarks and update PERFORMANCE_METRICS_MASTER.md (if affected)
- [ ] Update ARCHITECTURE_SUMMARY.md (if structural changes)
- [ ] Add entry to HISTORICAL_FIXES_REFERENCE.md (if bug fix)
- [ ] Add timestamp to all modified documents
- [ ] Review all cross-references for correctness
- [ ] Request review from documentation owner

```text

---

## Deprecation and Archiving

### Deprecation Process

When a document becomes obsolete:

1. **Mark as Deprecated**: Update header to ARCHIVED status
2. **Explain Reason**: Clear statement of why deprecated
3. **Provide Alternative**: Link to successor document
4. **Keep in Place**: Archive don't delete (preserves history)
5. **Link from Index**: Keep link in API_DOCUMENTATION_INDEX.md with deprecation note

### Deprecation Header Template

```markdown

# Document Title [ARCHIVED]

**Status**: ARCHIVED
**Archived Date**: December 1, 2025
**Deprecation Reason**: [Reason why this is no longer current]
**Successor Document**: [Link to replacement]
**Last Updated**: [Original date]
**Availability**: Preserved for historical reference only

---

## Historical Context

[Explanation of what this document covered and why it's no longer maintained]

[Content of original document...]

```text

---

## Critical Technical Documentation

The following documents contain critical technical information that MUST NOT be changed without careful review:

### 1. Geometry Calculations

- **Document**: CLAUDE.md (lines 141-149)
- **Critical Claim**: Cube center vs tetrahedron centroid calculations differ fundamentally
- **Never Change**: These calculations are based on mathematical correctness, not preference
- **Verification**: TetS0S5SubdivisionTest validates implementation

### 2. S0-S5 Tetrahedral Subdivision

- **Document**: S0_S5_TETRAHEDRAL_SUBDIVISION.md, CLAUDE.md (lines 151-158)
- **Critical Claim**: 6 tetrahedra tile a cube perfectly with no gaps/overlaps
- **Never Change**: This is proven by mathematical proof documented in TetS0S5SubdivisionTest
- **Verification**: 100% containment rate verified in unit tests

### 3. TET SFC Level Encoding

- **Document**: CLAUDE.md (lines 160-165), TM_INDEX_LIMITATIONS_AND_SOLUTIONS.md
- **Critical Claim**: tmIndex() is O(level) and cannot be optimized further
- **Never Change**: This is a fundamental architectural constraint required for global uniqueness
- **Verification**: Documented in performance analysis and confirmed by benchmarks

### 4. Ghost Layer Implementation

- **Document**: GHOST_API.md, PERFORMANCE_METRICS_MASTER.md (lines 97-108)
- **Critical Claim**: Ghost layer performance exceeds all targets (99% better than 2x baseline)
- **Never Change**: Verified by GhostPerformanceBenchmark
- **Verification**: All integration tests passing (7/7)

---

## Review Process

### Documentation Review Checklist

Before submitting documentation changes:

- [ ] Header information is complete and accurate
- [ ] All claims are verifiable (either by code or benchmark)
- [ ] All cross-references exist and are correct
- [ ] Examples compile and run (or are clearly marked as pseudo-code)
- [ ] Terminology uses standard terms from this document
- [ ] Performance data includes measurement context
- [ ] No dead links or references to non-existent files
- [ ] Changes are reflected in API_DOCUMENTATION_INDEX.md if applicable
- [ ] Date stamp is current
- [ ] Confidence level specified (if applicable)

### Peer Review Requirements

Documentation changes affecting:

- **Architecture**: Requires 1+ peer review
- **Performance Claims**: Requires verification against benchmarks
- **API Changes**: Requires API owner approval
- **Breaking Changes**: Requires project lead approval

---

## Conflict Resolution

When documentation conflicts with code, resolution priority is:

1. **Actual Code Behavior**: Current implementation is source of truth
2. **Benchmark Results**: Performance claims verified by recent benchmarks
3. **Architecture Documents**: Describes intended design
4. **Historical References**: Explains how we got here

**Resolution Process**:
1. Identify conflict
2. Check actual code behavior
3. Update documentation to match reality
4. If code is wrong, fix code AND documentation
5. Document in HISTORICAL_FIXES_REFERENCE.md if significant

---

## Special Considerations

### Performance Documentation

Performance metrics have special rules:

- **Location**: All metrics must be in PERFORMANCE_METRICS_MASTER.md
- **Other Docs**: Can reference but not duplicate metrics
- **Update Frequency**: Quarterly minimum, or after major changes
- **Verification**: Must include benchmark name, date, environment

### API Documentation

API documentation rules:

- **Completeness**: Every public API must be documented
- **Examples**: All APIs should include usage examples
- **Links**: Each API doc must appear in API_DOCUMENTATION_INDEX.md
- **Consistency**: Naming and organization must follow established patterns

### Historical Documentation

Historical documents have special status:

- **Preserve Exactly**: Never modify historical documentation
- **Archive Clearly**: Mark with ARCHIVED header
- **Explain Context**: Clarify why kept and what it describes
- **Link Sparingly**: Only cross-reference when necessary for understanding

---

## Tools and Automation

### Recommended Tools

- **Markdown Linting**: Use markdownlint for consistency
- **Link Checking**: Use markdown-link-check to verify cross-references
- **Spell Checking**: Use aspell or equivalent
- **Version Control**: Keep documentation in git with clear commit messages

### Automation Scripts

Consider implementing:

```bash

# Check all markdown files

markdownlint lucien/doc/*.md portal/doc/*.md

# Verify all links

markdown-link-check lucien/doc/**/*.md

# Check for outdated metrics (older than 90 days)

grep -r "Last Updated.*[0-9][0-9][0-9][0-9]-[01][0-9]-" lucien/doc/ | \
  grep -v "$(date -d '90 days ago' +'%Y-%m')"

```text

---

## Governance

### Documentation Owner

Assign a documentation owner who:

- Reviews all documentation changes
- Maintains consistency across documents
- Performs quarterly documentation audits
- Approves new API documentation

### Review Schedule

- **Quarterly**: Full documentation audit (every 3 months)
- **Per-Feature**: Update checklist review before merge
- **Annual**: Comprehensive restructuring review (once per year)

**Next Scheduled Review**: March 6, 2026

---

## Examples

### Example: Good Architecture Document

File: `/Users/hal.hildebrand/git/Luciferase/lucien/doc/LUCIEN_ARCHITECTURE.md`

**What it does right**:
- Clear header with purpose
- Well-organized package structure
- Proper cross-references
- Links to related documents
- Code examples where helpful

### Example: Good API Document

File: `/Users/hal.hildebrand/git/Luciferase/lucien/doc/CORE_SPATIAL_INDEX_API.md`

**What it does right**:
- Clear purpose statement
- Usage examples
- Parameter documentation
- Performance characteristics
- Links to related APIs

### Example: Good Performance Documentation

File: `/Users/hal.hildebrand/git/Luciferase/lucien/doc/PERFORMANCE_METRICS_MASTER.md`

**What it does right**:
- Clear measurement date
- Environment specifications
- Benchmark name
- Table format for easy comparison
- Context for interpreting results

---

## Questions and Updates

For questions about documentation standards, contact the documentation owner or create an issue in the project repository.

To update these standards, follow the same documentation process: create a pull request with proposed changes and request review from project maintainers.

---

**Document Version**: 1.0
**Effective Date**: December 6, 2025
**Last Reviewed**: December 6, 2025
