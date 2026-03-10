/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.util.Address;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import io.jeth.util.Units;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void testHexEncodeDecode() {
        byte[] bytes = {0x01, 0x02, (byte) 0xFF, 0x00};
        String hex = Hex.encode(bytes);
        assertEquals("0x0102ff00", hex);
        assertArrayEquals(bytes, Hex.decode(hex));
    }

    @Test
    void testHexDecodeWithoutPrefix() {
        byte[] bytes = Hex.decode("deadbeef");
        assertEquals(4, bytes.length);
        assertEquals((byte) 0xDE, bytes[0]);
    }

    @Test
    void testHexToBigInteger() {
        assertEquals(BigInteger.valueOf(255), Hex.toBigInteger("0xff"));
        assertEquals(BigInteger.ZERO, Hex.toBigInteger("0x0"));
        assertEquals(BigInteger.ZERO, Hex.toBigInteger("0x"));
    }

    @Test
    void testKeccak256KnownValues() {
        // keccak256("") = c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
        byte[] emptyHash = Keccak.hash(new byte[0]);
        assertEquals(
                "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                Hex.encode(emptyHash));
    }

    @Test
    void testFunctionSelectorKeccak() {
        // transfer(address,uint256) -> first 4 bytes of keccak = a9059cbb
        String selector = Keccak.functionSelector("transfer(address,uint256)");
        assertEquals("0xa9059cbb", selector);
    }

    @Test
    void testAddressChecksum() {
        String lower = "0xdac17f958d2ee523a2206206994597c13d831ec7";
        String checksummed = Address.toChecksumAddress(lower);
        // EIP-55 checksum
        assertEquals("0xdAC17F958D2ee523a2206206994597C13D831ec7", checksummed);
    }

    @Test
    void testAddressValidation() {
        assertTrue(Address.isValid("0xdAC17F958D2ee523a2206206994597C13D831ec7"));
        assertTrue(Address.isValid("0x0000000000000000000000000000000000000000"));
        assertFalse(Address.isValid("0x123")); // too short
        assertFalse(Address.isValid("invalid"));
        assertFalse(Address.isValid(null));
    }

    @Test
    void testAddressNormalize() {
        String normalized = Address.normalize("0xDAC17F958D2EE523A2206206994597C13D831EC7");
        assertEquals("0xdac17f958d2ee523a2206206994597c13d831ec7", normalized);
    }

    @Test
    void testUnitsEtherConversion() {
        BigInteger oneEther = Units.toWei("1.0");
        assertEquals(new BigInteger("1000000000000000000"), oneEther);
    }

    @Test
    void testUnitsGweiConversion() {
        BigInteger oneGwei = Units.gweiToWei(1L);
        assertEquals(BigInteger.valueOf(1_000_000_000L), oneGwei);
    }

    @Test
    void testUnitsFromWei() {
        BigInteger wei = new BigInteger("1500000000000000000"); // 1.5 ETH
        assertEquals("1.5", Units.fromWei(wei).stripTrailingZeros().toPlainString());
    }

    @Test
    void testHexPad32() {
        String padded = Hex.pad32("0xff");
        assertEquals(64, padded.length());
        assertTrue(padded.endsWith("ff"));
    }
}
