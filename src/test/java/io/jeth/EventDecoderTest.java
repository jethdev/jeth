/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.events.EventDecoder;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EventDecoder — covers the topic0 prefix bug fix and full decode path.
 *
 * <p>Critical regression: topic0 must be "0x..." not "0x0x..." (double-prefix bug).
 */
class EventDecoderTest {

    // Known ERC-20 Transfer signature hash
    static final String TRANSFER_SIG  = "Transfer(address indexed from, address indexed to, uint256 value)";
    static final String TRANSFER_T0   = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final String ADDR_FROM     = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String ADDR_TO       = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    static final EventDecoder TRANSFER = EventDecoder.of(TRANSFER_SIG);
    static final EventDecoder APPROVAL = EventDecoder.of(
        "Approval(address indexed owner, address indexed spender, uint256 value)");

    // ─── topic0 correctness (regression: no double-0x prefix) ─────────────────

    @Test @DisplayName("getTopic0() starts with exactly one '0x' prefix — not '0x0x'")
    void topic0_no_double_prefix() {
        String t0 = TRANSFER.getTopic0();
        assertTrue(t0.startsWith("0x"), "must start with 0x");
        assertFalse(t0.startsWith("0x0x"), "must NOT be double-prefixed '0x0x...'");
    }

    @Test @DisplayName("getTopic0() is 66 characters (0x + 64 hex chars = 32 bytes)")
    void topic0_length() {
        assertEquals(66, TRANSFER.getTopic0().length());
    }

    @Test @DisplayName("getTopic0() matches known ERC-20 Transfer hash")
    void topic0_known_transfer_hash() {
        assertEquals(TRANSFER_T0, TRANSFER.getTopic0());
    }

