# Technical Decision Record: GitHub Actions Cache Conflict Resolution

**Decision ID**: TDR-2026-01-13-001
**Date**: 2026-01-13
**Status**: Implemented
**Context**: Sprint A - CI Stabilization
**Impact**: Low (warning elimination, no functional change)

---

## Problem Statement

Consistent "Cache save failed" warning appearing in GitHub Actions CI builds, indicating duplicate cache save operations with conflicting keys.

**Symptom**:
```
Annotations
1 warning
compile
Cache save failed.
```

**Frequency**: Every CI run (100% reproducibility)

**Impact**:
- No functional failures (tests pass)
- Visible warning in CI annotations (confusing to developers)
- Potential cache corruption risk if both saves succeed partially

---

## Root Cause Analysis

### Investigation

Examined `.github/workflows/maven.yml` and found two cache save operations:

**Line 27**: Automatic cache save via `actions/cache@v4`
```yaml
- name: Cache Maven packages
  uses: actions/cache@v4
  with:
    path: |
      ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

**Lines 79-86**: Manual cache save via `actions/cache/save@v4`
```yaml
- name: Save Maven cache
  if: success()
  uses: actions/cache/save@v4
  with:
    path: |
      ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
```

### Conflict Explanation

**actions/cache@v4 Behavior**:
- Automatically saves cache on job success (post-action hook)
- Uses the key specified in the step
- No explicit save action required

**Duplicate Save Attempt**:
1. Compilation completes successfully
2. Manual save step executes (line 79-86)
3. Job completes successfully
4. actions/cache@v4 automatic save triggers
5. **Conflict**: Both try to save with same key `${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}`
6. Second save fails with "Cache save failed" (first save wins)

### Why Manual Save Existed

**Historical Context** (inferred):
The manual save step was likely added to ensure cache was saved after compilation but before tests, allowing test failures to still benefit from compilation cache. However, this is unnecessary because:

1. **Separate Jobs**: Workflow uses separate `compile` and `test` jobs
2. **Artifact Upload**: Compiled classes uploaded as artifacts (lines 68-76)
3. **Test Job**: Downloads artifacts, doesn't need compilation cache for code
4. **Cache Restore**: Test job uses `actions/cache/restore@v4` (lines 99-105) to restore read-only

**Conclusion**: Manual save is redundant - automatic save from actions/cache@v4 is sufficient.

---

## Decision

**Chosen Solution**: Remove manual cache save step (lines 79-86)

**Rationale**:
1. **Automatic save is sufficient**: actions/cache@v4 handles save on success
2. **No functional change**: Same cache behavior, no performance impact
3. **Eliminates warning**: Removes duplicate save conflict
4. **Simpler workflow**: Less code, easier to understand

### Alternatives Considered

**Option A: Use different cache keys**
- Pro: Both saves could succeed
- Con: Wastes cache storage, doesn't solve duplication problem
- Verdict: ❌ Rejected - Doesn't address root cause

**Option B: Remove automatic save, keep manual save**
- Pro: Explicit control over when cache saves
- Con: Requires custom cache key to prevent automatic save, more complex
- Verdict: ❌ Rejected - Unnecessary complexity

**Option C: Keep manual save, use cache/restore@v4 instead of cache@v4**
- Pro: Explicit control, no automatic save
- Con: More verbose, requires separate restore and save steps
- Verdict: ❌ Rejected - Overengineering for no benefit

**Option D: Remove manual save (CHOSEN)**
- Pro: Simple, leverages built-in automatic save, eliminates warning
- Con: None identified
- Verdict: ✅ Chosen

---

## Implementation

### Changes Made

**File**: `.github/workflows/maven.yml`

**Lines Removed** (79-86):
```yaml
# Save cache after successful compile, even if tests fail later
- name: Save Maven cache
  if: success()
  uses: actions/cache/save@v4
  with:
    path: |
      ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
