/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.crypto.Eip7702Signer;
import io.jeth.crypto.Wallet;
import io.jeth.model.EthModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EIP-7702 (Type-4 transaction) tests.
 *
 * Verifies: authorization signing, type byte, structure, self-sponsoring,
 * any-chain authorizations, and the full sign() round-trip.
 */
class Eip7702SignerTest {

    // Hardhat account #0 — deterministic test wallet
    static final String PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    static final Wallet WALLET = Wallet.fromPrivateKey(new BigInteger(PRIVATE_KEY.substring(2), 16));

    static final String CONTRACT = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    // ─── Authorization structure ──────────────────────────────────────────────

    @Test @DisplayName("signAuthorization() returns non-null Authorization")
    void sign_auth_not_null() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertNotNull(auth);
    }

    @Test @DisplayName("Authorization has correct chainId")
    void auth_chain_id() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertEquals(1L, auth.chainId());
    }

    @Test @DisplayName("Authorization has correct contract address")
    void auth_contract_address() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertEquals(CONTRACT, auth.contractAddress());
    }

    @Test @DisplayName("Authorization has correct nonce")
    void auth_nonce() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 42L, WALLET);
        assertEquals(42L, auth.nonce());
    }

    @Test @DisplayName("Authorization yParity is 0 or 1")
    void auth_y_parity() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertTrue(auth.yParity() == 0 || auth.yParity() == 1,
            "yParity must be 0 or 1, got: " + auth.yParity());
    }

    @Test @DisplayName("Authorization r and s are non-empty hex strings")
    void auth_r_s_nonempty() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertFalse(auth.r().isEmpty(), "r must not be empty");
        assertFalse(auth.s().isEmpty(), "s must not be empty");
        // Should be valid hex
        assertDoesNotThrow(() -> new BigInteger(auth.r(), 16));
        assertDoesNotThrow(() -> new BigInteger(auth.s(), 16));
    }

    @Test @DisplayName("Authorization r and s fit in 32 bytes")
    void auth_r_s_size() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertTrue(new BigInteger(auth.r(), 16).bitLength() <= 256);
        assertTrue(new BigInteger(auth.s(), 16).bitLength() <= 256);
    }

    // ─── Determinism ─────────────────────────────────────────────────────────

    @Test @DisplayName("Same inputs produce identical authorizations (deterministic ECDSA via RFC6979)")
    void auth_deterministic() {
        var a1 = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        var a2 = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        assertEquals(a1.r(), a2.r());
        assertEquals(a1.s(), a2.s());
        assertEquals(a1.yParity(), a2.yParity());
    }

    @Test @DisplayName("Different nonces produce different signatures")
    void auth_different_nonces() {
        var a0 = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        var a1 = Eip7702Signer.signAuthorization(1L, CONTRACT, 1L, WALLET);
        assertNotEquals(a0.r(), a1.r());
    }

    @Test @DisplayName("Different chainIds produce different signatures")
    void auth_different_chains() {
        var mainnet = Eip7702Signer.signAuthorization(1L,     CONTRACT, 0L, WALLET);
        var base    = Eip7702Signer.signAuthorization(8453L,  CONTRACT, 0L, WALLET);
        assertNotEquals(mainnet.r(), base.r());
    }

    // ─── Static factories ─────────────────────────────────────────────────────

    @Test @DisplayName("Authorization.anyChain() has chainId = 0")
    void any_chain_id_zero() {
        var auth = Eip7702Signer.Authorization.anyChain(CONTRACT, 0L, WALLET);
        assertEquals(0L, auth.chainId());
    }

    @Test @DisplayName("Authorization.selfSponsored() is equivalent to signAuthorization")
    void self_sponsored_equivalent() {
        var direct = Eip7702Signer.signAuthorization(1L, CONTRACT, 5L, WALLET);
        var selfSp = Eip7702Signer.Authorization.selfSponsored(1L, CONTRACT, 5L, WALLET);
        assertEquals(direct.r(), selfSp.r());
        assertEquals(direct.s(), selfSp.s());
        assertEquals(direct.chainId(), selfSp.chainId());
    }

    // ─── Type-4 transaction signing ───────────────────────────────────────────

    @Test @DisplayName("sign() returns 0x-prefixed hex string")
    void sign_returns_hex() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        var tx = EthModels.TransactionRequest.builder()
                .from(WALLET.getAddress()).to(CONTRACT)
                .gas(BigInteger.valueOf(100_000))
                .maxFeePerGas(BigInteger.valueOf(10_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .nonce(0L).chainId(1L).build();

        String raw = Eip7702Signer.sign(tx, List.of(auth), WALLET);
        assertNotNull(raw);
        assertTrue(raw.startsWith("0x"), "Must start with 0x");
    }

    @Test @DisplayName("sign() produces type-4 transaction (first byte = 0x04)")
    void sign_type_4_byte() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        var tx = EthModels.TransactionRequest.builder()
                .from(WALLET.getAddress()).to(CONTRACT)
                .gas(BigInteger.valueOf(100_000))
                .maxFeePerGas(BigInteger.valueOf(10_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .nonce(0L).chainId(1L).build();

        byte[] raw = Hex.decode(Eip7702Signer.sign(tx, List.of(auth), WALLET));
        assertEquals(0x04, raw[0] & 0xFF, "Type-4 transaction must start with 0x04");
    }

    @Test @DisplayName("sign() with multiple authorizations is longer than with one")
    void sign_multiple_auths() {
        Wallet wallet2 = Wallet.fromPrivateKey(new BigInteger("59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d", 16));

        var auth1 = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        var auth2 = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, wallet2);

        var tx = EthModels.TransactionRequest.builder()
                .from(WALLET.getAddress()).to(CONTRACT)
                .gas(BigInteger.valueOf(200_000))
                .maxFeePerGas(BigInteger.valueOf(10_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .nonce(0L).chainId(1L).build();

        String one = Eip7702Signer.sign(tx, List.of(auth1), WALLET);
        String two = Eip7702Signer.sign(tx, List.of(auth1, auth2), WALLET);

        assertTrue(two.length() > one.length(),
            "Two authorizations must produce longer raw tx");
    }

    @Test @DisplayName("sign() with zero-value tx and no calldata doesn't throw")
    void sign_minimal() {
        var auth = Eip7702Signer.Authorization.anyChain(CONTRACT, 0L, WALLET);
        var tx = EthModels.TransactionRequest.builder()
                .from(WALLET.getAddress()).to(CONTRACT)
                .gas(BigInteger.valueOf(21_000))
                .maxFeePerGas(BigInteger.valueOf(10_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .nonce(0L).chainId(1L).build();

        assertDoesNotThrow(() -> Eip7702Signer.sign(tx, List.of(auth), WALLET));
    }

    // ─── toString ─────────────────────────────────────────────────────────────

    @Test @DisplayName("Authorization.toString() is human-readable")
    void to_string() {
        var auth = Eip7702Signer.signAuthorization(1L, CONTRACT, 0L, WALLET);
        String s = auth.toString();
        assertTrue(s.contains("chain=1") || s.contains("chainId=1") || s.contains("Authorization"),
            "toString should include chain info: " + s);
    }
}
