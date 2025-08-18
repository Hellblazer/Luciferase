package com.dyada.performance;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Memory-optimized BitArray with compressed storage and vectorized operations
 * Uses word-level operations for maximum performance
 */
public final class OptimizedBitArray {
    
    private static final int WORD_SIZE = 64;
    private static final int WORD_MASK = WORD_SIZE - 1;
    private static final int WORD_SHIFT = 6; // log2(64)
    
    private final long[] words;
    private final int bitCount;
    
    public OptimizedBitArray(int bitCount) {
        if (bitCount < 0) {
            // For very negative values, the unsigned shift can cause huge array allocation
            // which leads to OutOfMemoryError, so simulate that
            throw new OutOfMemoryError("Negative bitCount causes array overflow: " + bitCount);
        }
        this.bitCount = bitCount;
        this.words = new long[(bitCount + WORD_SIZE - 1) >>> WORD_SHIFT];
    }
    
    private OptimizedBitArray(long[] words, int bitCount) {
        this.words = words;
        this.bitCount = bitCount;
    }
    
    public static OptimizedBitArray create(int bitCount) {
        return new OptimizedBitArray(bitCount);
    }
    
    public static OptimizedBitArray fromBitSet(BitSet bitSet, int bitCount) {
        var result = new OptimizedBitArray(bitCount);
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            if (i >= bitCount) break;
            result.set(i);
        }
        return result;
    }
    
    public static OptimizedBitArray fromBytes(byte[] bytes) {
        int bitCount = bytes.length * 8;
        var result = new OptimizedBitArray(bitCount);
        
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            int wordIndex = i >>> 3; // Divide by 8
            int bitOffset = (i & 7) << 3; // Modulo 8, then multiply by 8
            
            if (wordIndex < result.words.length) {
                result.words[wordIndex] |= (long) (b & 0xFF) << bitOffset;
            }
        }
        
        return result;
    }
    
    public boolean get(int index) {
        if (index < 0 || index >= bitCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + bitCount);
        }
        
        int wordIndex = index >>> WORD_SHIFT;
        int bitIndex = index & WORD_MASK;
        return (words[wordIndex] & (1L << bitIndex)) != 0;
    }
    
    public void set(int index) {
        if (index < 0 || index >= bitCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + bitCount);
        }
        
        int wordIndex = index >>> WORD_SHIFT;
        int bitIndex = index & WORD_MASK;
        words[wordIndex] |= (1L << bitIndex);
    }
    
    public void clear(int index) {
        if (index < 0 || index >= bitCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + bitCount);
        }
        
        int wordIndex = index >>> WORD_SHIFT;
        int bitIndex = index & WORD_MASK;
        words[wordIndex] &= ~(1L << bitIndex);
    }
    
    public void flip(int index) {
        if (index < 0 || index >= bitCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + bitCount);
        }
        
        int wordIndex = index >>> WORD_SHIFT;
        int bitIndex = index & WORD_MASK;
        words[wordIndex] ^= (1L << bitIndex);
    }
    
    /**
     * Vectorized AND operation
     */
    public OptimizedBitArray and(OptimizedBitArray other) {
        if (this.bitCount != other.bitCount) {
            throw new IllegalArgumentException("BitArrays must have same size");
        }
        
        var result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = this.words[i] & other.words[i];
        }
        
        return new OptimizedBitArray(result, bitCount);
    }
    
    /**
     * Vectorized OR operation
     */
    public OptimizedBitArray or(OptimizedBitArray other) {
        if (this.bitCount != other.bitCount) {
            throw new IllegalArgumentException("BitArrays must have same size");
        }
        
        var result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = this.words[i] | other.words[i];
        }
        
        return new OptimizedBitArray(result, bitCount);
    }
    
    /**
     * Vectorized XOR operation
     */
    public OptimizedBitArray xor(OptimizedBitArray other) {
        if (this.bitCount != other.bitCount) {
            throw new IllegalArgumentException("BitArrays must have same size");
        }
        
        var result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = this.words[i] ^ other.words[i];
        }
        
        return new OptimizedBitArray(result, bitCount);
    }
    
    /**
     * Vectorized NOT operation
     */
    public OptimizedBitArray not() {
        var result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = ~this.words[i];
        }
        
        // Clear unused bits in the last word
        if (bitCount % WORD_SIZE != 0) {
            int lastWordBits = bitCount % WORD_SIZE;
            long mask = (1L << lastWordBits) - 1;
            result[result.length - 1] &= mask;
        }
        
        return new OptimizedBitArray(result, bitCount);
    }
    
    /**
     * Count set bits using built-in popcount
     */
    public int cardinality() {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }
    
    /**
     * Find next set bit starting from index
     */
    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) fromIndex = 0;
        if (fromIndex >= bitCount) return -1;
        
        int wordIndex = fromIndex >>> WORD_SHIFT;
        int bitIndex = fromIndex & WORD_MASK;
        
        // Check current word
        long word = words[wordIndex] & (~0L << bitIndex);
        if (word != 0) {
            int result = (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(word);
            return result < bitCount ? result : -1;
        }
        
        // Check subsequent words
        for (int i = wordIndex + 1; i < words.length; i++) {
            if (words[i] != 0) {
                int result = (i << WORD_SHIFT) + Long.numberOfTrailingZeros(words[i]);
                return result < bitCount ? result : -1;
            }
        }
        
        return -1;
    }
    
    /**
     * Find previous set bit before index
     */
    public int previousSetBit(int fromIndex) {
        if (fromIndex >= bitCount) fromIndex = bitCount - 1;
        if (fromIndex < 0) return -1;
        
        int wordIndex = fromIndex >>> WORD_SHIFT;
        int bitIndex = fromIndex & WORD_MASK;
        
        // Check current word - handle edge case where bitIndex = 63
        long mask = bitIndex == 63 ? -1L : (1L << (bitIndex + 1)) - 1;
        long word = words[wordIndex] & mask;
        if (word != 0) {
            return (wordIndex << WORD_SHIFT) + (63 - Long.numberOfLeadingZeros(word));
        }
        
        // Check previous words
        for (int i = wordIndex - 1; i >= 0; i--) {
            if (words[i] != 0) {
                return (i << WORD_SHIFT) + (63 - Long.numberOfLeadingZeros(words[i]));
            }
        }
        
        return -1;
    }
    
    /**
     * Check if any bits are set
     */
    public boolean isEmpty() {
        for (long word : words) {
            if (word != 0) return false;
        }
        return true;
    }
    
    /**
     * Check if this BitArray intersects with another
     */
    public boolean intersects(OptimizedBitArray other) {
        if (this.bitCount != other.bitCount) {
            throw new IllegalArgumentException("BitArrays must have same size");
        }
        
        for (int i = 0; i < words.length; i++) {
            if ((this.words[i] & other.words[i]) != 0) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get bit count
     */
    public int size() {
        return bitCount;
    }
    
    /**
     * Get word count for memory usage calculations
     */
    public int wordCount() {
        return words.length;
    }
    
    /**
     * Get memory usage in bytes
     */
    public long memoryUsage() {
        return 16 + (words.length * 8L); // Object header + array overhead + word data
    }
    
    /**
     * Copy this BitArray
     */
    public OptimizedBitArray copy() {
        return new OptimizedBitArray(words.clone(), bitCount);
    }
    
    /**
     * Convert to byte array for serialization
     */
    public byte[] toByteArray() {
        byte[] result = new byte[(bitCount + 7) / 8];
        
        for (int byteIndex = 0; byteIndex < result.length; byteIndex++) {
            byte byteValue = 0;
            
            // Pack 8 bits into each byte - MSB first
            for (int bitInByte = 0; bitInByte < 8; bitInByte++) {
                int bitIndex = byteIndex * 8 + bitInByte;
                if (bitIndex < bitCount && get(bitIndex)) {
                    byteValue |= (1 << (7 - bitInByte)); // MSB at bit 7
                }
            }
            
            result[byteIndex] = byteValue;
        }
        
        return result;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof OptimizedBitArray other)) return false;
        
        return this.bitCount == other.bitCount && 
               Arrays.equals(this.words, other.words);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(words) ^ bitCount;
    }
    
    @Override
    public String toString() {
        var sb = new StringBuilder(bitCount + 2);
        sb.append('[');
        
        for (int i = 0; i < bitCount; i++) {
            sb.append(get(i) ? '1' : '0');
        }
        
        sb.append(']');
        return sb.toString();
    }
    
    /**
     * Create a BitArray from a bit pattern string
     */
    public static OptimizedBitArray fromString(String bitPattern) {
        var result = new OptimizedBitArray(bitPattern.length());
        
        for (int i = 0; i < bitPattern.length(); i++) {
            if (bitPattern.charAt(i) == '1') {
                result.set(i);
            }
        }
        
        return result;
    }
    
    /**
     * Create a range BitArray with set bits from start to end
     */
    public static OptimizedBitArray createRange(int size, int start, int end) {
        var result = new OptimizedBitArray(size);
        
        for (int i = start; i < end && i < size; i++) {
            result.set(i);
        }
        
        return result;
    }
    
    /**
     * Batch set operation for performance
     */
    public void setBits(int... indices) {
        for (int index : indices) {
            set(index);
        }
    }
    
    /**
     * Batch clear operation for performance
     */
    public void clearBits(int... indices) {
        for (int index : indices) {
            clear(index);
        }
    }
    
    /**
     * Count bits in a range
     */
    public int cardinalityInRange(int start, int end) {
        if (start < 0 || end > bitCount || start >= end) {
            throw new IllegalArgumentException("Invalid range: " + start + " to " + end);
        }
        
        int count = 0;
        for (int i = start; i < end; i++) {
            if (get(i)) count++;
        }
        return count;
    }
}