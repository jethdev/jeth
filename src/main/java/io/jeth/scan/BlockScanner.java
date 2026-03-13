/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.scan;

import io.jeth.core.EthClient;
import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Typed event block scanner — iterate historical events across arbitrary block ranges with progress
 * reporting, early exit, and resumable cursors.
 *
 * <p>Handles chunking, progress callbacks, concurrent fetching, and resumable cursors so you don't
 * have to hand-roll getLogs loops.
 *
 * <pre>
 * var scanner = BlockScanner.of(client);
 *
 * // Scan all USDC Transfer events from deployment to now
 * scanner.scan(
 *     "0xUSDC", EventDef.ERC20_TRANSFER,
 *     6_082_465L, 19_000_000L,
 *     (events, progress) -&gt; {
 *         for (var e : events) {
 *             System.out.println(e.address("from") + " → " + e.address("to"));
 *         }
 *         System.out.println(progress); // ScanProgress{5%, 250_000 blocks done}
 *     }
 * ).join();
 *
 * // Resume a previous scan from a saved cursor
 * ScanCursor cursor = ScanCursor.at(12_500_000L); // resume from block 12.5M
 * scanner.scan("0xUSDC", EventDef.ERC20_TRANSFER, cursor, 19_000_000L, handler).join();
 *
 * // Scan multiple event types at once
 * scanner.scanMulti(
 *     List.of("0xUSDC", "0xDAI"),
 *     List.of(EventDef.ERC20_TRANSFER, EventDef.ERC20_APPROVAL),
 *     fromBlock, toBlock, handler
 * ).join();
 *
 * // Early exit: return false from handler to stop
 * scanner.scan("0xUSDC", Transfer, from, to, (events, progress) -&gt; {
 *     if (foundWhatINeed) return false; // stop scanning
 *     return true;
 * }).join();
 * </pre>
 */
public class BlockScanner {

    private static final Logger LOG = Logger.getLogger(BlockScanner.class.getName());

    private static final int DEFAULT_CHUNK_SIZE = 2_000;

    private final EthClient client;
    private final int chunkSize;

    private BlockScanner(EthClient client, int chunkSize) {
        this.client = client;
        this.chunkSize = chunkSize;
    }

