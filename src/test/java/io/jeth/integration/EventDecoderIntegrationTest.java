/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.contract.Contract;
import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;
import io.jeth.event.EventDef;
import io.jeth.events.EventDecoder;
import io.jeth.model.EthModels;
import io.jeth.util.Units;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link EventDecoder} and {@link EventDef} using real logs from a deployed
 * TestToken contract on Besu dev network.
 *
 * <p>Key regression: EventDecoder.getTopic0() must produce "0x..." not "0x0x..." (double-prefix
 * bug). These tests verify the decoder works with REAL node-returned logs — if topic0 were
 * double-prefixed, {@code matches()} would never return true.
 *
 * <p>Run: {@code ./gradlew integrationTest} (requires Docker)
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventDecoderIntegrationTest {

    @Container static BesuContainer besu = new BesuContainer();

    static EthClient client;
    static Wallet dev;
    static String tokenAddr;
    static Contract token;

    static final String TRANSFER_T0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    static final EventDecoder TRANSFER_DECODER =
            EventDecoder.of("Transfer(address indexed from, address indexed to, uint256 value)");

    static final EventDecoder APPROVAL_DECODER =
            EventDecoder.of(
                    "Approval(address indexed owner, address indexed spender, uint256 value)");

    static final EventDef TRANSFER_DEF =
            EventDef.of(
                    "Transfer",
                    EventDef.indexed("from", "address"),
                    EventDef.indexed("to", "address"),
                    EventDef.data("value", "uint256"));

    @BeforeAll
    static void setup() {
        client = EthClient.of(besu.httpUrl());
        dev = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);
        tokenAddr = IntegrationTestBase.deploy(client, dev, SolcContainer.compile("TestToken"));
        token = new Contract(tokenAddr, client);
    }

    // ─── topic0 format regression (no contract needed) ────────────────────────

    @Test
    @Order(0)
    @DisplayName("EventDecoder.getTopic0() has exactly one '0x' prefix (regression)")
    void topic0_no_double_prefix() {
        String t0 = TRANSFER_DECODER.getTopic0();
        assertTrue(t0.startsWith("0x"), "must start with 0x");
        assertFalse(t0.startsWith("0x0x"), "must NOT be '0x0x...' (double-prefix bug)");
        assertEquals(66, t0.length(), "must be 0x + 64 hex chars = 32 bytes");
    }

    @Test
    @Order(1)
    @DisplayName("EventDecoder.getTopic0() matches known Transfer hash")
    void topic0_value_correct() {
        assertEquals(TRANSFER_T0, TRANSFER_DECODER.getTopic0());
    }

    @Test
    @Order(2)
    @DisplayName("EventDef.topic0Hex() has exactly one '0x' prefix (regression)")
    void event_def_topic0_no_double_prefix() {
        String t0 = TRANSFER_DEF.topic0Hex();
        assertFalse(t0.startsWith("0x0x"), "EventDef topic0 must not be double-prefixed");
        assertEquals(66, t0.length());
    }

    // ─── Constructor Transfer log (mint) ──────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Constructor emits Transfer(0x0 → deployer, totalSupply)")
    void constructor_mint_log_matches() {
        List<EthModels.Log> logs =
                client.getLogs("0x0", "latest", tokenAddr, List.of(TRANSFER_T0)).join();
        assertFalse(logs.isEmpty(), "Deploy must have emitted at least one Transfer log");

        EthModels.Log mintLog = logs.get(0);
        // Regression check: matches() uses topic0 comparison — would fail if double-prefixed
        assertTrue(
                TRANSFER_DECODER.matches(mintLog),
                "EventDecoder.matches() must return true for real Transfer log; "
                        + "failure = topic0 format bug (double-prefix)");
    }

    @Test
    @Order(11)
    @DisplayName("Constructor mint: decoded value = totalSupply (1_000_000 TT)")
    void constructor_mint_log_decoded_value() {
        List<EthModels.Log> logs =
                client.getLogs("0x0", "latest", tokenAddr, List.of(TRANSFER_T0)).join();
        var decoded = TRANSFER_DECODER.decode(logs.get(0));
        assertEquals(Units.toWei("1000000"), decoded.get("value"));
    }

    @Test
    @Order(12)
    @DisplayName("Constructor mint: from = 0x0 (mint from zero address)")
    void constructor_mint_log_from_zero() {
        List<EthModels.Log> logs =
                client.getLogs("0x0", "latest", tokenAddr, List.of(TRANSFER_T0)).join();
        String from = (String) TRANSFER_DECODER.decode(logs.get(0)).get("from");
        // from should be 0x0000...0000
        assertTrue(
                from.replaceAll("[0x]", "").isBlank()
                        || from.equals("0x0000000000000000000000000000000000000000"),
                "from must be zero address for mint log, got: " + from);
    }

    // ─── transfer() logs ──────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("matches() returns true for Transfer log from real transfer()")
    void matches_real_transfer_log() throws Exception {
        var recipient = Wallet.create();
        var receipt = doTransfer(recipient, Units.toWei("1"));

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(TRANSFER_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        assertTrue(TRANSFER_DECODER.matches(log));
    }

    @Test
    @Order(21)
    @DisplayName("decode() extracts correct 'from' address")
    void decode_from_address() throws Exception {
        var recipient = Wallet.create();
        var receipt = doTransfer(recipient, Units.toWei("2"));

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(TRANSFER_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        var decoded = TRANSFER_DECODER.decode(log);
        assertTrue(
                dev.getAddress().equalsIgnoreCase((String) decoded.get("from")),
                "Decoded 'from' must be the sender");
    }

    @Test
    @Order(22)
    @DisplayName("decode() extracts correct 'to' address")
    void decode_to_address() throws Exception {
        var recipient = Wallet.create();
        var receipt = doTransfer(recipient, Units.toWei("3"));

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(TRANSFER_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        var decoded = TRANSFER_DECODER.decode(log);
        assertTrue(
                recipient.getAddress().equalsIgnoreCase((String) decoded.get("to")),
                "Decoded 'to' must be the recipient");
    }

    @Test
    @Order(23)
    @DisplayName("decode() extracts correct 'value'")
    void decode_value() throws Exception {
        BigInteger amount = Units.toWei("42");
        var receipt = doTransfer(Wallet.create(), amount);

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(TRANSFER_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        assertEquals(amount, TRANSFER_DECODER.decode(log).get("value"));
    }

    @Test
    @Order(24)
    @DisplayName("decodeAll() returns only Transfer logs from receipt")
    void decode_all_from_receipt() throws Exception {
        var receipt = doTransfer(Wallet.create(), Units.toWei("1"));

        var transfers = TRANSFER_DECODER.decodeAll(receipt.logs);
        assertEquals(1, transfers.size(), "Simple transfer should emit exactly one Transfer log");
        assertNotNull(transfers.get(0).get("from"));
        assertNotNull(transfers.get(0).get("to"));
        assertNotNull(transfers.get(0).get("value"));
    }

    @Test
    @Order(25)
    @DisplayName("EventDecoder and EventDef decode value consistently")
    void decoder_and_def_consistent() throws Exception {
        BigInteger amount = Units.toWei("7");
        var receipt = doTransfer(Wallet.create(), amount);

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(TRANSFER_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        var fromDecoder = (BigInteger) TRANSFER_DECODER.decode(log).get("value");
        var fromDef = TRANSFER_DEF.decode(log).uint("value");
        assertEquals(fromDecoder, fromDef, "Both decoders must give same value");
    }

    @Test
    @Order(26)
    @DisplayName("approve() emits Approval log — decoded separately from Transfer")
    void approval_log_decoded() throws Exception {
        Wallet spender = Wallet.create();
        String txHash =
                token.fn("approve(address,uint256)")
                        .send(dev, spender.getAddress(), Units.toWei("500"))
                        .join();
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();

        // Approval log should match APPROVAL but not TRANSFER
        EthModels.Log approvalLog =
                receipt.logs.stream()
                        .filter(APPROVAL_DECODER::matches)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Approval log"));

        assertFalse(
                TRANSFER_DECODER.matches(approvalLog),
                "Approval log must not match Transfer decoder");

        var decoded = APPROVAL_DECODER.decode(approvalLog);
        assertTrue(dev.getAddress().equalsIgnoreCase((String) decoded.get("owner")));
        assertTrue(spender.getAddress().equalsIgnoreCase((String) decoded.get("spender")));
        assertEquals(Units.toWei("500"), decoded.get("value"));
    }

    @Test
    @Order(27)
    @DisplayName("getLogs() with topic0 filter returns only Transfer logs")
    void get_logs_filtered() throws Exception {
        long startBlock = client.getBlockNumber().join();
        doTransfer(Wallet.create(), Units.toWei("1"));
        long endBlock = client.getBlockNumber().join();

        List<EthModels.Log> logs =
                client.getLogs(
                                "0x" + Long.toHexString(startBlock),
                                "0x" + Long.toHexString(endBlock),
                                tokenAddr,
                                List.of(TRANSFER_T0))
                        .join();

        assertFalse(logs.isEmpty());
        assertTrue(
                logs.stream().allMatch(TRANSFER_DECODER::matches),
                "All getLogs results must match Transfer topic0");
    }

    // ─── EventDef (legacy API consistency) ────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("EventDef.decode() works on real Transfer log")
    void event_def_decode_real_log() throws Exception {
        BigInteger amount = Units.toWei("5");
        var receipt = doTransfer(Wallet.create(), amount);

        EthModels.Log log =
                receipt.logs.stream()
                        .filter(
                                l ->
                                        l.topics != null
                                                && !l.topics.isEmpty()
                                                && TRANSFER_T0.equalsIgnoreCase(l.topics.get(0)))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No Transfer log"));

        var decoded = TRANSFER_DEF.decode(log);
        assertNotNull(decoded, "EventDef.decode() must not return null for matching log");
        assertEquals(amount, decoded.uint("value"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    EthModels.TransactionReceipt doTransfer(Wallet recipient, BigInteger amount) throws Exception {
        String txHash =
                token.fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), amount)
                        .join();
        return client.waitForTransaction(txHash, 30_000, 500).join();
    }
}
