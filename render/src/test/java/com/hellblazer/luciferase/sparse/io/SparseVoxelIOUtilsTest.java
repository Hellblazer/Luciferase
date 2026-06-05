/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SparseVoxelIOUtils.readIntArray(FileChannel, int).
 * Specifically exercises the fill loop, overflow guard, and EOF handling.
 */
class SparseVoxelIOUtilsTest {

    @TempDir
    Path tempDir;

    /**
     * (a) FileChannel backed by a tmp file where we write 4 ints then read
     *     them back — normal happy path, verifies round-trip correctness.
     */
    @Test
    void roundTrip_smallArray() throws IOException {
        var file = tempDir.resolve("small.bin");
        int[] expected = { 1, 2, 3, 42 };

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            SparseVoxelIOUtils.writeIntArray(ch, expected);
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int[] actual = SparseVoxelIOUtils.readIntArray(ch, expected.length);
            assertArrayEquals(expected, actual);
        }
    }

    /**
     * (a) A ShortReadFileChannel that deliberately returns fewer bytes per
     *     channel.read() call than requested — the fill loop must keep going
     *     until the buffer is full.
     */
    @Test
    void fillLoop_shortReadsAreReattempted() throws IOException {
        int[] data = { 10, 20, 30, 40, 50 };
        // Serialise data into a byte array
        var raw = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) raw.putInt(v);
        byte[] bytes = raw.array();

        // Write to a real file so we can open a real FileChannel
        var file = tempDir.resolve("short.bin");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(bytes));
        }

        // Wrap the real FileChannel in a throttled wrapper that returns at most
        // 3 bytes per read(), forcing multiple iterations of the fill loop.
        try (var real = FileChannel.open(file, StandardOpenOption.READ)) {
            var throttled = new ThrottledFileChannel(real, 3);
            int[] actual = SparseVoxelIOUtils.readIntArray(throttled, data.length);
            assertArrayEquals(data, actual,
                    "fill loop must reassemble correctly despite short reads");
        }
    }

    /**
     * (b) Premature EOF (channel returns -1 before buffer is full) must
     *     throw EOFException with position info.
     */
    @Test
    void eofBeforeComplete_throwsEOFException() throws IOException {
        // Write only 2 of the 4 required ints
        int[] partial = { 99, 100 };
        var file = tempDir.resolve("partial.bin");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            SparseVoxelIOUtils.writeIntArray(ch, partial);
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            // Ask for 4 ints but only 2 are available → EOF partway through
            var ex = assertThrows(EOFException.class,
                    () -> SparseVoxelIOUtils.readIntArray(ch, 4));
            assertTrue(ex.getMessage().contains("unexpected EOF"),
                    "message should say 'unexpected EOF', got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("8 bytes") || ex.getMessage().contains("of 16"),
                    "message should reference position/total bytes, got: " + ex.getMessage());
        }
    }

    /**
     * (c) count > Integer.MAX_VALUE/4 must fail loudly with IOException
     *     before any allocation attempt.
     */
    @Test
    void overflowGuard_rejectsCountAboveMax() {
        // Dummy channel — should never be touched
        var dummy = new NullFileChannel();
        int tooLarge = Integer.MAX_VALUE / 4 + 1; // 536_870_912

        var ex = assertThrows(IOException.class,
                () -> SparseVoxelIOUtils.readIntArray(dummy, tooLarge));
        assertTrue(ex.getMessage().contains("overflow") || ex.getMessage().contains("max"),
                "message should reference overflow, got: " + ex.getMessage());
    }

    /**
     * (d) count == 0 returns empty array without touching the channel.
     */
    @Test
    void zeroCount_returnsEmptyArray() throws IOException {
        var dummy = new NullFileChannel();
        int[] result = SparseVoxelIOUtils.readIntArray(dummy, 0);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps a FileChannel and caps each read at {@code maxBytesPerCall} bytes,
     * simulating a kernel/OS that returns short reads.
     */
    static final class ThrottledFileChannel extends FileChannel {
        private final FileChannel delegate;
        private final int maxBytesPerCall;

        ThrottledFileChannel(FileChannel delegate, int maxBytesPerCall) {
            this.delegate = delegate;
            this.maxBytesPerCall = maxBytesPerCall;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!dst.hasRemaining()) return 0;
            int limit = dst.limit();
            // temporarily cap what the delegate can fill
            int capped = Math.min(dst.position() + maxBytesPerCall, limit);
            dst.limit(capped);
            try {
                return delegate.read(dst);
            } finally {
                dst.limit(limit);
            }
        }

        // Unused overrides — delegate or throw UnsupportedOperationException
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { return delegate.truncate(size); }
        @Override public void force(boolean metaData) throws IOException { delegate.force(metaData); }
        @Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return delegate.transferFrom(src, position, count); }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return delegate.read(dst, position); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
    }

    /**
     * A FileChannel stub that should never be read — any read attempt throws.
     */
    static final class NullFileChannel extends FileChannel {
        @Override public int read(ByteBuffer dst) throws IOException { throw new IOException("NullFileChannel.read() called unexpectedly"); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public long position() { return 0; }
        @Override public FileChannel position(long newPosition) { return this; }
        @Override public long size() { return 0; }
        @Override public FileChannel truncate(long size) { return this; }
        @Override public void force(boolean metaData) { }
        @Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public int read(ByteBuffer dst, long position) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override protected void implCloseChannel() { }
    }
}
