# H3 Mixedbread Validation: Quick Checklist

**5-Minute Validation** | Run these commands and check results

---

## Quick Start

```bash
# 1. Run automated validation (preferred)
cd /Users/hal.hildebrand/git/Luciferase
chmod +x validate_h3_mgrep_coverage.sh
./validate_h3_mgrep_coverage.sh

# 2. If ≥80% coverage: ✅ DONE
# 3. If <80% coverage: Run sync and re-validate
```

---

## Manual 5-Query Test (if script fails)

```bash
# Query 1: Clock interface
mgrep search "where is Clock interface used for deterministic testing" --store mgrep -a -m 20
# ✅ Should return: Clock.java

# Query 2: Migration timeout
mgrep search "how does CrossProcessMigration handle timeouts" --store mgrep -a -m 15
# ✅ Should return: CrossProcessMigration.java with PHASE_TIMEOUT_MS

# Query 3: setClock files
mgrep search "files with setClock method for test injection" --store mgrep -a -m 20
# ✅ Should return: 12+ files (all H3 Phase 1 + prerequisites)

# Query 4: Flaky tests
mgrep search "flaky test handling with DisabledIfEnvironmentVariable" --store mgrep -a -m 10
# ✅ Should return: FailureRecoveryTest.java, TwoNodeDistributedMigrationTest.java

# Query 5: Network simulation
mgrep search "FakeNetworkChannel packet loss simulation" --store mgrep -a -m 10
# ✅ Should return: FakeNetworkChannel.java
```

---

## Results Interpretation

| Queries Passing | Coverage | Action |
|-----------------|----------|--------|
| 5/5 | 100% | ✅ Perfect! Document findings |
| 4/5 | 80% | ✅ Good. Optional minor fixes |
| 3/5 | 60% | ⚠️ Sync recommended |
| 0-2/5 | ≤40% | ❌ Sync required immediately |

---

## If Sync Needed

```bash
# Sync recent H3 work
mgrep search "H3 Clock injection deterministic testing" --store mgrep -a -m 20 -s

# Wait 1-2 minutes for indexing

# Re-run validation
./validate_h3_mgrep_coverage.sh
```

---

## Quick Fixes

### Issue: BucketSynchronizedController not in logging queries
**Severity**: Low
**Fix**: File may not need logging. Ignore if no other issues.

### Issue: Recent commits not indexed
**Severity**: Medium
**Fix**: Run sync command above

### Issue: Test files not appearing
**Severity**: High
**Fix**: Check `.mgreprc.yaml` for test file exclusions:
```yaml
# BAD (remove this):
exclude_patterns:
  - "**/*Test.java"

# GOOD (keep this):
exclude_patterns:
  - "target/"
  - "*.class"
```

---

## Success Criteria

- [ ] Clock.java findable with interface queries
- [ ] All 12+ setClock() files discoverable
- [ ] CrossProcessMigration.java timeout handling in results
- [ ] Test files with @DisabledIfEnvironmentVariable appear
- [ ] FakeNetworkChannel.java packet loss code indexed
- [ ] Overall coverage ≥80%

---

## Time Estimate

- **Automated script**: 2-3 minutes
- **Manual 5-query test**: 5 minutes
- **Sync (if needed)**: 1-2 minutes
- **Re-validation**: 2-3 minutes

**Total**: 5-10 minutes (best case) to 15 minutes (with sync)

---

## Reference Documents

Full details in:
- `MIXEDBREAD_STORE_VALIDATION_REPORT.md` (complete methodology)
- `H3_SEMANTIC_QUERY_GUIDE.md` (20 queries + optimization tips)
- `H3_MIXEDBREAD_VALIDATION_SUMMARY.md` (agent findings + next steps)

---

**TIP**: Bookmark this checklist for weekly validation runs!
