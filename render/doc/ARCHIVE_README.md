# Archive Documentation

**Purpose**: Historical planning artifacts, audit reports, and superseded documentation
**Archive Date**: 2026-02-08
**Total Files**: 13 documents (~4,500 lines)

---

## What's Archived Here

This directory contains **historical documentation** that was active during development but has been superseded by completion reports or later versions. These files are preserved for:

1. **Historical context**: Understanding decision-making process
2. **Audit trails**: Tracking plan → audit → revision → results workflow
3. **Reference**: Looking up specific implementation details from planning phase
4. **Learning**: Seeing how complex features evolved through planning

---

## When to Reference Archived Docs

### ✅ Read Archived Docs When:
- Researching why a specific design decision was made
- Understanding the evolution of a feature through planning phases
- Tracing audit findings and how they were addressed
- Looking for detailed implementation specifications from planning stage
- Investigating historical performance targets or trade-offs

### ❌ Don't Read Archived Docs When:
- **Learning current API** → Use current documentation in `doc/`
- **Integrating with code** → Use current guides (DAG_INTEGRATION_GUIDE, PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE)
- **Understanding what's implemented** → Use completion summaries (PHASE_2_COMPLETION_SUMMARY, PHASE_5A5_RESULTS)
- **Looking for current status** → Check `doc/INDEX.md` or phase completion reports

**Rule of Thumb**: If you need to know **what was done**, read current docs. If you need to know **how decisions were made**, read archived docs.

---

## Archive Structure

### phase2/

**Phase 2: DAG Compression Implementation Details**

| File | Lines | Purpose | Superseded By |
|------|-------|---------|---------------|
| PHASE2_DAGBUILDER_IMPLEMENTATION_SUMMARY.md | 365 | DAGBuilder implementation details | PHASE_2_COMPLETION_SUMMARY.md |

**When to Reference**: Detailed DAGBuilder algorithm analysis, specific test coverage breakdown

---

### phase5a5/

**Phase 5A5: Integration Testing Planning Artifacts**

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| PHASE_5A5_IMPLEMENTATION_PLAN.md | 559 | Original implementation plan | Superseded by revised plan |
| PHASE_5A5_PLAN_AUDIT_REPORT.md | 282 | Plan audit findings | Addressed in revised plan |
| PHASE_5A5_IMPLEMENTATION_PLAN_REVISED.md | 885 | Revised plan after audit | Superseded by results |

**Final Status**: See `PHASE_5A5_RESULTS.md` (current documentation)

**When to Reference**:
- Understanding plan-auditor workflow
- Seeing how audit findings were addressed
- Tracing node reduction measurement methodology evolution
- Learning from the plan → audit → revision → implementation cycle

---

### gpu-testing/

**Multi-Vendor GPU Testing Evolution**

| File | Lines | Purpose | Superseded By |
|------|-------|---------|---------------|
| STREAM_D_MULTI_VENDOR_GPU_TESTING.md | 713 | Stream D planning document | MULTI_VENDOR_GPU_TESTING_GUIDE.md |
| VENDOR_TESTING_GUIDE.md | 245 | Phase 3.1 implementation guide | MULTI_VENDOR_GPU_TESTING_GUIDE.md |

**Final Status**: See `MULTI_VENDOR_GPU_TESTING_GUIDE.md` (Phase 5 P3, current)

**When to Reference**:
- Understanding vendor-specific workaround evolution
- Tracing 3-tier test strategy development
- Comparing planned vs actual multi-vendor testing approach

---

### plans/

**Completed Implementation Plans**

| File | Lines | Phase | Status |
|------|-------|-------|--------|
| PHASE_5a4_LIVE_METRICS_REVISED_PLAN.md | 1018 | Phase 5a.4 | Implementation complete |
| STREAM_A_GPU_MEMORY_OPTIMIZATION_PLAN.md | 482 | Stream A | Implementation complete |
| WORKGROUP_TUNING_PLAN.md | 538 | GPU tuning | Implementation complete |
| F3_1_3_BEAM_OPTIMIZATION_PLAN.md | 537 | F3.1.3 | Implementation complete |

**Final Status**: All features implemented and integrated into Phase 5 documentation

**When to Reference**:
- Detailed stream A/B/C/D implementation specifications
- Original performance targets and trade-off analysis
- Workgroup tuning strategy details
- Beam optimization algorithm planning

