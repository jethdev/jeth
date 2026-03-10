/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.crypto.Rlp;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Address;
import io.jeth.util.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {

    @Test
    void testWalletCreation() {
        Wallet wallet = Wallet.create();
        assertNotNull(wallet.getAddress());
        assertTrue(Address.isValid(wallet.getAddress()));
        assertNotNull(wallet.getPrivateKey());
        assertTrue(wallet.getPublicKeyBytes().length == 65); // uncompressed
    }

    @Test
    void testWalletFromPrivateKey() {
        // Known private key and corresponding address
        String privateKey = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
        Wallet wallet = Wallet.fromPrivateKey(privateKey);

        // Address derived from this well-known test key
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", wallet.getAddress());
    }

    @Test
    void testWalletAddressIsChecksummed() {
        Wallet wallet = Wallet.create();
        // All generated addresses should be EIP-55 checksummed
        assertTrue(Address.isValidChecksum(wallet.getAddress()));
    }

    @Test
    void testWalletSign() {
        Wallet wallet = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        byte[] hash = Hex.decode("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        Signature sig = wallet.sign(hash);

        assertNotNull(sig);
        assertNotNull(sig.r);
        assertNotNull(sig.s);
        assertTrue(sig.v == 0 || sig.v == 1);
        assertEquals(65, sig.toBytes().length);
    }

    @Test
    void testRlpEncodeUint() {
        // RLP(0) = 0x80 (empty bytes)
        byte[] encoded = Rlp.encode(BigInteger.ZERO);
        assertEquals(1, encoded.length);
        assertEquals((byte) 0x80, encoded[0]);
    }

    @Test
    void testRlpEncodeSmallInt() {
        // RLP(1) = 0x01
        byte[] encoded = Rlp.encode(BigInteger.ONE);
        assertEquals(1, encoded.length);
        assertEquals((byte) 0x01, encoded[0]);
    }

    @Test
    void testRlpEncodeList() {
        // RLP([]) = 0xC0
        byte[] encoded = Rlp.encode(List.of());
        assertEquals(1, encoded.length);
        assertEquals((byte) 0xC0, encoded[0]);
    }

    @Test
    void testPrivateKeyRoundtrip() {
        Wallet original = Wallet.create();
        String privKeyHex = original.getPrivateKeyHex();
        Wallet restored = Wallet.fromPrivateKey(privKeyHex);
        assertEquals(original.getAddress(), restored.getAddress());
    }

    @Test
    void testRlpDecodeRoundtripBytes() {
        // encode then decode a BigInteger — should recover the same bytes
        BigInteger val = BigInteger.valueOf(1_000_000_000L);
        byte[] encoded = Rlp.encode(val);
        Object decoded = Rlp.decode(encoded);
        assertTrue(decoded instanceof byte[]);
        assertEquals(val, new BigInteger(1, (byte[]) decoded));
    }

    @Test
    void testRlpDecodeRoundtripList() {
        List<Object> list = List.of(
            BigInteger.ONE, BigInteger.valueOf(255), BigInteger.ZERO
        );
        byte[] encoded = Rlp.encode(list);
        Object decoded = Rlp.decode(encoded);
        assertTrue(decoded instanceof List<?>);
        List<?> decodedList = (List<?>) decoded;
        assertEquals(3, decodedList.size());
        assertEquals(BigInteger.ONE,         new BigInteger(1, (byte[]) decodedList.get(0)));
        assertEquals(BigInteger.valueOf(255), new BigInteger(1, (byte[]) decodedList.get(1)));
        // RLP(0) = 0x80 = empty byte array
        assertEquals(0, ((byte[]) decodedList.get(2)).length);
    }

    @Test
    void testRlpDecodeEmptyList() {
        byte[] encoded = Rlp.encode(List.of());
        Object decoded = Rlp.decode(encoded);
        assertTrue(decoded instanceof List<?>);
        assertTrue(((List<?>) decoded).isEmpty());
    }

    @Test
    void testRlpDecodeNestedList() {
        List<Object> inner = List.of(BigInteger.ONE, BigInteger.TWO);
        List<Object> outer = List.of(BigInteger.ZERO, inner);
        byte[] encoded = Rlp.encode(outer);
        Object decoded = Rlp.decode(encoded);
        assertTrue(decoded instanceof List<?>);
        List<?> list = (List<?>) decoded;
        assertEquals(2, list.size());
        assertTrue(list.get(1) instanceof List<?>);
    }

}