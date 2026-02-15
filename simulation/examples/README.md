# Luciferase Examples

**Quick Start Guide** to running distributed volumetric animation examples.

**Last Updated**: 2026-02-10

---

## Overview

This directory contains runnable examples demonstrating Luciferase's distributed volumetric animation capabilities, progressing from single-process baseline to production multi-node deployment.

### Example Progression

| Example | Type | Nodes | Network | Purpose |
|---------|------|-------|---------|---------|
| **Simple** | Visualization | 1 | None | Baseline: single-process animation |
| **TwoNode** | Distributed | 2 | gRPC | Entity migration between processes |
| **MultiNode** | Distributed | 3-5 | gRPC | Topology operations (split/merge) |
| **Production** | Deployment | N | Docker | Production-ready multi-container setup |

**Estimated Time**: 5-10 minutes to run first example

---

## Prerequisites

### Required
- **Java**: 24+ (uses stable FFM API)
- **Maven**: 3.9.1+
- **Memory**: 4GB+ available

### Optional (for production deployment)
- **Docker**: 20.10+ with docker-compose
- **Network**: Localhost loopback (distributed examples)

### Verify Prerequisites

```bash
java --version    # Should show Java 24 or higher
mvn --version     # Should show Maven 3.9.1+
docker --version  # Optional, for production deployment
```text

---

## Quick Start

### 1. Build the Project

```bash
cd /path/to/Luciferase
mvn clean install -DskipTests
```text

**Time**: ~2 minutes (first build), ~30 seconds (subsequent)

### 2. Run Your First Example (TwoNode)

```bash
mvn test -Dtest=TwoNodeExampleTest -pl simulation
```text

**Expected Output**:
```text
Starting TwoNodeExample test:
  Node 1: port 12345 (bounds: 0-50)
  Node 2: port 12346 (bounds: 50-100)
✓ Both nodes ready
✓ Entity migration detected
✓ Entity accounting consistent
✓ TwoNodeExample test PASSED
```text

**Time**: ~12 seconds

**Success!** You've just run a distributed simulation with 2 JVM processes migrating entities via gRPC.

---

## Examples

### 1. Simple Local Demo (Single Process)

**Purpose**: Baseline single-process animation with visualization

**Location**: `simulation/src/main/java/.../viz/PredatorPreyGridDemo.java`

**Run**:
```bash
cd simulation
mvn process-classes exec:java \
  -Dexec.mainClass="com.hellblazer.luciferase.simulation.viz.PredatorPreyGridDemo"
```text

**What You'll See**:
- JavaFX window with 3D grid visualization
- Entities moving according to predator-prey dynamics
- Real-time FPS and entity count display
- No network communication (baseline)

**Key Concepts Demonstrated**:
- `EnhancedBubble` spatial indexing
- Entity behavior patterns
- Real-time visualization
- Tetrahedral spatial subdivision

**More Details**: [simple/README.md](simple/README.md)

---

### 2. TwoNode Distributed Demo (2 Processes)

**Purpose**: Entity migration between 2 JVM processes via gRPC

**Location**: `simulation/src/main/java/.../examples/TwoNodeExample.java`

**Run (Integration Test)**:
```bash
mvn test -Dtest=TwoNodeExampleTest -pl simulation
```text

**Run (Manual - 2 Terminals)**:

Terminal 1 (Node1):
```bash
cd simulation
mvn process-classes exec:java \
  -Dexec.mainClass="com.hellblazer.luciferase.simulation.examples.TwoNodeExample" \
  -Dexec.args="Node1 9000 9001"
```text

Terminal 2 (Node2):
```bash
cd simulation
mvn process-classes exec:java \
  -Dexec.mainClass="com.hellblazer.luciferase.simulation.examples.TwoNodeExample" \
  -Dexec.args="Node2 9001 9000"
```text

**What You'll See**:
- 2 separate JVM processes start
- Node1 spawns 50 entities
- Entities migrate to Node2 when crossing x=50 boundary
- Console shows migration events and entity distribution
- Clean shutdown with Ctrl+C

**Key Concepts Demonstrated**:
- `GrpcBubbleNetworkChannel` for inter-node communication
- Entity migration with 2-phase commit protocol
- Distributed spatial indexing
- Entity conservation (no duplication or loss)
- Process spawning with `ProcessBuilder`

