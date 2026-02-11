#!/bin/bash
# Migration scaling test - double entity count until saturation
# Usage: ./test-migration-scaling.sh

set -e

RESULTS_FILE="/tmp/migration-scaling-results.txt"
echo "Entity Count, Node1 Migrations, Node2 Migrations, Total Migrations, Migrations/sec, Duration(s)" > $RESULTS_FILE

# Test with progressively larger entity counts
for ENTITY_COUNT in 100 200 400 800 1600 3200 6400; do
    echo ""
    echo "=========================================="
    echo "Testing with $ENTITY_COUNT entities..."
    echo "=========================================="

    # Clean up any existing processes
    pkill -f SimpleMigrationNode || true
    sleep 2

    # Start Node2 (receiver)
    cd /Users/hal.hildebrand/git/Luciferase/simulation
    mvn process-classes exec:java -pl . \
      -Dexec.mainClass="com.hellblazer.luciferase.simulation.examples.benchmarks.SimpleMigrationNode" \
      -Dexec.args="Node2 9001 9000 0" \
      > /tmp/node2.log 2>&1 &
    NODE2_PID=$!
    echo "Started Node2 (PID: $NODE2_PID)"

    sleep 5

    # Start Node1 (sender with entities)
    mvn process-classes exec:java -pl . \
      -Dexec.mainClass="com.hellblazer.luciferase.simulation.examples.benchmarks.SimpleMigrationNode" \
      -Dexec.args="Node1 9000 9001 $ENTITY_COUNT" \
      > /tmp/node1.log 2>&1 &
    NODE1_PID=$!
    echo "Started Node1 (PID: $NODE1_PID) with $ENTITY_COUNT entities"

    # Run for 60 seconds
    echo "Running for 60 seconds..."
    START_TIME=$(date +%s)
    sleep 60
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))

    # Extract final migration counts
    NODE1_MIGRATIONS=$(grep 'MIGRATION_COUNT' /tmp/node1.log | tail -1 | awk '{print $3}')
    NODE2_MIGRATIONS=$(grep 'MIGRATION_COUNT' /tmp/node2.log | tail -1 | awk '{print $3}')

    # Handle case where nodes didn't output anything (crash)
    if [ -z "$NODE1_MIGRATIONS" ]; then
        echo "ERROR: Node1 failed to start or crashed"
        NODE1_MIGRATIONS=0
    fi
    if [ -z "$NODE2_MIGRATIONS" ]; then
        echo "ERROR: Node2 failed to start or crashed"
        NODE2_MIGRATIONS=0
    fi

    TOTAL_MIGRATIONS=$((NODE1_MIGRATIONS + NODE2_MIGRATIONS))
    MIGRATIONS_PER_SEC=$(echo "scale=2; $TOTAL_MIGRATIONS / $DURATION" | bc)

    echo "Results:"
    echo "  Node1 migrations: $NODE1_MIGRATIONS"
    echo "  Node2 migrations: $NODE2_MIGRATIONS"
    echo "  Total migrations: $TOTAL_MIGRATIONS"
    echo "  Migrations/sec: $MIGRATIONS_PER_SEC"
    echo "  Duration: ${DURATION}s"

    # Save to results file
    echo "$ENTITY_COUNT, $NODE1_MIGRATIONS, $NODE2_MIGRATIONS, $TOTAL_MIGRATIONS, $MIGRATIONS_PER_SEC, $DURATION" >> $RESULTS_FILE

    # Stop processes
    kill $NODE1_PID $NODE2_PID 2>/dev/null || true
    sleep 2

    # Check if system crashed or performance degraded significantly
    if [ "$TOTAL_MIGRATIONS" -eq 0 ]; then
        echo "STOPPED: System failed at $ENTITY_COUNT entities"
        break
    fi

    # Check for performance degradation (less than 50% of expected linear scaling)
    if [ $ENTITY_COUNT -gt 100 ]; then
        EXPECTED_MIN=$((TOTAL_MIGRATIONS / 2))  # Very conservative threshold
        if [ $TOTAL_MIGRATIONS -lt 1000 ]; then
            echo "STOPPED: Performance degraded significantly at $ENTITY_COUNT entities"
            break
        fi
    fi
done

echo ""
echo "=========================================="
echo "Test complete! Results:"
cat $RESULTS_FILE
echo "=========================================="
