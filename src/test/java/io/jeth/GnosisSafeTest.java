/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.safe.GnosisSafe;
import java.math.BigInteger;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GnosisSafeTest {

    static final String SAFE_ADDR = "0x1234567890123456789012345678901234567890";
    static final Wallet OWNER =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    static GnosisSafe.SafeTx buildTx() {
        return GnosisSafe.SafeTx.builder()
                .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                .value(BigInteger.ZERO)
                .data("0x")
                .operation(0)
                .nonce(BigInteger.ZERO)
                .build();
    }

    @Test
    @DisplayName("getAddress() returns the safe address")
    void get_address() throws Exception {
        try (var rpc = new RpcMock()) {
            // getNonce, getThreshold, getOwners stubs not needed for this test
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            assertEquals(SAFE_ADDR, safe.getAddress());
        }
    }

    @Test
    @DisplayName("getTransactionHash returns 32-byte hash (EIP-712)")
    void transaction_hash_length() throws Exception {
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            byte[] hash = safe.getTransactionHash(buildTx(), 1L);
            assertEquals(32, hash.length);
        }
    }

    @Test
    @DisplayName("getTransactionHash is deterministic")
    void transaction_hash_deterministic() throws Exception {
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            var tx = buildTx();
            assertArrayEquals(safe.getTransactionHash(tx, 1L), safe.getTransactionHash(tx, 1L));
        }
    }

    @Test
    @DisplayName("Different chainId → different hash (EIP-712 domain separation)")
    void transaction_hash_chainid_unique() throws Exception {
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            var tx = buildTx();
            assertFalse(
                    Arrays.equals(
                            safe.getTransactionHash(tx, 1L), safe.getTransactionHash(tx, 137L)));
        }
    }

    @Test
    @DisplayName("signTransaction returns 65-byte EIP-712 signature")
    void sign_transaction_length() throws Exception {
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            Signature sig = safe.signTransaction(buildTx(), 1L, OWNER);
            assertNotNull(sig);
            assertEquals(65, sig.toBytes().length);
            assertTrue(sig.v == 27 || sig.v == 28, "v should be 27 or 28 for EIP-712");
        }
    }

    @Test
    @DisplayName("signTransaction is deterministic")
    void sign_transaction_deterministic() throws Exception {
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            var tx = buildTx();
            Signature s1 = safe.signTransaction(tx, 1L, OWNER);
            Signature s2 = safe.signTransaction(tx, 1L, OWNER);
            assertEquals(s1.r, s2.r);
            assertEquals(s1.s, s2.s);
        }
    }

    @Test
    @DisplayName("Different wallets produce different signatures")
    void sign_different_wallets() throws Exception {
        Wallet other =
                Wallet.fromPrivateKey(
                        "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
        try (var rpc = new RpcMock()) {
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            Signature s1 = safe.signTransaction(buildTx(), 1L, OWNER);
            Signature s2 = safe.signTransaction(buildTx(), 1L, other);
            assertNotEquals(s1.r, s2.r);
        }
    }

    @Test
    @DisplayName("getNonce reads from contract via eth_call")
    void get_nonce() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"0x0000000000000000000000000000000000000000000000000000000000000005\"");
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            assertEquals(BigInteger.valueOf(5), safe.getNonce().join());
        }
    }

    @Test
    @DisplayName("getThreshold reads from contract via eth_call")
    void get_threshold() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"0x0000000000000000000000000000000000000000000000000000000000000002\"");
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            assertEquals(2, safe.getThreshold().join());
        }
    }

    @Test
    @DisplayName("isOwner returns true when response is 1")
    void is_owner_true() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"0x0000000000000000000000000000000000000000000000000000000000000001\"");
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            assertTrue(safe.isOwner(OWNER.getAddress()).join());
        }
    }

    @Test
    @DisplayName("isOwner returns false when response is 0")
    void is_owner_false() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"0x0000000000000000000000000000000000000000000000000000000000000000\"");
            var safe = new GnosisSafe(SAFE_ADDR, rpc.client());
            assertFalse(safe.isOwner("0xnotowner").join());
        }
    }
}
