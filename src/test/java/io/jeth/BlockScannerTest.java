/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.event.EventDef;
import io.jeth.scan.BlockScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockScanner tests — covers scan, collect, count, scanMulti, progress,
 * cursor/resume, early exit, chunking, and edge cases.
 */
class BlockScannerTest {

    // Standard ERC-20 Transfer event
    static final EventDef TRANSFER = EventDef.of("Transfer",
        EventDef.indexed("from",  "address"),
        EventDef.indexed("to",    "address"),
        EventDef.data("value",    "uint256"));

    static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Build a JSON string for a single Transfer log */
    static String transferLog(String from, String to, long value, long blockNumber) {
        String topic0 = TRANSFER.topic0Hex();
        String fromTopic = "0x000000000000000000000000" + from.substring(2).toLowerCase();
        String toTopic   = "0x000000000000000000000000" + to.substring(2).toLowerCase();
        String data      = "0x" + String.format("%064x", value);
        return "{\"address\":\"" + USDC + "\","
            + "\"topics\":[\"" + topic0 + "\",\"" + fromTopic + "\",\"" + toTopic + "\"],"
            + "\"data\":\"" + data + "\","
            + "\"blockNumber\":\"0x" + Long.toHexString(blockNumber) + "\","
            + "\"transactionHash\":\"0x" + String.format("%064x", blockNumber) + "\","
            + "\"transactionIndex\":\"0x0\","
            + "\"blockHash\":\"0x" + String.format("%064x", blockNumber + 1) + "\","
            + "\"logIndex\":\"0x0\","
            + "\"removed\":false}";
    }

    static final String FROM = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String TO   = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    // ─── Basic scan ───────────────────────────────────────────────────────────

    @Test @DisplayName("scan() returns COMPLETE for empty block range")
    void scan_empty_range() throws Exception {
        try (var rpc = new RpcMock()) {
            var scanner = BlockScanner.of(rpc.client());
            var result = scanner.scan(USDC, TRANSFER, 100L, 50L, // toBlock < fromBlock
                (events, p) -> true).join();
            assertEquals(BlockScanner.ScanResult.Reason.COMPLETE, result.reason());
            assertEquals(0, result.totalEvents());
            assertEquals(0, rpc.requestCount(), "Zero blocks = zero RPC calls");
        }
    }

