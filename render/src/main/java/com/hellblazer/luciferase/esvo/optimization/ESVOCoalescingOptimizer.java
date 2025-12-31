package com.hellblazer.luciferase.esvo.optimization;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.sparse.optimization.Optimizer;

import java.util.*;

/**
 * GPU memory coalescing optimization for ESVO data structures.
 * Analyzes and optimizes memory access patterns for maximum GPU memory throughput.
 *
 * <p><b>Note:</b> This optimizer analyzes access patterns but does not modify data layout.
 * The {@link #optimize} method returns input unchanged.
 */
public class ESVOCoalescingOptimizer implements Optimizer<ESVOOctreeData> {

    private static final int WARP_SIZE = 32; // GPU warp size
    private static final int CACHE_LINE_SIZE = 128; // bytes

    /** Returns input unchanged - this optimizer analyzes patterns, not data. */
    @Override
    public ESVOOctreeData optimize(ESVOOctreeData input) {
        return input;
    }
    
    public static class CoalescingAnalysis {
        private final float coalescingEfficiency;
        private final int totalTransactions;
        private final int optimalTransactions;
        private final Map<String, Float> metrics;
        
        public CoalescingAnalysis(float coalescingEfficiency, int totalTransactions,
                                int optimalTransactions, Map<String, Float> metrics) {
            this.coalescingEfficiency = coalescingEfficiency;
            this.totalTransactions = totalTransactions;
            this.optimalTransactions = optimalTransactions;
            this.metrics = new HashMap<>(metrics);
        }
        
        public float getCoalescingEfficiency() { return coalescingEfficiency; }
        public int getTotalTransactions() { return totalTransactions; }
        public int getOptimalTransactions() { return optimalTransactions; }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }
        
