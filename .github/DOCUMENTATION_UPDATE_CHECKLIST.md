# Documentation Update Checklist

**Purpose**: Ensure all documentation stays current when merging significant code changes.

**When to Use**: Before merging any feature branch or pull request with significant changes.

---

## Quick Reference

Use this checklist when:
- ✅ Adding new features or APIs
- ✅ Changing existing APIs or behavior
- ✅ Fixing significant bugs
- ✅ Improving performance (>10% change)
- ✅ Refactoring architecture
- ❌ Trivial bug fixes with no behavioral change
- ❌ Code formatting or comment-only changes

---

## Pre-Merge Documentation Checklist

### 1. Module-Level Documentation

- [ ] **Update module README.md**
  - Add feature description if new functionality
  - Update usage examples if API changed
  - Add any new dependencies or requirements
  - Update build/test instructions if changed

- [ ] **Update CLAUDE.md (project-level)**
  - Add new module if created
  - Update architecture overview if structure changed
  - Add critical warnings if new gotchas introduced
  - Update quick commands if new build steps needed

### 2. Architecture Documentation

- [ ] **Review ARCHITECTURE_SUMMARY.md**
  - Update if package structure changed
  - Add new packages or significant classes
  - Update class counts if changed
  - Verify module relationships diagram remains accurate

- [ ] **Review LUCIEN_ARCHITECTURE.md** (if lucien module affected)
  - Update package descriptions
  - Add new classes to appropriate sections
  - Update class counts
  - Verify architectural patterns remain accurate

### 3. API Documentation

- [ ] **Create/Update API Documentation**
  - Create new API doc file if new public API added
  - Update existing API docs if signatures changed
  - Add usage examples for new APIs
  - Document breaking changes with migration guide

- [ ] **Update API_DOCUMENTATION_INDEX.md**
  - Add new API documentation files
  - Update categories if new API types introduced
  - Verify all links work

### 4. Performance Documentation

- [ ] **Run Performance Benchmarks** (if performance-sensitive code changed)
  - Execute relevant benchmarks from PERFORMANCE_METRICS_MASTER.md
  - Compare results with baseline metrics
  - Document any significant changes (>10%)

- [ ] **Update PERFORMANCE_METRICS_MASTER.md** (if metrics changed)
  - Add new benchmark results with date
  - Update comparison tables
  - Add explanation for significant changes
  - Verify measurement environment documented

### 5. Testing Documentation

- [ ] **Update TEST_COVERAGE_SUMMARY.md** (if tests added/removed)
  - Add new test classes to appropriate module
  - Update test counts
  - Document any new testing requirements (GPU, etc.)
  - Add critical test cases if introduced

### 6. Project Status

- [ ] **Update PROJECT_STATUS.md**
  - Mark features as complete if finished
  - Update completion percentages
  - Add new features to appropriate category
  - Update last modified date

### 7. Historical Reference

- [ ] **Add to HISTORICAL_FIXES_REFERENCE.md** (if bug fix)
  - Document bug and root cause
  - Describe solution implemented
  - Add file/line references
  - Date the entry

### 8. Cross-References and Links

- [ ] **Verify all cross-references**
  - Check all internal links work
  - Verify file paths are correct
  - Update links if files were moved/renamed
  - Test external links still work

- [ ] **Update related documents**
  - Check for other docs that reference changed code
  - Update examples that use changed APIs
  - Verify tutorials still work

### 9. Documentation Headers

- [ ] **Update document headers**
  - Set "Last Updated" to current date
  - Verify "Status" is correct (Current/Draft/Archived)
  - Update confidence level if changed
  - Add author/owner if appropriate

### 10. Standards Compliance

- [ ] **Follow DOCUMENTATION_STANDARDS.md**
  - Use standard terminology
  - Include required header information
  - Format code examples correctly
  - Verify performance claims include measurement context

---

## Category-Specific Checklists

### For New Features

When adding a completely new feature:

- [ ] Create feature documentation in appropriate module doc/ directory
- [ ] Add feature to module README.md
- [ ] Create API documentation if public APIs introduced
- [ ] Add usage examples
- [ ] Document configuration options
- [ ] Add to PROJECT_STATUS.md
- [ ] Update architecture docs if new packages/classes
- [ ] Add tests to TEST_COVERAGE_SUMMARY.md

### For API Changes

When modifying existing APIs:

- [ ] Update affected API documentation files
- [ ] Mark breaking changes clearly
- [ ] Provide migration guide if breaking change
- [ ] Update all examples using changed API
- [ ] Update API_DOCUMENTATION_INDEX.md
- [ ] Search for all references to changed API in docs
- [ ] Update integration test documentation

### For Bug Fixes

When fixing significant bugs:

- [ ] Add entry to HISTORICAL_FIXES_REFERENCE.md
- [ ] Update affected module README.md if behavior changed
- [ ] Update examples if bug was in documented code
- [ ] Add regression test to TEST_COVERAGE_SUMMARY.md
- [ ] Document workaround removal if applicable