---

## Current vs Archived: Quick Comparison

| Topic | Archived Document(s) | Current Document | Difference |
|-------|---------------------|------------------|------------|
| Phase 2 DAG | PHASE2_DAGBUILDER_IMPLEMENTATION_SUMMARY.md | PHASE_2_COMPLETION_SUMMARY.md | Implementation details → Executive summary |
| Phase 5A5 | 3 planning docs | PHASE_5A5_RESULTS.md | Planning artifacts → Results |
| Multi-Vendor GPU | 2 earlier versions | MULTI_VENDOR_GPU_TESTING_GUIDE.md | Planning/interim → Final comprehensive guide |
| GPU Plans | 4 implementation plans | PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE.md | Individual plans → Unified guide |

---

## Migration Path: Finding Current Equivalents

If you're looking at an archived document, here's where to find current information:

### From Phase 2 Archive
- **PHASE2_DAGBUILDER_IMPLEMENTATION_SUMMARY.md** → `../PHASE_2_COMPLETION_SUMMARY.md`
- For API details → `../DAG_API_REFERENCE.md`
- For integration → `../DAG_INTEGRATION_GUIDE.md`

### From Phase 5A5 Archive
- **All planning artifacts** → `../PHASE_5A5_RESULTS.md`
- For test methodology → See PHASE_5A5_RESULTS.md §Test Scenarios

### From GPU Testing Archive
- **STREAM_D_MULTI_VENDOR_GPU_TESTING.md** → `../MULTI_VENDOR_GPU_TESTING_GUIDE.md`
- **VENDOR_TESTING_GUIDE.md** → `../MULTI_VENDOR_GPU_TESTING_GUIDE.md`

### From Plans Archive
- **All GPU plans** → `../PHASE_5_GPU_ACCELERATION_COMPLETE_GUIDE.md`
- For technical details → `../PHASE_5_TECHNICAL_REFERENCE.md`
- For P1-P4 specifics → See component guides in `../`

---

## Document Status in Archive

All archived documents have one of these statuses:

| Status | Meaning | Action |
|--------|---------|--------|
| **SUPERSEDED** | Replaced by newer/better document | Use current equivalent |
| **PLANNING** | Pre-implementation planning artifact | Reference only for historical context |
| **INTERIM** | Intermediate version during development | Use final version in current docs |
| **COMPLETE** | Implementation finished but plan archived | See completion report in current docs |

---

## Archive Maintenance

### What Gets Archived
- ✅ Original implementation plans (after completion)
- ✅ Plan audit reports (after implementation)
- ✅ Revised plans (after implementation)
- ✅ Interim/draft documentation (when final version exists)
- ✅ Duplicate topic coverage (keep most complete/current)

### What Stays Current
- ✅ Completion summaries/results
- ✅ API references
- ✅ Integration guides
- ✅ Current technical references
- ✅ Master indexes

### Retention Policy
- **Forever**: All archived documents preserved in git history
- **Accessibility**: Kept in archive/ for easy reference without cluttering current docs
- **No deletion**: Historical artifacts never deleted, only moved to archive

---

## FAQ

**Q: Should I read archived plans before implementing similar features?**
A: Yes! Archived plans contain valuable design rationale, trade-off analysis, and lessons learned.

**Q: Are archived documents tested/validated?**
A: They reflect the state at time of writing. For current API/behavior, always use current documentation.

**Q: Can I reference archived docs in new documentation?**
A: Yes, but clearly mark them as historical. Prefer referencing current docs when possible.

**Q: What if archived doc contradicts current doc?**
A: Current documentation is authoritative. Archived docs show historical thinking but may be outdated.

**Q: How do I find the current equivalent of an archived doc?**
A: Check the "Superseded By" column in tables above, or see `../INDEX.md` master index.

---

## Contributing

When archiving new documents:

1. **Create subdirectory** in `archive/` for the topic/phase
2. **Move files** with git mv (preserves history)
3. **Update this README** with new entries
4. **Update current INDEX.md** to remove archived items
5. **Verify cross-references** still work or update them

---

**Archive Curator**: Render module maintainers
**Last Updated**: 2026-02-08
**Total Archived**: 13 files, 4,500+ lines
**Accessibility**: All files in git history and archive/ directory
