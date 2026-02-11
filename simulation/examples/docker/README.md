# Docker Deployment

**Quick Start**: Run Luciferase distributed simulation in Docker containers

**Last Updated**: 2026-02-10

---

## Prerequisites

- **Docker**: 20.10+ with docker-compose
- **Memory**: 8GB+ available (2GB per node + monitoring stack)
- **Disk**: 20GB+ free space (includes persistent data and metrics)

### Verify Installation

```bash
docker --version       # Should show 20.10 or higher
docker-compose --version
```

---

## Quick Start (Development Mode)

**1. Build the Luciferase Image**

From the project root:

```bash
cd /path/to/Luciferase
mvn clean install -DskipTests
docker build -t luciferase:latest -f simulation/examples/docker/Dockerfile .
```

**2. Launch the Cluster**

```bash
cd simulation/examples/docker
docker-compose up -d
```

**3. Verify the Cluster is Running**

```bash
# Check container status
docker-compose ps

# View logs
docker-compose logs -f node1 node2

# Check health endpoints
curl http://localhost:8081/health  # Node 1
curl http://localhost:8082/health  # Node 2
```

**4. Access Monitoring**

- **Prometheus**: http://localhost:9090 (metrics collection)
- **Grafana**: http://localhost:3000 (dashboards - admin/admin)
- **Jaeger**: http://localhost:16686 (distributed tracing)

**5. Stop the Cluster**

```bash
# Graceful shutdown
docker-compose down

# Remove volumes (cleanup all data)
docker-compose down -v
```

---

## Configuration

### Environment Variables

Create a `.env` file in this directory:

```bash
# TLS Configuration
TLS_ENABLED=false              # Set to true for production

# Grafana Admin Password
GRAFANA_PASSWORD=secure_password_here

# Log Level
LOG_LEVEL=INFO                 # DEBUG, INFO, WARN, ERROR
```

### Directory Structure

```
docker/
├── docker-compose.yml         # Main orchestration file
├── Dockerfile                 # Container image definition
├── README.md                  # This file
├── .env                       # Environment variables (create manually)
├── certs/                     # TLS certificates (production)
│   ├── ca-cert.pem
│   ├── node1-cert.pem
│   ├── node1-key.pem
│   ├── node2-cert.pem
│   └── node2-key.pem
├── config/                    # Application configuration
│   └── application.properties
├── data/                      # Persistent data (auto-created)
│   ├── node1/
│   └── node2/
├── prometheus.yml             # Prometheus configuration
├── grafana-datasources.yml    # Grafana datasource config
└── grafana-dashboards/        # Grafana dashboard definitions
```

---

## Production Deployment

### 1. Generate TLS Certificates

```bash
# Create certs directory
mkdir -p certs

# Generate CA
openssl req -x509 -newkey rsa:4096 -days 365 -nodes \
  -keyout certs/ca-key.pem \
  -out certs/ca-cert.pem \
  -subj "/CN=Luciferase CA"

# Generate node certificates
for node in node1 node2; do
  # Generate key
  openssl genrsa -out certs/${node}-key.pem 4096

  # Generate CSR
  openssl req -new -key certs/${node}-key.pem \
    -out certs/${node}.csr \
    -subj "/CN=${node}"

  # Sign with CA
  openssl x509 -req -in certs/${node}.csr \
    -CA certs/ca-cert.pem \
    -CAkey certs/ca-key.pem \
    -CAcreateserial \
    -out certs/${node}-cert.pem \
    -days 365
done

# Set permissions
chmod 600 certs/*-key.pem
```

### 2. Enable TLS

Edit `.env`:
```bash
TLS_ENABLED=true
GRAFANA_PASSWORD=<strong-random-password>
```

### 3. Configure Prometheus Scraping

Create `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'luciferase'
    static_configs:
      - targets:
          - 'node1:9090'
          - 'node2:9090'
        labels:
          cluster: 'production'

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
```

### 4. Launch with TLS

```bash
docker-compose up -d

# Verify TLS is enabled
docker-compose logs node1 | grep -i tls
docker-compose logs node2 | grep -i tls
```

---

## Scaling the Cluster

### Add More Nodes

Edit `docker-compose.yml` and add a new node service:

```yaml
  node3:
    image: luciferase:latest
    container_name: luciferase-node3
    hostname: node3
    environment:
      NODE_NAME: Node3
      MY_PORT: 9002
      PEER_HOST: node1,node2
      PEER_PORT: 9000,9001
      # ... (copy configuration from node1/node2)
    ports:
      - "8083:8080"
      - "9093:9090"
    # ... (rest of configuration)
```

Restart the cluster:

```bash
docker-compose up -d --scale node=3
```

---

## Monitoring

### Prometheus Queries

Access Prometheus at http://localhost:9090 and run these queries:

**Entity Count**:
```promql
luciferase_entity_count
```

**Migration Rate**:
```promql
rate(luciferase_migration_total[5m])
```

**Tick Latency (P99)**:
```promql
histogram_quantile(0.99, rate(luciferase_tick_latency_seconds_bucket[5m]))
```

