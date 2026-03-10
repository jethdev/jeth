/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import io.jeth.util.Address;
import io.jeth.util.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Complete Solidity ABI encoder/decoder.
 *
 * Supports all Solidity types:
 * - Primitives: uint/int (8-256 bit), address, bool, bytesN, bytes, string
 * - Arrays: T[], T[N] (including nested arrays)
 * - Tuples: (T1,T2,...), (T1,T2,...)[], (T1,T2,...)[N]
 *
 * Follows the ABI spec: https://docs.soliditylang.org/en/latest/abi-spec.html
 */
public class AbiCodec {

    // ─── Encode ──────────────────────────────────────────────────────────────

    /**
     * Encode a list of typed values into ABI bytes.
     * This is the top-level encoding — the "calldata" minus the function selector.
     */
    public static byte[] encode(AbiType[] types, Object[] values) {
        if (types.length != values.length)
            throw new AbiException("types/values length mismatch: " + types.length + " vs " + values.length);
        return encodeSequence(types, values, 0);
    }

    /**
     * Encode a single tuple (struct) — same as encode() but takes a single AbiType.TUPLE.
     */
    public static byte[] encodeTuple(AbiType tupleType, Object[] values) {
        if (!tupleType.isTuple()) throw new AbiException("Type is not a tuple: " + tupleType);
        return encodeSequence(tupleType.getTupleTypes(), values, 0);
    }

    /**
     * Core encoding: handles head/tail layout for any sequence of typed values.
     */
    static byte[] encodeSequence(AbiType[] types, Object[] values, int tailBaseOffset) {
        // Step 1: encode each value and decide head (32-byte slot or offset pointer)
        byte[][] encodedTails = new byte[types.length][];
        boolean[] isDynamic = new boolean[types.length];

        for (int i = 0; i < types.length; i++) {
            isDynamic[i] = types[i].isDynamic();
            if (isDynamic[i]) {
                encodedTails[i] = encodeValue(types[i], values[i]);
            }
        }

        // Step 2: compute tail offsets
        int headSize = types.length * 32;
        int[] tailOffsets = new int[types.length];
        int tailSize = 0;
        for (int i = 0; i < types.length; i++) {
            if (isDynamic[i]) {
                tailOffsets[i] = tailBaseOffset + headSize + tailSize;
                tailSize += encodedTails[i].length;
            }
        }

        // Step 3: assemble output
        byte[] result = new byte[headSize + tailSize];
        int pos = 0;

        for (int i = 0; i < types.length; i++) {
            if (isDynamic[i]) {
                // Write offset pointer
                byte[] offsetBytes = encodeUint256(BigInteger.valueOf(tailOffsets[i]));
                System.arraycopy(offsetBytes, 0, result, pos, 32);
            } else {
                byte[] head = encodeValue(types[i], values[i]);
                System.arraycopy(head, 0, result, pos, 32);
            }
            pos += 32;
        }

        // Write tails
        for (int i = 0; i < types.length; i++) {
            if (isDynamic[i]) {
                System.arraycopy(encodedTails[i], 0, result, pos, encodedTails[i].length);
                pos += encodedTails[i].length;
            }
        }
        return result;
    }

    /**
     * Encode a single value of a given type. Returns 32 bytes for static types,
     * variable length for dynamic.
     */
    public static byte[] encodeValue(AbiType type, Object value) {
        if (type.isArray()) return encodeArray(type, value);
        if (type.isTuple()) return encodeTupleValue(type, value);

        return switch (type.baseType()) {
            case "uint"    -> encodeUint256(toBigInteger(value));
            case "int"     -> encodeInt(toBigInteger(value));
            case "address" -> encodeAddress(value.toString());
            case "bool"    -> encodeBool(toBoolean(value));
            case "bytes"   -> encodeDynamicBytes(toBytes(value));
            case "string"  -> encodeString(value.toString());
            default -> {
                if (type.baseType().startsWith("bytes")) yield encodeFixedBytes(toBytes(value), type.size());
                throw new AbiException("Unsupported type: " + type);
            }
        };
    }

    private static byte[] encodeTupleValue(AbiType tupleType, Object value) {
        AbiType[] components = tupleType.getTupleTypes();
        Object[] values;
        if (value instanceof Object[] arr) {
            values = arr;
        } else if (value instanceof List<?> list) {
            values = list.toArray();
        } else {
            throw new AbiException("Tuple value must be Object[] or List, got: " + value.getClass());
        }
        return encodeSequence(components, values, 0);
    }

