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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Shared I/O utilities for sparse voxel data structures.
 *
 * <p>This class provides common operations used by both ESVO (octree) and
 * ESVT (tetrahedral) I/O implementations:
 *
 * <ul>
 *   <li>Int array serialization/deserialization</li>
 *   <li>Metadata object serialization</li>
 *   <li>Version detection pattern</li>
 *   <li>Buffer allocation helpers</li>
 *   <li>GZIP compression utilities</li>
 * </ul>
 *
 * <p>All byte order operations use {@link ByteOrder#LITTLE_ENDIAN} for
 * cross-platform compatibility.
 *
 * @author hal.hildebrand
 */
public final class SparseVoxelIOUtils {
    private static final Logger log = LoggerFactory.getLogger(SparseVoxelIOUtils.class);

    /**
     * Standard byte order for sparse voxel file formats.
     */
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private SparseVoxelIOUtils() {
        // Utility class
    }

    // === Int Array Operations ===

    /**
     * Write an int array to a file channel.
     *
     * @param channel The channel to write to
     * @param array   The int array to write
     * @return Number of bytes written
     * @throws IOException if write fails
     */
    public static long writeIntArray(FileChannel channel, int[] array) throws IOException {
        if (array == null || array.length == 0) {
            return 0;
        }

        var buffer = ByteBuffer.allocate(array.length * 4).order(BYTE_ORDER);
        for (int value : array) {
            buffer.putInt(value);
        }
        buffer.flip();
        return channel.write(buffer);
    }

    /**
     * Read an int array from a file channel.
     *
     * @param channel The channel to read from
     * @param count   Number of ints to read
     * @return The int array, or empty array if count is 0
     * @throws IOException if read fails
     */
    public static int[] readIntArray(FileChannel channel, int count) throws IOException {
        if (count <= 0) {
            return new int[0];
        }
        if (count > Integer.MAX_VALUE / 4) {
            throw new IOException("count " + count + " would overflow buffer allocation (max " + (Integer.MAX_VALUE / 4) + ")");
        }

        long bytes = (long) count * 4;
        var buffer = ByteBuffer.allocate((int) bytes).order(BYTE_ORDER);
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer);
            if (n < 0) {
                throw new EOFException("unexpected EOF, read " + buffer.position() + " of " + bytes + " bytes");
            }
        }

        buffer.flip();
        var result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = buffer.getInt();
        }
        return result;
    }

    /**
     * Read an int array from a ByteBuffer.
     *
     * @param buffer The buffer to read from
     * @param count  Number of ints to read
     * @return The int array
     */
    public static int[] readIntArray(ByteBuffer buffer, int count) {
        if (count <= 0) {
            return new int[0];
        }

        var result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = buffer.getInt();
        }
        return result;
    }

    // === Metadata Serialization ===

    /**
     * Serialize a metadata object to a byte array.
     *
     * @param metadata The metadata object (must be Serializable)
     * @return The serialized bytes
     * @throws IOException if serialization fails
     */
    public static byte[] serializeMetadata(Object metadata) throws IOException {
        if (metadata == null) {
            return new byte[0];
        }

        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(metadata);
        }
        return baos.toByteArray();
    }

    /**
     * Deserialize a metadata object from a byte array.
     *
     * @param bytes The serialized bytes
     * @param <T>   The expected metadata type
     * @return The deserialized metadata, or null if bytes is empty
     * @throws IOException            if deserialization fails
     * @throws ClassNotFoundException if the class cannot be found
     */
    /**
     * @deprecated Calls without an expected-type filter accept arbitrary
     * deserialization which is a remote-code-execution risk on untrusted
     * input. Use {@link #deserializeMetadata(byte[], Class)} instead so the
     * input stream is constrained to the expected type plus JDK utility
     * classes.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static <T> T deserializeMetadata(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try (var bais = new ByteArrayInputStream(bytes);
             var ois = new ObjectInputStream(bais)) {
            // Best-effort filter for the deprecated path: allow only the
            // metadata types we ship + JDK utility classes. Callers wanting
            // a different type must use the type-parameterized overload
            // below so the filter matches.
            ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                "com.hellblazer.luciferase.esvo.io.ESVOMetadata;"
                + "com.hellblazer.luciferase.esvt.io.ESVTMetadata;"
                + "java.util.*;java.lang.*;java.time.*;java.math.*;"
                + "javax.vecmath.*;!*"));
            return (T) ois.readObject();
        }
    }

    /**
     * Deserialize metadata bytes into an instance of {@code expectedType},
     * with an {@link ObjectInputFilter} that rejects everything except
     * {@code expectedType} and the JDK utility/primitive types it may
     * transitively pull in. Use this whenever the metadata source is or
     * might be untrusted (file from disk, payload over the wire).
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserializeMetadata(byte[] bytes, Class<T> expectedType)
            throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (expectedType == null) {
            throw new IllegalArgumentException("expectedType cannot be null");
        }

        try (var bais = new ByteArrayInputStream(bytes);
             var ois = new ObjectInputStream(bais)) {
            ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                expectedType.getName() + ";"
                + "java.util.*;java.lang.*;java.time.*;java.math.*;"
                + "javax.vecmath.*;!*"));
            return (T) ois.readObject();
        }
    }

    /**
     * @deprecated Calls without an expected-type filter accept arbitrary
     * deserialization which is a remote-code-execution risk on untrusted
     * file inputs. Use {@link #readMetadata(FileChannel, long, long, Class)}
     * instead so the inner stream is constrained to the expected type.
     */
    @Deprecated
    public static <T> T readMetadata(FileChannel channel, long metadataOffset, long metadataSize)
            throws IOException, ClassNotFoundException {

        if (metadataSize <= 0) {
            return null;
        }

        channel.position(metadataOffset);
        var buffer = ByteBuffer.allocate((int) metadataSize);
        int bytesRead = channel.read(buffer);
        if (bytesRead != metadataSize) {
            throw new IOException("Expected " + metadataSize + " metadata bytes, read " + bytesRead);
        }

        return deserializeMetadata(buffer.array());
    }

    /**
     * Read metadata from a file channel at a specific offset, constraining
     * deserialization to {@code expectedType} via an {@link ObjectInputFilter}.
     * Use this whenever the file source is or might be untrusted.
     *
     * @param channel        The channel to read from
     * @param metadataOffset Offset where metadata starts
     * @param metadataSize   Size of metadata in bytes
     * @param expectedType   The expected metadata class (used to build the
     *                       deserialization filter)
     * @param <T>            The expected metadata type
     * @return The deserialized metadata, or null if size is 0
     * @throws IOException            if read fails
     * @throws ClassNotFoundException if the class cannot be found
     */
    public static <T> T readMetadata(FileChannel channel, long metadataOffset, long metadataSize,
                                     Class<T> expectedType)
            throws IOException, ClassNotFoundException {

        if (metadataSize <= 0) {
            return null;
        }

        channel.position(metadataOffset);
        var buffer = ByteBuffer.allocate((int) metadataSize);
        int bytesRead = channel.read(buffer);
        if (bytesRead != metadataSize) {
            throw new IOException("Expected " + metadataSize + " metadata bytes, read " + bytesRead);
        }

        return deserializeMetadata(buffer.array(), expectedType);
    }

    // === Version Detection ===

    /**
     * Detect the file format version by reading magic number and version.
     *
     * @param file        Path to the file
     * @param magicNumber Expected magic number
     * @return The version number
     * @throws IOException if file cannot be read or has invalid magic
     */
    public static int detectVersion(Path file, int magicNumber) throws IOException {
        try (var raf = new RandomAccessFile(file.toFile(), "r")) {
            // Read magic as little-endian
            byte[] magicBytes = new byte[4];
            raf.read(magicBytes);
            int magic = readLittleEndianInt(magicBytes);

            if (magic != magicNumber) {
                throw new IOException(String.format(
                        "Invalid file: bad magic number 0x%08X, expected 0x%08X",
                        magic, magicNumber));
            }

            // Read version as little-endian
            byte[] versionBytes = new byte[4];
            raf.read(versionBytes);
            return readLittleEndianInt(versionBytes);
        }
    }

    /**
     * Validate that a file has the expected magic number.
     *
     * @param file        Path to the file
     * @param magicNumber Expected magic number
     * @return true if file has valid magic number
     */
    public static boolean hasValidMagic(Path file, int magicNumber) {
        try {
            detectVersion(file, magicNumber);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // === Buffer Helpers ===

    /**
     * Create a ByteBuffer with the standard byte order.
     *
     * @param capacity Buffer capacity in bytes
     * @return A new ByteBuffer with LITTLE_ENDIAN order
     */
    public static ByteBuffer allocateBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(BYTE_ORDER);
    }

    /**
     * Create a direct ByteBuffer with the standard byte order.
     *
     * @param capacity Buffer capacity in bytes
     * @return A new direct ByteBuffer with LITTLE_ENDIAN order
     */
    public static ByteBuffer allocateDirectBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(BYTE_ORDER);
    }

    // === Compression Utilities ===

    /**
     * GZIP compress data.
     *
     * @param data The data to compress
     * @return Compressed bytes
     * @throws IOException if compression fails
     */
    public static byte[] gzipCompress(byte[] data) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        byte[] compressed = baos.toByteArray();
        log.debug("GZIP compressed {} -> {} bytes ({}% ratio)",
                data.length, compressed.length,
                String.format("%.1f", 100.0 * compressed.length / data.length));
        return compressed;
    }

    /**
     * GZIP decompress data.
     *
     * @param compressedData The compressed data
     * @return Decompressed bytes
     * @throws IOException if decompression fails
     */
    public static byte[] gzipDecompress(byte[] compressedData) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzis = new GZIPInputStream(new ByteArrayInputStream(compressedData))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        return baos.toByteArray();
    }

    // === Private Helpers ===

    private static int readLittleEndianInt(byte[] bytes) {
        return (bytes[0] & 0xFF) |
               ((bytes[1] & 0xFF) << 8) |
               ((bytes[2] & 0xFF) << 16) |
               ((bytes[3] & 0xFF) << 24);
    }
}
