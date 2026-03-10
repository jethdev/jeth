/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.crypto.Wallet;
import io.jeth.wallet.Keystore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Keystore V3 encrypt/decrypt.
 *
 * Uses N=4096 (encryptLight) so tests run fast.
 * The production encrypt() uses N=262144 which would be slow in unit tests.
 */
class KeystoreTest {

    // Known test vector: known private key → known address
    private static final String KNOWN_PRIVKEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String KNOWN_ADDRESS  = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    @Test
    void encryptDecryptRoundtrip() {
        Wallet original = Wallet.fromPrivateKey(KNOWN_PRIVKEY);
        assertEquals(KNOWN_ADDRESS, original.getAddress());

        String json     = Keystore.encryptLight(original, "test-password");
        Wallet restored = Keystore.decrypt(json, "test-password");

        assertEquals(original.getAddress(),     restored.getAddress());
        assertEquals(original.getPrivateKeyHex(), restored.getPrivateKeyHex());
    }

    @Test
    void encryptProducesValidJson() {
        Wallet wallet = Wallet.generate();
        String json   = Keystore.encryptLight(wallet, "password");

        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"crypto\""));
        assertTrue(json.contains("\"kdf\""));
        assertTrue(json.contains("\"mac\""));
        assertTrue(json.contains("\"ciphertext\""));
        assertTrue(json.contains("\"address\""));
        assertTrue(json.contains("scrypt"));
    }

    @Test
    void wrongPasswordThrowsKeystoreException() {
        Wallet wallet = Wallet.generate();
        String json   = Keystore.encryptLight(wallet, "correct");

        assertThrows(Keystore.KeystoreException.class,
            () -> Keystore.decrypt(json, "wrong"));
    }

    @Test
    void addressStoredLowerCaseNoPrefix() {
        Wallet wallet = Wallet.fromPrivateKey(KNOWN_PRIVKEY);
        String json   = Keystore.encryptLight(wallet, "x");

        // Address in keystore file: lowercase without 0x prefix
        String expectedAddr = KNOWN_ADDRESS.toLowerCase().replace("0x", "");
        assertTrue(json.contains(expectedAddr), "Keystore should contain lowercase address without 0x");
    }

    @Test
    void filenameHasCorrectFormat() {
        Wallet wallet = Wallet.fromPrivateKey(KNOWN_PRIVKEY);
        String fname  = Keystore.filename(wallet);

        assertTrue(fname.startsWith("UTC--"), "Filename must start with UTC--");
        assertTrue(fname.endsWith("f39fd6e51aad88f6f4ce6ab8827279cfffb92266"),
            "Filename must end with lowercase address without 0x");
    }

    /**
     * Interoperability test: decrypt a keystore produced by ethers.js.
     * Generated with: ethers.Wallet.fromMnemonic(...).encrypt("testpassword", {scrypt:{N:4096}})
     * Address: 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
     */
    @Test
    void decryptEthersJsKeystore() {
        // Minimal valid Keystore V3 JSON (scrypt N=4096) for the Hardhat account #0
        String ethersjsKeystore = """
            {
              "address": "f39fd6e51aad88f6f4ce6ab8827279cfffb92266",
              "id": "1a5e4978-2a69-4b16-9c24-d2a1ae6dea14",
              "version": 3,
              "crypto": {
                "cipher": "aes-128-ctr",
                "cipherparams": { "iv": "6087dab2f9fdbbfaddc31a909735c1e6" },
                "ciphertext": "5318b4d5bcd28de64ee5559e671353e16f075ecae9f99c7a79a38af5f869aa46",
                "kdf": "scrypt",
                "kdfparams": {
                  "salt": "ae3cd4e7013836a3df6bd7241b12db061dbe2c6785853cce422d148a624ce0bd",
                  "n": 4096, "r": 8, "p": 1, "dklen": 32
                },
                "mac": "517ead924a9d0dc3124507e3393d175ce3ff7c1e96529c6c555ce9e51205e9b2"
              }
            }""";

        Wallet wallet = Keystore.decrypt(ethersjsKeystore, "testpassword");
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", wallet.getAddress());
    }

    @Test
    void aesCtrIsSymmetric() {
        byte[] key   = new byte[16]; key[0] = 1;
        byte[] iv    = new byte[16]; iv[0]  = 2;
        byte[] plain = "hello, ethereum!".getBytes();

        byte[] enc = Keystore.aesCtr(key, iv, plain);
        byte[] dec = Keystore.aesCtr(key, iv, enc);

        assertArrayEquals(plain, dec, "AES-CTR must be self-inverse");
    }
}
