#!/bin/bash
# Extended migration scaling test - continue from 6400 to find saturation
# Usage: ./test-migration-scaling-extended.sh

set -e

RESULTS_FILE="/tmp/migration-scaling-extended-results.txt"
echo "Entity Count, Node1 Migrations, Node2 Migrations, Total Migrations, Migrations/sec, Duration(s), Scaling Efficiency(%)" > $RESULTS_FILE

PREV_MIGRATIONS=36799  # From 6400 entity run

# Test with progressively larger entity counts
for ENTITY_COUNT in 12800 25600 51200 102400; do
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
    echo "Starting Node1 with $ENTITY_COUNT entities (this may take time to spawn)..."
    mvn process-classes exec:java -pl . \
      -Dexec.mainClass="com.hellblazer.luciferase.simulation.examples.benchmarks.SimpleMigrationNode" \
      -Dexec.args="Node1 9000 9001 $ENTITY_COUNT" \
      > /tmp/node1.log 2>&1 &
    NODE1_PID=$!
    echo "Started Node1 (PID: $NODE1_PID)"

    # Wait longer for large entity counts to spawn
    if [ $ENTITY_COUNT -gt 50000 ]; then
        echo "Waiting 30 seconds for entities to spawn..."
        sleep 30
    else
        echo "Waiting 15 seconds for entities to spawn..."
        sleep 15
    fi

    # Run for 60 seconds
    echo "Running benchmark for 60 seconds..."
    START_TIME=$(date +%s)
    sleep 60
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))

    # Check if processes are still running
    if ! kill -0 $NODE1_PID 2>/dev/null; then
        echo "ERROR: Node1 crashed during test!"
        NODE1_MIGRATIONS=0
        NODE2_MIGRATIONS=0
    elif ! kill -0 $NODE2_PID 2>/dev/null; then
        echo "ERROR: Node2 crashed during test!"
        NODE1_MIGRATIONS=0
        NODE2_MIGRATIONS=0
    else
        # Extract final migration counts
        NODE1_MIGRATIONS=$(grep 'MIGRATION_COUNT' /tmp/node1.log | tail -1 | awk '{print $3}')
        NODE2_MIGRATIONS=$(grep 'MIGRATION_COUNT' /tmp/node2.log | tail -1 | awk '{print $3}')

        # Handle case where nodes didn't output anything
        if [ -z "$NODE1_MIGRATIONS" ]; then
            echo "WARNING: Node1 produced no output"
            NODE1_MIGRATIONS=0
        fi
        if [ -z "$NODE2_MIGRATIONS" ]; then
            echo "WARNING: Node2 produced no output"
            NODE2_MIGRATIONS=0
        fi
    fi

    TOTAL_MIGRATIONS=$((NODE1_MIGRATIONS + NODE2_MIGRATIONS))
    MIGRATIONS_PER_SEC=$(echo "scale=2; $TOTAL_MIGRATIONS / $DURATION" | bc)

    # Calculate scaling efficiency (% of ideal 2x scaling)
    SCALING_EFFICIENCY=$(echo "scale=2; ($TOTAL_MIGRATIONS / $PREV_MIGRATIONS - 1.0) * 100" | bc)

    echo "Results:"
    echo "  Node1 migrations: $NODE1_MIGRATIONS"
    echo "  Node2 migrations: $NODE2_MIGRATIONS"
    echo "  Total migrations: $TOTAL_MIGRATIONS"
    echo "  Migrations/sec: $MIGRATIONS_PER_SEC"
    echo "  Scaling efficiency: ${SCALING_EFFICIENCY}% (ideal = 100%)"
    echo "  Duration: ${DURATION}s"

    # Save to results file
    echo "$ENTITY_COUNT, $NODE1_MIGRATIONS, $NODE2_MIGRATIONS, $TOTAL_MIGRATIONS, $MIGRATIONS_PER_SEC, $DURATION, $SCALING_EFFICIENCY" >> $RESULTS_FILE

    # Stop processes
    kill $NODE1_PID $NODE2_PID 2>/dev/null || true
    sleep 2

    # Check if system crashed
    if [ "$TOTAL_MIGRATIONS" -eq 0 ]; then
        echo "STOPPED: System failed at $ENTITY_COUNT entities"
        break
    fi

    # Check for severe performance degradation (less than 20% scaling efficiency)
    if (( $(echo "$SCALING_EFFICIENCY < 20.0" | bc -l) )); then
        echo "STOPPED: Scaling efficiency dropped below 20% at $ENTITY_COUNT entities"
        echo "Found saturation point!"
        break
    fi

    PREV_MIGRATIONS=$TOTAL_MIGRATIONS
done

echo ""
echo "=========================================="
echo "Extended test complete! Results:"
cat $RESULTS_FILE
echo "=========================================="
