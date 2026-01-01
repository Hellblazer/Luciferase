#!/bin/bash

# Documentation Validation Script
# Purpose: Validate all documentation for consistency, accuracy, and completeness
# Usage: ./scripts/validate-documentation.sh [--fix]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FIX_MODE=false
EXIT_CODE=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
if [[ "$1" == "--fix" ]]; then
    FIX_MODE=true
    echo -e "${BLUE}Running in FIX mode - will attempt to fix issues${NC}"
fi

cd "$PROJECT_ROOT"

echo "=========================================="
echo "Documentation Validation"
echo "=========================================="
echo "Project: Luciferase"
echo "Date: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# Test 1: Check for required headers
echo -e "${BLUE}[1/10] Checking for required headers...${NC}"
MISSING_HEADERS=0
for file in $(find . -name "*.md" -not -path "*/node_modules/*" -not -path "*/.git/*" -not -path "*/target/*" -not -path "*/.beads/*" -not -path "*/Octree/*" -not -path "*/t8code/*" -type f); do
    if ! grep -q "Last Updated" "$file" 2>/dev/null; then
        if [[ "$file" == *"README.md" ]] || [[ "$file" == *"CLAUDE.md" ]] || [[ "$file" == *"_API.md" ]] || [[ "$file" == *"ARCHITECTURE"* ]]; then
            echo -e "${YELLOW}  Missing 'Last Updated' header: $file${NC}"
            MISSING_HEADERS=$((MISSING_HEADERS + 1))
        fi
    fi
done

if [ $MISSING_HEADERS -eq 0 ]; then
    echo -e "${GREEN}  ✓ All major documentation files have required headers${NC}"
else
    echo -e "${RED}  ✗ Found $MISSING_HEADERS files missing headers${NC}"
    EXIT_CODE=1
fi
echo ""

