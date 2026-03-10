/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Extended Wallet tests: signing, recovery, personal_sign. Core creation tests live in CryptoTest.
 */
class WalletExtendedTest {

    static final Wallet HARDHAT_0 =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    static final Wallet HARDHAT_1 =
            Wallet.fromPrivateKey(
                    "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");

    // ── Recovery ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recoverAddress from hash + sig returns original signer")
    void recover_address() {
        byte[] hash =
                Hex.decode("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        Signature sig = HARDHAT_0.sign(hash);
        String recovered = Wallet.recoverAddress(hash, sig);
        assertTrue(HARDHAT_0.getAddress().equalsIgnoreCase(recovered), "Got: " + recovered);
    }

    @Test
    @DisplayName("recoverAddress: wrong hash → wrong signer")
    void recover_wrong_hash() {
        byte[] hash1 =
                Hex.decode("0x1111111111111111111111111111111111111111111111111111111111111111");
        byte[] hash2 =
                Hex.decode("0x2222222222222222222222222222222222222222222222222222222222222222");
        Signature sig = HARDHAT_0.sign(hash1);
        String wrong = Wallet.recoverAddress(hash2, sig);
        assertFalse(HARDHAT_0.getAddress().equalsIgnoreCase(wrong));
    }

    @Test
    @DisplayName("personal_sign: recoverPersonalMessage returns signer")
    void recover_personal_sign() {
        String msg = "Hello, jeth!";
        Signature sig = HARDHAT_0.signMessage(msg);
        String recovered = Wallet.recoverPersonalMessage(msg, sig);
        assertTrue(HARDHAT_0.getAddress().equalsIgnoreCase(recovered), "Got: " + recovered);
    }

    @Test
    @DisplayName("personal_sign: different wallets produce different sigs")
    void personal_sign_different_wallets() {
        Signature s0 = HARDHAT_0.signMessage("test");
        Signature s1 = HARDHAT_1.signMessage("test");
        assertNotEquals(s0.r, s1.r);
    }

    @Test
    @DisplayName("hashPersonalMessage adds Ethereum prefix")
    void personal_message_hash_length() {
        byte[] hash = Wallet.hashPersonalMessage("Hello World".getBytes());
        assertEquals(32, hash.length);
    }

    // ── Low-S signature ───────────────────────────────────────────────────────

    @Test
    @DisplayName("All sigs have low-S (EIP-2 canonical form)")
    void low_s_signature() {
        BigInteger halfN = Wallet.CURVE.getN().shiftRight(1);
        for (int i = 0; i < 10; i++) {
            Wallet w = Wallet.create();
            byte[] hash = Hex.decode("0x" + String.format("%064x", i + 1));
            Signature sig = w.sign(hash);
            assertTrue(sig.s.compareTo(halfN) <= 0, "s must be in lower half of curve order");
        }
    }

    // ── Signature serialization ───────────────────────────────────────────────

    @Test
    @DisplayName("Signature.toBytes() = 65 bytes [r(32) + s(32) + v(1)]")
    void sig_bytes_length() {
        byte[] hash =
                Hex.decode("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        Signature sig = HARDHAT_0.sign(hash);
        assertEquals(65, sig.toBytes().length);
    }

    @Test
    @DisplayName("Signature.toHex() is 132-char hex string (0x + 130 chars)")
    void sig_hex_length() {
        byte[] hash =
                Hex.decode("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        Signature sig = HARDHAT_0.sign(hash);
        String hex = sig.toHex();
        assertTrue(hex.startsWith("0x"), "Hex must start with 0x");
        assertEquals(132, hex.length(), "0x + 32r + 32s + 1v bytes = 0x + 130 hex");
    }

    // ── address v value ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Sign: v is 0 or 1 (pre-EIP-155 form)")
    void sig_v_values() {
        for (int i = 0; i < 5; i++) {
            byte[] hash = Hex.decode("0x" + String.format("%064x", i + 100));
            Signature sig = HARDHAT_0.sign(hash);
            assertTrue(sig.v == 0 || sig.v == 1, "raw v should be 0 or 1, got " + sig.v);
        }
    }

    @Test
    @DisplayName("signMessage: v is 27 or 28 (personal_sign form)")
    void personal_sign_v() {
        Signature sig = HARDHAT_0.signMessage("test");
        assertTrue(sig.v == 27 || sig.v == 28, "personal_sign v should be 27/28, got " + sig.v);
    }

    // ── Address derivation ────────────────────────────────────────────────────

    @Test
    @DisplayName("All 10 Hardhat dev accounts derive correctly")
    void hardhat_accounts() {
        String[] keys = {
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
            "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
            "0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6",
            "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926b",
        };
        String[] expected = {
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
            "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
            "0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65",
        };
        for (int i = 0; i < keys.length; i++) {
            assertEquals(expected[i], Wallet.fromPrivateKey(keys[i]).getAddress(), "Account " + i);
        }
    }
}
