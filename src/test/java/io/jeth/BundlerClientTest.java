/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.aa.BundlerClient;
import io.jeth.aa.UserOperation;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for BundlerClient — ERC-4337 bundler RPC client.
 *
 * <p>Regression: BundlerClient.poll() previously compared void == null (compile error). Fixed to
 * use supplyAsync + delayedExecutor. These tests verify the polling API works.
 */
class BundlerClientTest {

    static final String ENTRY_POINT = UserOperation.ENTRY_POINT_V06;

    static UserOperation minimalOp() {
        return UserOperation.builder()
                .sender("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                .nonce(BigInteger.ZERO)
                .callData("0x")
                .callGasLimit(BigInteger.valueOf(100_000))
                .verificationGasLimit(BigInteger.valueOf(150_000))
                .preVerificationGas(BigInteger.valueOf(21_000))
                .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .build();
    }

    // ─── sendUserOperation ────────────────────────────────────────────────────

    @Test
    @DisplayName("sendUserOperation returns userOpHash string from bundler")
    void send_user_operation_returns_hash() throws Exception {
        String expectedHash = "0x" + "ab".repeat(32);
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr(expectedHash);
            var client = BundlerClient.of(rpc.url());
            String result = client.sendUserOperation(minimalOp(), ENTRY_POINT).join();
            assertEquals(expectedHash, result);
        }
    }

    @Test
    @DisplayName("sendUserOperation makes exactly one RPC call")
    void send_user_operation_one_rpc_call() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x" + "ab".repeat(32));
            var client = BundlerClient.of(rpc.url());
            client.sendUserOperation(minimalOp(), ENTRY_POINT).join();
            assertEquals(1, rpc.requestCount());
        }
    }

    @Test
    @DisplayName("sendUserOperation throws on bundler RPC error")
    void send_user_operation_bundler_error() throws Exception {
        try (var rpc = new RpcMock()) {
            // Enqueue nothing — RpcMock returns error when queue is empty
            var client = BundlerClient.of(rpc.url());
            assertThrows(
                    Exception.class,
                    () -> client.sendUserOperation(minimalOp(), ENTRY_POINT).join());
        }
    }

    // ─── estimateUserOperationGas ─────────────────────────────────────────────

    @Test
    @DisplayName("estimateUserOperationGas returns GasEstimate with positive fields")
    void estimate_gas_returns_estimate() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "{\"callGasLimit\":\"0x186a0\",\"verificationGasLimit\":\"0x249f0\",\"preVerificationGas\":\"0x5208\"}");
            var client = BundlerClient.of(rpc.url());
            var est = client.estimateUserOperationGas(minimalOp(), ENTRY_POINT).join();
            assertNotNull(est);
            assertEquals(BigInteger.valueOf(0x186a0), est.callGasLimit());
            assertEquals(BigInteger.valueOf(0x249f0), est.verificationGasLimit());
            assertEquals(BigInteger.valueOf(0x5208), est.preVerificationGas());
        }
    }

    // ─── getSupportedEntryPoints ───────────────────────────────────────────────

    @Test
    @DisplayName("getSupportedEntryPoints returns list of addresses")
    void supported_entry_points() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[\"" + ENTRY_POINT + "\"]");
            var client = BundlerClient.of(rpc.url());
            var eps = client.getSupportedEntryPoints().join();
            assertNotNull(eps);
            assertEquals(1, eps.size());
            assertEquals(ENTRY_POINT, eps.get(0));
        }
    }

    @Test
    @DisplayName("getSupportedEntryPoints returns empty list when bundler supports none")
    void supported_entry_points_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson("[]");
            var client = BundlerClient.of(rpc.url());
            var eps = client.getSupportedEntryPoints().join();
            assertNotNull(eps);
            assertTrue(eps.isEmpty());
        }
    }

    // ─── getUserOperationReceipt (null until mined) ────────────────────────────

    @Test
    @DisplayName("getUserOperationReceipt returns null when not yet mined")
    void get_receipt_null_when_pending() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueNull(); // bundler returns null when not yet mined
            var client = BundlerClient.of(rpc.url());
            var result = client.getUserOperationReceipt("0x" + "ab".repeat(32)).join();
            assertNull(result);
        }
    }

    // ─── waitForUserOperationReceipt (poll fix regression) ────────────────────

    @Test
    @DisplayName("waitForUserOperationReceipt returns immediately when already mined")
    @Timeout(5)
    void wait_for_receipt_already_mined() throws Exception {
        try (var rpc = new RpcMock()) {
            // First poll: receipt available immediately
            rpc.enqueueJson("{\"transactionHash\":\"0x" + "cd".repeat(32) + "\",\"success\":true}");
            var client = BundlerClient.of(rpc.url());
            var receipt = client.waitForUserOperationReceipt("0x" + "ab".repeat(32)).join();
            assertNotNull(receipt, "Receipt should be non-null when immediately available");
        }
    }

    @Test
    @DisplayName("waitForUserOperationReceipt times out when op never mines")
    @Timeout(5)
    void wait_for_receipt_timeout() throws Exception {
        try (var rpc = new RpcMock()) {
            // Always return null (op never mined) — should timeout
            for (int i = 0; i < 20; i++) rpc.enqueueNull();
            var client = BundlerClient.of(rpc.url());
            // Use very short timeout (200ms) and poll interval (50ms)
            assertThrows(
                    Exception.class,
                    () ->
                            client.waitForUserOperationReceipt("0x" + "ab".repeat(32), 200, 50)
                                    .join(),
                    "Should throw timeout exception when op never mines");
        }
    }

    @Test
    @DisplayName("waitForUserOperationReceipt eventually resolves after pending polls")
    @Timeout(5)
    void wait_for_receipt_after_pending() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueNull(); // first poll: pending
            rpc.enqueueNull(); // second poll: still pending
            rpc.enqueueJson("{\"transactionHash\":\"0x" + "cd".repeat(32) + "\"}"); // third: mined
            var client = BundlerClient.of(rpc.url());
            var receipt =
                    client.waitForUserOperationReceipt("0x" + "ab".repeat(32), 10_000, 50).join();
            assertNotNull(receipt, "Should resolve once mined");
        }
    }

    // ─── BundlerClient.of(Provider) factory ───────────────────────────────────

    @Test
    @DisplayName("BundlerClient.of(url) creates a client")
    void of_url_factory() throws Exception {
        try (var rpc = new RpcMock()) {
            var client = BundlerClient.of(rpc.url());
            assertNotNull(client);
        }
    }
}
