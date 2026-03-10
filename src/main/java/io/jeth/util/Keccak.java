/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.util;

import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.digests.KeccakDigest;

/** Keccak-256 hashing utility (Ethereum uses Keccak-256, NOT SHA3-256). */
public final class Keccak {

    private Keccak() {}

    public static byte[] hash(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] result = new byte[32];
        digest.doFinal(result, 0);
        return result;
    }

    public static byte[] hash(String input) {
        return hash(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hashHex(byte[] input) {
        return Hex.encode(hash(input));
    }

    public static String hashHex(String input) {
        return Hex.encode(hash(input));
    }

    /**
     * Returns the 4-byte function selector for a given ABI signature. e.g.
     * "transfer(address,uint256)" -> "0xa9059cbb"
     */
    public static String functionSelector(String signature) {
        byte[] hash = hash(signature.getBytes(StandardCharsets.UTF_8));
        byte[] selector = new byte[4];
        System.arraycopy(hash, 0, selector, 0, 4);
        return Hex.encode(selector);
    }
}
