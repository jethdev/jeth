/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.util;

import java.util.Locale;

/** Ethereum address utilities: EIP-55 checksum, validation, normalization. */
public final class Address {

    public static final String ZERO = "0x0000000000000000000000000000000000000000";

    private Address() {}

    /** Apply EIP-55 checksum encoding. Input can have or omit 0x prefix, any case. */
    public static String toChecksumAddress(String address) {
        if (address == null) throw new IllegalArgumentException("address is null");
        String addr =
                address.startsWith("0x") || address.startsWith("0X")
                        ? address.substring(2)
                        : address;
        if (addr.length() != 40)
            throw new IllegalArgumentException("Invalid address length: " + address);
        addr = addr.toLowerCase(Locale.ROOT);
        byte[] hash = Keccak.hash(addr);
        StringBuilder result = new StringBuilder("0x");
        for (int i = 0; i < 40; i++) {
            char c = addr.charAt(i);
            if (Character.isAlphabetic(c)) {
                int nibble = (hash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0xF;
                result.append(nibble >= 8 ? Character.toUpperCase(c) : c);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** Validate an Ethereum address (40 hex chars, with or without 0x). */
    public static boolean isValid(String address) {
        if (address == null || address.isEmpty()) return false;
        String clean =
                address.startsWith("0x") || address.startsWith("0X")
                        ? address.substring(2)
                        : address;
        return clean.length() == 40 && clean.matches("[0-9a-fA-F]+");
    }

    /** Validate and throw if invalid. */
    public static String validate(String address) {
        if (!isValid(address))
            throw new IllegalArgumentException("Invalid Ethereum address: " + address);
        return address;
    }

    /** Normalize to lowercase 0x-prefixed. */
    public static String normalize(String address) {
        if (!isValid(address)) throw new IllegalArgumentException("Invalid address: " + address);
        String clean =
                address.startsWith("0x") || address.startsWith("0X")
                        ? address.substring(2)
                        : address;
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    /** Compare two addresses case-insensitively. */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) return a == b;
        return normalize(a).equals(normalize(b));
    }

    /** True if the address is the zero address. */
    public static boolean isZero(String address) {
        if (!isValid(address)) return false;
        String clean = address.startsWith("0x") ? address.substring(2) : address;
        return clean.chars().allMatch(c -> c == '0');
    }

    /** True if the address has valid EIP-55 checksum. */
    public static boolean isValidChecksum(String address) {
        if (!isValid(address)) return false;
        String clean = address.startsWith("0x") ? address.substring(2) : address;
        if (clean.equals(clean.toLowerCase()) || clean.equals(clean.toUpperCase(Locale.ROOT)))
            return false;
        return address.equals(toChecksumAddress(clean));
    }
}
