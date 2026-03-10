/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.core.EthClient;
import io.jeth.model.EthModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;

/**
 * Tests for new EthClient methods: sendTransaction, getAccounts, getNetwork,
 * getLogs auto-chunking, MAX_LOG_CHUNK constant.
 */
class EthClientNewMethodsTest {

    // ─── MAX_LOG_CHUNK constant ───────────────────────────────────────────────

    @Test @DisplayName("MAX_LOG_CHUNK is 2000 (reasonable default)")
    void max_log_chunk_value() {
        assertEquals(2_000, EthClient.MAX_LOG_CHUNK);
    }

    // ─── getLogs auto-chunking ────────────────────────────────────────────────

    @Test @DisplayName("getLogs with range <= MAX_LOG_CHUNK makes ONE RPC call")
    void get_logs_no_chunk_needed() throws Exception {
        // Range of 1000 blocks — fits in one chunk
        String from = "0x" + Long.toHexString(18_000_000L);
        String to   = "0x" + Long.toHexString(18_001_000L);

        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");  // empty log array
            var client = rpc.client();
            var logs = client.getLogs(from, to, null, null).join();
            assertTrue(logs.isEmpty());
            assertEquals(1, rpc.requestCount(), "Only 1 RPC call expected for small range");
        }
    }

    @Test @DisplayName("getLogs with range > MAX_LOG_CHUNK makes multiple RPC calls (chunking)")
    void get_logs_chunks_large_range() throws Exception {
        // Range spans 4001 blocks → 3 chunks: [0..1999], [2000..3999], [4000..4000]
        long start = 0;
        long end   = 4_000;

        try (var rpc = new RpcMock()) {
            // Enqueue 3 responses (one per chunk)
            rpc.enqueueJson("[{\"address\":\"0xA\",\"topics\":[],\"data\":\"0x\",\"blockNumber\":\"0x1\","
                + "\"transactionHash\":\"0x1\",\"transactionIndex\":\"0x0\","
                + "\"blockHash\":\"0x1\",\"logIndex\":\"0x0\",\"removed\":false}]");
            rpc.enqueueJson("[]");
            rpc.enqueueJson("[]");

            var client = rpc.client();
            var logs = client.getLogs(
                "0x" + Long.toHexString(start),
                "0x" + Long.toHexString(end),
                null, null).join();

            assertEquals(1, logs.size(), "Should have merged 1 log from first chunk");
            assertEquals(3, rpc.requestCount(), "Should have made 3 chunked RPC calls");
        }
    }

    @Test @DisplayName("getLogs with range exactly MAX_LOG_CHUNK makes 1 call")
    void get_logs_exactly_chunk_size() throws Exception {
        long from = 18_000_000L;
        long to   = from + EthClient.MAX_LOG_CHUNK - 1;

        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var client = rpc.client();
            client.getLogs("0x" + Long.toHexString(from), "0x" + Long.toHexString(to), null, null).join();
            assertEquals(1, rpc.requestCount(), "Exactly MAX_LOG_CHUNK range should be 1 call");
        }
    }

    @Test @DisplayName("getLogs with 'latest' toBlock does NOT chunk (string block tag)")
    void get_logs_latest_no_chunk() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var client = rpc.client();
            // "latest" is not a hex number — chunking should not kick in
            client.getLogs("0x" + Long.toHexString(1L), "latest", null, null).join();
            assertEquals(1, rpc.requestCount(), "'latest' should not trigger chunking");
        }
    }

    @Test @DisplayName("getLogsRaw bypasses chunking for large ranges")
    void get_logs_raw_no_chunk() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var client = rpc.client();
            var filter = new LinkedHashMap<String, Object>();
            filter.put("fromBlock", "0x0");
            filter.put("toBlock",   "0x" + Long.toHexString(100_000L));
            client.getLogsRaw(filter).join();
            assertEquals(1, rpc.requestCount(), "getLogsRaw must never chunk");
        }
    }

    @Test @DisplayName("getLogs merges results from all chunks in order")
    void get_logs_merge_order() throws Exception {
        try (var rpc = new RpcMock()) {
            // Chunk 1: log at block 1
            rpc.enqueueJson("[{\"address\":\"0xA\",\"topics\":[],\"data\":\"0x\",\"blockNumber\":\"0x1\","
                + "\"transactionHash\":\"0xaa\",\"transactionIndex\":\"0x0\","
                + "\"blockHash\":\"0xb1\",\"logIndex\":\"0x0\",\"removed\":false}]");
            // Chunk 2: log at block 2001
            rpc.enqueueJson("[{\"address\":\"0xB\",\"topics\":[],\"data\":\"0x\",\"blockNumber\":\"0x7D1\","
                + "\"transactionHash\":\"0xbb\",\"transactionIndex\":\"0x0\","
                + "\"blockHash\":\"0xb2\",\"logIndex\":\"0x0\",\"removed\":false}]");

            var client = rpc.client();
            var logs = client.getLogs("0x0", "0x" + Long.toHexString(2001L), null, null).join();

            assertEquals(2, logs.size());
            assertEquals("0xA", logs.get(0).address);
            assertEquals("0xB", logs.get(1).address);
        }
    }

    // ─── getAccounts ──────────────────────────────────────────────────────────

    @Test @DisplayName("getAccounts() parses an array of addresses")
    void get_accounts_parses() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\","
                          + "\"0x70997970C51812dc3A010C7d01b50e0d17dc79C8\"]");
            var accounts = rpc.client().getAccounts().join();
            assertEquals(2, accounts.size());
            assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", accounts.get(0));
            assertEquals("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", accounts.get(1));
        }
    }

    @Test @DisplayName("getAccounts() returns empty list for production nodes")
    void get_accounts_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var accounts = rpc.client().getAccounts().join();
            assertTrue(accounts.isEmpty());
        }
    }

    @Test @DisplayName("getAccounts() sends eth_accounts method")
    void get_accounts_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            rpc.client().getAccounts().join();
            var req = rpc.takeRequest();
            assertTrue(req.getBody().readUtf8().contains("eth_accounts"));
        }
    }

    // ─── getNetwork ───────────────────────────────────────────────────────────

    @Test @DisplayName("getNetwork() returns NetworkInfo with correct chainId")
    void get_network_chain_id() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);  // chainId = 1
            var network = rpc.client().getNetwork().join();
            assertEquals(1L, network.chainId());
        }
    }

    @Test @DisplayName("getNetwork() resolves known chain name for mainnet")
    void get_network_name_mainnet() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);
            var network = rpc.client().getNetwork().join();
            assertEquals("Ethereum", network.name());
        }
    }

    @Test @DisplayName("getNetwork() returns 'unknown' for unregistered chain")
    void get_network_unknown() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(99999L);
            var network = rpc.client().getNetwork().join();
            assertEquals("unknown", network.name());
        }
    }

    @Test @DisplayName("NetworkInfo.toString() is human-readable")
    void network_to_string() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(8453L);  // Base
            var network = rpc.client().getNetwork().join();
            String s = network.toString();
            assertTrue(s.contains("8453"), "Should contain chainId: " + s);
        }
    }

    // ─── sendTransaction ─────────────────────────────────────────────────────

    @Test @DisplayName("sendTransaction() sends eth_sendTransaction and returns tx hash")
    void send_transaction_returns_hash() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
            var req = EthModels.CallRequest.builder()
                    .from("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                    .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                    .build();
            String hash = rpc.client().sendTransaction(req).join();
            assertNotNull(hash);
            assertTrue(hash.startsWith("0x"), "Hash must start with 0x");
        }
    }

    @Test @DisplayName("sendTransaction() sends eth_sendTransaction method name")
    void send_transaction_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0xabc");
            var req = EthModels.CallRequest.builder()
                    .from("0xSender").to("0xTo").build();
            rpc.client().sendTransaction(req).join();
            var recorded = rpc.takeRequest();
            assertTrue(recorded.getBody().readUtf8().contains("eth_sendTransaction"));
        }
    }
}
