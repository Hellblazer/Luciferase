# Grafana Dashboards

**Last Updated**: 2026-02-11
**Status**: Active
**Owner**: Project Maintainers

Place Grafana dashboard JSON files in this directory. They will be automatically provisioned when the container starts.

## Creating Dashboards

1. **Access Grafana**: http://localhost:3000 (admin/admin)
2. **Create Dashboard**: Click "+" → "Create Dashboard"
3. **Add Panel**: Click "Add panel"
4. **Configure Query**:
   - Select "Prometheus" datasource
   - Enter PromQL query (examples below)
   - Configure visualization type

5. **Save Dashboard**: Click "Save dashboard" (disk icon)
6. **Export JSON**: Dashboard Settings → JSON Model → Copy JSON
7. **Save to File**: Create `<dashboard-name>.json` in this directory

## Example Queries

### Entity Count by Node
```promql
luciferase_entity_count
```

### Migration Rate (5-minute average)
```promql
rate(luciferase_migration_total[5m])
```

### Migration Success Rate
```promql
sum(rate(luciferase_migration_total{status="success"}[5m])) /
sum(rate(luciferase_migration_total[5m]))
```

### Tick Latency (P99)
```promql
histogram_quantile(0.99, rate(luciferase_tick_latency_seconds_bucket[5m]))
```

### Network Bandwidth
```promql
rate(luciferase_network_bytes_sent[5m])
rate(luciferase_network_bytes_received[5m])
```

### JVM Memory Usage
```promql
jvm_memory_used_bytes{area="heap"}
```

### GC Pause Time
```promql
rate(jvm_gc_pause_seconds_sum[5m])
```

## Example Dashboard Structure

```json
{
  "title": "Luciferase Overview",
  "panels": [
    {
      "title": "Entity Distribution",
      "type": "graph",
      "targets": [
        {
          "expr": "luciferase_entity_count",
          "legendFormat": "{{node}}"
        }
      ]
    },
    {
      "title": "Migration Rate",
      "type": "graph",
      "targets": [
        {
          "expr": "rate(luciferase_migration_total[5m])",
          "legendFormat": "{{node}} -> {{target_bubble}}"
        }
      ]
    },
    {
      "title": "Tick Latency",
      "type": "graph",
      "targets": [
        {
          "expr": "histogram_quantile(0.99, rate(luciferase_tick_latency_seconds_bucket[5m]))",
          "legendFormat": "P99"
        }
      ]
    }
  ]
}
```

## Pre-Built Dashboards

Community dashboards for similar systems:
- **JVM Micrometer**: Grafana ID 4701
- **gRPC**: Grafana ID 12890
- **Docker**: Grafana ID 893

Import via: Dashboard → Import → Enter Grafana.com dashboard ID