**More Details**: [distributed/README.md](distributed/README.md)

---

### 3. MultiNode Distributed Demo (3-5 Processes)

**Purpose**: Topology operations (split/merge) with multiple nodes

**Status**: 🚧 **Coming Soon** - Create `MultiNodeExample.java`

**Planned Features**:
- 3-5 JVM processes with spatial subdivision
- Dynamic topology changes (node join/leave)
- Split operation (node disconnect)
- Merge operation (node reconnect)
- Demonstrates full distributed capabilities

**More Details**: [distributed/README.md](distributed/README.md#multinodeexample)

---

### 4. Production Deployment (Docker)

**Purpose**: Production-ready multi-container setup

**Status**: 🚧 **Coming Soon** - Create `docker-compose.yml`

**Planned Features**:
- Multi-container deployment with Docker Compose
- TLS configuration for secure communication
- Authentication/authorization between nodes
- Monitoring and logging setup
- Production deployment checklist

**More Details**: [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md#production-deployment)

---

## Common Issues

### Build Fails
```bash
# Clean and rebuild
mvn clean install -DskipTests

# Check Java version
java --version  # Must be Java 24+
```text

### Port Conflicts
```bash
# TwoNodeExample uses dynamic ports by default
# If running manually, choose unused ports:
mvn exec:java -Dexec.args="Node1 9000 9001"  # Change 9000/9001 as needed
```text

### PrimeMover Transformation Error
```bash
# Always use process-classes phase, not compile:
mvn process-classes exec:java ...  # CORRECT
mvn compile exec:java ...          # WRONG - missing bytecode transformation
```text

### gRPC Connection Timeout
```bash
# Ensure both nodes start within 10 seconds of each other
# Node2 must be running when Node1 tries to connect
```text

---

## Next Steps

1. **Run Simple Demo**: Get familiar with single-process visualization
2. **Run TwoNode Demo**: See distributed entity migration in action
3. **Read Deployment Guide**: Understand production deployment ([DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md))
4. **Explore Architecture**: Read distributed architecture docs (`simulation/doc/ARCHITECTURE_DISTRIBUTED.md`)
5. **Review Performance**: See validated performance metrics (`simulation/doc/PERFORMANCE_DISTRIBUTED.md`)

---

## Directory Structure

```text
examples/
├── README.md                    # This file - navigation guide
├── DEPLOYMENT_GUIDE.md          # Production deployment instructions
├── simple/                      # Single-process examples
│   └── README.md                # Guide to running PredatorPreyGridDemo
├── distributed/                 # Multi-process examples
│   └── README.md                # Guide to TwoNode and MultiNode demos
└── docker/                      # Production deployment (coming soon)
    ├── docker-compose.yml
    └── README.md
```text

**Java Source Code Locations**:
- Simple: `simulation/src/main/java/.../viz/PredatorPreyGridDemo.java`
- TwoNode: `simulation/src/main/java/.../examples/TwoNodeExample.java`
- MultiNode: `simulation/src/main/java/.../examples/MultiNodeExample.java` (coming soon)

---

## Documentation

### Core Documentation
- **Architecture**: `simulation/doc/ARCHITECTURE_DISTRIBUTED.md`
- **Performance**: `simulation/doc/PERFORMANCE_DISTRIBUTED.md`
- **Testing**: `simulation/doc/TEST_FRAMEWORK_GUIDE.md`

### Integration Tests
- **Multi-Process**: `simulation/src/integration-test/java/.../multiprocess/README.md`
- **TwoNodeExample Test**: `simulation/src/test/java/.../examples/TwoNodeExampleTest.java`

### Project Documentation
- **Main README**: `README.md` (project root)
- **CLAUDE.md**: Development guidelines and build commands

---

## Support

**Issues**: Report at [GitHub Issues](https://github.com/Hellblazer/Luciferase/issues)

**Documentation**: See `simulation/doc/` for detailed architecture and performance documentation

**License**: AGPL v3.0

---

**Document Version**: 1.0
**Last Updated**: 2026-02-10
**Status**: Active (MultiNode and Docker examples in progress)
