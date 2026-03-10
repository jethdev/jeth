/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ContractEvents. Tests the EventDef decode path (the part that doesn't require a
 * live WebSocket).
 */
class ContractEventsTest {

    static final EventDef Transfer =
            EventDef.of(
                    "Transfer",
                    EventDef.indexed("from", "address"),
                    EventDef.indexed("to", "address"),
                    EventDef.data("value", "uint256"));

    static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    static final String ADDR_FROM = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    static final String ADDR_TO = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void eventDefTopic0MatchesKeccakOfSignature() {
        // topic0 = keccak256("Transfer(address,address,uint256)")
        byte[] expected = Keccak.hash("Transfer(address,address,uint256)");
        assertArrayEquals(expected, Transfer.getTopic0());
        assertEquals("0x" + Hex.encodeNoPrefx(expected), Transfer.topic0Hex());
    }

    @Test
    void eventDefSignatureIsCorrect() {
        assertEquals("Transfer(address,address,uint256)", Transfer.getSignature());
        assertEquals("Transfer", Transfer.getName());
    }

    @Test
    void decodeTransferLog() {
        EthModels.Log log = buildTransferLog(ADDR_FROM, ADDR_TO, BigInteger.valueOf(1_000_000L));

        EventDef.DecodedEvent decoded = Transfer.decode(log);
        assertNotNull(decoded, "Should decode matching log");

        assertEquals(ADDR_FROM.toLowerCase(), decoded.address("from").toLowerCase());
        assertEquals(ADDR_TO.toLowerCase(), decoded.address("to").toLowerCase());
        assertEquals(BigInteger.valueOf(1_000_000L), decoded.uint("value"));
    }

    @Test
    void decodeReturnsNullForWrongTopic0() {
        EthModels.Log log = buildTransferLog(ADDR_FROM, ADDR_TO, BigInteger.ONE);
        log.topics.set(0, "0xdeadbeef"); // wrong topic0

        assertNull(Transfer.decode(log), "Wrong topic0 should return null");
    }

    @Test
    void decodeAllFiltersMatching() {
        EthModels.Log matching = buildTransferLog(ADDR_FROM, ADDR_TO, BigInteger.valueOf(100L));
        EthModels.Log nonMatching = buildTransferLog(ADDR_FROM, ADDR_TO, BigInteger.valueOf(200L));
        nonMatching.topics.set(0, "0x0000"); // wrong topic

        List<EventDef.DecodedEvent> results = Transfer.decodeAll(List.of(matching, nonMatching));
        assertEquals(1, results.size());
        assertEquals(BigInteger.valueOf(100L), results.get(0).uint("value"));
    }

    @Test
    void decodeHandlesNullLog() {
        assertNull(Transfer.decode(null));
    }

    @Test
    void decodeHandlesEmptyTopics() {
        EthModels.Log log = new EthModels.Log();
        log.topics = List.of();
        assertNull(Transfer.decode(log));
    }

    @Test
    void decodedEventToString() {
        EthModels.Log log = buildTransferLog(ADDR_FROM, ADDR_TO, BigInteger.TEN);
        EventDef.DecodedEvent e = Transfer.decode(log);
        assertNotNull(e);
        String str = e.toString();
        assertTrue(str.startsWith("Transfer"), "toString should start with event name");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static EthModels.Log buildTransferLog(String from, String to, BigInteger value) {
        EthModels.Log log = new EthModels.Log();
        log.address = USDC;
        log.transactionHash = "0xabc123";
        log.data = encodeUint256(value);
        log.removed = false;

        // topics[0] = Transfer sig, [1] = from (padded), [2] = to (padded)
        log.topics =
                new ArrayList<>(List.of(Transfer.topic0Hex(), padAddress(from), padAddress(to)));
        return log;
    }

    private static String padAddress(String addr) {
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0x" + "0".repeat(64 - clean.length()) + clean;
    }

    private static String encodeUint256(BigInteger value) {
        String hex = value.toString(16);
        return "0x" + "0".repeat(64 - hex.length()) + hex;
    }
}
