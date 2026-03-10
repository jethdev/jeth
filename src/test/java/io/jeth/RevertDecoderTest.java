/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.RevertDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RevertDecoder tests — covers:
 * - Error(string) and Panic passthrough to AbiDecodeError
 * - OZ custom error resolution
 * - Uniswap custom error resolution
 * - Common error resolution
 * - Custom registration
 * - selector() computation
 * - lookup() optional
 * - Edge cases (null, empty, too-short data)
 */
class RevertDecoderTest {

    // ─── Error(string) passthrough ────────────────────────────────────────────

    @Test @DisplayName("decodes Error(string) → 'execution reverted: ...'")
    void error_string_passthrough() {
        // Error(string) selector: 0x08c379a0
        // ABI-encoded "transfer amount exceeds balance"
        String hex = "0x08c379a0"
            + "0000000000000000000000000000000000000000000000000000000000000020"
            + "000000000000000000000000000000000000000000000000000000000000001e"
            + "7472616e7366657220616d6f756e7420657863656564732062616c616e636500";
        String result = RevertDecoder.decode(hex);
        assertTrue(result.contains("transfer amount exceeds balance"), result);
        assertTrue(result.startsWith("execution reverted"), result);
    }

    @Test @DisplayName("decodes Panic(0x11) → arithmetic overflow")
    void panic_overflow() {
        // Panic(uint256) selector: 0x4e487b71, code 0x11 = overflow
        String hex = "0x4e487b71"
            + "0000000000000000000000000000000000000000000000000000000000000011";
        String result = RevertDecoder.decode(hex);
        assertTrue(result.contains("overflow") || result.contains("Panic"), result);
    }

    // ─── OpenZeppelin errors ──────────────────────────────────────────────────

    @Test @DisplayName("resolves OZ ERC-20 InsufficientBalance custom error")
    void oz_erc20_insufficient_balance() {
        String sel = RevertDecoder.selector("ERC20InsufficientBalance(address,uint256,uint256)");
        String result = RevertDecoder.decode(sel);
        assertTrue(result.contains("ERC20InsufficientBalance") || result.contains("OZ"),
            "Expected OZ ERC-20 error, got: " + result);
    }

    @Test @DisplayName("resolves OZ ERC-20 InvalidSender custom error")
    void oz_erc20_invalid_sender() {
        String sel = RevertDecoder.selector("ERC20InvalidSender(address)");
        assertFalse(RevertDecoder.decode(sel).contains("custom error"),
            "Should resolve to named error, not 'custom error'");
    }

    @Test @DisplayName("resolves OZ ERC-721 NonexistentToken custom error")
    void oz_erc721_nonexistent_token() {
        String sel = RevertDecoder.selector("ERC721NonexistentToken(uint256)");
        String result = RevertDecoder.decode(sel);
        assertTrue(result.contains("ERC721") || result.contains("OZ"), result);
    }

    @Test @DisplayName("resolves OZ AccessControl UnauthorizedAccount")
    void oz_access_control_unauthorized() {
        String sel = RevertDecoder.selector("AccessControlUnauthorizedAccount(address,bytes32)");
        String result = RevertDecoder.decode(sel);
        assertTrue(result.contains("AccessControl") || result.contains("Unauthorized"), result);
    }

