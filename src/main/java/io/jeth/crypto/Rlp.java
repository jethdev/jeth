/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.crypto;

import io.jeth.util.Hex;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** RLP (Recursive Length Prefix) encoder for Ethereum transaction signing. */
public class Rlp {

    public static byte[] encode(Object item) {
        if (item instanceof byte[] bytes) return encodeBytes(bytes);
        if (item instanceof BigInteger bi) return encodeBytes(toMinimalBytes(bi));
        if (item instanceof Long l) return encodeBytes(toMinimalBytes(BigInteger.valueOf(l)));
        if (item instanceof Integer i) return encodeBytes(toMinimalBytes(BigInteger.valueOf(i)));
        if (item instanceof List<?> list) return encodeList(list);
        if (item instanceof String s) return encodeBytes(hexToBytes(s));
        throw new IllegalArgumentException("Cannot RLP encode: " + item.getClass());
    }

    private static byte[] encodeBytes(byte[] input) {
        if (input.length == 0) return new byte[] {(byte) 0x80};
        if (input.length == 1 && (input[0] & 0xFF) < 0x80) return input;
        return concat(encodeLength(input.length, 0x80), input);
    }

    private static byte[] encodeList(List<?> items) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (Object item : items) {
            byte[] encoded = encode(item);
            baos.write(encoded, 0, encoded.length);
        }
        byte[] payload = baos.toByteArray();
        return concat(encodeLength(payload.length, 0xC0), payload);
    }

    private static byte[] encodeLength(int length, int offset) {
        if (length < 56) return new byte[] {(byte) (length + offset)};
        byte[] lenBytes = toBigEndianBytes(length);
        byte[] result = new byte[1 + lenBytes.length];
        result[0] = (byte) (offset + 55 + lenBytes.length);
        System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
        return result;
    }

    private static byte[] toMinimalBytes(BigInteger value) {
        if (value.signum() == 0) return new byte[0];
        byte[] raw = value.toByteArray();
        // Strip leading zero byte added by BigInteger for sign
        if (raw[0] == 0 && raw.length > 1) {
            byte[] stripped = new byte[raw.length - 1];
            System.arraycopy(raw, 1, stripped, 0, stripped.length);
            return stripped;
        }
        return raw;
    }

    private static byte[] toBigEndianBytes(int value) {
        if (value == 0) return new byte[] {0};
        int byteCount = 0;
        int v = value;
        while (v > 0) {
            byteCount++;
            v >>= 8;
        }
        byte[] result = new byte[byteCount];
        for (int i = byteCount - 1; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.equals("0x") || hex.isEmpty()) return new byte[0];
        return Hex.decode(hex);
    }

    // ─── Decode ──────────────────────────────────────────────────────────────

    /**
     * Decode a top-level RLP item.
     *
     * @return a {@code byte[]} for byte-string items, or a {@code List<Object>} for lists. Nested
     *     lists are decoded recursively, so items inside are also either {@code byte[]} or {@code
     *     List<Object>}.
     */
    public static Object decode(byte[] input) {
        return decodeAt(input, 0)[0];
    }

    /**
     * Decode all top-level items in {@code input} starting at {@code offset}. Returns a two-element
     * array: [decodedValue, nextOffset].
     */
    private static Object[] decodeAt(byte[] input, int offset) {
        int prefix = input[offset] & 0xFF;

        // Single byte 0x00–0x7f: value is itself
        if (prefix < 0x80) {
            return new Object[] {new byte[] {(byte) prefix}, offset + 1};
        }

        // Short string 0x80–0xb7: 0–55 bytes
        if (prefix <= 0xb7) {
            int len = prefix - 0x80;
            byte[] bytes = copyRange(input, offset + 1, len);
            return new Object[] {bytes, offset + 1 + len};
        }

        // Long string 0xb8–0xbf: length of length in (prefix - 0xb7) bytes
        if (prefix <= 0xbf) {
            int lenBytes = prefix - 0xb7;
            int len = readLength(input, offset + 1, lenBytes);
            byte[] bytes = copyRange(input, offset + 1 + lenBytes, len);
            return new Object[] {bytes, offset + 1 + lenBytes + len};
        }

        // Short list 0xc0–0xf7: 0–55 bytes of payload
        if (prefix <= 0xf7) {
            int payloadLen = prefix - 0xc0;
            return new Object[] {
                decodeList(input, offset + 1, payloadLen), offset + 1 + payloadLen
            };
        }

        // Long list 0xf8–0xff: length of length in (prefix - 0xf7) bytes
        int lenBytes = prefix - 0xf7;
        int payloadLen = readLength(input, offset + 1, lenBytes);
        return new Object[] {
            decodeList(input, offset + 1 + lenBytes, payloadLen), offset + 1 + lenBytes + payloadLen
        };
    }

    private static List<Object> decodeList(byte[] input, int start, int payloadLen) {
        List<Object> items = new ArrayList<>();
        int pos = start;
        int end = start + payloadLen;
        while (pos < end) {
            Object[] result = decodeAt(input, pos);
            items.add(result[0]);
            pos = (int) result[1];
        }
        return Collections.unmodifiableList(items);
    }

    private static int readLength(byte[] input, int offset, int numBytes) {
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (input[offset + i] & 0xFF);
        }
        return len;
    }

    private static byte[] copyRange(byte[] input, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(input, offset, out, 0, len);
        return out;
    }
}
