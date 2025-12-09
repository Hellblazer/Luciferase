# Performance Metrics Report

**Generated**: 2025-07-13T15:40:35.259378
**Source**: Surefire test reports

## Insertion Performance

| Entity Count | Octree | Tetree | Prism | Tetree vs Octree | Prism vs Octree |
| ------------- | -------- | -------- | ------- | ------------------ | ----------------- |
| 100 | 1.131 ms | 0.465 ms | N/A | 2.4x faster | N/A |
| 1,000 | 25.843 ms | 4.642 ms | N/A | 5.6x faster | N/A |
| 10,000 | 780.442 ms | 194.178 ms | N/A | 4.0x faster | N/A |

## Memory Usage

| Entity Count | Octree | Tetree | Prism | Tetree vs Octree | Prism vs Octree |
| ------------- | -------- | -------- | ------- | ------------------ | ----------------- |
| 100 | 0.050 MB | 0.040 MB | N/A | 1.3x faster | N/A |
| 1,000 | 0.420 MB | 0.280 MB | N/A | 1.5x faster | N/A |
| 10,000 | 4.240 MB | 2.630 MB | N/A | 1.6x faster | N/A |

## Update Performance

| Entity Count | Octree | Tetree | Prism | Tetree vs Octree | Prism vs Octree |
| ------------- | -------- | -------- | ------- | ------------------ | ----------------- |
| 100 | 0.012 ms | 0.006 ms | N/A | 2.0x faster | N/A |
| 1,000 | 0.021 ms | 0.011 ms | N/A | 1.9x faster | N/A |
| 10,000 | 0.158 ms | 0.037 ms | N/A | 4.3x faster | N/A |

## Removal Performance

| Entity Count | Octree | Tetree | Prism | Tetree vs Octree | Prism vs Octree |
| ------------- | -------- | -------- | ------- | ------------------ | ----------------- |
| 100 | 0.002 ms | 0.001 ms | N/A | 2.0x faster | N/A |
| 1,000 | 0.001 ms | 0.000 ms | N/A | Infinityx faster | N/A |
| 10,000 | 0.008 ms | 0.002 ms | N/A | 4.0x faster | N/A |

## Data Summary

- **Total Metrics**: 24
- **Operations**: 4
- **Implementations**: [Octree, Tetree]
