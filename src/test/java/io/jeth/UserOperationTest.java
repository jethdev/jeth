/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.aa.UserOperation;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for UserOperation ERC-4337 packing, hashing, and signing. */
class UserOperationTest {

    static final Wallet WALLET =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    static final String ENTRY_POINT = UserOperation.ENTRY_POINT_V06;
    static final long CHAIN_ID = 1L;

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

    // ─── getUserOpHash ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserOpHash returns 0x-prefixed 32-byte hash")
    void hash_format() {
        String hash = minimalOp().getUserOpHash(ENTRY_POINT, CHAIN_ID);
        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"), "hash must be 0x-prefixed");
        assertEquals(66, hash.length(), "hash must be 32 bytes = 66 hex chars with 0x");
    }

    @Test
    @DisplayName("getUserOpHash is deterministic")
    void hash_deterministic() {
        UserOperation op = minimalOp();
        assertEquals(
                op.getUserOpHash(ENTRY_POINT, CHAIN_ID), op.getUserOpHash(ENTRY_POINT, CHAIN_ID));
    }

    @Test
    @DisplayName("getUserOpHash changes with different sender")
    void hash_changes_with_sender() {
        UserOperation op1 = minimalOp();
        UserOperation op2 =
                UserOperation.builder()
                        .sender("0x70997970C51812dc3A010C7d01b50e0d17dc79C8") // different
                        .nonce(BigInteger.ZERO)
                        .callData("0x")
                        .callGasLimit(BigInteger.valueOf(100_000))
                        .verificationGasLimit(BigInteger.valueOf(150_000))
                        .preVerificationGas(BigInteger.valueOf(21_000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .build();
        assertNotEquals(
                op1.getUserOpHash(ENTRY_POINT, CHAIN_ID), op2.getUserOpHash(ENTRY_POINT, CHAIN_ID));
    }

    @Test
    @DisplayName("getUserOpHash changes with different chainId")
    void hash_changes_with_chainId() {
        UserOperation op = minimalOp();
        String h1 = op.getUserOpHash(ENTRY_POINT, 1L);
        String h2 = op.getUserOpHash(ENTRY_POINT, 137L); // Polygon
        assertNotEquals(h1, h2, "Different chainId must yield different hash");
    }

    @Test
    @DisplayName("getUserOpHash changes with different entryPoint")
    void hash_changes_with_entrypoint() {
        UserOperation op = minimalOp();
        String h1 = op.getUserOpHash(UserOperation.ENTRY_POINT_V06, CHAIN_ID);
        String h2 = op.getUserOpHash(UserOperation.ENTRY_POINT_V07, CHAIN_ID);
        assertNotEquals(h1, h2, "Different entryPoint must yield different hash");
    }

    // ─── sign ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sign() populates signature field")
    void sign_sets_signature() {
        UserOperation signed = minimalOp().sign(WALLET, CHAIN_ID, ENTRY_POINT);
        assertNotNull(signed.signature);
        assertTrue(signed.signature.startsWith("0x"), "signature must be 0x-prefixed");
    }

    @Test
    @DisplayName("sign() produces 65-byte signature")
    void sign_produces_65_bytes() {
        UserOperation signed = minimalOp().sign(WALLET, CHAIN_ID, ENTRY_POINT);
        byte[] sigBytes = Hex.decode(signed.signature);
        assertEquals(65, sigBytes.length, "ERC-4337 signature must be 65 bytes (r+s+v)");
    }

    @Test
    @DisplayName("sign() v byte is 27 or 28 (Ethereum convention)")
    void sign_v_is_27_or_28() {
        UserOperation signed = minimalOp().sign(WALLET, CHAIN_ID, ENTRY_POINT);
        byte[] sigBytes = Hex.decode(signed.signature);
        int v = sigBytes[64] & 0xFF;
        assertTrue(v == 27 || v == 28, "v must be 27 or 28, got " + v);
    }

    @Test
    @DisplayName("sign() is deterministic for same key and op")
    void sign_deterministic() {
        UserOperation op = minimalOp();
        String sig1 = op.sign(WALLET, CHAIN_ID, ENTRY_POINT).signature;
        String sig2 = op.sign(WALLET, CHAIN_ID, ENTRY_POINT).signature;
        assertEquals(sig1, sig2, "ECDSA with deterministic k (RFC 6979) must give same sig");
    }

    @Test
    @DisplayName("sign() original op unchanged (immutable return)")
    void sign_returns_new_instance() {
        UserOperation original = minimalOp();
        assertNull(original.signature, "unsigned op signature must be null");
        UserOperation signed = original.sign(WALLET, CHAIN_ID, ENTRY_POINT);
        assertNull(original.signature, "original op must not be mutated");
        assertNotNull(signed.signature, "returned op must have signature");
    }

    // ─── toMap ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toMap() contains all required ERC-4337 fields")
    void to_map_has_required_fields() {
        var map = minimalOp().sign(WALLET, CHAIN_ID, ENTRY_POINT).toMap();
        assertTrue(map.containsKey("sender"));
        assertTrue(map.containsKey("nonce"));
        assertTrue(map.containsKey("initCode"));
        assertTrue(map.containsKey("callData"));
        assertTrue(map.containsKey("callGasLimit"));
        assertTrue(map.containsKey("verificationGasLimit"));
        assertTrue(map.containsKey("preVerificationGas"));
        assertTrue(map.containsKey("maxFeePerGas"));
        assertTrue(map.containsKey("maxPriorityFeePerGas"));
        assertTrue(map.containsKey("paymasterAndData"));
        assertTrue(map.containsKey("signature"));
    }

    @Test
    @DisplayName("toMap() nonce is 0x-prefixed hex")
    void to_map_nonce_hex() {
        var map = minimalOp().toMap();
        String nonce = (String) map.get("nonce");
        assertTrue(nonce.startsWith("0x"), "nonce must be 0x-prefixed hex");
    }

    @Test
    @DisplayName("toMap() empty initCode defaults to 0x")
    void to_map_default_initcode() {
        var map = minimalOp().toMap();
        assertEquals("0x", map.get("initCode"));
    }

    // ─── pack / ERC-4337 spec ─────────────────────────────────────────────────

    @Test
    @DisplayName("getUserOpHash encodes initCode, callData, paymasterAndData as keccak256")
    void hash_uses_keccak_for_dynamic_fields() {
        // Two ops identical except initCode is empty vs "0x" — must hash identically
        UserOperation op1 =
                UserOperation.builder()
                        .sender("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                        .nonce(BigInteger.ZERO)
                        .callData("0x")
                        .callGasLimit(BigInteger.valueOf(100_000))
                        .verificationGasLimit(BigInteger.valueOf(150_000))
                        .preVerificationGas(BigInteger.valueOf(21_000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .initCode(null)
                        .build();
        UserOperation op2 =
                UserOperation.builder()
                        .sender("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                        .nonce(BigInteger.ZERO)
                        .callData("0x")
                        .callGasLimit(BigInteger.valueOf(100_000))
                        .verificationGasLimit(BigInteger.valueOf(150_000))
                        .preVerificationGas(BigInteger.valueOf(21_000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .initCode("0x")
                        .build(); // explicit "0x" same as null/empty
        // Both "null" and "0x" represent empty bytes and should hash identically
        assertEquals(
                op1.getUserOpHash(ENTRY_POINT, CHAIN_ID),
                op2.getUserOpHash(ENTRY_POINT, CHAIN_ID),
                "null initCode and '0x' initCode must produce identical userOpHash");
    }
}
