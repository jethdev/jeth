/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.codegen;

import java.util.List;

/** Maps Solidity ABI types to Java types for code generation. */
public class TypeMapper {

    /**
     * Java type for a Solidity type in method signatures. e.g. "uint256" -> "BigInteger", "address"
     * -> "String", "bool" -> "boolean"
     */
    public static String toJavaType(String solidityType) {
        if (solidityType == null) return "Object";

        // Arrays — dynamic (uint256[]) or fixed (uint256[3])
        if (solidityType.endsWith("]")) {
            int bracket = solidityType.lastIndexOf('[');
            String base = solidityType.substring(0, bracket);
            return "java.util.List<" + toJavaTypeBoxed(base) + ">";
        }

        return switch (solidityType) {
            case "address" -> "String";
            case "bool" -> "boolean";
            case "string" -> "String";
            case "bytes" -> "byte[]";
            case "uint8", "uint16", "uint32" -> "int";
            case "int8", "int16", "int32" -> "int";
            case "uint64", "uint128", "uint256", "uint" -> "java.math.BigInteger";
            case "int64", "int128", "int256", "int" -> "java.math.BigInteger";
            default -> {
                if (solidityType.startsWith("bytes")) yield "byte[]"; // bytes1..bytes32
                if (solidityType.startsWith("uint")) yield "java.math.BigInteger";
                if (solidityType.startsWith("int")) yield "java.math.BigInteger";
                if (solidityType.startsWith("tuple")) yield "Object[]";
                yield "Object";
            }
        };
    }

    /** Boxed version for generics (e.g. {@code List<Integer>} not {@code List<int>}) */
    public static String toJavaTypeBoxed(String solidityType) {
        return switch (toJavaType(solidityType)) {
            case "boolean" -> "Boolean";
            case "int" -> "Integer";
            case "long" -> "Long";
            default -> toJavaType(solidityType);
        };
    }

    /**
     * Cast expression to convert Object (from ABI decoded array) to the target Java type. e.g. for
     * "uint256": "(BigInteger) result[0]"
     */
    public static String castFrom(String solidityType, String expr) {
        String javaType = toJavaType(solidityType);
        return switch (javaType) {
            case "boolean" -> "(Boolean) " + expr;
            case "int" -> "((java.math.BigInteger) " + expr + ").intValue()";
            case "long" -> "((java.math.BigInteger) " + expr + ").longValue()";
            case "byte[]" -> "(byte[]) " + expr;
            default -> "(" + javaType + ") " + expr;
        };
    }

    /** Required imports for a given Solidity type. */
    public static List<String> importsFor(String solidityType) {
        String javaType = toJavaType(solidityType);
        if (javaType.contains("BigInteger")) return List.of("java.math.BigInteger");
        if (javaType.contains("List")) return List.of("java.util.List");
        return List.of();
    }

    /**
     * AbiType constant name for a Solidity type, used in Function.of() calls. e.g. "uint256" ->
     * "AbiType.UINT256", "address" -> "AbiType.ADDRESS"
     */
    public static String toAbiTypeExpr(String solidityType) {
        if (solidityType.endsWith("]")) {
            // Arrays not yet supported as first-class AbiType — use raw
            return "AbiType.of(\"" + solidityType + "\")";
        }
        return switch (solidityType) {
            case "address" -> "AbiType.ADDRESS";
            case "bool" -> "AbiType.BOOL";
            case "string" -> "AbiType.STRING";
            case "bytes" -> "AbiType.BYTES";
            case "uint256", "uint" -> "AbiType.UINT256";
            case "uint128" -> "AbiType.UINT128";
            case "uint64" -> "AbiType.UINT64";
            case "uint32" -> "AbiType.UINT32";
            case "uint16" -> "AbiType.UINT16";
            case "uint8" -> "AbiType.UINT8";
            case "int256", "int" -> "AbiType.INT256";
            case "int128" -> "AbiType.INT128";
            case "bytes32" -> "AbiType.BYTES32";
            case "bytes4" -> "AbiType.BYTES4";
            default -> "AbiType.of(\"" + solidityType + "\")";
        };
    }
}
