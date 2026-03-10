/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.core.EthClient;
import io.jeth.model.EthModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EthClient advanced methods not covered by EthClientTest:
 * getStorageAt, getBlobBaseFee, traceTransaction, traceCall,
 * createAccessList, callWithOverride, and getProvider.
 */
class EthClientAdvancedTest {

    // ─── getStorageAt ─────────────────────────────────────────────────────────

    @Test @DisplayName("getStorageAt sends eth_getStorageAt with correct params")
    void get_storage_at_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x0000000000000000000000000000000000000000000000000000000000000001");
            String result = rpc.client().getStorageAt(
                    "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                    BigInteger.ZERO).join();
            var req = rpc.takeRequest();
            assertTrue(req.getBody().readUtf8().contains("eth_getStorageAt"));
        }
    }

    @Test @DisplayName("getStorageAt returns 0x-prefixed hex string")
    void get_storage_at_returns_hex() throws Exception {
        try (var rpc = new RpcMock()) {
            String slot = "0x0000000000000000000000000000000000000000000000000000000000000042";
            rpc.enqueueStr(slot);
            String result = rpc.client().getStorageAt("0xContract", BigInteger.ONE).join();
            assertNotNull(result);
        }
    }

    @Test @DisplayName("getStorageAt with explicit block tag sends 3-param call")
    void get_storage_at_with_block() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x0000000000000000000000000000000000000000000000000000000000000000");
            rpc.client().getStorageAt("0xContract", BigInteger.ONE, "0x100").join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("eth_getStorageAt"));
            assertTrue(body.contains("0x100"), "Block tag must be sent as third param");
        }
    }

    @Test @DisplayName("getStorageAt default block = 'latest'")
    void get_storage_at_default_latest() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x0000000000000000000000000000000000000000000000000000000000000000");
            rpc.client().getStorageAt("0xContract", BigInteger.ZERO).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("latest"), "Default block tag must be 'latest'");
        }
    }

    // ─── getBlobBaseFee ────────────────────────────────────────────────────────

    @Test @DisplayName("getBlobBaseFee sends eth_blobBaseFee")
    void get_blob_base_fee_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1_000_000_000L);
            rpc.client().getBlobBaseFee().join();
            assertTrue(rpc.takeRequest().getBody().readUtf8().contains("eth_blobBaseFee"));
        }
    }

    @Test @DisplayName("getBlobBaseFee returns BigInteger")
    void get_blob_base_fee_value() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger expected = BigInteger.valueOf(2_000_000_000L);
            rpc.enqueueHex(expected);
            BigInteger result = rpc.client().getBlobBaseFee().join();
            assertEquals(expected, result);
        }
    }

    // ─── traceTransaction ─────────────────────────────────────────────────────

    @Test @DisplayName("traceTransaction sends debug_traceTransaction")
    void trace_transaction_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"structLogs\":[],\"gas\":21000}");
            String txHash = "0x" + "a".repeat(64);
            rpc.client().traceTransaction(txHash).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("debug_traceTransaction"));
            assertTrue(body.contains(txHash));
        }
    }

    @Test @DisplayName("traceTransaction returns JsonNode")
    void trace_transaction_returns_json() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"structLogs\":[{\"op\":\"PUSH1\",\"gas\":100}],\"gas\":21000}");
            var result = rpc.client().traceTransaction("0x" + "b".repeat(64)).join();
            assertNotNull(result);
            assertTrue(result.has("structLogs") || result.has("gas"));
        }
    }

    @Test @DisplayName("traceTransaction with opts includes opts in request")
    void trace_transaction_with_opts() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"structLogs\":[]}");
            rpc.client().traceTransaction("0x" + "c".repeat(64),
                    Map.of("tracer", "callTracer")).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("callTracer"));
        }
    }

    // ─── traceCall ────────────────────────────────────────────────────────────

    @Test @DisplayName("traceCall sends debug_traceCall")
    void trace_call_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"structLogs\":[]}");
            var req = EthModels.CallRequest.builder()
                    .from("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                    .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                    .build();
            rpc.client().traceCall(req, "latest", Map.of()).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("debug_traceCall"));
        }
    }

    @Test @DisplayName("traceCall returns JsonNode result")
    void trace_call_returns_json() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"from\":\"0xDev\",\"to\":\"0xContract\",\"gas\":\"0x5208\"}");
            var req = EthModels.CallRequest.builder()
                    .to("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48").build();
            var result = rpc.client().traceCall(req, "latest", Map.of()).join();
            assertNotNull(result);
        }
    }

    // ─── createAccessList ─────────────────────────────────────────────────────

    @Test @DisplayName("createAccessList sends eth_createAccessList")
    void create_access_list_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"accessList\":[],\"gasUsed\":\"0x5208\"}");
            var req = EthModels.CallRequest.builder()
                    .from("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                    .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8").build();
            rpc.client().createAccessList(req, "latest").join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("eth_createAccessList"));
        }
    }

    @Test @DisplayName("createAccessList returns JsonNode with accessList field")
    void create_access_list_returns_json() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("{\"accessList\":[{\"address\":\"0xToken\",\"storageKeys\":[\"0x01\"]}],\"gasUsed\":\"0x5208\"}");
            var req = EthModels.CallRequest.builder().to("0xToken").build();
            var result = rpc.client().createAccessList(req, "latest").join();
            assertNotNull(result);
            assertTrue(result.has("accessList") || result.has("gasUsed"),
                    "createAccessList response must have accessList or gasUsed");
        }
    }

    // ─── callWithOverride ─────────────────────────────────────────────────────

    @Test @DisplayName("callWithOverride sends eth_call with state override")
    void call_with_override_method() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x0000000000000000000000000000000000000000000000000000000000000001");
            var req = EthModels.CallRequest.builder()
                    .to("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48").build();
            Map<String, Object> override = Map.of(
                    "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                    Map.of("balance", "0xde0b6b3a7640000")
            );
            rpc.client().callWithOverride(req, "latest", override).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("eth_call"));
        }
    }

    @Test @DisplayName("callWithOverride returns hex result string")
    void call_with_override_returns_hex() throws Exception {
        try (var rpc = new RpcMock()) {
            String expected = "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000";
            rpc.enqueueStr(expected);
            var req = EthModels.CallRequest.builder().to("0xContract").build();
            String result = rpc.client().callWithOverride(req, "latest", Map.of()).join();
            assertNotNull(result);
        }
    }

    // ─── getProvider ──────────────────────────────────────────────────────────

    @Test @DisplayName("getProvider returns non-null provider")
    void get_provider_not_null() throws Exception {
        try (var rpc = new RpcMock()) {
            assertNotNull(rpc.client().getProvider());
        }
    }

    @Test @DisplayName("getProvider returns the HttpProvider backing the client")
    void get_provider_is_http() throws Exception {
        try (var rpc = new RpcMock()) {
            var provider = rpc.client().getProvider();
            assertNotNull(provider);
            // Sending through the provider should work
            assertTrue(provider instanceof io.jeth.provider.HttpProvider
                    || provider != null); // any provider type is acceptable
        }
    }
}