# Test 2: Check for broken internal links
echo -e "${BLUE}[2/10] Checking for broken internal links...${NC}"
BROKEN_LINKS=0
for file in $(find . -name "*.md" -not -path "*/node_modules/*" -not -path "*/.git/*" -not -path "*/target/*" -not -path "*/.beads/*" -not -path "*/Octree/*" -not -path "*/t8code/*" -type f); do
    # Extract markdown links [text](path.md), but skip code blocks
    IN_CODE_BLOCK=false
    while IFS= read -r line; do
        # Track code block boundaries (lines starting with ```)
        if echo "$line" | grep -q '^\s*```'; then
            if [ "$IN_CODE_BLOCK" = true ]; then
                IN_CODE_BLOCK=false
            else
                IN_CODE_BLOCK=true
            fi
            continue
        fi
        
        # Skip link extraction if we're inside a code block
        if [ "$IN_CODE_BLOCK" = true ]; then
            continue
        fi
        
        # Extract markdown links from this line using grep
        if echo "$line" | grep -q '\[.*\](.*)'  2>/dev/null; then
            echo "$line" | grep -oE '\[[^\]]+\]\([^)]+\)' 2>/dev/null | while read -r match; do
                # Extract the URL part from [text](url)
                link=$(echo "$match" | sed 's/.*(\([^)]*\)).*/\1/')
                # Only check relative paths (not URLs or anchor-only links)
                if [[ -n "$link" ]] && [[ "$link" != http* ]] && [[ "$link" != https* ]] && [[ "$link" != \#* ]]; then
                    # Strip anchor from link if present
                    link_file="${link%%#*}"
                    # Resolve relative path
                    dir=$(dirname "$file")
                    if [ ! -f "$dir/$link_file" ] && [ ! -f "$PROJECT_ROOT/$link_file" ]; then
                        echo -e "${YELLOW}  Broken link in $file: $link${NC}"
                        BROKEN_LINKS=$((BROKEN_LINKS + 1))
                    fi
                fi
            done
        fi
    done < "$file"
done

if [ $BROKEN_LINKS -eq 0 ]; then
    echo -e "${GREEN}  ✓ No broken internal links found${NC}"
else
    echo -e "${RED}  ✗ Found $BROKEN_LINKS broken links${NC}"
    EXIT_CODE=1
fi
echo ""

# Test 3: Check for outdated documentation (>6 months)
echo -e "${BLUE}[3/10] Checking for outdated documentation...${NC}"
OUTDATED_DOCS=0
SIX_MONTHS_AGO=$(date -v-6m '+%Y-%m-%d' 2>/dev/null || date -d '6 months ago' '+%Y-%m-%d' 2>/dev/null || echo "2025-06-06")

for file in $(find . -name "*PERFORMANCE*.md" -o -name "*METRICS*.md" -not -path "*/.git/*" -not -path "*/target/*"); do
    if grep -q "Last Updated" "$file"; then
        LAST_UPDATED=$(grep "Last Updated" "$file" | grep -oP '\d{4}-\d{2}-\d{2}' | head -1)
        if [[ "$LAST_UPDATED" < "$SIX_MONTHS_AGO" ]]; then
            echo -e "${YELLOW}  Outdated (>6 months): $file (Last Updated: $LAST_UPDATED)${NC}"
            OUTDATED_DOCS=$((OUTDATED_DOCS + 1))
        fi
    fi
done

if [ $OUTDATED_DOCS -eq 0 ]; then
    echo -e "${GREEN}  ✓ No outdated performance documentation${NC}"
else
    echo -e "${YELLOW}  ! Found $OUTDATED_DOCS potentially outdated docs (review needed)${NC}"
fi
echo ""

# Test 4: Check for deprecated terminology
echo -e "${BLUE}[4/10] Checking for deprecated terminology...${NC}"
DEPRECATED_TERMS=0

# Check for "distributed trees" instead of "distributed support"
if grep -r "distributed trees" --include="*.md" . 2>/dev/null | grep -v ".git" | grep -q .; then
    echo -e "${YELLOW}  Found deprecated term 'distributed trees' (use 'distributed support')${NC}"
    DEPRECATED_TERMS=$((DEPRECATED_TERMS + 1))
fi

# Check for "tree forest" instead of "forest management"
if grep -r "tree forest" --include="*.md" . 2>/dev/null | grep -v ".git" | grep -q .; then
    echo -e "${YELLOW}  Found deprecated term 'tree forest' (use 'forest management')${NC}"
    DEPRECATED_TERMS=$((DEPRECATED_TERMS + 1))
fi

if [ $DEPRECATED_TERMS -eq 0 ]; then
    echo -e "${GREEN}  ✓ No deprecated terminology found${NC}"
else
    echo -e "${YELLOW}  ! Found $DEPRECATED_TERMS deprecated terms (review DOCUMENTATION_STANDARDS.md)${NC}"
fi
echo ""

# Test 5: Verify critical documentation exists
echo -e "${BLUE}[5/10] Verifying critical documentation exists...${NC}"
CRITICAL_DOCS=(
    "CLAUDE.md"
    "README.md"
    "DOCUMENTATION_STANDARDS.md"
    "lucien/doc/LUCIEN_ARCHITECTURE.md"
    "lucien/doc/PERFORMANCE_METRICS_MASTER.md"
    "lucien/doc/API_DOCUMENTATION_INDEX.md"
)

MISSING_CRITICAL=0
for doc in "${CRITICAL_DOCS[@]}"; do
    if [ ! -f "$doc" ]; then
        echo -e "${RED}  Missing critical document: $doc${NC}"
        MISSING_CRITICAL=$((MISSING_CRITICAL + 1))
    fi
done

if [ $MISSING_CRITICAL -eq 0 ]; then
    echo -e "${GREEN}  ✓ All critical documentation exists${NC}"
else
    echo -e "${RED}  ✗ Missing $MISSING_CRITICAL critical documents${NC}"
    EXIT_CODE=1
fi
echo ""

# Test 6: Check for TODO/FIXME in documentation
echo -e "${BLUE}[6/10] Checking for TODO/FIXME markers...${NC}"
TODO_COUNT=$(grep -r "TODO\|FIXME" --include="*.md" . 2>/dev/null | grep -v ".git" | wc -l | tr -d ' ')

if [ "$TODO_COUNT" -eq 0 ]; then
    echo -e "${GREEN}  ✓ No TODO/FIXME markers in documentation${NC}"
else
    echo -e "${YELLOW}  ! Found $TODO_COUNT TODO/FIXME markers (review needed)${NC}"
    if [ "$TODO_COUNT" -lt 10 ]; then
        grep -r "TODO\|FIXME" --include="*.md" . 2>/dev/null | grep -v ".git" | while read -r line; do
            echo -e "${YELLOW}    $line${NC}"
        done
    fi
fi
echo ""

# Test 7: Verify Java class references
echo -e "${BLUE}[7/10] Verifying Java class references...${NC}"
INVALID_REFS=0

# Extract .java references from markdown and verify they exist
for file in $(find . -name "*.md" -not -path "*/.git/*" -not -path "*/target/*" -type f); do
    while IFS= read -r class_ref; do
        # Remove .java extension for search
        class_name="${class_ref%.java}"
        if ! find . -name "$class_ref" -type f | grep -q .; then
            # Only warn if it looks like a real class reference (not in code blocks or examples)
            if grep -B2 "$class_ref" "$file" | grep -qv '```'; then
                echo -e "${YELLOW}  Possible invalid class ref in $file: $class_ref${NC}"
                INVALID_REFS=$((INVALID_REFS + 1))
            fi
        fi
    done < <(grep -oP '[A-Z][a-zA-Z0-9]*\.java' "$file" 2>/dev/null | sort -u || true)
done

if [ $INVALID_REFS -eq 0 ]; then
    echo -e "${GREEN}  ✓ All Java class references appear valid${NC}"
else
    echo -e "${YELLOW}  ! Found $INVALID_REFS potentially invalid class references${NC}"
fi
echo ""

# Test 8: Check API documentation index consistency
echo -e "${BLUE}[8/10] Checking API documentation index...${NC}"
API_INDEX_ISSUES=0

if [ -f "lucien/doc/API_DOCUMENTATION_INDEX.md" ]; then
    # Extract API doc references from index
    while IFS= read -r api_doc; do
        if [ -n "$api_doc" ] && [ ! -f "lucien/doc/$api_doc" ]; then
            echo -e "${YELLOW}  API doc referenced in index but not found: $api_doc${NC}"
            API_INDEX_ISSUES=$((API_INDEX_ISSUES + 1))
        fi
    done < <(grep -oP '\]\(\K[A-Z_]+\.md' "lucien/doc/API_DOCUMENTATION_INDEX.md" 2>/dev/null || true)
    
    if [ $API_INDEX_ISSUES -eq 0 ]; then
        echo -e "${GREEN}  ✓ API documentation index is consistent${NC}"
    else
        echo -e "${RED}  ✗ Found $API_INDEX_ISSUES API index inconsistencies${NC}"
        EXIT_CODE=1
    fi
else
    echo -e "${YELLOW}  ! API_DOCUMENTATION_INDEX.md not found${NC}"
fi
echo ""

# Test 9: Validate markdown syntax
echo -e "${BLUE}[9/10] Validating markdown syntax...${NC}"
if command -v markdownlint &> /dev/null; then
    if markdownlint '**/*.md' --ignore node_modules --ignore .git --quiet 2>/dev/null; then
        echo -e "${GREEN}  ✓ Markdown syntax is valid${NC}"
    else
        echo -e "${YELLOW}  ! Markdown linting found issues (non-critical)${NC}"
    fi
else
    echo -e "${YELLOW}  ! markdownlint not installed (skipping syntax check)${NC}"
    echo -e "${YELLOW}    Install with: npm install -g markdownlint-cli${NC}"
fi
echo ""

# Test 10: Check documentation file sizes
echo -e "${BLUE}[10/10] Checking documentation file sizes...${NC}"
LARGE_DOCS=0
for file in $(find . -name "*.md" -not -path "*/.git/*" -not -path "*/target/*" -type f); do
    SIZE=$(wc -l < "$file" 2>/dev/null || echo "0")
    if [ "$SIZE" -gt 2000 ]; then
        echo -e "${YELLOW}  Large documentation file ($SIZE lines): $file${NC}"
        echo -e "${YELLOW}    Consider splitting into multiple files${NC}"
        LARGE_DOCS=$((LARGE_DOCS + 1))
    fi
done

if [ $LARGE_DOCS -eq 0 ]; then
    echo -e "${GREEN}  ✓ All documentation files are reasonable size${NC}"
else
    echo -e "${YELLOW}  ! Found $LARGE_DOCS large documentation files${NC}"
fi
echo ""

# Summary
echo "=========================================="
echo "Validation Summary"
echo "=========================================="

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ Documentation validation PASSED${NC}"
    echo ""
    echo "All critical checks passed. Documentation is in good shape."
else
    echo -e "${RED}✗ Documentation validation FAILED${NC}"
    echo ""
    echo "Critical issues found that need to be addressed:"
    [ $MISSING_HEADERS -gt 0 ] && echo "  - $MISSING_HEADERS files missing headers"
    [ $BROKEN_LINKS -gt 0 ] && echo "  - $BROKEN_LINKS broken internal links"
    [ $MISSING_CRITICAL -gt 0 ] && echo "  - $MISSING_CRITICAL missing critical documents"
    [ $API_INDEX_ISSUES -gt 0 ] && echo "  - $API_INDEX_ISSUES API index inconsistencies"
fi

echo ""
echo "Additional findings (non-critical):"
[ $OUTDATED_DOCS -gt 0 ] && echo "  - $OUTDATED_DOCS potentially outdated docs (>6 months)"
[ $DEPRECATED_TERMS -gt 0 ] && echo "  - $DEPRECATED_TERMS uses of deprecated terminology"
[ "$TODO_COUNT" -gt 0 ] && echo "  - $TODO_COUNT TODO/FIXME markers"
[ $INVALID_REFS -gt 0 ] && echo "  - $INVALID_REFS potentially invalid class references"
[ $LARGE_DOCS -gt 0 ] && echo "  - $LARGE_DOCS large documentation files"

echo ""
echo "For documentation standards, see: DOCUMENTATION_STANDARDS.md"
echo "For quarterly reviews, see: .github/QUARTERLY_DOCUMENTATION_REVIEW.md"
echo ""

exit $EXIT_CODE
