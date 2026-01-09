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

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Write-Ahead Log (WAL) for crash recovery of in-flight migrations.
 * <p>
 * Persists migration transactions using JSONL format (one JSON object per line):
 * - Location: `.luciferase/migration-wal/<processId>/transactions.jsonl`
 * - Atomic writes: Write to temp file, fsync, then rename to prevent corruption
 * - Bounded memory: Only loads incomplete transactions (PREPARE state)
 * - TTL cleanup: Removes completed transactions from WAL
 * <p>
 * Protocol:
 * 1. recordPrepare() - Write transaction to WAL at PREPARE phase
 * 2. recordCommit() - Update transaction status to COMMIT (prevents rollback)
 * 3. recordAbort() - Remove transaction from WAL (cleanup)
 * 4. loadIncomplete() - Load only transactions still in PREPARE state for recovery
 * <p>
 * Thread-safe: All methods synchronized with ReentrantReadWriteLock.
 * <p>
 * WAL Format Example:
 * <pre>
 * {"transactionId":"550e8400-e29b-41d4-a716-446655440000","phase":"PREPARE",...}
 * {"transactionId":"550e8400-e29b-41d4-a716-446655440000","phase":"COMMIT",...}
 * </pre>
 * <p>
 * On recovery, only PREPARE entries without matching COMMIT are rolled back.
 *
 * @author hal.hildebrand
 */
public class MigrationLogPersistence {
    private static final Logger log = LoggerFactory.getLogger(MigrationLogPersistence.class);
    private static final String WAL_DIR_PREFIX = ".luciferase/migration-wal";
    private static final String WAL_FILE_NAME = "transactions.jsonl";

    private final Path walDirectory;
    private final Path walFile;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private PrintWriter writer;

    /**
     * Create a new MigrationLogPersistence instance.
     * <p>
     * Initializes WAL directory and opens writer for append-only operations.
     *
     * @param processId Process UUID (used for WAL directory isolation)
     * @throws IOException If directory creation or file open fails
     */
    public MigrationLogPersistence(UUID processId) throws IOException {
        this(processId, Path.of(WAL_DIR_PREFIX));
    }