        public float getWastedBandwidth() {
            return totalTransactions > optimalTransactions ? 
                1.0f - ((float) optimalTransactions / totalTransactions) : 0.0f;
        }
    }
    
    public static class MemoryAccessPattern {
        private final int[] accessIndices;
        private final int elementSize;
        private final String patternType;
        private final float predictability;
        
        public MemoryAccessPattern(int[] accessIndices, int elementSize, 
                                 String patternType, float predictability) {
            this.accessIndices = Arrays.copyOf(accessIndices, accessIndices.length);
            this.elementSize = elementSize;
            this.patternType = patternType;
            this.predictability = predictability;
        }
        
        public int[] getAccessIndices() { return Arrays.copyOf(accessIndices, accessIndices.length); }
        public int getElementSize() { return elementSize; }
        public String getPatternType() { return patternType; }
        public float getPredictability() { return predictability; }
    }
    
    public static class OptimizedAccessPattern {
        private final int[] originalIndices;
        private final int[] optimizedIndices;
        private final float improvementFactor;
        private final Map<String, Object> optimizationDetails;
        
        public OptimizedAccessPattern(int[] originalIndices, int[] optimizedIndices,
                                    float improvementFactor, Map<String, Object> details) {
            this.originalIndices = Arrays.copyOf(originalIndices, originalIndices.length);
            this.optimizedIndices = Arrays.copyOf(optimizedIndices, optimizedIndices.length);
            this.improvementFactor = improvementFactor;
            this.optimizationDetails = new HashMap<>(details);
        }
        
        public int[] getOriginalIndices() { return Arrays.copyOf(originalIndices, originalIndices.length); }
        public int[] getOptimizedIndices() { return Arrays.copyOf(optimizedIndices, optimizedIndices.length); }
        public float getImprovementFactor() { return improvementFactor; }
        public Map<String, Object> getOptimizationDetails() { 
            return Collections.unmodifiableMap(optimizationDetails); 
        }
    }
    
    /**
     * Analyzes memory coalescing efficiency for given access pattern
     */
    public CoalescingAnalysis analyzeCoalescing(int[] accessPattern, int elementSize) {
        if (accessPattern.length == 0) {
            var metrics = Map.of("accessCount", 0.0f, "elementSize", (float) elementSize);
            return new CoalescingAnalysis(1.0f, 0, 0, metrics);
        }
        
        // Group accesses by warp (32 threads)
        var warpGroups = groupByWarps(accessPattern);
        var totalTransactions = 0;
        var optimalTransactions = 0;
        var coalescedWarps = 0;
        var totalWarps = warpGroups.size();
        
        for (var warpAccesses : warpGroups) {
            var warpAnalysis = analyzeWarpCoalescing(warpAccesses, elementSize);
            totalTransactions += warpAnalysis.actualTransactions;
            optimalTransactions += warpAnalysis.optimalTransactions;
            
            if (warpAnalysis.isCoalesced) {
                coalescedWarps++;
            }
        }
        
        // Calculate overall efficiency
        var coalescingEfficiency = totalTransactions > 0 ? 
            (float) optimalTransactions / totalTransactions : 1.0f;
        
        // Calculate metrics
        var metrics = new HashMap<String, Float>();
        metrics.put("accessCount", (float) accessPattern.length);
        metrics.put("elementSize", (float) elementSize);
        metrics.put("totalWarps", (float) totalWarps);
        metrics.put("coalescedWarps", (float) coalescedWarps);
        metrics.put("warpCoalescingRatio", totalWarps > 0 ? (float) coalescedWarps / totalWarps : 1.0f);
        metrics.put("averageStride", calculateAverageStride(accessPattern));
        
        return new CoalescingAnalysis(coalescingEfficiency, totalTransactions, 
                                    optimalTransactions, metrics);
    }
    
    /**
     * Optimizes access pattern for better coalescing
     */
    public int[] optimizeForCoalescing(int[] originalPattern) {
        if (originalPattern.length <= 1) {
            return Arrays.copyOf(originalPattern, originalPattern.length);
        }
        
        // Sort accesses to create sequential pattern
        var sortedPattern = Arrays.stream(originalPattern)
            .sorted()
            .toArray();
        
        // Remove duplicates while preserving access counts
        var accessCounts = new HashMap<Integer, Integer>();
        for (int access : originalPattern) {
            accessCounts.merge(access, 1, Integer::sum);
        }
        
        // Create optimized pattern with sequential ordering
        var optimizedList = new ArrayList<Integer>();
        var sortedUniqueAccesses = accessCounts.keySet().stream()
            .sorted()
            .toArray(Integer[]::new);
        
        for (int access : sortedUniqueAccesses) {
            var count = accessCounts.get(access);
            for (int i = 0; i < count; i++) {
                optimizedList.add(access);
            }
        }
        
        return optimizedList.stream().mapToInt(Integer::intValue).toArray();
    }
    
    /**
     * Analyzes access pattern characteristics
     */
    public MemoryAccessPattern analyzeAccessPattern(int[] accessIndices, int elementSize) {
        if (accessIndices.length == 0) {
            return new MemoryAccessPattern(accessIndices, elementSize, "empty", 1.0f);
        }
        
        // Determine pattern type
        var patternType = classifyAccessPattern(accessIndices);
        
        // Calculate predictability (how regular the pattern is)
        var predictability = calculatePatternPredictability(accessIndices);
        
        return new MemoryAccessPattern(accessIndices, elementSize, patternType, predictability);
    }
    
    /**
     * Optimizes warp-level access patterns
     */
    public OptimizedAccessPattern optimizeWarpAccess(int[] warpAccessPattern, int elementSize) {
        if (warpAccessPattern.length == 0) {
            var details = Map.<String, Object>of("optimization", "empty_pattern");
            return new OptimizedAccessPattern(warpAccessPattern, warpAccessPattern, 1.0f, details);
        }
        
        var originalEfficiency = analyzeCoalescing(warpAccessPattern, elementSize).getCoalescingEfficiency();
        var optimizedPattern = optimizeForCoalescing(warpAccessPattern);
        var optimizedEfficiency = analyzeCoalescing(optimizedPattern, elementSize).getCoalescingEfficiency();
        
        var improvement = optimizedEfficiency > 0 ? optimizedEfficiency / originalEfficiency : 1.0f;
        
        var details = new HashMap<String, Object>();
        details.put("originalEfficiency", originalEfficiency);
        details.put("optimizedEfficiency", optimizedEfficiency);
        details.put("optimization", "sequential_reordering");
        details.put("patternLength", warpAccessPattern.length);
        
        return new OptimizedAccessPattern(warpAccessPattern, optimizedPattern, improvement, details);
    }
    
    /**
     * Estimates bandwidth utilization for access pattern
     */
    public Map<String, Float> estimateBandwidthUtilization(int[] accessPattern, 
                                                          int elementSize,
                                                          int memoryBandwidth) {
        var utilization = new HashMap<String, Float>();
        
        if (accessPattern.length == 0) {
            utilization.put("utilization", 0.0f);
            utilization.put("efficiency", 0.0f);
            return utilization;
        }
        
        var analysis = analyzeCoalescing(accessPattern, elementSize);
        
        // Calculate theoretical bandwidth usage
        var bytesTransferred = (long) accessPattern.length * elementSize;
        var actualTransactions = analysis.getTotalTransactions();
        var optimalTransactions = analysis.getOptimalTransactions();
        
        // Bandwidth utilization considering coalescing
        var coalescingUtilization = analysis.getCoalescingEfficiency();
        var theoreticalUtilization = Math.min(1.0f, (float) bytesTransferred / memoryBandwidth);
        var actualUtilization = theoreticalUtilization * coalescingUtilization;
        
        utilization.put("utilization", actualUtilization);
        utilization.put("efficiency", coalescingUtilization);
        utilization.put("theoreticalUtilization", theoreticalUtilization);
        utilization.put("wastedBandwidth", analysis.getWastedBandwidth());
        utilization.put("transactionOverhead", actualTransactions > optimalTransactions ? 
            ((float) actualTransactions / optimalTransactions) - 1.0f : 0.0f);
        
        return utilization;
    }
    
    /**
     * Suggests optimal memory layout for given access patterns
     */
    public Map<String, Object> suggestOptimalLayout(List<int[]> accessPatterns, int elementSize) {
        var suggestions = new HashMap<String, Object>();
        
        if (accessPatterns.isEmpty()) {
            suggestions.put("layout", "default");
            suggestions.put("reason", "no_access_patterns");
            return suggestions;
        }
        
        // Analyze all patterns
        var totalEfficiency = 0.0f;
        var patternTypes = new HashMap<String, Integer>();
        
        for (var pattern : accessPatterns) {
            var analysis = analyzeCoalescing(pattern, elementSize);
            totalEfficiency += analysis.getCoalescingEfficiency();
            
            var patternAnalysis = analyzeAccessPattern(pattern, elementSize);
            patternTypes.merge(patternAnalysis.getPatternType(), 1, Integer::sum);
        }
        
        var averageEfficiency = totalEfficiency / accessPatterns.size();
        
        // Determine optimal layout strategy
        String recommendedLayout;
        String reason;
        
        if (averageEfficiency > 0.8f) {
            recommendedLayout = "current";
            reason = "already_optimized";
        } else if (patternTypes.getOrDefault("sequential", 0) > accessPatterns.size() / 2) {
            recommendedLayout = "linear";
            reason = "sequential_patterns_dominant";
        } else if (patternTypes.getOrDefault("strided", 0) > accessPatterns.size() / 3) {
            recommendedLayout = "interleaved";
            reason = "strided_patterns_detected";
        } else {
            recommendedLayout = "blocked";
            reason = "random_patterns_require_blocking";
        }
        
        suggestions.put("layout", recommendedLayout);
        suggestions.put("reason", reason);
        suggestions.put("averageEfficiency", averageEfficiency);
        suggestions.put("patternDistribution", patternTypes);
        suggestions.put("expectedImprovement", Math.max(0.0f, 0.9f - averageEfficiency));
        
        return suggestions;
    }
    
    // Private helper methods
    
    private static class WarpCoalescingAnalysis {
        final boolean isCoalesced;
        final int actualTransactions;
        final int optimalTransactions;
        
        WarpCoalescingAnalysis(boolean isCoalesced, int actualTransactions, int optimalTransactions) {
            this.isCoalesced = isCoalesced;
            this.actualTransactions = actualTransactions;
            this.optimalTransactions = optimalTransactions;
        }
    }
    
    private List<int[]> groupByWarps(int[] accessPattern) {
        var warpGroups = new ArrayList<int[]>();
        
        for (int i = 0; i < accessPattern.length; i += WARP_SIZE) {
            var endIndex = Math.min(i + WARP_SIZE, accessPattern.length);
            var warpAccesses = Arrays.copyOfRange(accessPattern, i, endIndex);
            warpGroups.add(warpAccesses);
        }
        
        return warpGroups;
    }
    
    private WarpCoalescingAnalysis analyzeWarpCoalescing(int[] warpAccesses, int elementSize) {
        if (warpAccesses.length == 0) {
            return new WarpCoalescingAnalysis(true, 0, 0);
        }
        
        // Calculate memory addresses
        var addresses = new long[warpAccesses.length];
        for (int i = 0; i < warpAccesses.length; i++) {
            addresses[i] = (long) warpAccesses[i] * elementSize;
        }
        
        // Find address range
        var minAddr = Arrays.stream(addresses).min().orElse(0L);
        var maxAddr = Arrays.stream(addresses).max().orElse(0L);
        var addressRange = maxAddr - minAddr + elementSize;
        
        // Calculate cache lines spanned
        var firstCacheLine = minAddr / CACHE_LINE_SIZE;
        var lastCacheLine = maxAddr / CACHE_LINE_SIZE;
        var cacheLineSpan = (int) (lastCacheLine - firstCacheLine + 1);
        
        // Optimal would be sequential access within minimal cache lines
        var optimalTransactions = Math.max(1, (int) ((addressRange + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE));
        
        // Actual transactions depend on access pattern
        var actualTransactions = calculateActualTransactions(addresses);
        
        // Consider coalesced if accessing sequential or near-sequential addresses
        var isCoalesced = isSequentialAccess(warpAccesses) || (cacheLineSpan <= 2);
        
        return new WarpCoalescingAnalysis(isCoalesced, actualTransactions, optimalTransactions);
    }
    
    private int calculateActualTransactions(long[] addresses) {
        if (addresses.length == 0) return 0;
        
        // Group addresses by cache line
        var cacheLineAccesses = new HashSet<Long>();
        for (var addr : addresses) {
            cacheLineAccesses.add(addr / CACHE_LINE_SIZE);
        }
        
        return cacheLineAccesses.size();
    }
    
    private boolean isSequentialAccess(int[] accesses) {
        if (accesses.length <= 1) return true;
        
        var sortedAccesses = Arrays.stream(accesses).sorted().toArray();
        
        // Check if accesses are mostly sequential
        var sequentialCount = 0;
        for (int i = 1; i < sortedAccesses.length; i++) {
            if (sortedAccesses[i] - sortedAccesses[i-1] <= 2) { // Allow small gaps
                sequentialCount++;
            }
        }
        
        return (float) sequentialCount / (sortedAccesses.length - 1) > 0.8f;
    }
    
    private String classifyAccessPattern(int[] accessIndices) {
        if (accessIndices.length <= 1) return "single";
        
        var sortedAccesses = Arrays.stream(accessIndices).sorted().toArray();
        
        // Check for sequential pattern
        if (isSequentialAccess(accessIndices)) {
            return "sequential";
        }
        
        // Check for strided pattern
        if (isStridedAccess(sortedAccesses)) {
            return "strided";
        }
        
        // Check for random pattern
        var uniqueAccesses = Arrays.stream(accessIndices).distinct().count();
        var spreadFactor = (sortedAccesses[sortedAccesses.length - 1] - sortedAccesses[0]) / 
                          (double) uniqueAccesses;
        
        if (spreadFactor > 10.0) {
            return "random";
        } else {
            return "clustered";
        }
    }
    
    private boolean isStridedAccess(int[] sortedAccesses) {
        if (sortedAccesses.length < 3) return false;
        
        // Calculate strides
        var strides = new int[sortedAccesses.length - 1];
        for (int i = 1; i < sortedAccesses.length; i++) {
            strides[i-1] = sortedAccesses[i] - sortedAccesses[i-1];
        }
        
        // Check if strides are consistent
        var firstStride = strides[0];
        var consistentStrides = 0;
        
        for (var stride : strides) {
            if (Math.abs(stride - firstStride) <= 1) { // Allow small variations
                consistentStrides++;
            }
        }
        
        return (float) consistentStrides / strides.length > 0.7f;
    }
    
    private float calculatePatternPredictability(int[] accessIndices) {
        if (accessIndices.length <= 1) return 1.0f;
        
        // Calculate entropy-like measure
        var accessCounts = new HashMap<Integer, Integer>();
        for (int access : accessIndices) {
            accessCounts.merge(access, 1, Integer::sum);
        }
        
        var entropy = 0.0;
        var totalAccesses = accessIndices.length;
        
        for (var count : accessCounts.values()) {
            var probability = (double) count / totalAccesses;
            if (probability > 0) {
                entropy -= probability * Math.log(probability) / Math.log(2);
            }
        }
        
        // Normalize entropy to [0,1] range
        var maxEntropy = Math.log(accessCounts.size()) / Math.log(2);
        return maxEntropy > 0 ? (float) (1.0 - entropy / maxEntropy) : 1.0f;
    }
    
    private float calculateAverageStride(int[] accessPattern) {
        if (accessPattern.length <= 1) return 0.0f;
        
        var totalStride = 0L;
        var strideCount = 0;
        
        for (int i = 1; i < accessPattern.length; i++) {
            totalStride += Math.abs(accessPattern[i] - accessPattern[i-1]);
            strideCount++;
        }
        
        return strideCount > 0 ? (float) totalStride / strideCount : 0.0f;
    }
}