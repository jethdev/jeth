/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventDefTest {

    static final String TRANSFER_SIG = "Transfer(address,address,uint256)";
    static final String TRANSFER_T0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final String ADDR_FROM = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String ADDR_TO = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    static final EventDef TRANSFER =
            EventDef.of(
                    "Transfer",
                    EventDef.indexed("from", "address"),
                    EventDef.indexed("to", "address"),
                    EventDef.data("value", "uint256"));

    static final EventDef APPROVAL =
            EventDef.of(
                    "Approval",
                    EventDef.indexed("owner", "address"),
                    EventDef.indexed("spender", "address"),
                    EventDef.data("value", "uint256"));

    // ── topic0 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Transfer topic0 = keccak256 of signature")
    void topic0_keccak() {
        byte[] expected = Keccak.hash(TRANSFER_SIG.getBytes(StandardCharsets.UTF_8));
        assertEquals("0x" + Hex.encodeNoPrefx(expected), TRANSFER.topic0Hex());
    }

    @Test
    @DisplayName("topic0 matches known ERC-20 Transfer value")
    void topic0_known_value() {
        assertEquals(TRANSFER_T0, TRANSFER.topic0Hex());
    }

    @Test
    @DisplayName("Different events have different topic0")
    void topic0_unique() {
        assertNotEquals(TRANSFER.topic0Hex(), APPROVAL.topic0Hex());
    }

    @Test
    @DisplayName("getTopic0() returns 32 bytes")
    void topic0_bytes_length() {
        assertEquals(32, TRANSFER.getTopic0().length);
    }

    // ── matches() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("matches() true when topic0 matches")
    void matches_true() {
        assertNotNull(
                TRANSFER.decode(
                        buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(1000))));
    }

    @Test
    @DisplayName("matches() false when topic0 is different event")
    void matches_false() {
        assertNull(
                TRANSFER.decode(
                        buildLog(
                                APPROVAL.topic0Hex(),
                                pad(ADDR_FROM),
                                pad(ADDR_TO),
                                encodeU256(1000))));
    }

    // ── decode() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("decode() extracts indexed address params")
    void decode_addresses() {
        var log = buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(1_000_000L));
        var decoded = TRANSFER.decode(log);
        assertNotNull(decoded);
        assertTrue(
                ADDR_FROM.equalsIgnoreCase(decoded.get("from").toString()),
                "from: " + decoded.get("from"));
        assertTrue(
                ADDR_TO.equalsIgnoreCase(decoded.get("to").toString()), "to: " + decoded.get("to"));
    }

    @Test
    @DisplayName("decode() extracts uint256 data field")
    void decode_uint256_value() {
        BigInteger expected = new BigInteger("1000000000000000000");
        var log =
                buildLog(
                        TRANSFER_T0,
                        pad(ADDR_FROM),
                        pad(ADDR_TO),
                        encodeU256(expected.longValue()));
        assertEquals(expected, TRANSFER.decode(log).get("value"));
    }

    @Test
    @DisplayName("decode() returns null for non-matching log")
    void decode_null_mismatch() {
        var log = buildLog(APPROVAL.topic0Hex(), pad(ADDR_FROM), pad(ADDR_TO), encodeU256(0));
        assertNull(TRANSFER.decode(log));
    }

    @Test
    @DisplayName("decoded.address() helper returns string")
    void decoded_address_helper() {
        var log = buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(100));
        var d = TRANSFER.decode(log);
        assertTrue(ADDR_FROM.equalsIgnoreCase(d.address("from")));
    }

    @Test
    @DisplayName("decoded.uint() helper returns BigInteger")
    void decoded_uint_helper() {
        var log = buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(12345));
        assertEquals(BigInteger.valueOf(12345), TRANSFER.decode(log).uint("value"));
    }

    // ── decodeAll() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("decodeAll() filters only matching logs")
    void decode_all_filtered() {
        var t1 = buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(1));
        var ap = buildLog(APPROVAL.topic0Hex(), pad(ADDR_FROM), pad(ADDR_TO), encodeU256(2));
        var t2 = buildLog(TRANSFER_T0, pad(ADDR_FROM), pad(ADDR_TO), encodeU256(3));
        var list = TRANSFER.decodeAll(List.of(t1, ap, t2));
        assertEquals(2, list.size(), "Only Transfer logs should be decoded");
    }

    @Test
    @DisplayName("decodeAll() on empty log list returns empty")
    void decode_all_empty() {
        assertTrue(TRANSFER.decodeAll(List.of()).isEmpty());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static EthModels.Log buildLog(String topic0, String t1, String t2, String data) {
        var log = new EthModels.Log();
        log.topics = List.of(topic0, t1, t2);
        log.data = data;
        return log;
    }

    static String pad(String addr) {
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0x" + "0".repeat(24) + clean.toLowerCase();
    }

    static String encodeU256(long v) {
        byte[] b =
                AbiCodec.encode(
                        new AbiType[] {AbiType.UINT256}, new Object[] {BigInteger.valueOf(v)});
        return Hex.encode(b);
    }
}
