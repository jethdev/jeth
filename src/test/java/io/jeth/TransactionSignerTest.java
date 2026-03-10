/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionSignerTest {

    static final Wallet W =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    static EthModels.TransactionRequest tx(long chainId, long nonce) {
        return EthModels.TransactionRequest.builder()
                .from(W.getAddress())
                .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                .value(BigInteger.ZERO)
                .gas(BigInteger.valueOf(21000))
                .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .nonce(nonce)
                .chainId(chainId)
                .build();
    }

    @Test
    @DisplayName("EIP-1559 starts with 0x02 prefix")
    void eip1559_prefix() {
        assertTrue(TransactionSigner.signEip1559(tx(1, 0), W).startsWith("0x02"));
    }

    @Test
    @DisplayName("EIP-1559 deterministic (RFC 6979)")
    void eip1559_deterministic() {
        var t = tx(1, 5);
        assertEquals(TransactionSigner.signEip1559(t, W), TransactionSigner.signEip1559(t, W));
    }

    @Test
    @DisplayName("EIP-1559 different nonce → different tx")
    void eip1559_nonce_changes_tx() {
        assertNotEquals(
                TransactionSigner.signEip1559(tx(1, 0), W),
                TransactionSigner.signEip1559(tx(1, 1), W));
    }

    @Test
    @DisplayName("EIP-1559 different chainId → different tx (replay protection)")
    void eip1559_chain_replay_protection() {
        assertNotEquals(
                TransactionSigner.signEip1559(tx(1, 0), W),
                TransactionSigner.signEip1559(tx(42161, 0), W));
    }

    @Test
    @DisplayName("EIP-1559 with ETH value and calldata")
    void eip1559_with_value_and_data() {
        var t =
                EthModels.TransactionRequest.builder()
                        .from(W.getAddress())
                        .to("0xToken")
                        .value(BigInteger.valueOf(1_000_000_000_000_000_000L))
                        .gas(BigInteger.valueOf(65000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .data("0xa9059cbb" + "0".repeat(64))
                        .nonce(0)
                        .chainId(1)
                        .build();
        String raw = TransactionSigner.signEip1559(t, W);
        assertTrue(raw.startsWith("0x02"));
        assertTrue(Hex.decode(raw).length > 150);
    }

    @Test
    @DisplayName("EIP-1559 contract creation (to=null)")
    void eip1559_contract_creation() {
        var t =
                EthModels.TransactionRequest.builder()
                        .from(W.getAddress())
                        .to(null)
                        .value(BigInteger.ZERO)
                        .gas(BigInteger.valueOf(300000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .data("0x6080604052")
                        .nonce(0)
                        .chainId(1)
                        .build();
        assertTrue(TransactionSigner.signEip1559(t, W).startsWith("0x02"));
    }

    @Test
    @DisplayName("Legacy tx EIP-155 replay protection")
    void legacy_eip155() {
        String raw = TransactionSigner.signLegacy(tx(1, 0), W);
        assertFalse(raw.startsWith("0x02"));
        assertTrue(Hex.decode(raw).length > 70);
    }

    @Test
    @DisplayName("Legacy tx deterministic")
    void legacy_deterministic() {
        var t = tx(1, 3);
        assertEquals(TransactionSigner.signLegacy(t, W), TransactionSigner.signLegacy(t, W));
    }

    @Test
    @DisplayName("Legacy different chainId → different tx")
    void legacy_chain_replay() {
        assertNotEquals(
                TransactionSigner.signLegacy(tx(1, 0), W),
                TransactionSigner.signLegacy(tx(137, 0), W));
    }

    @Test
    @DisplayName("transactionHash computes keccak256 of raw tx bytes")
    void tx_hash() {
        String raw = TransactionSigner.signEip1559(tx(1, 0), W);
        String hash = TransactionSigner.transactionHash(raw);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("transactionHash is deterministic")
    void tx_hash_deterministic() {
        String raw = TransactionSigner.signEip1559(tx(1, 0), W);
        assertEquals(
                TransactionSigner.transactionHash(raw), TransactionSigner.transactionHash(raw));
    }

    @Test
    @DisplayName("recoverSigner returns signer address for EIP-1559 tx")
    void recoverSigner_eip1559() {
        String raw = TransactionSigner.signEip1559(tx(1, 0), W);
        String recovered = TransactionSigner.recoverSigner(raw);
        assertEquals(W.getAddress().toLowerCase(), recovered.toLowerCase());
    }

    @Test
    @DisplayName("recoverSigner returns signer address for legacy tx")
    void recoverSigner_legacy() {
        String raw = TransactionSigner.signLegacy(tx(1, 0), W);
        String recovered = TransactionSigner.recoverSigner(raw);
        assertEquals(W.getAddress().toLowerCase(), recovered.toLowerCase());
    }
}