    @Test @DisplayName("getTopic0() matches manual keccak256 of normalized signature")
    void topic0_matches_keccak() {
        String norm = "Transfer(address,address,uint256)";
        String expected = Hex.encode(Keccak.hash(norm.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, TRANSFER.getTopic0());
    }

    @Test @DisplayName("Different events have different topic0")
    void topic0_unique_per_event() {
        assertNotEquals(TRANSFER.getTopic0(), APPROVAL.getTopic0());
    }

    @Test @DisplayName("Static ERC20_TRANSFER constant has correct topic0")
    void erc20_transfer_constant() {
        assertEquals(TRANSFER_T0, EventDecoder.ERC20_TRANSFER.getTopic0());
    }

    @Test @DisplayName("Static ERC20_APPROVAL constant has 0x-prefixed topic0")
    void erc20_approval_constant_prefix() {
        String t0 = EventDecoder.ERC20_APPROVAL.getTopic0();
        assertTrue(t0.startsWith("0x"));
        assertFalse(t0.startsWith("0x0x"));
        assertEquals(66, t0.length());
    }

    @Test @DisplayName("Static ERC721_TRANSFER constant has 0x-prefixed topic0")
    void erc721_transfer_constant_prefix() {
        String t0 = EventDecoder.ERC721_TRANSFER.getTopic0();
        assertTrue(t0.startsWith("0x"));
        assertFalse(t0.startsWith("0x0x"));
    }

    // ─── matches() ────────────────────────────────────────────────────────────

    @Test @DisplayName("matches() true when log topic0 equals event topic0")
    void matches_correct_log() {
        assertTrue(TRANSFER.matches(buildLog(TRANSFER_T0,
            padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(1000))));
    }

    @Test @DisplayName("matches() false for wrong event")
    void matches_wrong_event() {
        assertFalse(TRANSFER.matches(buildLog(APPROVAL.getTopic0(),
            padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(1000))));
    }

    @Test @DisplayName("matches() false for log with no topics")
    void matches_empty_topics() {
        EthModels.Log log = new EthModels.Log();
        log.topics = List.of();
        assertFalse(TRANSFER.matches(log));
    }

    @Test @DisplayName("matches() false for null topics")
    void matches_null_topics() {
        EthModels.Log log = new EthModels.Log();
        log.topics = null;
        assertFalse(TRANSFER.matches(log));
    }

    @Test @DisplayName("matches() is case-insensitive for topic0 comparison")
    void matches_case_insensitive() {
        String upperT0 = TRANSFER_T0.toUpperCase();
        assertTrue(TRANSFER.matches(buildLog(upperT0,
            padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(1))));
    }

    // ─── decode() ─────────────────────────────────────────────────────────────

    @Test @DisplayName("decode() extracts indexed 'from' address")
    void decode_from_address() {
        var log = buildLog(TRANSFER_T0, padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(100));
        Map<String, Object> result = TRANSFER.decode(log);
        assertTrue(ADDR_FROM.equalsIgnoreCase((String) result.get("from")));
    }

    @Test @DisplayName("decode() extracts indexed 'to' address")
    void decode_to_address() {
        var log = buildLog(TRANSFER_T0, padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(100));
        Map<String, Object> result = TRANSFER.decode(log);
        assertTrue(ADDR_TO.equalsIgnoreCase((String) result.get("to")));
    }

    @Test @DisplayName("decode() extracts non-indexed uint256 value from data field")
    void decode_value() {
        BigInteger amount = new BigInteger("1000000000000000000"); // 1 ETH in wei
        var log = buildLog(TRANSFER_T0, padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(amount));
        Map<String, Object> result = TRANSFER.decode(log);
        assertEquals(amount, result.get("value"));
    }

    @Test @DisplayName("decode() throws when log does not match")
    void decode_throws_on_mismatch() {
        var log = buildLog(APPROVAL.getTopic0(), padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(0));
        assertThrows(IllegalArgumentException.class, () -> TRANSFER.decode(log));
    }

    @Test @DisplayName("decode() handles all three indexed params (ERC-721 Transfer)")
    void decode_all_indexed() {
        // ERC-721 Transfer: all three params are indexed
        EventDecoder erc721 = EventDecoder.ERC721_TRANSFER;
        BigInteger tokenId = BigInteger.valueOf(42);
        var log = buildLog(erc721.getTopic0(),
            padAddr(ADDR_FROM), padAddr(ADDR_TO), padUint(tokenId));
        Map<String, Object> result = erc721.decode(log);
        assertTrue(ADDR_FROM.equalsIgnoreCase((String) result.get("from")));
        assertTrue(ADDR_TO.equalsIgnoreCase((String) result.get("to")));
        assertEquals(tokenId, result.get("tokenId"));
    }

    // ─── decodeAll() ─────────────────────────────────────────────────────────

    @Test @DisplayName("decodeAll() returns only matching logs")
    void decode_all_filters() {
        var t1 = buildLog(TRANSFER_T0, padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(1));
        var ap = buildLog(APPROVAL.getTopic0(), padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(2));
        var t2 = buildLog(TRANSFER_T0, padAddr(ADDR_FROM), padAddr(ADDR_TO), encodeUint(3));
        List<Map<String, Object>> results = TRANSFER.decodeAll(List.of(t1, ap, t2));
        assertEquals(2, results.size());
        assertEquals(BigInteger.ONE, results.get(0).get("value"));
        assertEquals(BigInteger.valueOf(3), results.get(1).get("value"));
    }

    @Test @DisplayName("decodeAll() on empty list returns empty")
    void decode_all_empty() {
        assertTrue(TRANSFER.decodeAll(List.of()).isEmpty());
    }

    // ─── signature normalization ───────────────────────────────────────────────

    @Test @DisplayName("Verbose and minimal signatures produce same topic0")
    void signature_normalization() {
        EventDecoder verbose = EventDecoder.of(
            "Transfer(address indexed from, address indexed to, uint256 value)");
        EventDecoder minimal = EventDecoder.of(
            "Transfer(address,address,uint256)");
        assertEquals(verbose.getTopic0(), minimal.getTopic0());
    }

    @Test @DisplayName("getSignature() returns canonical (no param names)")
    void get_signature_canonical() {
        assertEquals("Transfer(address,address,uint256)", TRANSFER.getSignature());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static EthModels.Log buildLog(String t0, String t1, String t2, String data) {
        EthModels.Log log = new EthModels.Log();
        log.topics = List.of(t0, t1, t2);
        log.data   = data;
        return log;
    }

    static EthModels.Log buildLog(String t0, String t1, String t2, String t3, String data) {
        EthModels.Log log = new EthModels.Log();
        log.topics = List.of(t0, t1, t2, t3);
        log.data   = data;
        return log;
    }

    /** Pad an address to a 32-byte 0x-prefixed topic. */
    static String padAddr(String addr) {
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0x" + "0".repeat(24) + clean.toLowerCase();
    }

    /** Encode a uint256 as ABI-encoded 0x-prefixed 32-byte hex for data field. */
    static String encodeUint(long v) { return encodeUint(BigInteger.valueOf(v)); }
    static String encodeUint(BigInteger v) {
        byte[] b = new byte[32];
        byte[] raw = v.toByteArray();
        System.arraycopy(raw, Math.max(0, raw.length - 32), b, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return Hex.encode(b);
    }

    /** Pad a uint256 as a 32-byte 0x-prefixed topic. */
    static String padUint(BigInteger v) { return encodeUint(v); }
}
