/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents any Solidity ABI type, including compound types (arrays, tuples).
 *
 * <p>Covers the full Solidity type system:
 *
 * <ul>
 *   <li>Integers: {@code uint8} to {@code uint256}, {@code int8} to {@code int256}
 *   <li>Fixed bytes: {@code bytes1} to {@code bytes32}
 *   <li>Primitive: {@code address}, {@code bool}, dynamic {@code bytes}, {@code string}
 *   <li>Fixed arrays: {@code T[N]} via {@link #arrayOf(AbiType, int)}
 *   <li>Dynamic arrays: {@code T[]} via {@link #arrayOf(AbiType)}
 *   <li>Tuples (structs): {@code (T1, T2, ...)} via {@link #tuple(AbiType...)}
 *   <li>Nested: {@code (address, uint256)[]} via {@link #of(String)}
 * </ul>
 *
 * <pre>
 * AbiType.UINT256
 * AbiType.arrayOf(AbiType.ADDRESS)             // address[]
 * AbiType.arrayOf(AbiType.UINT256, 5)          // uint256[5]
 * AbiType.tuple(AbiType.ADDRESS, AbiType.UINT256)    // (address,uint256)
 * AbiType.of("(address,uint256)[]")            // parsed from Solidity notation
 * </pre>
 *
 * <p>Instances are reusable and thread-safe. Store them as static constants (as done in {@link
 * io.jeth.price.PriceOracle}) to avoid re-parsing signatures on every call.
 *
 * @see io.jeth.abi.AbiCodec
 * @see io.jeth.abi.Function
 */
public class AbiType {

    // ─── Primitives ───────────────────────────────────────────────────────────
    public static final AbiType UINT256 = new AbiType("uint", 256, false);
    public static final AbiType UINT128 = new AbiType("uint", 128, false);
    public static final AbiType UINT64 = new AbiType("uint", 64, false);
    public static final AbiType UINT32 = new AbiType("uint", 32, false);
    public static final AbiType UINT16 = new AbiType("uint", 16, false);
    public static final AbiType UINT8 = new AbiType("uint", 8, false);
    public static final AbiType INT256 = new AbiType("int", 256, false);
    public static final AbiType INT128 = new AbiType("int", 128, false);
    public static final AbiType INT64 = new AbiType("int", 64, false);
    public static final AbiType INT32 = new AbiType("int", 32, false);
    public static final AbiType INT8 = new AbiType("int", 8, false);
    public static final AbiType ADDRESS = new AbiType("address", 160, false);
    public static final AbiType BOOL = new AbiType("bool", 1, false);
    public static final AbiType BYTES = new AbiType("bytes", 0, true);
    public static final AbiType STRING = new AbiType("string", 0, true);
    public static final AbiType BYTES32 = new AbiType("bytes32", 32, false);
    public static final AbiType BYTES16 = new AbiType("bytes16", 16, false);
    public static final AbiType BYTES4 = new AbiType("bytes4", 4, false);
    public static final AbiType BYTES1 = new AbiType("bytes1", 1, false);

    private final String baseType;
    private final int size; // bit size for uint/int, byte size for bytesN, 0 for dynamic
    private final boolean dynamic;
    private final AbiType arrayElementType; // non-null for arrays
    private final int arraySize; // -1 for dynamic array, N for fixed T[N]
    private final AbiType[] tupleTypes; // non-null for tuples

    // Primitive constructor
    public AbiType(String baseType, int size, boolean dynamic) {
        this.baseType = baseType;
        this.size = size;
        this.dynamic = dynamic;
        this.arrayElementType = null;
        this.arraySize = 0;
        this.tupleTypes = null;
    }

    // Array constructor
    private AbiType(AbiType element, int arraySize) {
        this.arrayElementType = element;
        this.arraySize = arraySize;
        this.baseType = "array";
        this.size = 0;
        this.dynamic = arraySize == -1 || element.isDynamic();
        this.tupleTypes = null;
    }

    // Tuple constructor
    private AbiType(AbiType[] tupleTypes) {
        this.tupleTypes = tupleTypes;
        this.baseType = "tuple";
        this.size = 0;
        this.dynamic = Arrays.stream(tupleTypes).anyMatch(AbiType::isDynamic);
        this.arrayElementType = null;
        this.arraySize = 0;
    }

    /** Dynamic array: T[] */
    public static AbiType arrayOf(AbiType element) {
        return new AbiType(element, -1);
    }

    /** Fixed array: T[N] */
    public static AbiType arrayOf(AbiType element, int size) {
        return new AbiType(element, size);
    }

    /** Tuple: (T1,T2,...) */
    public static AbiType tuple(AbiType... components) {
        return new AbiType(components);
    }

    /**
     * Parse a Solidity type string into an AbiType. Handles: uint256, address, bool, string, bytes,
     * bytesN, T[], T[N], (T1,T2,...), (T1,T2,...)[], (T1,T2,...)[N]
     */
    public static AbiType of(String typeName) {
        typeName = typeName.trim();

        // Tuple
        if (typeName.startsWith("(")) {
            return parseTupleOrArray(typeName);
        }

        // Array suffix: T[] or T[N]
        if (typeName.endsWith("]")) {
            int bracket = findArrayBracket(typeName);
            String inner = typeName.substring(0, bracket);
            String suffix = typeName.substring(bracket);
            AbiType element = of(inner);
            if (suffix.equals("[]")) return arrayOf(element);
            int n = Integer.parseInt(suffix.substring(1, suffix.length() - 1));
            return arrayOf(element, n);
        }

        return switch (typeName) {
            case "address" -> ADDRESS;
            case "bool" -> BOOL;
            case "string" -> STRING;
            case "bytes" -> BYTES;
            case "uint", "uint256" -> UINT256;
            case "uint128" -> UINT128;
            case "uint64" -> UINT64;
            case "uint32" -> UINT32;
            case "uint16" -> UINT16;
            case "uint8" -> UINT8;
            case "int", "int256" -> INT256;
            case "int128" -> INT128;
            case "int64" -> INT64;
            case "int32" -> INT32;
            case "int8" -> INT8;
            case "bytes32" -> BYTES32;
            case "bytes16" -> BYTES16;
            case "bytes4" -> BYTES4;
            case "bytes1" -> BYTES1;
            default -> {
                if (typeName.startsWith("uint") && typeName.length() > 4) {
                    int bits = Integer.parseInt(typeName.substring(4));
                    if (bits < 8 || bits > 256 || bits % 8 != 0)
                        throw new AbiException("Invalid uint size: " + typeName);
                    yield new AbiType("uint", bits, false);
                }
                if (typeName.startsWith("int") && typeName.length() > 3) {
                    int bits = Integer.parseInt(typeName.substring(3));
                    if (bits < 8 || bits > 256 || bits % 8 != 0)
                        throw new AbiException("Invalid int size: " + typeName);
                    yield new AbiType("int", bits, false);
                }
                if (typeName.startsWith("bytes") && typeName.length() > 5) {
                    int sz = Integer.parseInt(typeName.substring(5));
                    if (sz < 1 || sz > 32)
                        throw new AbiException("Invalid bytesN size: " + typeName);
                    yield new AbiType(typeName, sz, false);
                }
                throw new AbiException("Unknown ABI type: " + typeName);
            }
        };
    }

    private static AbiType parseTupleOrArray(String typeName) {
        // Find matching closing paren
        int depth = 0, closeIdx = -1;
        for (int i = 0; i < typeName.length(); i++) {
            if (typeName.charAt(i) == '(') depth++;
            else if (typeName.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    closeIdx = i;
                    break;
                }
            }
        }
        if (closeIdx < 0) throw new AbiException("Unmatched parenthesis: " + typeName);

        String inner = typeName.substring(1, closeIdx);
        String suffix = typeName.substring(closeIdx + 1);

        AbiType[] components = inner.isEmpty() ? new AbiType[0] : splitTupleTypes(inner);
        AbiType tuple = new AbiType(components);

        if (suffix.isEmpty()) return tuple;
        if (suffix.equals("[]")) return arrayOf(tuple);
        if (suffix.startsWith("[") && suffix.endsWith("]")) {
            int n = Integer.parseInt(suffix.substring(1, suffix.length() - 1));
            return arrayOf(tuple, n);
        }
        throw new AbiException("Invalid tuple suffix: " + suffix);
    }

    private static AbiType[] splitTupleTypes(String inner) {
        List<AbiType> types = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                types.add(of(inner.substring(start, i).trim()));
                start = i + 1;
            }
        }
        types.add(of(inner.substring(start).trim()));
        return types.toArray(AbiType[]::new);
    }

    private static int findArrayBracket(String typeName) {
        int depth = 0;
        for (int i = typeName.length() - 1; i >= 0; i--) {
            char c = typeName.charAt(i);
            if (c == ']') depth++;
            else if (c == '[') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public String baseType() {
        return baseType;
    }

    public int size() {
        return size;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public boolean isArray() {
        return arrayElementType != null;
    }

    public boolean isTuple() {
        return tupleTypes != null;
    }

    public boolean isDynamicArray() {
        return isArray() && arraySize == -1;
    }

    public boolean isFixedArray() {
        return isArray() && arraySize >= 0;
    }

    public AbiType getArrayElementType() {
        return arrayElementType;
    }

    public int getArraySize() {
        return arraySize;
    }

    public AbiType[] getTupleTypes() {
        return tupleTypes != null ? Arrays.copyOf(tupleTypes, tupleTypes.length) : null;
    }

    @Override
    public String toString() {
        if (isTuple()) {
            String inner =
                    Arrays.stream(tupleTypes)
                            .map(AbiType::toString)
                            .collect(Collectors.joining(","));
            return "(" + inner + ")";
        }
        if (isArray()) {
            String elemStr = arrayElementType.toString();
            return arraySize == -1 ? elemStr + "[]" : elemStr + "[" + arraySize + "]";
        }
        if (baseType.equals("uint") || baseType.equals("int")) return baseType + size;
        if (baseType.equals("bytes") && size > 0) return "bytes" + size;
        return baseType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AbiType t)) return false;
        return Objects.equals(baseType, t.baseType)
                && size == t.size
                && dynamic == t.dynamic
                && Objects.equals(arrayElementType, t.arrayElementType)
                && arraySize == t.arraySize
                && Arrays.equals(tupleTypes, t.tupleTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                baseType, size, dynamic, arrayElementType, arraySize, Arrays.hashCode(tupleTypes));
    }
}