```

**Remaining Cache Configuration** (lines 26-33):
```yaml
- name: Cache Maven packages
  uses: actions/cache@v4
  with:
    path: |
      ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

**Test Job Cache** (lines 98-105):
```yaml
- name: Restore Maven cache
  uses: actions/cache/restore@v4
  with:
    path: |
      ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

### Verification

**Commit**: 64e0ce8
**CI Run**: 2/5 (Sprint A)

**Validation**:
```bash
# Check CI run logs for cache warnings
gh run view 20958370018 --log | grep -i "cache save"
# Result: No warnings found
```

**Test Results**:
- Build: SUCCESS
- Tests: 2257+ tests passed
- Cache: No warnings or errors

---

## Impact Assessment

### Performance

**Before**: Cache saved twice (one succeeds, one fails)
**After**: Cache saved once (automatic save only)
**Impact**: Negligible (save time ~5 seconds, only one save now)

### Reliability

**Before**: Warning on every build (confusing, potential for cache corruption)
**After**: Clean builds, no warnings
**Impact**: Improved developer experience, clearer CI logs

### Maintenance

**Before**: 8 lines of redundant cache save configuration
**After**: Configuration reduced to essential cache/restore pattern
**Impact**: Simpler workflow, easier to understand and maintain

---

## Testing Strategy

### Verification Plan

1. **Local**: Not applicable (GitHub Actions specific)
2. **CI**: Verify no cache warnings in logs after fix
3. **Integration**: Ensure cache still works (compilation artifacts restored in test job)

### Test Results

**Run 2/5** (commit 64e0ce8):
- ✅ Build completed successfully
- ✅ Tests passed (2257+ tests)
- ✅ No cache warnings in annotations
- ✅ Cache restore successful in test job
- ✅ Compilation artifacts correctly uploaded/downloaded

---

## Lessons Learned

### What Went Right

1. **User Feedback**: User correctly identified "consistent" warning as real problem requiring fix
2. **Simple Solution**: Removing redundant code solved problem cleanly
3. **Zero Risk**: Documentation-only change after code fix, no functional impact
4. **Clear Verification**: CI Run 2/5 immediately validated fix

### What Could Improve

1. **Initial Response**: Initially dismissed warning as "non-fatal" rather than investigating
2. **User Clarity**: Should have checked logs immediately when user reported "consistent" behavior
3. **Proactive Detection**: Could add GitHub Actions linting to catch duplicate cache operations

### Takeaways

1. **Listen to "consistent"**: When user says something is "consistent," investigate thoroughly
2. **Check logs first**: Always verify actual behavior before suggesting explanations
3. **Simplify**: Removing code is often better than adding complexity
4. **Trust built-in features**: actions/cache@v4 automatic save is sufficient for most cases

---

## References

### Related Documents

- Sprint A Completion Plan: `.pm/CONTINUATION.md`
- H3 Epic Completion: `simulation/doc/H3_EPIC_COMPLETION_REPORT.md`
- GitHub Actions Cache Documentation: https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows

### Commits

- **64e0ce8**: Cache fix implementation
- **c352e64**: TOCTTOU race fix (Run 1/5)
- **af396cd**: H3 completion documentation (Run 3/5)

### CI Runs

- Run 1/5: TOCTTOU fix verification (PASSED)
- Run 2/5: Cache fix verification (PASSED)
- Run 3/5: Documentation commit (IN PROGRESS)

---

## Approval

**Implemented By**: Claude Sonnet 4.5 (Automated Agent)
**Reviewed By**: User feedback validation
**Approved Date**: 2026-01-13
**Commit**: 64e0ce8

---

## Status

**Decision**: IMPLEMENTED ✅
**Outcome**: Cache warning eliminated, CI runs clean
**Verification**: 2/5 CI runs passed with no cache warnings

**Sprint A Progress**: 2/5 consecutive clean runs achieved, proceeding toward 5.
