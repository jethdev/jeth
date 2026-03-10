/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Wallet;
import io.jeth.wallet.Keystore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Additional Keystore tests — regression coverage for the timing-safe MAC fix.
 *
 * <p>The bug: MAC comparison used Arrays.equals() (timing-leaking). The fix:
 * MessageDigest.isEqual() (constant-time).
 *
 * <p>These tests verify the decrypt path correctly validates the MAC, i.e., wrong password fails
 * and right password succeeds.
 */
class KeystoreExtendedTest {

    static final Wallet W =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    // ─── MAC validation (regression: timing-safe check must reject wrong pass) ─

    @Test
    @DisplayName("decrypt with correct password recovers original wallet address")
    void decrypt_correct_password() {
        String json = Keystore.encryptLight(W, "correct-pass");
        Wallet recovered = Keystore.decrypt(json, "correct-pass");
        assertEquals(W.getAddress(), recovered.getAddress());
    }

    @Test
    @DisplayName("decrypt with wrong password throws (MAC mismatch)")
    void decrypt_wrong_password_throws() {
        String json = Keystore.encryptLight(W, "correct-pass");
        assertThrows(
                Exception.class,
                () -> Keystore.decrypt(json, "wrong-pass"),
                "Wrong password must fail MAC check");
    }

    @Test
    @DisplayName("decrypt with empty password throws when encrypted with non-empty")
    void decrypt_empty_password_throws() {
        String json = Keystore.encryptLight(W, "secret");
        assertThrows(
                Exception.class,
                () -> Keystore.decrypt(json, ""),
                "Empty password must fail MAC check");
    }

    @Test
    @DisplayName("decrypt with empty password works when encrypted with empty password")
    void decrypt_empty_password_roundtrip() {
        String json = Keystore.encryptLight(W, "");
        Wallet recovered = Keystore.decrypt(json, "");
        assertEquals(W.getAddress(), recovered.getAddress());
    }

    @Test
    @DisplayName("encrypt produces valid JSON with required keystore fields")
    void encrypt_has_required_fields() {
        String json = Keystore.encryptLight(W, "pass");
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"crypto\"") || json.contains("\"Crypto\""));
        assertTrue(json.contains("\"ciphertext\""));
        assertTrue(json.contains("\"mac\""));
        assertTrue(json.contains("\"kdf\""));
    }

    @Test
    @DisplayName("encrypt produces different ciphertext each time (random IV)")
    void encrypt_is_nondeterministic() {
        String json1 = Keystore.encryptLight(W, "pass");
        String json2 = Keystore.encryptLight(W, "pass");
        // Extract ciphertext field — must differ due to random IV
        String ct1 = extractField(json1, "ciphertext");
        String ct2 = extractField(json2, "ciphertext");
        assertNotEquals(ct1, ct2, "Each encrypt must produce different ciphertext (random IV)");
    }

    @Test
    @DisplayName("encrypt/decrypt roundtrip preserves private key exactly")
    void roundtrip_preserves_private_key() {
        String json = Keystore.encryptLight(W, "roundtrip-test");
        Wallet recovered = Keystore.decrypt(json, "roundtrip-test");
        // If private keys match, derived addresses match
        assertEquals(W.getAddress(), recovered.getAddress());
        assertEquals(W.getPrivateKeyHex(), recovered.getPrivateKeyHex());
    }

    @Test
    @DisplayName("tampered MAC in JSON throws on decrypt")
    void tampered_mac_throws() {
        String json = Keystore.encryptLight(W, "pass");
        // Replace MAC value with zeros
        String tampered =
                json.replaceFirst(
                        "\"mac\":\"[0-9a-f]+\"",
                        "\"mac\":\"0000000000000000000000000000000000000000000000000000000000000000\"");
        assertThrows(
                Exception.class,
                () -> Keystore.decrypt(tampered, "pass"),
                "Tampered MAC must be rejected");
    }

    @Test
    @DisplayName("tampered ciphertext throws on decrypt (MAC covers ciphertext)")
    void tampered_ciphertext_throws() {
        String json = Keystore.encryptLight(W, "pass");
        // Flip one hex digit in ciphertext
        String tampered =
                json.replaceFirst(
                        "\"ciphertext\":\"([0-9a-f]{2})([0-9a-f]+)\"",
                        "\"ciphertext\":\"ff$2\""); // replace first byte
        // This may or may not throw depending on whether MAC covers ciphertext
        // (it does: MAC = keccak256(decryptionKey[16:32] || ciphertext))
        assertThrows(
                Exception.class,
                () -> Keystore.decrypt(tampered, "pass"),
                "Tampered ciphertext must be rejected by MAC check");
    }

    @Test
    @DisplayName("different wallets produce different ciphertexts")
    void different_wallets_different_ciphertexts() {
        Wallet w2 = Wallet.create();
        String json1 = Keystore.encryptLight(W, "pass");
        String json2 = Keystore.encryptLight(w2, "pass");
        String ct1 = extractField(json1, "ciphertext");
        String ct2 = extractField(json2, "ciphertext");
        assertNotEquals(ct1, ct2);
    }

    @Test
    @DisplayName("unicode password is handled correctly")
    void unicode_password() {
        String pass = "pässwörd-with-ümlauts-🔑";
        String json = Keystore.encryptLight(W, pass);
        Wallet recovered = Keystore.decrypt(json, pass);
        assertEquals(W.getAddress(), recovered.getAddress());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Extract a JSON field value without pulling in a JSON library dependency. */
    private static String extractField(String json, String field) {
        String pat = "\"" + field + "\":\"";
        int start = json.indexOf(pat);
        if (start < 0) return "";
        start += pat.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