**Migration Success Rate**:
```promql
sum(rate(luciferase_migration_total{status="success"}[5m])) /
sum(rate(luciferase_migration_total[5m]))
```

### Grafana Dashboards

1. Access Grafana: http://localhost:3000
2. Login: admin / admin (or your configured password)
3. Import dashboard:
   - Click "+" → "Import"
   - Upload `grafana-dashboards/luciferase-overview.json`

**Key Metrics Displayed**:
- Entity distribution across nodes
- Migration throughput per node
- P50/P95/P99 tick latency
- Network bandwidth usage
- JVM memory and GC activity

### Distributed Tracing

1. Access Jaeger: http://localhost:16686
2. Select "luciferase" service
3. Search for traces by operation:
   - `entity.migrate` - Entity migration events
   - `entity.create` - Entity creation
   - `entity.remove` - Entity removal

**Example Trace Query**:
- Service: `luciferase`
- Operation: `entity.migrate`
- Tags: `source.bubble=node1`, `target.bubble=node2`

---

## Troubleshooting

### Containers Won't Start

```bash
# Check logs
docker-compose logs

# Verify network
docker network ls | grep luciferase

# Check port conflicts
lsof -i :9000
lsof -i :9001
```

### Health Check Failing

```bash
# Exec into container
docker exec -it luciferase-node1 /bin/sh

# Check Java process
ps aux | grep java

# Check network connectivity
ping node2
nc -zv node2 9001
```

### TLS Handshake Failures

```bash
# Verify certificates exist
docker exec luciferase-node1 ls -la /certs

# Check certificate validity
docker exec luciferase-node1 openssl x509 -in /certs/node1-cert.pem -text -noout

# Verify CA trust
docker exec luciferase-node1 openssl verify -CAfile /certs/ca-cert.pem /certs/node1-cert.pem
```

### High Memory Usage

```bash
# Check container stats
docker stats

# View JVM heap dump
docker exec luciferase-node1 jmap -heap $(docker exec luciferase-node1 pgrep java)

# Adjust memory limits in docker-compose.yml
# Edit: deploy.resources.limits.memory
```

### Data Persistence Issues

```bash
# Check volume mounts
docker volume ls
docker volume inspect docker_prometheus-data

# Backup data
docker run --rm -v docker_prometheus-data:/data -v $(pwd):/backup alpine tar czf /backup/prometheus-backup.tar.gz -C /data .

# Restore data
docker run --rm -v docker_prometheus-data:/data -v $(pwd):/backup alpine tar xzf /backup/prometheus-backup.tar.gz -C /data
```

---

## Advanced Configuration

### Custom JVM Flags

Edit `docker-compose.yml`:

```yaml
environment:
  JAVA_OPTS: >-
    -Xms4g -Xmx4g
    -XX:+UseZGC
    -XX:ConcGCThreads=4
    -XX:ParallelGCThreads=8
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/data/heapdump.hprof
```

### Network Tuning

Edit `docker-compose.yml`:

```yaml
networks:
  luciferase-net:
    driver: bridge
    driver_opts:
      com.docker.network.driver.mtu: 9000  # Jumbo frames
    ipam:
      config:
        - subnet: 172.28.0.0/16
```

### Resource Limits

```yaml
deploy:
  resources:
    limits:
      cpus: '4.0'
      memory: 8G
      pids: 500
    reservations:
      cpus: '2.0'
      memory: 4G
```

---

## Performance Benchmarks

**Tested Configuration** (Docker Desktop on macOS M1):

| Metric | 2 Nodes | 4 Nodes | 8 Nodes |
|--------|---------|---------|---------|
| Entity Capacity | 50K | 100K | 200K |
| Migration Rate | 500/sec | 1K/sec | 2K/sec |
| P99 Tick Latency | 25ms | 35ms | 50ms |
| Memory per Node | 2GB | 2GB | 2GB |
| CPU per Node | 1.5 cores | 1.5 cores | 1.5 cores |

**Scaling Limits**:
- Docker Desktop: 8 nodes recommended max
- Production (Kubernetes): 50+ nodes validated

See `../../doc/PERFORMANCE_DISTRIBUTED.md` for detailed benchmarks.

---

## Next Steps

1. **Run the Quick Start**: Get familiar with Docker deployment
2. **Enable Monitoring**: Explore Prometheus and Grafana
3. **Production TLS**: Configure certificates and enable encryption
4. **Kubernetes Migration**: See `../DEPLOYMENT_GUIDE.md#kubernetes-deployment`
5. **Load Testing**: Validate performance with your entity workload

---

## Support

**Issues**: Report at [GitHub Issues](https://github.com/Hellblazer/Luciferase/issues)

**Documentation**:
- Main deployment guide: `../DEPLOYMENT_GUIDE.md`
- Architecture: `../../doc/ARCHITECTURE_DISTRIBUTED.md`
- Performance: `../../doc/PERFORMANCE_DISTRIBUTED.md`

**License**: AGPL v3.0

---

**Document Version**: 1.0
**Last Updated**: 2026-02-10
**Status**: Production deployment pattern validated
