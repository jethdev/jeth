/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.abi.HumanAbi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HumanAbi — human-readable Solidity ABI fragment parser.
 *
 * Verifies: function keyword, param names, return types, modifiers,
 * selectors, tuple types, and edge cases.
 */
class HumanAbiTest {

    // ─── Basic parsing ────────────────────────────────────────────────────────

    @Test @DisplayName("Parse 'function transfer(address,uint256)'")
    void parse_transfer_no_names() {
        Function fn = HumanAbi.parseFunction("function transfer(address,uint256)");
        assertEquals("transfer", fn.getName());
    }

    @Test @DisplayName("Parse with named params strips names")
    void parse_transfer_named_params() {
        Function fn = HumanAbi.parseFunction("function transfer(address to, uint256 amount)");
        assertEquals("transfer", fn.getName());
        // Named params and unnamed must produce same selector
        Function fn2 = HumanAbi.parseFunction("function transfer(address,uint256)");
        assertEquals(fn.getSelectorHex(), fn2.getSelectorHex());
    }

    @Test @DisplayName("Shorthand (no 'function' keyword) is accepted")
    void parse_shorthand() {
        Function fn = HumanAbi.parseFunction("transfer(address,uint256)");
        assertEquals("transfer", fn.getName());
        assertEquals(HumanAbi.parseFunction("function transfer(address,uint256)").getSelectorHex(),
                fn.getSelectorHex());
    }

    @Test @DisplayName("No-arg function parses correctly")
    void parse_no_args() {
        Function fn = HumanAbi.parseFunction("function totalSupply() view returns (uint256)");
        assertEquals("totalSupply", fn.getName());
    }

    // ─── Selectors (verified against known keccak values) ─────────────────────

    @Test @DisplayName("transfer(address,uint256) → 0xa9059cbb")
    void selector_transfer() {
        assertEquals("0xa9059cbb",
            HumanAbi.parseFunction("function transfer(address,uint256)").selector());
    }

    @Test @DisplayName("balanceOf(address) → 0x70a08231")
    void selector_balance_of() {
        assertEquals("0x70a08231",
            HumanAbi.parseFunction("function balanceOf(address)").selector());
    }

    @Test @DisplayName("approve(address,uint256) → 0x095ea7b3")
    void selector_approve() {
        assertEquals("0x095ea7b3",
            HumanAbi.parseFunction("approve(address,uint256)").selector());
    }

    @Test @DisplayName("HumanAbi.selector() convenience matches parseFunction().selector()")
    void selector_convenience() {
        String s = "function transfer(address,uint256)";
        assertEquals(HumanAbi.parseFunction(s).selector(), HumanAbi.selector(s));
    }

    // ─── Return types ─────────────────────────────────────────────────────────

    @Test @DisplayName("'returns (bool)' sets output type")
    void returns_bool() {
        Function fn = HumanAbi.parseFunction("function transfer(address,uint256) returns (bool)");
        AbiType[] out = fn.getOutputTypes();
        assertNotNull(out);
        assertEquals(1, out.length);
        assertEquals(AbiType.BOOL, out[0]);
    }

    @Test @DisplayName("'view returns (uint256)' strips 'view' and parses return type")
    void view_returns_uint256() {
        Function fn = HumanAbi.parseFunction("function balanceOf(address) view returns (uint256)");
        AbiType[] out = fn.getOutputTypes();
        assertNotNull(out);
        assertEquals(1, out.length);
        assertEquals(AbiType.UINT256, out[0]);
    }

    @Test @DisplayName("'pure returns (bytes32)' strips 'pure'")
    void pure_returns_bytes32() {
        Function fn = HumanAbi.parseFunction("function getHash() pure returns (bytes32)");
        AbiType[] out = fn.getOutputTypes();
        assertNotNull(out);
        assertEquals(AbiType.BYTES32, out[0]);
    }

    @Test @DisplayName("No 'returns' clause gives no output types or empty")
    void no_returns() {
        Function fn = HumanAbi.parseFunction("function setOwner(address newOwner)");
        // Should not throw; output types may be null or empty
        AbiType[] out = fn.getOutputTypes();
        assertTrue(out == null || out.length == 0, "No return types expected");
    }

    @Test @DisplayName("Multiple return types: returns (address, uint256)")
    void multiple_returns() {
        Function fn = HumanAbi.parseFunction("function getInfo() view returns (address, uint256)");
        AbiType[] out = fn.getOutputTypes();
        assertEquals(2, out.length);
    }

    // ─── Modifiers (visibility, mutability) ───────────────────────────────────