### For Performance Improvements

When optimizing performance:

- [ ] Run full benchmark suite
- [ ] Update PERFORMANCE_METRICS_MASTER.md with new results
- [ ] Document optimization approach in architecture docs
- [ ] Update performance claims in affected API docs
- [ ] Add benchmark to TEST_COVERAGE_SUMMARY.md if new

### For Architecture Refactoring

When restructuring code:

- [ ] Update ARCHITECTURE_SUMMARY.md with new structure
- [ ] Update module-specific architecture docs
- [ ] Update package descriptions
- [ ] Move/update API documentation if packages changed
- [ ] Update all cross-references to moved code
- [ ] Document migration path if breaking change
- [ ] Update CLAUDE.md if significant structural change

---

## Automation Helpers

### Check for Outdated Documentation

```bash
# Find docs older than 90 days
find . -name "*.md" -type f -mtime +90 -not -path "*/node_modules/*" -not -path "*/.git/*"

# Check for broken internal links (requires markdown-link-check)
find . -name "*.md" -type f -exec markdown-link-check {} \;
```

### Run Full Documentation Validation

```bash
# Lint all markdown files
markdownlint '**/*.md' --ignore node_modules --ignore .git

# Check for TODO/FIXME in documentation
grep -r "TODO\|FIXME" --include="*.md" .

# Verify all referenced Java classes exist
grep -r "\.java" --include="*.md" . | grep -o "[A-Z][a-zA-Z]*\.java" | sort -u
```

---

## Review Process

### Before Creating Pull Request

1. Complete applicable items from checklist above
2. Run automation helpers to catch issues
3. Review diff of all documentation changes
4. Verify no broken links introduced
5. Check that headers have current dates

### During Code Review

Reviewer should verify:
- [ ] Documentation updated appropriately
- [ ] Examples compile and work
- [ ] Terminology follows standards
- [ ] Cross-references are correct
- [ ] Performance claims are verified

### After Merge

- [ ] Verify documentation appears correctly in repository
- [ ] Check that CI/CD documentation checks pass
- [ ] Spot-check a few links to verify they work
- [ ] Update any external documentation if needed

---

## Questions and Exceptions

### What if documentation impact is unclear?

Ask yourself:
- Did I add/change/remove any public API?
- Did behavior change in a user-visible way?
- Would someone reading the docs be confused by my changes?

If yes to any, update documentation.

### What if I'm unsure which docs to update?

1. Search for references to changed code: `grep -r "MyChangedClass" --include="*.md"`
2. Check API_DOCUMENTATION_INDEX.md for related APIs
3. Look at module README.md for feature descriptions
4. Ask documentation owner for guidance

### What if documentation is already incorrect?

Fix it as part of your PR:
1. Note the existing error in PR description
2. Update documentation to be correct
3. Add to HISTORICAL_FIXES_REFERENCE.md if significant

---

## Common Mistakes to Avoid

❌ **Don't**: Update code without updating examples  
✅ **Do**: Find all examples and update them to match new behavior

❌ **Don't**: Add features without documenting them  
✅ **Do**: Create documentation before marking feature complete

❌ **Don't**: Copy-paste outdated performance claims  
✅ **Do**: Run benchmarks and update PERFORMANCE_METRICS_MASTER.md

❌ **Don't**: Break links when moving files  
✅ **Do**: Search for all references and update them

❌ **Don't**: Leave documentation headers with old dates  
✅ **Do**: Update "Last Updated" date in all changed docs

---

## Templates

### New Feature Documentation Template

```markdown
# Feature Name

**Last Updated**: YYYY-MM-DD
**Status**: Current
**Module**: [module-name]
**Related APIs**: [Links to API docs]

## Overview

[1-2 sentence description of what this feature does]

## Use Cases

- Use case 1: [Description]
- Use case 2: [Description]

## Usage Example

```java
// Complete working example
import com.hellblazer.luciferase.*;

public class Example {
    public static void main(String[] args) {
        // Example code here
    }
}
```

## Configuration

[Any configuration options]

## Performance Characteristics

[Performance notes with benchmark references]

## Related Documentation

- [Link to API docs]
- [Link to architecture docs]
```

### API Change Migration Guide Template

```markdown
# Migration Guide: [Feature Name] API Changes

**Effective Date**: YYYY-MM-DD
**Affects**: [List of affected classes/packages]
**Severity**: BREAKING | Non-Breaking

## What Changed

[Clear description of the change]

## Migration Steps

### Before (Old API)

```java
// Old code
oldApi.doSomething(param);
```

### After (New API)

```java
// New code
newApi.doSomething(param1, param2);
```

## Rationale

[Why this change was made]

## Timeline

- **Deprecated**: [Date if applicable]
- **Removed**: [Date if applicable]
```

---

**Last Updated**: December 6, 2025  
**Maintained By**: Documentation Owner  
**Questions**: Create issue in project repository