    /**
     * Creates a scanner with default settings: 2000-block chunks, sequential (no concurrency). This
     * is the right starting point for most use cases.
     */
    public static BlockScanner of(EthClient client) {
        return new BlockScanner(client, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Creates a scanner with a custom chunk size.
     *
     * <p>Smaller chunks (e.g. 500) are safer for slow or rate-limited nodes. Larger chunks (e.g.
     * 5000) reduce the number of RPC calls but may hit node limits. The Ethereum JSON-RPC default
     * cap is typically 2000 blocks per {@code eth_getLogs}.
     *
     * @param chunkSize number of blocks per {@code eth_getLogs} call
     */
    public static BlockScanner of(EthClient client, int chunkSize) {
        return new BlockScanner(client, chunkSize);
    }

    /**
     * Creates a scanner with custom chunk size and concurrency.
     *
     * <p><strong>Note:</strong> concurrent chunk fetching is not yet implemented; the {@code
     * concurrency} parameter is reserved for a future release. Scanning is always sequential
     * regardless of the value set here.
     *
     * @param chunkSize blocks per chunk
     * @param concurrency reserved — has no effect in the current implementation
     * @deprecated Use {@link #of(EthClient, int)} until concurrent fetching is implemented
     */
    @Deprecated
    @SuppressWarnings("unused")
    public static BlockScanner of(EthClient client, int chunkSize, int concurrency) {
        return new BlockScanner(client, chunkSize);
    }

    // ─── Single contract, single event ───────────────────────────────────────

    /**
     * Scan a single contract for a single event type across a block range.
     *
     * @param contractAddress the contract to scan
     * @param event the event definition to filter for
     * @param fromBlock first block (inclusive)
     * @param toBlock last block (inclusive)
     * @param handler called for each batch of decoded events; return false to stop early
     * @return future that completes when scanning is done (or handler returned false)
     */
    public CompletableFuture<ScanResult> scan(
            String contractAddress,
            EventDef event,
            long fromBlock,
            long toBlock,
            ScanHandler handler) {
        return scan(contractAddress, event, ScanCursor.at(fromBlock), toBlock, handler);
    }

    /** Scan with a resumable cursor (pick up where a previous scan left off). */
    public CompletableFuture<ScanResult> scan(
            String contractAddress,
            EventDef event,
            ScanCursor cursor,
            long toBlock,
            ScanHandler handler) {
        long startBlock = cursor.nextBlock();
        long totalBlocks = toBlock - startBlock + 1;
        if (totalBlocks <= 0)
            return CompletableFuture.completedFuture(
                    new ScanResult(0, 0, startBlock, ScanResult.Reason.COMPLETE));

        return CompletableFuture.supplyAsync(
                () -> {
                    long processed = 0;
                    long eventsFound = 0;
                    long current = startBlock;

                    while (current <= toBlock) {
                        long chunkEnd = Math.min(current + chunkSize - 1, toBlock);

                        // Fetch and decode this chunk
                        List<EventDef.DecodedEvent> decoded;
                        try {
                            var filter = buildFilter(contractAddress, event, current, chunkEnd);
                            List<EthModels.Log> logs = client.getLogs(filter).join();
                            decoded = event.decodeAll(logs);
                        } catch (Exception e) {
                            throw new ScanException(
                                    "Failed to fetch logs for blocks " + current + "-" + chunkEnd,
                                    e);
                        }

                        processed += (chunkEnd - current + 1);
                        eventsFound += decoded.size();

                        double pct = (double) processed / totalBlocks * 100;
                        ScanProgress progress =
                                new ScanProgress(
                                        current,
                                        chunkEnd,
                                        toBlock,
                                        processed,
                                        totalBlocks,
                                        pct,
                                        eventsFound);

                        boolean cont = handler.onBatch(decoded, progress);
                        if (!cont)
                            return new ScanResult(
                                    eventsFound, processed, chunkEnd, ScanResult.Reason.EARLY_EXIT);

                        current = chunkEnd + 1;
                    }

                    return new ScanResult(
                            eventsFound, processed, toBlock, ScanResult.Reason.COMPLETE);
                });
    }

    // ─── Multiple contracts + events ─────────────────────────────────────────

    /**
     * Scan multiple contracts and event types simultaneously in each chunk. All matching events
     * across all contracts are returned in each batch, merged and sorted by block.
     *
     * @param contracts list of contract addresses
     * @param events list of event definitions (matched against all contracts)
     * @param fromBlock first block
     * @param toBlock last block
     * @param handler receives merged, block-sorted batches
     */
    public CompletableFuture<ScanResult> scanMulti(
            List<String> contracts,
            List<EventDef> events,
            long fromBlock,
            long toBlock,
            ScanHandler handler) {

        if (contracts.isEmpty() || events.isEmpty())
            return CompletableFuture.completedFuture(
                    new ScanResult(0, 0, fromBlock, ScanResult.Reason.COMPLETE));

        long totalBlocks = toBlock - fromBlock + 1;

        return CompletableFuture.supplyAsync(
                () -> {
                    long processed = 0;
                    long eventsFound = 0;
                    long current = fromBlock;

                    while (current <= toBlock) {
                        long chunkEnd = Math.min(current + chunkSize - 1, toBlock);

                        // Build one filter per (contract × event) pair — or merge topics
                        List<EventDef.DecodedEvent> allDecoded = new ArrayList<>();
                        try {
                            // Fetch logs once with merged topic0 list
                            List<String> topic0s =
                                    events.stream().map(EventDef::topic0Hex).toList();
                            var filter = new LinkedHashMap<String, Object>();
                            filter.put("address", contracts);
                            filter.put("topics", List.of(topic0s));
                            filter.put("fromBlock", "0x" + Long.toHexString(current));
                            filter.put("toBlock", "0x" + Long.toHexString(chunkEnd));

                            List<EthModels.Log> logs = client.getLogsRaw(filter).join();

                            // Decode each log against the matching event
                            for (EthModels.Log log : logs) {
                                if (log.topics == null || log.topics.isEmpty()) continue;
                                String logTopic0 = log.topics.getFirst();
                                for (EventDef ev : events) {
                                    if (ev.topic0Hex().equalsIgnoreCase(logTopic0)) {
                                        try {
                                            allDecoded.add(ev.decode(log));
                                        } catch (Exception e) {
                                            LOG.warning(
                                                    "Skipping undecoded log: " + e.getMessage());
                                        }
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            throw new ScanException(
                                    "Failed to fetch logs for block chunk starting at " + current,
                                    e);
                        }

                        processed += (chunkEnd - current + 1);
                        eventsFound += allDecoded.size();

                        double pct = (double) processed / totalBlocks * 100;
                        ScanProgress progress =
                                new ScanProgress(
                                        current,
                                        chunkEnd,
                                        toBlock,
                                        processed,
                                        totalBlocks,
                                        pct,
                                        eventsFound);

                        boolean cont = handler.onBatch(allDecoded, progress);
                        if (!cont)
                            return new ScanResult(
                                    eventsFound, processed, chunkEnd, ScanResult.Reason.EARLY_EXIT);

                        current = chunkEnd + 1;
                    }

                    return new ScanResult(
                            eventsFound, processed, toBlock, ScanResult.Reason.COMPLETE);
                });
    }

    // ─── Collect variant (returns all events in memory) ──────────────────────

    /**
     * Scan and collect ALL matching events into a list. Only suitable for small ranges — can OOM on
     * very large scans.
     *
     * @return list of all decoded events in block order
     */
    public CompletableFuture<List<EventDef.DecodedEvent>> collect(
            String contractAddress, EventDef event, long fromBlock, long toBlock) {
        List<EventDef.DecodedEvent> all = Collections.synchronizedList(new ArrayList<>());
        return scan(
                        contractAddress,
                        event,
                        fromBlock,
                        toBlock,
                        (batch, __) -> {
                            all.addAll(batch);
                            return true;
                        })
                .thenApply(__ -> all);
    }

    // ─── Count variant ────────────────────────────────────────────────────────

    /**
     * Count matching events in a block range without materializing them. More memory-efficient than
     * {@link #collect} for analytics use cases.
     */
    public CompletableFuture<Long> count(
            String contractAddress, EventDef event, long fromBlock, long toBlock) {
        return scan(contractAddress, event, fromBlock, toBlock, (batch, __) -> true)
                .thenApply(ScanResult::totalEvents);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> buildFilter(
            String address, EventDef event, long from, long to) {
        var filter = new LinkedHashMap<String, Object>();
        filter.put("address", address);
        filter.put("topics", List.of(event.topic0Hex()));
        filter.put("fromBlock", "0x" + Long.toHexString(from));
        filter.put("toBlock", "0x" + Long.toHexString(to));
        return filter;
    }

    // ─── Types ───────────────────────────────────────────────────────────────

    /** Callback invoked for each chunk of decoded events. */
    @FunctionalInterface
    public interface ScanHandler {
        /**
         * @param events decoded events in this batch
         * @param progress current scan progress
         * @return {@code true} to continue scanning, {@code false} to stop early
         */
        boolean onBatch(List<EventDef.DecodedEvent> events, ScanProgress progress);
    }

    /**
     * Progress snapshot for a single chunk.
     *
     * @param chunkFrom first block of this chunk
     * @param chunkTo last block of this chunk
     * @param targetBlock final block of the overall scan
     * @param blocksScanned total blocks scanned so far
     * @param totalBlocks total blocks in the scan range
     * @param percentDone 0.0–100.0
     * @param eventsFound total events found so far across all chunks
     */
    public record ScanProgress(
            long chunkFrom,
            long chunkTo,
            long targetBlock,
            long blocksScanned,
            long totalBlocks,
            double percentDone,
            long eventsFound) {

        /** Cursor to resume from the next block after this chunk. */
        @SuppressWarnings("unused")
        public ScanCursor cursor() {
            return ScanCursor.at(chunkTo + 1);
        }

        @Override
        public String toString() {
            return String.format(
                    "ScanProgress{%.1f%%, blocks=%d/%d, events=%d, chunk=%d..%d}",
                    percentDone, blocksScanned, totalBlocks, eventsFound, chunkFrom, chunkTo);
        }
    }

    /**
     * Resumable cursor — saves the next block to scan. Serialize {@link #nextBlock()} to persist
     * between runs.
     *
     * @param nextBlock the next block number to scan
     */
    public record ScanCursor(long nextBlock) {
        public static ScanCursor at(long block) {
            return new ScanCursor(block);
        }

        public static ScanCursor start() {
            return new ScanCursor(0L);
        }
    }

    /**
     * Final result of a scan.
     *
     * @param totalEvents total decoded events found
     * @param blocksScanned total blocks scanned
     * @param lastBlock last block scanned (use as resume cursor)
     * @param reason why the scan ended: COMPLETE or EARLY_EXIT
     */
    public record ScanResult(long totalEvents, long blocksScanned, long lastBlock, Reason reason) {
        @SuppressWarnings("unused")
        public boolean isComplete() {
            return reason == Reason.COMPLETE;
        }

        @SuppressWarnings("unused")
        public boolean isEarlyExit() {
            return reason == Reason.EARLY_EXIT;
        }

        /** Resume cursor for the next run. */
        @SuppressWarnings("unused")
        public ScanCursor cursor() {
            return ScanCursor.at(lastBlock + 1);
        }

        public enum Reason {
            COMPLETE,
            EARLY_EXIT
        }

        @Override
        public String toString() {
            return String.format(
                    "ScanResult{events=%d, blocks=%d, lastBlock=%d, %s}",
                    totalEvents, blocksScanned, lastBlock, reason);
        }
    }

    /** Thrown when fetching a chunk fails. Wraps the underlying RPC exception. */
    public static class ScanException extends RuntimeException {
        public ScanException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
