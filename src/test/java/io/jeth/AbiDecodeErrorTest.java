/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiDecodeError;
import io.jeth.abi.AbiType;
import io.jeth.util.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class AbiDecodeErrorTest {

    @Test @DisplayName("decode null → generic revert message")
    void decode_null()  { assertFalse(AbiDecodeError.decode((String)null).isEmpty()); }

    @Test @DisplayName("decode empty → generic revert message")
    void decode_empty() { assertFalse(AbiDecodeError.decode("").isEmpty()); }

    @Test @DisplayName("decode 0x → generic revert message")
    void decode_0x()    { assertFalse(AbiDecodeError.decode("0x").isEmpty()); }

    @Test @DisplayName("decode Error(string) — standard revert")
    void decode_error_string() {
        byte[] msg = AbiCodec.encode(new AbiType[]{AbiType.STRING}, new Object[]{"ERC20: transfer amount exceeds balance"});
        String result = AbiDecodeError.decode(concat(Hex.decode("08c379a0"), msg));
        assertTrue(result.contains("ERC20: transfer amount exceeds balance"), "Got: " + result);
        assertTrue(result.startsWith("execution reverted:"), "Got: " + result);
    }

    @Test @DisplayName("decode Error(string) — short message")
    void decode_error_short() {
        byte[] msg = AbiCodec.encode(new AbiType[]{AbiType.STRING}, new Object[]{"FAIL"});
        assertTrue(AbiDecodeError.decode(concat(Hex.decode("08c379a0"), msg)).contains("FAIL"));
    }

    @Test @DisplayName("decode Panic(0x01) — assertion failed")
    void decode_panic_assert() {
        byte[] data = AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.ONE});
        String r = AbiDecodeError.decode(concat(Hex.decode("4e487b71"), data));
        assertTrue(r.contains("Panic"), "Got: " + r);
        assertTrue(r.contains("assertion"), "Got: " + r);
    }

    @Test @DisplayName("decode Panic(0x11) — arithmetic overflow")
    void decode_panic_overflow() {
        byte[] data = AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.valueOf(0x11)});
        assertTrue(AbiDecodeError.decode(concat(Hex.decode("4e487b71"), data)).contains("overflow"));
    }

    @Test @DisplayName("decode Panic(0x12) — division by zero")
    void decode_panic_divzero() {
        byte[] data = AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.valueOf(0x12)});
        String r = AbiDecodeError.decode(concat(Hex.decode("4e487b71"), data));
        assertTrue(r.contains("zero") || r.contains("division"), "Got: " + r);
    }

    @Test @DisplayName("decode Panic(0x32) — array out of bounds")
    void decode_panic_bounds() {
        byte[] data = AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.valueOf(0x32)});
        assertTrue(AbiDecodeError.decode(concat(Hex.decode("4e487b71"), data)).contains("bounds"));
    }

    @Test @DisplayName("decode custom error — shows selector as 0xXXXXXXXX")
    void decode_custom() {
        byte[] custom = Hex.decode("deadbeef" + "0".repeat(64));
        String r = AbiDecodeError.decode(custom);
        assertTrue(r.contains("0xdeadbeef"), "Got: " + r);
    }

    @Test @DisplayName("errorSelector computes 4-byte hex selector")
    void error_selector_format() {
        String s = AbiDecodeError.errorSelector("Unauthorized()");
        assertEquals(10, s.length());
        assertTrue(s.startsWith("0x"));
    }

    @Test @DisplayName("decode from hex string (RPC error format)")
    void decode_from_hex_string() {
        byte[] msg = AbiCodec.encode(new AbiType[]{AbiType.STRING}, new Object[]{"test error"});
        String hexStr = Hex.encode(concat(Hex.decode("08c379a0"), msg));
        assertTrue(AbiDecodeError.decode(hexStr).contains("test error"));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