    private static byte[] encodeArray(AbiType arrayType, Object value) {
        AbiType elemType = arrayType.getArrayElementType();
        Object[] elements = toObjectArray(value);

        if (arrayType.isDynamicArray()) {
            // Dynamic array: encode length + elements
            byte[] encodedElements = encodeArrayElements(elemType, elements);
            byte[] lenBytes = encodeUint256(BigInteger.valueOf(elements.length));
            return concat(lenBytes, encodedElements);
        } else {
            // Fixed array: no length prefix, just elements
            if (elements.length != arrayType.getArraySize())
                throw new AbiException("Fixed array size mismatch: expected " + arrayType.getArraySize()
                    + " but got " + elements.length);
            return encodeArrayElements(elemType, elements);
        }
    }

    private static byte[] encodeArrayElements(AbiType elemType, Object[] elements) {
        // Elements themselves form a sub-sequence
        AbiType[] types = new AbiType[elements.length];
        Arrays.fill(types, elemType);
        return encodeSequence(types, elements, 0);
    }

    public static byte[] encodeUint256(BigInteger value) {
        if (value.signum() < 0) {
            value = value.add(BigInteger.ONE.shiftLeft(256));
        }
        byte[] raw = value.toByteArray();
        byte[] padded = new byte[32];
        if (raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length); // strip sign byte
        System.arraycopy(raw, 0, padded, 32 - Math.min(raw.length, 32), Math.min(raw.length, 32));
        return padded;
    }

    public static byte[] encodeInt(BigInteger value) {
        if (value.signum() < 0) {
            value = value.add(BigInteger.ONE.shiftLeft(256));
        }
        return encodeUint256(value);
    }

    public static byte[] encodeAddress(String address) {
        String addr = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        if (addr.length() != 40) throw new AbiException("Invalid address length: " + address);
        byte[] addrBytes = Hex.decode(addr);
        byte[] padded = new byte[32];
        System.arraycopy(addrBytes, 0, padded, 12, 20);
        return padded;
    }

    public static byte[] encodeBool(boolean value) {
        byte[] padded = new byte[32];
        padded[31] = value ? (byte) 1 : 0;
        return padded;
    }

    public static byte[] encodeFixedBytes(byte[] value, int declaredSize) {
        byte[] padded = new byte[32];
        int copy = Math.min(value.length, declaredSize > 0 ? declaredSize : value.length);
        System.arraycopy(value, 0, padded, 0, copy);
        return padded;
    }

    public static byte[] encodeDynamicBytes(byte[] value) {
        byte[] lenBytes = encodeUint256(BigInteger.valueOf(value.length));
        int paddedLen = (value.length + 31) / 32 * 32;
        byte[] data = new byte[paddedLen];
        System.arraycopy(value, 0, data, 0, value.length);
        return concat(lenBytes, data);
    }

