/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse human-readable Solidity ABI fragments into {@link Function} objects.
 *
 * <p>Supports the full syntax including named params, returns, modifiers, and visibility.
 *
 * <pre>
 * // Simple
 * Function transfer = HumanAbi.parseFunction("function transfer(address to, uint256 amount) returns (bool)");
 *
 * // View/pure modifiers (ignored for signing purposes)
 * Function balanceOf = HumanAbi.parseFunction("function balanceOf(address account) view returns (uint256)");
 *
 * // No-return
 * Function approve  = HumanAbi.parseFunction("function approve(address spender, uint256 amount)");
 *
 * // Shorthand (no 'function' keyword, no param names)
 * Function fn = HumanAbi.parseFunction("transfer(address,uint256)");
 *
 * // Parse many at once
 * List&lt;Function&gt; fns = HumanAbi.parseFunctions("""
 *     function totalSupply() view returns (uint256)
 *     function balanceOf(address account) view returns (uint256)
 *     function transfer(address to, uint256 amount) returns (bool)
 * """);
 * </pre>
 */
public final class HumanAbi {

    private HumanAbi() {}

    /**
     * Parse a single human-readable ABI function fragment.
     *
     * <p>Accepts both full form ({@code function foo(type name) returns (type)}) and shorthand
     * ({@code foo(type,type)}).
     *
     * @throws IllegalArgumentException if the fragment cannot be parsed
     */
    public static Function parseFunction(String fragment) {
        fragment = fragment.trim();

        // Strip leading 'function' keyword if present
        String body = fragment.startsWith("function ") ? fragment.substring(9).trim() : fragment;

        // Extract function name
        int parenOpen = body.indexOf('(');
        if (parenOpen < 0) throw new IllegalArgumentException("Missing '(' in: " + fragment);

        String name = body.substring(0, parenOpen).trim();
        if (name.isEmpty())
            throw new IllegalArgumentException("Missing function name in: " + fragment);

        // Extract parameter list (balanced parens)
        int parenClose = findMatchingParen(body, parenOpen);
        if (parenClose < 0)
            throw new IllegalArgumentException("Unbalanced parentheses in: " + fragment);

        String paramList = body.substring(parenOpen + 1, parenClose).trim();

        // Parse return types if present
        String rest = body.substring(parenClose + 1).trim();
        AbiType[] returnTypes = parseReturnTypes(rest, fragment);

        // Parse input parameter types
        AbiType[] inputTypes = parseParamList(paramList);

        if (returnTypes.length == 0) {
            return Function.of(name, inputTypes);
        } else {
            return Function.of(name, inputTypes).withReturns(returnTypes);
        }
    }

    /**
     * Parse multiple function fragments, one per non-empty line. Lines starting with {@code //} or
     * {@code #} are treated as comments.
     */
    public static List<Function> parseFunctions(String fragments) {
        List<Function> result = new ArrayList<>();
        for (String line : fragments.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue;
            result.add(parseFunction(trimmed));
        }
        return result;
    }

    /**
     * Parse an ABI fragment and return the 4-byte function selector. Equivalent to {@code
     * keccak256(canonicalSignature)[0..4]}.
     *
     * <pre>
     * String sel = HumanAbi.selector("function transfer(address,uint256)"); // "0xa9059cbb"
     * </pre>
     */
    public static String selector(String fragment) {
        return parseFunction(fragment).selector();
    }

    // ─── Internal parsers ────────────────────────────────────────────────────

    /** Parse "type name, type name, ..." stripping names and modifiers. */
    public static AbiType[] parseParamList(String paramList) {
        if (paramList.isBlank()) return new AbiType[0];
        List<AbiType> types = new ArrayList<>();
        for (String param : splitParams(paramList)) {
            String type = extractType(param.trim());
            types.add(AbiType.of(type));
        }
        return types.toArray(new AbiType[0]);
    }

    /** Parse "returns (type, type)" or "view returns (type)" from rest-of-line. */
    static AbiType[] parseReturnTypes(String rest, String original) {
        if (rest.isEmpty()) return new AbiType[0];

        // Strip modifiers: view, pure, external, public, payable, nonpayable
        String stripped =
                rest.replaceAll(
                                "\\b(view|pure|external|internal|public|private|payable|nonpayable)\\b",
                                "")
                        .trim();

        if (!stripped.startsWith("returns")) return new AbiType[0];

        String afterReturns = stripped.substring(7).trim();
        if (!afterReturns.startsWith("("))
            throw new IllegalArgumentException("Expected '(' after 'returns' in: " + original);

        int close = findMatchingParen(afterReturns, 0);
        if (close < 0)
            throw new IllegalArgumentException("Unbalanced parens in returns of: " + original);

        String returnList = afterReturns.substring(1, close).trim();
        return parseParamList(returnList);
    }

    /**
     * Extract just the Solidity type from a "type name" or "type" string. Handles tuples:
     * "(address,uint256) name" → "(address,uint256)"
     */
    public static String extractType(String paramDecl) {
        paramDecl = paramDecl.trim();
        if (paramDecl.isEmpty()) return paramDecl;

        // Tuple: starts with '('
        if (paramDecl.startsWith("(")) {
            int close = findMatchingParen(paramDecl, 0);
            if (close < 0) return paramDecl;
            // Check for array suffix like [][] after the tuple
            String after = paramDecl.substring(close + 1).trim();
            String arraySuffix = extractArraySuffix(after);
            return paramDecl.substring(0, close + 1) + arraySuffix;
        }

        // Regular type: take first token
        String[] tokens = paramDecl.split("\\s+");
        String type = tokens[0];

        // Handle "indexed" modifier (for events)
        if (type.equals("indexed") && tokens.length > 1) type = tokens[1];

        return type;
    }

    /** Extract array suffix like "[]", "[5]", "[][]" from the start of a string. */
    private static String extractArraySuffix(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length() && s.charAt(i) == '[') {
            int close = s.indexOf(']', i);
            if (close < 0) break;
            sb.append(s, i, close + 1);
            i = close + 1;
        }
        return sb.toString();
    }

    /** Split "a,b,(c,d),e" respecting nested parentheses. */
    public static List<String> splitParams(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                String part = s.substring(start, i).trim();
                if (!part.isEmpty()) parts.add(part);
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }

    /** Find the closing paren matching the open paren at {@code openIdx}. */
    static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }
}
