/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.util;

import java.math.BigInteger;

/** Hex encoding/decoding utilities. */
public final class Hex {

    private Hex() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Encode bytes to 0x-prefixed lowercase hex. */
    public static String encode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "0x";
        char[] c = new char[bytes.length * 2 + 2];
        c[0] = '0'; c[1] = 'x';
        for (int i = 0; i < bytes.length; i++) {
            c[2 + i * 2]     = HEX[(bytes[i] >> 4) & 0xF];
            c[2 + i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(c);
    }

    /** Encode bytes to lowercase hex WITHOUT 0x prefix. */
    public static String encodeNoPrefx(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] c = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            c[i * 2]     = HEX[(bytes[i] >> 4) & 0xF];
            c[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(c);
    }

    /** Decode a hex string (with or without 0x prefix) to bytes. */
    public static byte[] decode(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (h.isEmpty()) return new byte[0];
        if (h.length() % 2 != 0) h = "0" + h;
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((fromHexChar(h.charAt(i * 2)) << 4) | fromHexChar(h.charAt(i * 2 + 1)));
        }
        return out;
    }

    private static int fromHexChar(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("Invalid hex char: " + c);
    }

    /** Parse a hex string (0x-prefixed or not) to BigInteger. */
    public static BigInteger toBigInteger(String hex) {
        if (hex == null || hex.isEmpty() || hex.equals("0x") || hex.equals("0X")) return BigInteger.ZERO;
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return h.isEmpty() ? BigInteger.ZERO : new BigInteger(h, 16);
    }

    /** Encode BigInteger to 0x-prefixed hex (no leading zeros, except "0x0" for zero). */
    public static String fromBigInteger(BigInteger value) {
        if (value == null || value.signum() == 0) return "0x0";
        return "0x" + value.toString(16);
    }

    /** Encode a long to 0x-prefixed hex. */
    public static String fromLong(long value) {
        return value == 0 ? "0x0" : "0x" + Long.toHexString(value);
    }

    /** True if a string looks like valid hex (with or without 0x). */
    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        String h = s.startsWith("0x") ? s.substring(2) : s;
        return !h.isEmpty() && h.chars().allMatch(c -> (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    /** Zero-pad a hex string to the given byte length. */
    public static String zeroPad(String hex, int byteLen) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        while (h.length() < byteLen * 2) h = "0" + h;
        return "0x" + h;
    }
}