    public static byte[] encodeString(String value) {
        return encodeDynamicBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    // ─── Decode ──────────────────────────────────────────────────────────────

    /**
     * Decode ABI-encoded bytes into typed Java objects.
     */
    public static Object[] decode(AbiType[] types, byte[] data) {
        return decodeSequence(types, data, 0);
    }

    static Object[] decodeSequence(AbiType[] types, byte[] data, int baseOffset) {
        Object[] results = new Object[types.length];
        int headPos = baseOffset;
        for (int i = 0; i < types.length; i++) {
            AbiType type = types[i];
            if (type.isDynamic()) {
                int offset = (int) decodeBigInt(data, headPos).longValue();
                results[i] = decodeValue(type, data, baseOffset + offset);
            } else {
                results[i] = decodeValue(type, data, headPos);
            }
            headPos += 32;
        }
        return results;
    }

    public static Object decodeValue(AbiType type, byte[] data, int offset) {
        if (type.isArray()) return decodeArray(type, data, offset);
        if (type.isTuple()) return decodeTuple(type, data, offset);

        return switch (type.baseType()) {
            case "uint"    -> decodeBigInt(data, offset);
            case "int"     -> decodeSignedInt(data, offset, type.size());
            case "address" -> decodeAddress(data, offset);
            case "bool"    -> data[offset + 31] != 0;
            case "bytes"   -> decodeDynamicBytes(data, offset);
            case "string"  -> decodeString(data, offset);
            default -> {
                if (type.baseType().startsWith("bytes")) yield decodeFixedBytes(data, offset, type.size());
                throw new AbiException("Unsupported type: " + type);
            }
        };
    }

    private static Object[] decodeTuple(AbiType tupleType, byte[] data, int offset) {
        AbiType[] components = tupleType.getTupleTypes();
        return decodeSequence(components, data, offset);
    }

    private static Object decodeArray(AbiType arrayType, byte[] data, int offset) {
        AbiType elemType = arrayType.getArrayElementType();
        int length;
        int dataStart;

        if (arrayType.isDynamicArray()) {
            length = (int) decodeBigInt(data, offset).longValue();
            dataStart = offset + 32;
        } else {
            length = arrayType.getArraySize();
            dataStart = offset;
        }

        AbiType[] elemTypes = new AbiType[length];
        Arrays.fill(elemTypes, elemType);
        Object[] decoded = decodeSequence(elemTypes, data, dataStart);
        return decoded; // return as Object[]
    }

    public static BigInteger decodeBigInt(byte[] data, int offset) {
        byte[] slice = safeSlice(data, offset, 32);
        return new BigInteger(1, slice);
    }

    public static BigInteger decodeSignedInt(byte[] data, int offset, int bits) {
        byte[] slice = safeSlice(data, offset, 32);
        BigInteger value = new BigInteger(1, slice);
        BigInteger max = BigInteger.ONE.shiftLeft(bits - 1);
        if (value.compareTo(max) >= 0) {
            value = value.subtract(BigInteger.ONE.shiftLeft(bits));
        }
        return value;
    }

    public static String decodeAddress(byte[] data, int offset) {
        byte[] addrBytes = safeSlice(data, offset + 12, 20);
        return Address.toChecksumAddress(Hex.encodeNoPrefx(addrBytes));
    }

    public static byte[] decodeFixedBytes(byte[] data, int offset, int length) {
        return safeSlice(data, offset, length);
    }

    public static byte[] decodeDynamicBytes(byte[] data, int offset) {
        int length = (int) decodeBigInt(data, offset).longValue();
        return safeSlice(data, offset + 32, length);
    }

    public static String decodeString(byte[] data, int offset) {
        return new String(decodeDynamicBytes(data, offset), StandardCharsets.UTF_8);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static byte[] safeSlice(byte[] data, int offset, int length) {
        if (offset + length > data.length) {
            // Pad with zeros if data is shorter (some nodes return truncated data)
            byte[] padded = new byte[length];
            int available = Math.max(0, data.length - offset);
            System.arraycopy(data, offset, padded, 0, Math.min(available, length));
            return padded;
        }
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger bi) return bi;
        if (value instanceof Long l)        return BigInteger.valueOf(l);
        if (value instanceof Integer i)     return BigInteger.valueOf(i);
        if (value instanceof Short s)       return BigInteger.valueOf(s);
        if (value instanceof Byte b)        return BigInteger.valueOf(b & 0xFF);
        if (value instanceof Boolean bool)  return bool ? BigInteger.ONE : BigInteger.ZERO;
        if (value instanceof String s) {
            if (s.startsWith("0x")) return new BigInteger(s.substring(2), 16);
            return new BigInteger(s);
        }
        throw new AbiException("Cannot convert to BigInteger: " + value + " (" + value.getClass().getSimpleName() + ")");
    }

    static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof BigInteger bi) return bi.signum() != 0;
        if (value instanceof Integer i) return i != 0;
        throw new AbiException("Cannot convert to boolean: " + value);
    }

    static byte[] toBytes(Object value) {
        if (value instanceof byte[] b) return b;
        if (value instanceof String s) return Hex.decode(s);
        throw new AbiException("Cannot convert to bytes: " + value + " (" + value.getClass().getSimpleName() + ")");
    }

    @SuppressWarnings("unchecked")
    private static Object[] toObjectArray(Object value) {
        if (value instanceof Object[] arr) return arr;
        if (value instanceof List<?> list) return list.toArray();
        if (value instanceof int[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = BigInteger.valueOf(arr[i]);
            return out;
        }
        if (value instanceof long[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = BigInteger.valueOf(arr[i]);
            return out;
        }
        throw new AbiException("Cannot convert to array: " + value.getClass());
    }
}
