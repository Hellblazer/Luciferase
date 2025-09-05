package com.dyada.core.bitarray;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Immutable BitArray implementation optimized for DyAda's spatial data structures.
 * Uses long[] backing storage for memory efficiency and supports all bitwise operations.
 * 
 * This is the foundation data structure for DyAda's dyadic adaptivity system,
 * providing memory-efficient storage of refinement decisions and spatial addressing.
 */
public final class BitArray implements Iterable<Boolean> {
    private static final int BITS_PER_WORD = 64;
    private static final long ALL_ONES = 0xFFFFFFFFFFFFFFFFL;
    
    private final long[] words;
    private final int size;
    
    private BitArray(long[] words, int size) {
        this.words = words.clone(); // Defensive copy
        this.size = size;
        clearUnusedBits();
    }
    
    /**
     * Creates a BitArray of the specified size with all bits set to false.
     */
    public static BitArray of(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }
        var wordCount = (size + BITS_PER_WORD - 1) / BITS_PER_WORD;
        return new BitArray(new long[wordCount], size);
    }
    
    /**
     * Creates a BitArray from the given boolean values.
     */
    public static BitArray of(boolean... bits) {
        var result = of(bits.length);
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                result = result.set(i, true);
            }
        }
        return result;
    }
    
    /**
     * Creates a BitArray from the given Boolean array.
     */
    public static BitArray of(Boolean[] bits) {
        var result = of(bits.length);
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                result = result.set(i, true);
            }
        }
        return result;
    }
    
    /**
     * Parses a binary string into a BitArray.
     * @param binaryString String containing only '0' and '1' characters
     */
    public static BitArray parse(String binaryString) {
        if (binaryString == null) {
            throw new IllegalArgumentException("Binary string cannot be null");
        }
        
        for (char c : binaryString.toCharArray()) {
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException("Invalid character in binary string: " + c);
            }
        }
        
        var result = of(binaryString.length());
        for (int i = 0; i < binaryString.length(); i++) {
            if (binaryString.charAt(i) == '1') {
                result = result.set(i, true);
            }
        }
        return result;
    }
    
    /**
     * Creates a BitArray from a long value with specified bit count.
     */
    public static BitArray fromLong(long value, int bitCount) {
        if (bitCount < 0 || bitCount > 64) {
            throw new IllegalArgumentException("Bit count must be between 0 and 64: " + bitCount);
        }
        
        var result = of(bitCount);
        for (int i = 0; i < bitCount; i++) {
            if ((value & (1L << i)) != 0) {
                result = result.set(i, true);
            }
        }
        return result;
    }
    
    /**
     * Creates a BitArray with all bits set to true.
     */
    public static BitArray ones(int size) {
        var result = of(size);
        for (int i = 0; i < size; i++) {
            result = result.set(i, true);
        }
        return result;
    }
    
    /**
     * Returns the number of bits in this BitArray.
     */
    public int size() {
        return size;
    }
    
    /**
     * Returns the bit at the specified index.
     */
    public boolean get(int index) {
        checkBounds(index);
        var wordIndex = index / BITS_PER_WORD;
        var bitIndex = index % BITS_PER_WORD;
        return (words[wordIndex] & (1L << bitIndex)) != 0;
    }
    
    /**
     * Returns a new BitArray with the bit at the specified index set to the given value.
     */
    public BitArray set(int index, boolean value) {
        checkBounds(index);
        var newWords = words.clone();
        var wordIndex = index / BITS_PER_WORD;
        var bitIndex = index % BITS_PER_WORD;
        
        if (value) {
            newWords[wordIndex] |= (1L << bitIndex);
        } else {
            newWords[wordIndex] &= ~(1L << bitIndex);
        }
        
        return new BitArray(newWords, size);
    }
    
    /**
     * Returns a new BitArray with the bit at the specified index flipped (toggled).
     * If the bit is 0, it becomes 1; if it's 1, it becomes 0.
     */
    public BitArray flip(int index) {
        checkBounds(index);
        var newWords = words.clone();
        var wordIndex = index / BITS_PER_WORD;
        var bitIndex = index % BITS_PER_WORD;
        
        newWords[wordIndex] ^= (1L << bitIndex);
        
        return new BitArray(newWords, size);
    }
    
    /**
     * Returns a new BitArray that is the bitwise AND of this and the other BitArray.
     */
    public BitArray and(BitArray other) {
        checkSameSize(other);
        var newWords = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            newWords[i] = words[i] & other.words[i];
        }
        return new BitArray(newWords, size);
    }
    
    /**
     * Returns a new BitArray that is the bitwise OR of this and the other BitArray.
     */
    public BitArray or(BitArray other) {
        checkSameSize(other);
        var newWords = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            newWords[i] = words[i] | other.words[i];
        }
        return new BitArray(newWords, size);
    }
    
    /**
     * Returns a new BitArray that is the bitwise XOR of this and the other BitArray.
     */
    public BitArray xor(BitArray other) {
        checkSameSize(other);
        var newWords = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            newWords[i] = words[i] ^ other.words[i];
        }
        return new BitArray(newWords, size);
    }
    
    /**
     * Returns a new BitArray that is the bitwise NOT of this BitArray.
     */
    public BitArray not() {
        var newWords = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            newWords[i] = ~words[i];
        }
        var result = new BitArray(newWords, size);
        result.clearUnusedBits();
        return result;
    }
    
    /**
     * Returns the number of bits set to true (population count).
     */
    public int count() {
        var count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        
        // Adjust for partial last word
        if (size % BITS_PER_WORD != 0) {
            var lastWordIndex = words.length - 1;
            var validBits = size % BITS_PER_WORD;
            var mask = (1L << validBits) - 1;
            var lastWordValidBits = words[lastWordIndex] & mask;
            count -= Long.bitCount(words[lastWordIndex]);
            count += Long.bitCount(lastWordValidBits);
        }
        
        return count;
    }
    
    /**
     * Returns a slice of this BitArray from start (inclusive) to end (exclusive).
     */
    public BitArray slice(int start, int end) {
        if (start < 0 || end > size || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice bounds: [" + start + ", " + end + ") for size " + size);
        }
        if (start > end) {
            throw new IllegalArgumentException("Start index cannot be greater than end index");
        }
        
        var sliceSize = end - start;
        var result = of(sliceSize);
        
        for (int i = 0; i < sliceSize; i++) {
            if (get(start + i)) {
                result = result.set(i, true);
            }
        }
        
        return result;
    }
    
    /**
     * Returns a new BitArray that is the concatenation of this and the other BitArray.
     */
    public BitArray concatenate(BitArray other) {
        var newSize = size + other.size;
        var result = of(newSize);
        
        // Copy this BitArray
        for (int i = 0; i < size; i++) {
            if (get(i)) {
                result = result.set(i, true);
            }
        }
        
        // Copy other BitArray
        for (int i = 0; i < other.size; i++) {
            if (other.get(i)) {
                result = result.set(size + i, true);
            }
        }
        
        return result;
    }
    
    /**
     * Returns this BitArray resized to the specified size.
     * If the new size is larger, the new bits are set to false.
     * If the new size is smaller, the BitArray is truncated.
     */
    public BitArray resize(int newSize) {
        if (newSize < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + newSize);
        }
        
        if (newSize == size) {
            return this;
        }
        
        var result = of(newSize);
        var copySize = Math.min(size, newSize);
        
        for (int i = 0; i < copySize; i++) {
            if (get(i)) {
                result = result.set(i, true);
            }
        }
        
        return result;
    }
    
    /**
     * Clears all bits (sets them to false).
     */
    public BitArray clear() {
        return of(size);
    }
    
    /**
     * Returns an array of integers representing the bits (0 for false, 1 for true).
     */
    public int[] toIntArray() {
        var result = new int[size];
        for (int i = 0; i < size; i++) {
            result[i] = get(i) ? 1 : 0;
        }
        return result;
    }
    
    /**
     * Returns a binary string representation of this BitArray.
     */
    public String toBinaryString() {
        var sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append(get(i) ? '1' : '0');
        }
        return sb.toString();
    }
    
    /**
     * Returns a Stream of Boolean values representing the bits.
     */
    public Stream<Boolean> stream() {
        return IntStream.range(0, size)
            .mapToObj(this::get);
    }
    
    @Override
    public Iterator<Boolean> iterator() {
        return new Iterator<>() {
            private int index = 0;
            
            @Override
            public boolean hasNext() {
                return index < size;
            }
            
            @Override
            public Boolean next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return get(index++);
            }
        };
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        var other = (BitArray) obj;
        if (size != other.size) return false;
        
        // Compare words, but only the valid bits in the last word
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i] != other.words[i]) {
                return false;
            }
        }
        
        // Handle last word specially if it exists
        if (words.length > 0) {
            var lastIndex = words.length - 1;
            var validBits = size % BITS_PER_WORD;
            if (validBits == 0) validBits = BITS_PER_WORD;
            
            var mask = (1L << validBits) - 1;
            return (words[lastIndex] & mask) == (other.words[lastIndex] & mask);
        }
        
        return true;
    }
    
    @Override
    public int hashCode() {
        var result = Objects.hash(size);
        for (int i = 0; i < words.length - 1; i++) {
            result = 31 * result + Long.hashCode(words[i]);
        }
        
        // Handle last word specially if it exists
        if (words.length > 0) {
            var lastIndex = words.length - 1;
            var validBits = size % BITS_PER_WORD;
            if (validBits == 0) validBits = BITS_PER_WORD;
            
            var mask = (1L << validBits) - 1;
            result = 31 * result + Long.hashCode(words[lastIndex] & mask);
        }
        
        return result;
    }
    
    @Override
    public String toString() {
        return "BitArray{" + toBinaryString() + "}";
    }
    
    // Private helper methods
    
    private void checkBounds(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        }
    }
    
    private void checkSameSize(BitArray other) {
        if (size != other.size) {
            throw new IllegalArgumentException("BitArrays must have same size: " + size + " vs " + other.size);
        }
    }
    
    private void clearUnusedBits() {
        if (words.length > 0 && size % BITS_PER_WORD != 0) {
            var lastWordIndex = words.length - 1;
            var validBits = size % BITS_PER_WORD;
            var mask = (1L << validBits) - 1;
            words[lastWordIndex] &= mask;
        }
    }
}