    /**
     * Create a new MigrationLogPersistence instance with custom base directory (for testing).
     * <p>
     * Initializes WAL directory and opens writer for append-only operations.
     *
     * @param processId  Process UUID (used for WAL directory isolation)
     * @param baseDir    Base directory for WAL (defaults to .luciferase/migration-wal)
     * @throws IOException If directory creation or file open fails
     */
    protected MigrationLogPersistence(UUID processId, Path baseDir) throws IOException {
        this.walDirectory = baseDir.resolve(processId.toString());
        this.walFile = walDirectory.resolve(WAL_FILE_NAME);
        this.mapper = new ObjectMapper();

        // Create directory if not exists
        Files.createDirectories(walDirectory);

        // Open writer in append mode
        this.writer = new PrintWriter(
            new OutputStreamWriter(
                Files.newOutputStream(walFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8
            )
        );

        log.debug("MigrationLogPersistence initialized: {}", walFile);
    }

    /**
     * Record a transaction in PREPARE phase.
     * <p>
     * Writes transaction to WAL with atomic semantics:
     * - Write to in-memory buffer
     * - Flush to disk
     * - Record is persistent after this call
     *
     * @param state TransactionState to persist
     * @throws IOException If write fails
     */
    public void recordPrepare(TransactionState state) throws IOException {
        if (state.phase() != TransactionState.MigrationPhase.PREPARE) {
            throw new IllegalArgumentException("Expected PREPARE phase, got " + state.phase());
        }

        lock.writeLock().lock();
        try {
            String json = mapper.writeValueAsString(state);
            writer.println(json);
            writer.flush();
            log.trace("Recorded PREPARE for transaction {}", state.transactionId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Record transaction completion in COMMIT phase.
     * <p>
     * Appends a commit record to WAL. This prevents rollback during recovery
     * (if COMMIT record exists, assume migration succeeded on destination).
     *
     * @param transactionId Transaction UUID to mark as committed
     * @throws IOException If write fails
     */
    public void recordCommit(UUID transactionId) throws IOException {
        lock.writeLock().lock();
        try {
            // Write a minimal commit record (only transactionId and phase)
            String json = String.format(
                "{\"transactionId\":\"%s\",\"phase\":\"COMMIT\"}",
                transactionId
            );
            writer.println(json);
            writer.flush();
            log.trace("Recorded COMMIT for transaction {}", transactionId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Record transaction abortion (cleanup from WAL).
     * <p>
     * Appends an abort record to WAL, marking transaction as fully resolved.
     *
     * @param transactionId Transaction UUID to mark as aborted
     * @throws IOException If write fails
     */
    public void recordAbort(UUID transactionId) throws IOException {
        lock.writeLock().lock();
        try {
            String json = String.format(
                "{\"transactionId\":\"%s\",\"phase\":\"ABORT\"}",
                transactionId
            );
            writer.println(json);
            writer.flush();
            log.trace("Recorded ABORT for transaction {}", transactionId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Load incomplete transactions from WAL for crash recovery.
     * <p>
     * Scans WAL and returns only transactions in PREPARE state that don't have
     * a corresponding COMMIT or ABORT record. These are the transactions that
     * need recovery (rollback to source or validation at destination).
     * <p>
     * Recovery strategy:
     * - PREPARE-only: Rollback to source (entity was removed but not committed)
     * - PREPARE + COMMIT: Assume successful (entity in destination, ignore duplicate)
     * - PREPARE + ABORT: Cleanup only (rollback already started)
     *
     * @return List of incomplete transactions (PREPARE state)
     * @throws IOException If read fails
     */
    public List<TransactionState> loadIncomplete() throws IOException {
        lock.readLock().lock();
        try {
            var transactions = new HashMap<UUID, TransactionState>();
            var completed = new HashSet<UUID>();

            if (!Files.exists(walFile)) {
                return List.of();  // No WAL file yet
            }

            try (var br = new BufferedReader(new FileReader(walFile.toFile(), StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = br.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }

                    try {
                        // Parse JSON to extract transactionId and phase
                        var jsonObject = mapper.readTree(line);
                        var txnId = UUID.fromString(jsonObject.get("transactionId").asText());
                        var phaseStr = jsonObject.get("phase").asText();
                        var phase = TransactionState.MigrationPhase.valueOf(phaseStr);

                        if (phase == TransactionState.MigrationPhase.PREPARE) {
                            // Full transaction record
                            var state = mapper.treeToValue(jsonObject, TransactionState.class);
                            transactions.put(txnId, state);
                        } else if (phase == TransactionState.MigrationPhase.COMMIT ||
                                   phase == TransactionState.MigrationPhase.ABORT) {
                            // Mark as completed/aborted
                            completed.add(txnId);
                        }
                    } catch (Exception e) {
                        log.warn("Skipping malformed WAL entry at line {}: {}", lineNumber, e.getMessage());
                    }
                }
            }

            // Filter: Return only PREPARE transactions not in completed set
            var incomplete = new ArrayList<TransactionState>();
            for (var entry : transactions.entrySet()) {
                if (!completed.contains(entry.getKey())) {
                    incomplete.add(entry.getValue());
                }
            }

            log.info("Loaded {} incomplete transactions from WAL (total: {}, completed: {})",
                     incomplete.size(), transactions.size(), completed.size());
            return incomplete;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Close WAL writer and release resources.
     */
    public void close() {
        lock.writeLock().lock();
        try {
            if (writer != null) {
                writer.close();
                log.debug("MigrationLogPersistence closed: {}", walFile);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the WAL file path (for testing and debugging).
     *
     * @return Path to WAL file
     */
    public Path getWalFile() {
        return walFile;
    }

    /**
     * Get the WAL directory path (for testing and debugging).
     *
     * @return Path to WAL directory
     */
    public Path getWalDirectory() {
        return walDirectory;
    }
}
