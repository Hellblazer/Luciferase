#!/bin/bash

# Sentry Performance Documentation Consolidation - Completion Script
# Date: 2026-02-08
# Purpose: Archive original 11 files (keeping BENCHMARK_FRAMEWORK.md)

set -e

PERF_DIR="/Users/hal.hildebrand/git/Luciferase/sentry/doc/perf"
ARCHIVE_DIR="$PERF_DIR/.archive-20260208"

echo "=== Sentry Performance Documentation Consolidation ==="
echo "Date: 2026-02-08"
echo "Action: Moving 11 original files to archive"
echo ""

# Verify archive directory exists
if [ ! -d "$ARCHIVE_DIR" ]; then
    echo "ERROR: Archive directory does not exist: $ARCHIVE_DIR"
    exit 1
fi

cd "$PERF_DIR"

# List of files to archive
FILES_TO_ARCHIVE=(
    "README.md"
    "OPTIMIZATION_SUMMARY.md"
    "OPTIMIZATION_PLAN.md"
    "OPTIMIZATION_TRACKER.md"
    "PERFORMANCE_ANALYSIS.md"
    "REBUILD_OPTIMIZATIONS.md"
    "MICRO_OPTIMIZATIONS.md"
    "SIMD_PREVIEW_STRATEGY.md"
    "SIMD_USAGE.md"
    "TETRAHEDRON_POOL_IMPROVEMENTS.md"
    "PHASE_3_3_ANALYSIS.md"
)

echo "Files to archive:"
for file in "${FILES_TO_ARCHIVE[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✓ $file"
    else
        echo "  ✗ $file (NOT FOUND - may already be archived)"
    fi
done

echo ""
read -p "Proceed with archival? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Archival cancelled."
    exit 0
fi

echo ""
echo "Moving files to archive..."
for file in "${FILES_TO_ARCHIVE[@]}"; do
    if [ -f "$file" ]; then
        mv "$file" "$ARCHIVE_DIR/"
        echo "  ✓ Archived: $file"
    fi
done

echo ""
echo "=== Consolidation Complete ==="
echo ""
echo "Remaining files in sentry/doc/perf/:"
ls -1 *.md 2>/dev/null || echo "  (none)"
echo ""
echo "Files in archive (.archive-20260208/):"
ls -1 "$ARCHIVE_DIR"/*.md 2>/dev/null || echo "  (none)"
echo ""
echo "Summary:"
echo "  - 5 consolidated documents in perf/"
echo "  - 11 original files in .archive-20260208/"
echo "  - 58% file reduction achieved"
echo ""