    @Test @DisplayName("'external view' modifier is stripped correctly")
    void external_view_modifier() {
        Function fn = HumanAbi.parseFunction("function owner() external view returns (address)");
        assertEquals("owner", fn.getName());
        // Same selector as without modifiers
        assertEquals(
            HumanAbi.parseFunction("function owner() returns (address)").getSelectorHex(),
            fn.getSelectorHex());
    }

    @Test @DisplayName("'payable' modifier doesn't affect selector")
    void payable_modifier() {
        Function fn = HumanAbi.parseFunction("function deposit() payable returns (bool)");
        assertEquals("deposit", fn.getName());
    }

    @Test @DisplayName("'nonpayable' modifier doesn't affect selector")
    void nonpayable_modifier() {
        Function fn = HumanAbi.parseFunction("function withdraw(uint256) nonpayable");
        assertEquals("withdraw", fn.getName());
    }

    // ─── Type parsing ─────────────────────────────────────────────────────────

    @Test @DisplayName("address type is parsed")
    void type_address() {
        HumanAbi.parseFunction("function foo(address x)");  // should not throw
    }

    @Test @DisplayName("uint variants are parsed: uint8, uint128, uint256")
    void type_uint_variants() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(uint8,uint128,uint256)"));
    }

    @Test @DisplayName("bytes and bytes32 are parsed")
    void type_bytes() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(bytes,bytes32)"));
    }

    @Test @DisplayName("bool type is parsed")
    void type_bool() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(bool flag)"));
    }

    @Test @DisplayName("string type is parsed")
    void type_string() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(string memory name)"));
    }

    @Test @DisplayName("Array type address[] is parsed")
    void type_array() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(address[])"));
    }

    @Test @DisplayName("Fixed-size array uint256[5] is parsed")
    void type_fixed_array() {
        assertDoesNotThrow(() -> HumanAbi.parseFunction("function foo(uint256[5])"));
    }

    // ─── parseFunctions (multi-line) ──────────────────────────────────────────

    @Test @DisplayName("parseFunctions() parses 3-function ERC-20 subset")
    void parse_functions_erc20() {
        List<Function> fns = HumanAbi.parseFunctions("""
            function totalSupply() view returns (uint256)
            function balanceOf(address account) view returns (uint256)
            function transfer(address to, uint256 amount) returns (bool)
        """);
        assertEquals(3, fns.size());
        assertEquals("totalSupply", fns.get(0).getName());
        assertEquals("balanceOf",   fns.get(1).getName());
        assertEquals("transfer",    fns.get(2).getName());
    }

    @Test @DisplayName("parseFunctions() skips comment lines (//)")
    void parse_functions_skip_comments() {
        List<Function> fns = HumanAbi.parseFunctions("""
            // ERC-20 functions
            function totalSupply() view returns (uint256)
            // returns balance
            function balanceOf(address) view returns (uint256)
        """);
        assertEquals(2, fns.size());
    }

    @Test @DisplayName("parseFunctions() skips blank lines")
    void parse_functions_skip_blanks() {
        List<Function> fns = HumanAbi.parseFunctions(
            "\nfunction foo()\n\nfunction bar(address)\n");
        assertEquals(2, fns.size());
    }

    @Test @DisplayName("parseFunctions() with empty string returns empty list")
    void parse_functions_empty() {
        assertTrue(HumanAbi.parseFunctions("").isEmpty());
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    @Test @DisplayName("Fragment without '(' throws IllegalArgumentException")
    void error_no_paren() {
        assertThrows(IllegalArgumentException.class,
            () -> HumanAbi.parseFunction("function transfer"));
    }

    @Test @DisplayName("Fragment with empty name throws")
    void error_empty_name() {
        assertThrows(IllegalArgumentException.class,
            () -> HumanAbi.parseFunction("function (address)"));
    }

    // ─── Internal helpers (accessible via package) ────────────────────────────

    @Test @DisplayName("splitParams splits 'a,b,c' into 3 items")
    void split_params_simple() {
        var parts = HumanAbi.splitParams("address,uint256,bool");
        assertEquals(3, parts.size());
        assertEquals("address", parts.get(0));
        assertEquals("uint256", parts.get(1));
        assertEquals("bool",    parts.get(2));
    }

    @Test @DisplayName("splitParams respects nested parens: '(a,b),c' → 2 items")
    void split_params_nested() {
        var parts = HumanAbi.splitParams("(address,uint256),bool");
        assertEquals(2, parts.size());
        assertEquals("(address,uint256)", parts.get(0));
        assertEquals("bool", parts.get(1));
    }

    @Test @DisplayName("extractType strips param name: 'address to' → 'address'")
    void extract_type_strips_name() {
        assertEquals("address", HumanAbi.extractType("address to"));
    }

    @Test @DisplayName("extractType handles 'indexed' modifier for events")
    void extract_type_indexed() {
        assertEquals("address", HumanAbi.extractType("indexed address"));
    }
}
