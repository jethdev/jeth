/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Decode Solidity revert reasons and custom errors from raw revert data.
 *
 * <pre>
 * try {
 *     client.call(req).join();
 * } catch (EthException e) {
 *     String reason = AbiDecodeError.decode(e.getRevertData());
 *     System.out.println("Reverted: " + reason);
 *     // e.g. "ERC20: transfer amount exceeds balance"
 * }
 * </pre>
 */
public final class AbiDecodeError {

    // Error(string) selector: 0x08c379a0
    private static final byte[] ERROR_SELECTOR = Hex.decode("08c379a0");
    // Panic(uint256) selector: 0x4e487b71
    private static final byte[] PANIC_SELECTOR = Hex.decode("4e487b71");

    private static final Map<Integer, String> PANIC_CODES =
            Map.of(
                    0x00, "generic panic",
                    0x01, "assertion failed",
                    0x11, "arithmetic overflow/underflow",
                    0x12, "divide by zero",
                    0x21, "invalid enum value",
                    0x22, "storage out of bounds",
                    0x31, "pop on empty array",
                    0x32, "array index out of bounds",
                    0x41, "out of memory",
                    0x51, "invalid function pointer");

    private AbiDecodeError() {}

    /** Decode a revert reason from raw hex revert data. Returns a human-readable string. */
    public static String decode(String hexData) {
        if (hexData == null || hexData.isEmpty() || hexData.equals("0x"))
            return "execution reverted (no reason)";
        try {
            byte[] data = Hex.decode(hexData);
            return decode(data);
        } catch (Exception e) {
            return "execution reverted: " + hexData;
        }
    }

    public static String decode(byte[] data) {
        if (data == null || data.length < 4) return "execution reverted (no data)";

        // Error(string)
        if (startsWith(data, ERROR_SELECTOR)) {
            try {
                byte[] payload = new byte[data.length - 4];
                System.arraycopy(data, 4, payload, 0, payload.length);
                Object[] decoded = AbiCodec.decode(new AbiType[] {AbiType.STRING}, payload);
                return "execution reverted: " + decoded[0];
            } catch (Exception e) {
                return "execution reverted (malformed Error)";
            }
        }

        // Panic(uint256)
        if (startsWith(data, PANIC_SELECTOR)) {
            try {
                byte[] payload = new byte[data.length - 4];
                System.arraycopy(data, 4, payload, 0, payload.length);
                Object[] decoded = AbiCodec.decode(new AbiType[] {AbiType.UINT256}, payload);
                int code = ((BigInteger) decoded[0]).intValue();
                String desc = PANIC_CODES.getOrDefault(code, "unknown panic code " + code);
                return "execution reverted (Panic 0x" + Integer.toHexString(code) + "): " + desc;
            } catch (Exception e) {
                return "execution reverted (Panic)";
            }
        }

        // Custom error — show selector
        String selector = Hex.encode(new byte[] {data[0], data[1], data[2], data[3]});
        return "execution reverted with custom error 0x" + selector;
    }

    /**
     * Compute the 4-byte selector for a custom error signature. e.g.
     * errorSelector("InsufficientBalance(address,uint256)")
     */
    public static String errorSelector(String errorSignature) {
        byte[] hash = Keccak.hash(errorSignature.getBytes(StandardCharsets.UTF_8));
        return Hex.encode(new byte[] {hash[0], hash[1], hash[2], hash[3]});
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