    @Test @DisplayName("resolves OZ Ownable UnauthorizedAccount")
    void oz_ownable_unauthorized() {
        String sel = RevertDecoder.selector("OwnableUnauthorizedAccount(address)");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    @Test @DisplayName("resolves OZ ReentrancyGuard error")
    void oz_reentrancy_guard() {
        String sel = RevertDecoder.selector("ReentrancyGuardReentrantCall()");
        assertTrue(RevertDecoder.decode(sel).contains("ReentrancyGuard")
                || RevertDecoder.decode(sel).contains("Reentrant"));
    }

    // ─── Uniswap errors ──────────────────────────────────────────────────────

    @Test @DisplayName("resolves Uniswap V3TooLittleReceived")
    void uniswap_too_little_received() {
        String sel = RevertDecoder.selector("V3TooLittleReceived()");
        String result = RevertDecoder.decode(sel);
        assertFalse(result.contains("custom error 0x"), "Should resolve: " + result);
        assertTrue(result.contains("Uniswap") || result.contains("TooLittle"), result);
    }

    @Test @DisplayName("resolves Uniswap DeadlinePassed")
    void uniswap_deadline_passed() {
        String sel = RevertDecoder.selector("DeadlinePassed()");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    @Test @DisplayName("resolves Uniswap ExecutionFailed")
    void uniswap_execution_failed() {
        String sel = RevertDecoder.selector("ExecutionFailed(uint256,bytes)");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    // ─── Common errors ────────────────────────────────────────────────────────

    @Test @DisplayName("resolves common Unauthorized()")
    void common_unauthorized() {
        String sel = RevertDecoder.selector("Unauthorized()");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    @Test @DisplayName("resolves common ZeroAddress()")
    void common_zero_address() {
        String sel = RevertDecoder.selector("ZeroAddress()");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    @Test @DisplayName("resolves common SlippageExceeded()")
    void common_slippage_exceeded() {
        String sel = RevertDecoder.selector("SlippageExceeded()");
        assertFalse(RevertDecoder.decode(sel).contains("custom error 0x"));
    }

    // ─── Custom registration ──────────────────────────────────────────────────

    @Test @DisplayName("registerError() resolves newly added error")
    void register_custom_error() {
        String sig = "InsufficientCollateralXyz(address,uint256)"; // unique name to avoid clash
        RevertDecoder.registerError(sig, "MyProtocol");
        String sel = RevertDecoder.selector(sig);
        String result = RevertDecoder.decode(sel);
        assertTrue(result.contains("InsufficientCollateralXyz") || result.contains("MyProtocol"),
            "Expected custom error to be resolved, got: " + result);
    }

    @Test @DisplayName("register() with explicit selector resolves correctly")
    void register_explicit_selector() {
        RevertDecoder.register("0xdeadbeef", "TestProtocol: TestError");
        assertEquals(Optional.of("TestProtocol: TestError"), RevertDecoder.lookup("0xdeadbeef"));
        assertEquals(Optional.of("TestProtocol: TestError"), RevertDecoder.lookup("deadbeef"));
    }

    @Test @DisplayName("registerError() is idempotent (registering twice doesn't throw)")
    void register_idempotent() {
        assertDoesNotThrow(() -> {
            RevertDecoder.registerError("DuplicateError()", "Proto1");
            RevertDecoder.registerError("DuplicateError()", "Proto2");
        });
    }

    // ─── selector() computation ───────────────────────────────────────────────

    @Test @DisplayName("selector() for Error(string) = 0x08c379a0")
    void selector_error_string() {
        assertEquals("0x08c379a0", RevertDecoder.selector("Error(string)"));
    }

    @Test @DisplayName("selector() for Panic(uint256) = 0x4e487b71")
    void selector_panic() {
        assertEquals("0x4e487b71", RevertDecoder.selector("Panic(uint256)"));
    }

    @Test @DisplayName("selector() for Transfer(address,address,uint256) = 0xddf252ad")
    void selector_transfer() {
        assertEquals("0xddf252ad", RevertDecoder.selector("Transfer(address,address,uint256)"));
    }

    @Test @DisplayName("selector() returns 0x-prefixed 10-char string")
    void selector_format() {
        String sel = RevertDecoder.selector("SomeError(uint256)");
        assertTrue(sel.startsWith("0x"), sel);
        assertEquals(10, sel.length(), sel); // 0x + 8 hex chars
    }

    // ─── lookup() ─────────────────────────────────────────────────────────────

    @Test @DisplayName("lookup() returns empty for unknown selector")
    void lookup_unknown() {
        assertEquals(Optional.empty(), RevertDecoder.lookup("0x00000000"));
    }

    @Test @DisplayName("lookup() is case-insensitive (uppercase hex)")
    void lookup_case_insensitive() {
        String sig = "CaseSensitivityTestError()";
        RevertDecoder.registerError(sig, "TestProto");
        String sel = RevertDecoder.selector(sig);
        String upper = sel.substring(2).toUpperCase();
        assertTrue(RevertDecoder.lookup("0x" + upper).isPresent()
                || RevertDecoder.lookup(upper).isPresent());
    }

    // ─── registry() ───────────────────────────────────────────────────────────

    @Test @DisplayName("registry() contains many entries (OZ + Uniswap + Aave + common)")
    void registry_has_entries() {
        assertTrue(RevertDecoder.registry().size() >= 50,
            "Expected at least 50 registered errors, got: " + RevertDecoder.registry().size());
    }

    @Test @DisplayName("registry() is unmodifiable")
    void registry_unmodifiable() {
        assertThrows(UnsupportedOperationException.class,
            () -> RevertDecoder.registry().put("abc", "def"));
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Test @DisplayName("decode(null) returns 'execution reverted' (no throw)")
    void decode_null() {
        assertDoesNotThrow(() -> RevertDecoder.decode((String) null));
        String result = RevertDecoder.decode((String) null);
        assertTrue(result.contains("reverted"), result);
    }

    @Test @DisplayName("decode('') returns graceful message")
    void decode_empty() {
        String result = RevertDecoder.decode("");
        assertTrue(result.contains("reverted"), result);
    }

    @Test @DisplayName("decode('0x') returns graceful message")
    void decode_0x() {
        String result = RevertDecoder.decode("0x");
        assertTrue(result.contains("reverted"), result);
    }

    @Test @DisplayName("decode with unknown 4-byte selector returns 'custom error 0x...'")
    void decode_unknown_selector() {
        // Use a selector that is very unlikely to be in the registry
        String result = RevertDecoder.decode("0xdeadcafe");
        assertTrue(result.contains("custom error") || result.contains("deadcafe"), result);
    }

    @Test @DisplayName("decode(byte[]) works the same as decode(String)")
    void decode_bytes_overload() {
        byte[] bytes = new byte[]{(byte)0x08, (byte)0xc3, (byte)0x79, (byte)0xa0};
        // Partial Error(string) — may not fully decode but should not throw
        assertDoesNotThrow(() -> RevertDecoder.decode(bytes));
    }
}
