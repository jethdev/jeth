/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.util.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbiCodecTest {

    @Test
    void testEncodeUint256() {
        byte[] encoded = AbiCodec.encodeUint256(BigInteger.valueOf(1000));
        assertEquals(32, encoded.length);
        assertEquals(BigInteger.valueOf(1000), AbiCodec.decodeUint(encoded, 0));
    }

    @Test
    void testEncodeAddress() {
        String address = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
        byte[] encoded = AbiCodec.encodeAddress(address);
        assertEquals(32, encoded.length);
        // First 12 bytes are zero padding
        for (int i = 0; i < 12; i++) assertEquals(0, encoded[i]);
    }

    @Test
    void testEncodeBool() {
        byte[] trueBytes = AbiCodec.encodeBool(true);
        byte[] falseBytes = AbiCodec.encodeBool(false);
        assertEquals(32, trueBytes.length);
        assertEquals(32, falseBytes.length);
        assertTrue(AbiCodec.decodeBool(trueBytes, 0));
        assertFalse(AbiCodec.decodeBool(falseBytes, 0));
    }

    @Test
    void testEncodeDecodeString() {
        String original = "Hello, Ethereum!";
        byte[] encoded = AbiCodec.encodeString(original);
        // Should be at least 64 bytes (32 for length + 32 for content)
        assertTrue(encoded.length >= 64);
        String decoded = AbiCodec.decodeString(encoded, 0);
        assertEquals(original, decoded);
    }

    @Test
    void testEncodeDecodeBytes() {
        byte[] original = Hex.decode("0xdeadbeef");
        byte[] encoded = AbiCodec.encodeDynamicBytes(original);
        byte[] decoded = AbiCodec.decodeDynamicBytes(encoded, 0);
        assertArrayEquals(original, decoded);
    }

    @Test
    void testFunctionSelectorTransfer() {
        // Known: transfer(address,uint256) = 0xa9059cbb
        Function fn = Function.of("transfer", AbiType.ADDRESS, AbiType.UINT256);
        assertEquals("0xa9059cbb", fn.getSelectorHex());
    }

    @Test
    void testFunctionSelectorBalanceOf() {
        // Known: balanceOf(address) = 0x70a08231
        Function fn = Function.of("balanceOf", AbiType.ADDRESS);
        assertEquals("0x70a08231", fn.getSelectorHex());
    }

    @Test
    void testEncodeTransferCall() {
        Function transfer = Function.of("transfer", AbiType.ADDRESS, AbiType.UINT256)
                .withReturns(AbiType.BOOL);

        String calldata = transfer.encode(
                "0xdAC17F958D2ee523a2206206994597C13D831ec7",
                BigInteger.valueOf(1_000_000L)
        );

        assertNotNull(calldata);
        assertTrue(calldata.startsWith("0xa9059cbb")); // selector
        assertTrue(calldata.length() > 10);
    }

    @Test
    void testDecodeMultipleTypes() {
        // Encode then decode address + uint256
        AbiType[] types = {AbiType.ADDRESS, AbiType.UINT256};
        Object[] values = {"0xdAC17F958D2ee523a2206206994597C13D831ec7", BigInteger.valueOf(42_000L)};

        byte[] encoded = AbiCodec.encode(types, values);
        Object[] decoded = AbiCodec.decode(types, encoded);

        assertEquals(2, decoded.length);
        assertTrue(decoded[0].toString().equalsIgnoreCase((String) values[0]));
        assertEquals(values[1], decoded[1]);
    }

    @Test
    void testAbiTypeOf() {
        assertEquals("uint256", AbiType.of("uint256").toString());
        assertEquals("address", AbiType.of("address").toString());
        assertEquals("bool",    AbiType.of("bool").toString());
        assertEquals("string",  AbiType.of("string").toString());
        assertEquals("bytes32", AbiType.of("bytes32").toString());
        assertEquals("uint8",   AbiType.of("uint8").toString());
    }
}
