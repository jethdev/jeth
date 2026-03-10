/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.crypto;

import io.jeth.util.Hex;
import java.math.BigInteger;

/** ECDSA Signature (r, s, v components). */
public class Signature {
    public final BigInteger r;
    public final BigInteger s;
    public final int v;

    public Signature(BigInteger r, BigInteger s, int v) {
        this.r = r;
        this.s = s;
        this.v = v;
    }

    /** Returns the signature as 65-byte array: r (32) + s (32) + v (1) */
    public byte[] toBytes() {
        byte[] result = new byte[65];
        byte[] rBytes = padTo32(r.toByteArray());
        byte[] sBytes = padTo32(s.toByteArray());
        System.arraycopy(rBytes, 0, result, 0, 32);
        System.arraycopy(sBytes, 0, result, 32, 32);
        result[64] = (byte) v;
        return result;
    }

    /** Returns hex representation: 0x + r + s + v */
    public String toHex() {
        return Hex.encode(toBytes());
    }

    private static byte[] padTo32(byte[] bytes) {
        if (bytes.length == 32) return bytes;
        byte[] padded = new byte[32];
        if (bytes.length < 32) {
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        } else {
            // BigInteger sometimes adds leading 0x00 sign byte
            System.arraycopy(bytes, bytes.length - 32, padded, 0, 32);
        }
        return padded;
    }

    @Override
    public String toString() {
        return "Signature{r=" + r.toString(16) + ", s=" + s.toString(16) + ", v=" + v + "}";
    }
}
