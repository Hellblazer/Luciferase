# Design Review Process

**Purpose**: Prevent architectural thrashing and wasted effort through mandatory design review for significant changes.

**Example**: The Raft→Fireflies rewrite discarded 75 tests and months of work due to lack of architectural review.

---

## When Design Review is Required

### 1. Phase X Day 1 Work

Any significant new phase/epic implementation requires:

- [ ] plan-auditor agent approval before implementation
- [ ] Architectural spike or proof-of-concept completed
- [ ] Integration points validated with existing architecture
- [ ] ADR created if introducing new patterns

### 2. New Abstractions

For code adding >100 LOC or introducing new design patterns:

- [ ] ADR documenting decision (Architecture Decision Record)
- [ ] Alternatives analysis showing why this approach chosen
- [ ] Rollback/migration plan if abstraction proves wrong

### 3. Major Refactoring

For changes affecting >5 files or >500 LOC:

- [ ] plan-auditor validation of refactoring strategy
- [ ] Test coverage verification (no regression)
- [ ] Incremental commit plan (avoid "big bang" PRs)

---

## Design Review Checklist

Before starting implementation, answer these questions:

### Scope Assessment

- [ ] **Does this add >100 lines of code?**
  - If YES: What will be deleted to maintain 2:1 deletion ratio?
  - Deletion plan: [describe specific files/classes to remove]

- [ ] **Does this introduce new abstraction** (interface, abstract class, generic type)?
  - If YES: Why not use existing pattern?
  - Justification: [explain why existing patterns insufficient]

- [ ] **Does this change public API or module boundaries?**
  - If YES: What's the migration path for existing callers?
  - Migration plan: [describe compatibility strategy]

### Architectural Validation

- [ ] **plan-auditor agent review completed?**
  - Review session ID: [link to agent output]
  - Approval status: [Approved/Needs revision]

- [ ] **ADR created for new architectural decision?**
  - ADR number: [e.g., ADR-0004]
  - Location: `.pm/decisions/adr-NNNN-{title}.md`

- [ ] **Integration points validated?**
  - List affected components: [enumerate]
  - Breaking changes documented: [yes/no]

### Risk Assessment

- [ ] **Rollback plan defined?**
  - How to revert if this fails: [describe]

- [ ] **Performance impact measured?**
  - Benchmark results: [before/after metrics]

- [ ] **Test coverage maintained or improved?**
  - Coverage before: [%]
  - Coverage after: [%]

---

## Process Workflow

### 1. Before Implementation

```bash
# 1. Create design review document from template
cp .pm/templates/design-review-template.md .pm/reviews/review-YYYYMMDD-{feature}.md

# 2. Fill out checklist in review document
# 3. Run plan-auditor agent for validation
# 4. Address feedback, update review document
# 5. Get approval, proceed with implementation
```

### 2. During Implementation

- Reference design review document in commit messages
- Update review document if scope changes
- Re-validate with plan-auditor if assumptions change

### 3. After Implementation

- Update ADR to "Accepted" status
- Document actual vs planned changes in review document
- Archive review to `.pm/reviews/archive/`

---

## Examples of Work Requiring Design Review

### ✅ Requires Review

- Implementing new Ghost Layer consensus protocol
- Refactoring god classes (e.g., MultiBubbleSimulation B1 decomposition)
- Consolidating migration coordinator responsibilities
- Introducing new spatial index algorithm
- Major architectural changes affecting multiple modules

### ❌ Does Not Require Review

- Bug fixes maintaining existing behavior
- Test additions/fixes
- Documentation updates
- Refactoring <100 LOC within single class
- Deleting dead code

---

## Enforcement

- **Pre-push hook**: Reminds about design review for large PRs (>5 files or >300 LOC)
- **Code review**: Rejects PRs without design review link for Phase X Day 1 work
- **Agent access**: plan-auditor available via `/plan-audit` command or Task tool

---

## Templates and Examples

### Design Review Template

Location: `.pm/templates/design-review-template.md`

Contains comprehensive checklist including:
- Scope assessment (LOC, abstractions, API changes)
- Architectural validation (plan-auditor approval, ADR)
- Risk assessment (rollback, performance, coverage)
- Implementation plan (phases, tasks, tests)
- Success criteria and approval tracking

### Example Review

See: `.pm/reviews/review-20260111-multibubble-decomposition.md`

Example of completed design review for Sprint B B1 (MultiBubbleSimulation god class decomposition):
- Shows proper scope assessment
- Demonstrates plan-auditor approval
- Documents 6-phase implementation plan
- Tracks actual vs planned outcomes
- Records lessons learned

---

## ADR (Architecture Decision Record) Template

When design decisions require documentation:

**Location**: `.pm/decisions/adr-NNNN-{title}.md`

**Structure**:
```markdown
# ADR-NNNN: {Title}

## Status: {Proposed|Accepted|Deprecated}

## Context
[Situation that triggered decision]

## Decision
[What we decided and why]

## Rationale
[Why this over alternatives]

## Consequences
[Benefits and drawbacks]

## Alternatives Considered
- [Option A]: Why rejected
- [Option B]: Why rejected

## Related Decisions
- ADR-XXXX (related)
- ADR-YYYY (overrides)
```

---

## Questions?

- Consult `.pm/METHODOLOGY.md` for complete engineering discipline
- Run `/plan-audit` to invoke plan-auditor agent
- Reference example review for guidance
- Escalate to technical lead if unsure whether review is needed

**Default Rule**: When in doubt, get design review. Better to over-communicate than throw away work.
