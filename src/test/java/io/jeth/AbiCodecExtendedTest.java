/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.util.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended AbiCodec tests covering type-specific encode/decode paths not exercised
 * by the existing AbiCodecTest: signed integers, fixed-size bytes, tuples, arrays,
 * and the low-level decodeValue/decodeAddress/decodeFixedBytes helpers.
 */
class AbiCodecExtendedTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Signed integers (int8, int128, int256)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Signed integers")
    class SignedIntegers {

        @Test @DisplayName("encodeInt then decodeSignedInt roundtrip: positive")
        void signed_positive() {
            BigInteger val = BigInteger.valueOf(42);
            byte[] enc = AbiCodec.encodeInt(val);
            assertEquals(32, enc.length);
            BigInteger dec = AbiCodec.decodeSignedInt(enc, 0, 256);
            assertEquals(val, dec);
        }

        @Test @DisplayName("encodeInt then decodeSignedInt roundtrip: negative")
        void signed_negative() {
            BigInteger val = BigInteger.valueOf(-1);
            byte[] enc = AbiCodec.encodeInt(val);
            assertEquals(32, enc.length);
            BigInteger dec = AbiCodec.decodeSignedInt(enc, 0, 256);
            assertEquals(val, dec);
        }

        @Test @DisplayName("encodeInt: -1 as int256 = 0xff...ff (all ones)")
        void minus_one_is_all_ones() {
            byte[] enc = AbiCodec.encodeInt(BigInteger.valueOf(-1));
            for (byte b : enc) assertEquals((byte) 0xff, b, "all bytes must be 0xff for -1");
        }

        @Test @DisplayName("decodeSignedInt: int8 max positive = 127")
        void int8_max() {
            byte[] enc = AbiCodec.encodeUint256(BigInteger.valueOf(127));
            assertEquals(BigInteger.valueOf(127), AbiCodec.decodeSignedInt(enc, 0, 8));
        }

        @Test @DisplayName("decodeSignedInt: int8 min negative = -128 (0x80 in MSB)")
        void int8_min() {
            // 128 as uint, but interpreted as int8 = -128
            byte[] enc = AbiCodec.encodeUint256(BigInteger.valueOf(128));
            assertEquals(BigInteger.valueOf(-128), AbiCodec.decodeSignedInt(enc, 0, 8));
        }

        @Test @DisplayName("encode/decode int256 via AbiType roundtrip")
        void int256_roundtrip_via_type() {
            BigInteger val = BigInteger.valueOf(-999_999_999L);
            byte[] enc = AbiCodec.encode(new AbiType[]{AbiType.INT256}, new Object[]{val});
            Object[] dec = AbiCodec.decode(new AbiType[]{AbiType.INT256}, enc);
            assertEquals(val, dec[0]);
        }

        @Test @DisplayName("encode/decode int8 negative roundtrip")
        void int8_negative_roundtrip() {
            BigInteger val = BigInteger.valueOf(-42);
            AbiType int8 = AbiType.of("int8");
            byte[] enc = AbiCodec.encode(new AbiType[]{int8}, new Object[]{val});
            Object[] dec = AbiCodec.decode(new AbiType[]{int8}, enc);
            assertEquals(val, dec[0]);
        }

        @Test @DisplayName("encode/decode int128 max/min roundtrip")
        void int128_boundaries() {
            BigInteger max128 = BigInteger.TWO.pow(127).subtract(BigInteger.ONE);
            BigInteger min128 = BigInteger.TWO.pow(127).negate();
            AbiType int128 = AbiType.of("int128");

            byte[] encMax = AbiCodec.encode(new AbiType[]{int128}, new Object[]{max128});
            byte[] encMin = AbiCodec.encode(new AbiType[]{int128}, new Object[]{min128});

            assertEquals(max128, AbiCodec.decode(new AbiType[]{int128}, encMax)[0]);
            assertEquals(min128, AbiCodec.decode(new AbiType[]{int128}, encMin)[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fixed-size bytes (bytes1, bytes4, bytes32)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Fixed bytes")
    class FixedBytes {

        @Test @DisplayName("encodeFixedBytes left-pads to 32 bytes")
        void encode_fixed_bytes_padding() {
            byte[] val = new byte[]{0x01, 0x02, 0x03};
            byte[] enc = AbiCodec.encodeFixedBytes(val, 3);
            assertEquals(32, enc.length);
            // Fixed bytes are left-aligned (no padding on left)
            assertEquals(0x01, enc[0] & 0xff);
            assertEquals(0x02, enc[1] & 0xff);
            assertEquals(0x03, enc[2] & 0xff);
            // Remaining bytes are zero
            for (int i = 3; i < 32; i++) assertEquals(0, enc[i]);
        }

        @Test @DisplayName("decodeFixedBytes recovers original bytes")
        void decode_fixed_bytes() {
            byte[] val = new byte[]{(byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef};
            byte[] enc = AbiCodec.encodeFixedBytes(val, 4);
            byte[] dec = AbiCodec.decodeFixedBytes(enc, 0, 4);
            assertArrayEquals(val, dec);
        }

        @Test @DisplayName("bytes4 roundtrip via AbiType")
        void bytes4_roundtrip() {
            byte[] val = new byte[]{0x12, 0x34, 0x56, 0x78};
            AbiType t  = AbiType.of("bytes4");
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{val});
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            assertArrayEquals(val, (byte[]) dec[0]);
        }

        @Test @DisplayName("bytes32 full slot roundtrip")
        void bytes32_roundtrip() {
            byte[] val = new byte[32];
            for (int i = 0; i < 32; i++) val[i] = (byte) (i + 1);
            AbiType t  = AbiType.of("bytes32");
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{val});
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            assertArrayEquals(val, (byte[]) dec[0]);
        }

        @Test @DisplayName("bytes1 roundtrip")
        void bytes1_roundtrip() {
            byte[] val = new byte[]{(byte)0xab};
            AbiType t  = AbiType.of("bytes1");
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{val});
            byte[] dec = (byte[]) AbiCodec.decode(new AbiType[]{t}, enc)[0];
            assertEquals(val[0], dec[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tuples
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Tuples")
    class Tuples {

        @Test @DisplayName("encodeTuple: (address, uint256) roundtrip")
        void tuple_address_uint() {
            AbiType tupleType = AbiType.of("(address,uint256)");
            String  addr = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
            BigInteger val = BigInteger.valueOf(1_000_000L);
            Object[] values = {addr, val};

            byte[] enc = AbiCodec.encodeTuple(tupleType, values);
            assertNotNull(enc);
            assertTrue(enc.length >= 64);

            // Roundtrip via encode/decode
            byte[] enc2 = AbiCodec.encode(new AbiType[]{tupleType}, new Object[]{values});
            Object[] dec = AbiCodec.decode(new AbiType[]{tupleType}, enc2);
            Object[] inner = (Object[]) dec[0];
            assertTrue(addr.equalsIgnoreCase((String) inner[0]));
            assertEquals(val, inner[1]);
        }

        @Test @DisplayName("tuple with dynamic string member")
        void tuple_with_string() {
            AbiType tupleType = AbiType.of("(uint256,string)");
            Object[] values = {BigInteger.valueOf(42), "hello"};
            byte[] enc = AbiCodec.encode(new AbiType[]{tupleType}, new Object[]{values});
            Object[] dec = AbiCodec.decode(new AbiType[]{tupleType}, enc);
            Object[] inner = (Object[]) dec[0];
            assertEquals(BigInteger.valueOf(42), inner[0]);
            assertEquals("hello", inner[1]);
        }

        @Test @DisplayName("nested tuple: ((uint256,bool), address)")
        void nested_tuple() {
            AbiType inner = AbiType.of("(uint256,bool)");
            AbiType outer = AbiType.of("((uint256,bool),address)");
            String addr   = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
            Object[] innerVals = {BigInteger.valueOf(99), true};
            Object[] outerVals = {innerVals, addr};

            byte[] enc = AbiCodec.encode(new AbiType[]{outer}, new Object[]{outerVals});
            Object[] dec = AbiCodec.decode(new AbiType[]{outer}, enc);
            Object[] outerDec = (Object[]) dec[0];
            Object[] innerDec = (Object[]) outerDec[0];
            assertEquals(BigInteger.valueOf(99), innerDec[0]);
            assertEquals(true, innerDec[1]);
            assertTrue(addr.equalsIgnoreCase((String) outerDec[1]));
        }

        @Test @DisplayName("Uniswap-style QuoteParams tuple: (address,address,uint256,uint24,uint160)")
        void uniswap_quote_params_tuple() {
            AbiType tupleType = AbiType.of("(address,address,uint256,uint24,uint160)");
            Object[] values = {
                "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",
                new BigInteger("1000000000000000000"),
                BigInteger.valueOf(3000),
                BigInteger.ZERO
            };
            byte[] enc = AbiCodec.encode(new AbiType[]{tupleType}, new Object[]{values});
            Object[] dec = AbiCodec.decode(new AbiType[]{tupleType}, enc);
            Object[] inner = (Object[]) dec[0];
            assertEquals(new BigInteger("1000000000000000000"), inner[2]);
            assertEquals(BigInteger.valueOf(3000), inner[3]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Arrays
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Arrays")
    class Arrays {

        @Test @DisplayName("dynamic uint256[] roundtrip")
        void dynamic_uint_array() {
            AbiType t = AbiType.of("uint256[]");
            Object[] values = {
                new Object[]{BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)}
            };
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, values);
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            Object[] arr = (Object[]) dec[0];
            assertEquals(3, arr.length);
            assertEquals(BigInteger.ONE, arr[0]);
            assertEquals(BigInteger.valueOf(3), arr[2]);
        }

        @Test @DisplayName("fixed uint256[3] roundtrip")
        void fixed_uint_array() {
            AbiType t = AbiType.of("uint256[3]");
            Object[] arr = {BigInteger.valueOf(10), BigInteger.valueOf(20), BigInteger.valueOf(30)};
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{arr});
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            Object[] result = (Object[]) dec[0];
            assertEquals(BigInteger.valueOf(10), result[0]);
            assertEquals(BigInteger.valueOf(20), result[1]);
            assertEquals(BigInteger.valueOf(30), result[2]);
        }

        @Test @DisplayName("address[] roundtrip")
        void address_array() {
            String a1 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
            String a2 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
            AbiType t  = AbiType.of("address[]");
            Object[] arr = {a1, a2};
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{arr});
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            Object[] result = (Object[]) dec[0];
            assertEquals(2, result.length);
            assertTrue(a1.equalsIgnoreCase((String) result[0]));
            assertTrue(a2.equalsIgnoreCase((String) result[1]));
        }

        @Test @DisplayName("empty dynamic array encodes/decodes correctly")
        void empty_array() {
            AbiType t = AbiType.of("uint256[]");
            byte[] enc = AbiCodec.encode(new AbiType[]{t}, new Object[]{new Object[0]});
            Object[] dec = AbiCodec.decode(new AbiType[]{t}, enc);
            assertEquals(0, ((Object[]) dec[0]).length);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Low-level helpers: decodeAddress, decodeValue, decodeBigInt
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Low-level decode helpers")
    class LowLevelDecode {

        @Test @DisplayName("decodeAddress extracts checksummed address from 32-byte slot")
        void decode_address() {
            String addr = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
            byte[] enc  = AbiCodec.encodeAddress(addr);
            String dec  = AbiCodec.decodeAddress(enc, 0);
            assertTrue(addr.equalsIgnoreCase(dec), "decoded address must match: " + dec);
        }

        @Test @DisplayName("decodeAddress result is EIP-55 checksummed")
        void decode_address_checksummed() {
            String addr = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"; // all lowercase
            byte[] enc  = AbiCodec.encodeAddress(addr);
            String dec  = AbiCodec.decodeAddress(enc, 0);
            // Must be checksummed (mixed case)
            assertFalse(dec.substring(2).equals(dec.substring(2).toLowerCase()),
                    "decoded address must be EIP-55 checksummed, got: " + dec);
        }

        @Test @DisplayName("decodeBigInt handles zero")
        void decode_big_int_zero() {
            byte[] enc = AbiCodec.encodeUint256(BigInteger.ZERO);
            assertEquals(BigInteger.ZERO, AbiCodec.decodeBigInt(enc, 0));
        }

        @Test @DisplayName("decodeBigInt handles max uint256")
        void decode_big_int_max() {
            BigInteger max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
            byte[] enc = AbiCodec.encodeUint256(max);
            assertEquals(max, AbiCodec.decodeBigInt(enc, 0));
        }

        @Test @DisplayName("decodeValue dispatches correctly for uint type")
        void decode_value_uint() {
            byte[] enc = AbiCodec.encodeUint256(BigInteger.valueOf(12345));
            Object dec = AbiCodec.decodeValue(AbiType.UINT256, enc, 0);
            assertEquals(BigInteger.valueOf(12345), dec);
        }

        @Test @DisplayName("decodeValue dispatches correctly for bool type")
        void decode_value_bool() {
            byte[] encT = AbiCodec.encodeBool(true);
            byte[] encF = AbiCodec.encodeBool(false);
            assertEquals(true,  AbiCodec.decodeValue(AbiType.BOOL, encT, 0));
            assertEquals(false, AbiCodec.decodeValue(AbiType.BOOL, encF, 0));
        }

        @Test @DisplayName("decodeValue dispatches correctly for address type")
        void decode_value_address() {
            String addr = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
            byte[] enc  = AbiCodec.encodeAddress(addr);
            Object dec  = AbiCodec.decodeValue(AbiType.ADDRESS, enc, 0);
            assertTrue(addr.equalsIgnoreCase((String) dec));
        }

        @Test @DisplayName("decodeFixedBytes at non-zero offset")
        void decode_fixed_bytes_offset() {
            byte[] data = new byte[64];
            data[32] = 0x42; data[33] = 0x43;
            byte[] dec = AbiCodec.decodeFixedBytes(data, 32, 2);
            assertEquals(0x42, dec[0] & 0xff);
            assertEquals(0x43, dec[1] & 0xff);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Multi-type compound encode/decode
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Mixed type roundtrips")
    class MixedTypes {

        @Test @DisplayName("(address, int256, bool, bytes32, string) full roundtrip")
        void all_types_roundtrip() {
            AbiType[] types = {
                AbiType.ADDRESS,
                AbiType.INT256,
                AbiType.BOOL,
                AbiType.of("bytes32"),
                AbiType.STRING
            };
            String addr  = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
            BigInteger n = BigInteger.valueOf(-42);
            byte[] b32   = new byte[32]; b32[0] = 1; b32[31] = 99;
            String s     = "hello world";

            Object[] in = {addr, n, true, b32, s};
            byte[] enc  = AbiCodec.encode(types, in);
            Object[] dec = AbiCodec.decode(types, enc);

            assertTrue(addr.equalsIgnoreCase((String) dec[0]));
            assertEquals(n, dec[1]);
            assertEquals(true, dec[2]);
            assertArrayEquals(b32, (byte[]) dec[3]);
            assertEquals(s, dec[4]);
        }

        @Test @DisplayName("(uint256, uint256[]) mixed static+dynamic")
        void static_and_dynamic() {
            AbiType[] types = {AbiType.UINT256, AbiType.of("uint256[]")};
            Object[] arr    = {BigInteger.TEN, BigInteger.valueOf(20)};
            Object[] in     = {BigInteger.valueOf(999), arr};

            byte[] enc  = AbiCodec.encode(types, in);
            Object[] dec = AbiCodec.decode(types, enc);

            assertEquals(BigInteger.valueOf(999), dec[0]);
            Object[] decArr = (Object[]) dec[1];
            assertEquals(2, decArr.length);
            assertEquals(BigInteger.TEN, decArr[0]);
        }
    }
}
