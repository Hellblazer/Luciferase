#!/bin/bash
# H3 Mixedbread Store Validation Script
# Version: 1.0
# Date: 2026-01-12

set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}H3 Mixedbread Store Validation${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Track results
TOTAL_QUERIES=0
SUCCESSFUL_QUERIES=0
FAILED_QUERIES=0

# Function to run query and validate
run_query() {
    local description="$1"
    local query="$2"
    local expected_files="$3"
    local result_count="$4"

    TOTAL_QUERIES=$((TOTAL_QUERIES + 1))

    echo -e "${YELLOW}Query $TOTAL_QUERIES: $description${NC}"
    echo -e "Command: mgrep search \"$query\" --store mgrep -a -m $result_count"
    echo ""

    # Run the query and capture output
    if mgrep search "$query" --store mgrep -a -m "$result_count" > /tmp/mgrep_output_$TOTAL_QUERIES.txt 2>&1; then
        echo -e "${GREEN}✓ Query executed successfully${NC}"

        # Check if expected files are in the output
        local files_found=0
        local files_missing=0

        IFS=',' read -ra FILE_ARRAY <<< "$expected_files"
        for file in "${FILE_ARRAY[@]}"; do
            if grep -q "$file" /tmp/mgrep_output_$TOTAL_QUERIES.txt; then
                echo -e "  ${GREEN}✓ Found: $file${NC}"
                files_found=$((files_found + 1))
            else
                echo -e "  ${RED}✗ Missing: $file${NC}"
                files_missing=$((files_missing + 1))
            fi
        done

        if [ $files_missing -eq 0 ]; then
            SUCCESSFUL_QUERIES=$((SUCCESSFUL_QUERIES + 1))
            echo -e "${GREEN}✓ All expected files found${NC}"
        else
            echo -e "${YELLOW}⚠ Some expected files missing ($files_missing/${#FILE_ARRAY[@]})${NC}"
        fi
    else
        echo -e "${RED}✗ Query execution failed${NC}"
        FAILED_QUERIES=$((FAILED_QUERIES + 1))
    fi

    echo ""
    echo "---"
    echo ""
}

# Group 1: Clock Infrastructure
echo -e "${BLUE}=== GROUP 1: Clock Infrastructure ===${NC}"
echo ""

run_query \
    "Clock interface location" \
    "where is Clock interface used for deterministic testing" \
    "Clock.java" \
    20

run_query \
    "Clock injection pattern" \
    "how to inject Clock for deterministic testing" \
    "setClock" \
    15

run_query \
    "TestClock usage" \
    "TestClock usage examples" \
    "TestClock" \
    10

# Group 2: Migration Protocol
echo -e "${BLUE}=== GROUP 2: Migration Protocol ===${NC}"
echo ""

run_query \
    "2PC implementation" \
    "entity migration 2PC protocol implementation" \
    "CrossProcessMigration.java,MigrationProtocolMessages.java" \
    15

run_query \
    "Timeout handling" \
    "how does CrossProcessMigration handle timeouts" \
    "CrossProcessMigration.java,PHASE_TIMEOUT_MS" \
    15

run_query \
    "Migration rollback" \
    "migration rollback procedure" \
    "CrossProcessMigration.java,ABORT" \
    10

# Group 3: Code Locations
echo -e "${BLUE}=== GROUP 3: Code Locations ===${NC}"
echo ""

run_query \
    "Files with setClock()" \
    "files with setClock method for test injection" \
    "CrossProcessMigration.java,BubbleMigrator.java,VolumeAnimator.java" \
    20

run_query \
    "nanoTime() usages" \
    "where is nanoTime used for timing measurements" \
    "Clock.java,nanoTime" \
    15

# Group 4: Test Patterns
echo -e "${BLUE}=== GROUP 4: Test Patterns ===${NC}"
echo ""

run_query \
    "Flaky test handling" \
    "flaky test handling with DisabledIfEnvironmentVariable" \
    "DisabledIfEnvironmentVariable,FailureRecoveryTest" \
    10

run_query \
    "Probabilistic tests" \
    "probabilistic test examples with random failures" \
    "SingleBubbleWithEntitiesTest" \
    10

# Group 5: Network Simulation
echo -e "${BLUE}=== GROUP 5: Network Simulation ===${NC}"
echo ""

run_query \
    "Packet loss simulation" \
    "FakeNetworkChannel packet loss simulation" \
    "FakeNetworkChannel.java" \
    10

run_query \
    "Remote bubble proxy caching" \
    "remote bubble proxy caching implementation" \
    "RemoteBubbleProxy.java" \
    10

# Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}VALIDATION SUMMARY${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "Total queries executed: $TOTAL_QUERIES"
echo -e "${GREEN}Successful: $SUCCESSFUL_QUERIES${NC}"
echo -e "${YELLOW}Partial: $((TOTAL_QUERIES - SUCCESSFUL_QUERIES - FAILED_QUERIES))${NC}"
echo -e "${RED}Failed: $FAILED_QUERIES${NC}"
echo ""

COVERAGE_PERCENT=$((SUCCESSFUL_QUERIES * 100 / TOTAL_QUERIES))
echo -e "Coverage: ${COVERAGE_PERCENT}%"

if [ $COVERAGE_PERCENT -ge 80 ]; then
    echo -e "${GREEN}✓ Coverage meets target (≥80%)${NC}"
    exit 0
elif [ $COVERAGE_PERCENT -ge 60 ]; then
    echo -e "${YELLOW}⚠ Coverage below target (60-79%). Consider syncing.${NC}"
    echo -e "Run: ${YELLOW}mgrep search \"H3 Clock injection\" --store mgrep -a -m 20 -s${NC}"
    exit 1
else
    echo -e "${RED}✗ Coverage critically low (<60%). Sync required.${NC}"
    echo -e "Run: ${RED}mgrep search \"H3 Clock injection\" --store mgrep -a -m 20 -s${NC}"
    exit 2
fi