    @Test @DisplayName("scan() with no matching events returns 0 events, COMPLETE")
    void scan_no_events() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]"); // empty logs
            var scanner = BlockScanner.of(rpc.client(), 2000);
            var result = scanner.scan(USDC, TRANSFER, 0L, 100L,
                (events, p) -> { assertTrue(events.isEmpty()); return true; }).join();
            assertEquals(0, result.totalEvents());
            assertEquals(BlockScanner.ScanResult.Reason.COMPLETE, result.reason());
        }
    }

    @Test @DisplayName("scan() decodes and delivers Transfer events to handler")
    void scan_delivers_events() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[" + transferLog(FROM, TO, 1_000_000L, 100L) + "]");

            List<EventDef.DecodedEvent> received = new ArrayList<>();
            var scanner = BlockScanner.of(rpc.client(), 2000);
            var result = scanner.scan(USDC, TRANSFER, 0L, 100L, (events, p) -> {
                received.addAll(events);
                return true;
            }).join();

            assertEquals(1, received.size());
            assertEquals(1, result.totalEvents());
            assertEquals(BlockScanner.ScanResult.Reason.COMPLETE, result.reason());
        }
    }

    @Test @DisplayName("scan() aggregates events across multiple chunks")
    void scan_multiple_chunks() throws Exception {
        // Range 0..5999 with chunkSize=2000 → 3 chunks
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[" + transferLog(FROM, TO, 100L, 500L) + "]");   // chunk 0-1999
            rpc.enqueueJson("[" + transferLog(FROM, TO, 200L, 2500L) + "]");  // chunk 2000-3999
            rpc.enqueueJson("[]");                                              // chunk 4000-5999

            var scanner = BlockScanner.of(rpc.client(), 2000);
            var result  = scanner.scan(USDC, TRANSFER, 0L, 5999L, (e, p) -> true).join();

            assertEquals(2, result.totalEvents());
            assertEquals(3, rpc.requestCount(), "3 chunks = 3 RPC calls");
        }
    }

    // ─── Early exit ───────────────────────────────────────────────────────────

    @Test @DisplayName("scan() stops early when handler returns false")
    void scan_early_exit() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[" + transferLog(FROM, TO, 100L, 100L) + "]");
            rpc.enqueueJson("[" + transferLog(FROM, TO, 200L, 2100L) + "]");

            AtomicInteger calls = new AtomicInteger(0);
            var scanner = BlockScanner.of(rpc.client(), 2000);
            var result  = scanner.scan(USDC, TRANSFER, 0L, 5999L, (e, p) -> {
                calls.incrementAndGet();
                return false; // stop after first chunk
            }).join();

            assertEquals(BlockScanner.ScanResult.Reason.EARLY_EXIT, result.reason());
            assertEquals(1, calls.get(), "Handler should only be called once");
        }
    }

    // ─── collect() ────────────────────────────────────────────────────────────

    @Test @DisplayName("collect() returns all events as a list")
    void collect_returns_list() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("["
                + transferLog(FROM, TO, 100L, 100L) + ","
                + transferLog(FROM, TO, 200L, 200L) + "]");

            var scanner = BlockScanner.of(rpc.client(), 2000);
            List<EventDef.DecodedEvent> all = scanner.collect(USDC, TRANSFER, 0L, 500L).join();
            assertEquals(2, all.size());
        }
    }

    @Test @DisplayName("collect() with no events returns empty list")
    void collect_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var scanner = BlockScanner.of(rpc.client(), 2000);
            assertTrue(scanner.collect(USDC, TRANSFER, 0L, 100L).join().isEmpty());
        }
    }

    // ─── count() ──────────────────────────────────────────────────────────────

    @Test @DisplayName("count() returns total number of events")
    void count_events() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("["
                + transferLog(FROM, TO, 1L, 10L) + ","
                + transferLog(FROM, TO, 2L, 20L) + ","
                + transferLog(FROM, TO, 3L, 30L) + "]");

            var scanner = BlockScanner.of(rpc.client(), 2000);
            long count = scanner.count(USDC, TRANSFER, 0L, 100L).join();
            assertEquals(3L, count);
        }
    }

    @Test @DisplayName("count() with no events returns 0")
    void count_zero() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            assertEquals(0L, BlockScanner.of(rpc.client()).count(USDC, TRANSFER, 0L, 100L).join());
        }
    }

    // ─── ScanProgress ─────────────────────────────────────────────────────────

    @Test @DisplayName("ScanProgress percentDone increases chunk to chunk")
    void progress_increases() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            rpc.enqueueJson("[]");

            List<Double> pcts = new ArrayList<>();
            var scanner = BlockScanner.of(rpc.client(), 2000);
            scanner.scan(USDC, TRANSFER, 0L, 3999L, (e, p) -> {
                pcts.add(p.percentDone());
                return true;
            }).join();

            assertEquals(2, pcts.size());
            assertTrue(pcts.get(0) < pcts.get(1), "Progress should increase");
            assertEquals(100.0, pcts.get(1), 0.1, "Last chunk should be 100%");
        }
    }

    @Test @DisplayName("ScanProgress contains correct block range fields")
    void progress_block_range() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");

            var scanner = BlockScanner.of(rpc.client(), 2000);
            scanner.scan(USDC, TRANSFER, 500L, 600L, (e, p) -> {
                assertEquals(500L, p.chunkFrom());
                assertEquals(600L, p.chunkTo());
                assertEquals(600L, p.targetBlock());
                return true;
            }).join();
        }
    }

    @Test @DisplayName("ScanProgress.cursor() returns next block after chunk")
    void progress_cursor() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");

            var scanner = BlockScanner.of(rpc.client(), 2000);
            scanner.scan(USDC, TRANSFER, 0L, 100L, (e, p) -> {
                assertEquals(101L, p.cursor().nextBlock());
                return true;
            }).join();
        }
    }

    @Test @DisplayName("ScanProgress.toString() is human-readable")
    void progress_to_string() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var scanner = BlockScanner.of(rpc.client(), 2000);
            scanner.scan(USDC, TRANSFER, 0L, 100L, (e, p) -> {
                assertTrue(p.toString().contains("%"), p.toString());
                return true;
            }).join();
        }
    }

    // ─── ScanCursor (resume) ──────────────────────────────────────────────────

    @Test @DisplayName("scan() with cursor starts from cursor.nextBlock()")
    void scan_with_cursor() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[" + transferLog(FROM, TO, 5L, 5000L) + "]");

            var scanner = BlockScanner.of(rpc.client(), 2000);
            var cursor  = BlockScanner.ScanCursor.at(5000L);
            var result  = scanner.scan(USDC, TRANSFER, cursor, 6000L, (e, p) -> true).join();
            assertEquals(1, result.totalEvents());
        }
    }

    @Test @DisplayName("ScanResult.cursor() = lastBlock + 1")
    void result_cursor() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var result = BlockScanner.of(rpc.client(), 2000)
                    .scan(USDC, TRANSFER, 0L, 999L, (e, p) -> true).join();
            assertEquals(1000L, result.cursor().nextBlock());
        }
    }

    // ─── ScanResult ───────────────────────────────────────────────────────────

    @Test @DisplayName("ScanResult.isComplete() true when fully scanned")
    void result_is_complete() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var result = BlockScanner.of(rpc.client(), 2000)
                    .scan(USDC, TRANSFER, 0L, 100L, (e, p) -> true).join();
            assertTrue(result.isComplete());
            assertFalse(result.isEarlyExit());
        }
    }

    @Test @DisplayName("ScanResult.isEarlyExit() true when handler returned false")
    void result_is_early_exit() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var result = BlockScanner.of(rpc.client(), 2000)
                    .scan(USDC, TRANSFER, 0L, 100L, (e, p) -> false).join();
            assertTrue(result.isEarlyExit());
            assertFalse(result.isComplete());
        }
    }

    @Test @DisplayName("ScanResult.toString() is human-readable")
    void result_to_string() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var result = BlockScanner.of(rpc.client(), 2000)
                    .scan(USDC, TRANSFER, 0L, 100L, (e, p) -> true).join();
            assertTrue(result.toString().contains("events") || result.toString().contains("COMPLETE"),
                result.toString());
        }
    }

    // ─── BlockScanner.of() variants ───────────────────────────────────────────

    @Test @DisplayName("BlockScanner.of(client) uses default chunk size 2000")
    void default_chunk_size() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var scanner = BlockScanner.of(rpc.client());  // default 2000
            scanner.scan(USDC, TRANSFER, 0L, 1999L, (e, p) -> true).join();
            assertEquals(1, rpc.requestCount(), "Range <= 2000 should be 1 call");
        }
    }

    @Test @DisplayName("BlockScanner.of(client, 500) uses custom chunk size")
    void custom_chunk_size() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            rpc.enqueueJson("[]");
            rpc.enqueueJson("[]");
            var scanner = BlockScanner.of(rpc.client(), 500);
            scanner.scan(USDC, TRANSFER, 0L, 1499L, (e, p) -> true).join();
            assertEquals(3, rpc.requestCount(), "1500 blocks / 500 chunk = 3 calls");
        }
    }

    // ─── scanMulti() ─────────────────────────────────────────────────────────

    @Test @DisplayName("scanMulti() with empty contracts/events returns COMPLETE immediately")
    void scan_multi_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            var result = BlockScanner.of(rpc.client())
                    .scanMulti(List.of(), List.of(), 0L, 100L, (e, p) -> true).join();
            assertEquals(BlockScanner.ScanResult.Reason.COMPLETE, result.reason());
            assertEquals(0, rpc.requestCount());
        }
    }

    @Test @DisplayName("scanMulti() merges events from multiple contracts")
    void scan_multi_merges() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[" + transferLog(FROM, TO, 1L, 10L) + "]");

            var result = BlockScanner.of(rpc.client(), 2000)
                    .scanMulti(
                        List.of(USDC, "0xDAI"),
                        List.of(TRANSFER),
                        0L, 100L, (e, p) -> true).join();

            assertEquals(1, result.totalEvents());
        }
    }
}
