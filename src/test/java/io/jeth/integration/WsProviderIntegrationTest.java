/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;
import io.jeth.util.Units;
import io.jeth.ws.WsProvider;
import java.math.BigInteger;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link WsProvider} against a live Besu dev node.
 *
 * <p>Tests require Docker. Run with: {@code ./gradlew integrationTest}
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>connect() establishes a WebSocket connection
 *   <li>onNewBlock() receives live block headers when blocks are mined
 *   <li>onPendingTransaction() receives tx hashes when txs are submitted
 *   <li>unsubscribe() stops the handler from firing
 *   <li>close() cleanly terminates the connection
 *   <li>Multiple concurrent subscriptions work independently
 * </ul>
 */
@Tag("integration")
@Testcontainers
class WsProviderIntegrationTest {

    @Container static BesuContainer besu = new BesuContainer();

    static EthClient http;
    static Wallet dev;

    @BeforeAll
    static void setup() {
        http = EthClient.of(besu.httpUrl());
        dev = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);
    }

    WsProvider ws;

    @AfterEach
    void teardown() {
        if (ws != null) {
            try {
                ws.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ─── connect ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect() establishes WS connection and provides a provider")
    @Timeout(15)
    void connect_establishes_connection() {
        ws = WsProvider.connect(besu.wsUrl());
        assertNotNull(ws);
        // Verify the connection works by making a regular RPC call through it
        EthClient wsClient = EthClient.of(ws);
        long chainId = wsClient.getChainId().join();
        assertEquals(1337L, chainId, "WS provider should connect to Besu dev chain (chainId=1337)");
    }

    @Test
    @DisplayName("connect() with builder allows custom settings")
    @Timeout(15)
    void connect_via_builder() {
        ws =
                WsProvider.builder(besu.wsUrl())
                        .connectTimeout(Duration.ofSeconds(10))
                        .noHeartbeat()
                        .maxReconnectAttempts(2)
                        .connect();
        assertNotNull(ws);
        EthClient wsClient = EthClient.of(ws);
        assertTrue(wsClient.getBlockNumber().join() >= 0);
    }

    // ─── onNewBlock ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onNewBlock() receives block headers when blocks are mined")
    @Timeout(30)
    void on_new_block_receives_headers() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedHash = new AtomicReference<>();

        String subId =
                ws.onNewBlock(
                                header -> {
                                    receivedHash.set(header.path("hash").asText(null));
                                    latch.countDown();
                                })
                        .join();

        assertNotNull(subId, "Subscription ID must not be null");
        assertFalse(subId.isEmpty(), "Subscription ID must not be empty");

        // Mine a block by sending an ETH transfer
        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));

        boolean received = latch.await(20, TimeUnit.SECONDS);
        assertTrue(received, "Should receive a new block notification within 20s");
        assertNotNull(receivedHash.get(), "Block header must contain a hash field");
        assertTrue(receivedHash.get().startsWith("0x"), "Block hash must be 0x-prefixed");
        assertEquals(66, receivedHash.get().length(), "Block hash must be 32 bytes");
    }

    @Test
    @DisplayName("onNewBlock() subscription has a valid 0x-prefixed sub ID")
    @Timeout(15)
    void on_new_block_subscription_id() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        String subId = ws.onNewBlock(h -> {}).join();
        assertNotNull(subId);
        assertTrue(subId.startsWith("0x"), "Subscription ID must be 0x-prefixed");
    }

    @Test
    @DisplayName("onNewBlock() headers contain expected fields")
    @Timeout(30)
    void on_new_block_header_fields() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> blockRef = new AtomicReference<>();

        ws.onNewBlock(
                        header -> {
                            blockRef.set(header);
                            latch.countDown();
                        })
                .join();
        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));

        assertTrue(latch.await(20, TimeUnit.SECONDS));
        var block = blockRef.get();
        assertNotNull(block.get("hash"), "header must have 'hash'");
        assertNotNull(block.get("number"), "header must have 'number'");
        assertNotNull(block.get("parentHash"), "header must have 'parentHash'");
        assertNotNull(block.get("timestamp"), "header must have 'timestamp'");
        assertNotNull(block.get("gasLimit"), "header must have 'gasLimit'");
    }

    @Test
    @DisplayName("Multiple onNewBlock() subscriptions both fire independently")
    @Timeout(30)
    void multiple_new_block_subscriptions() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        String sub1 = ws.onNewBlock(h -> latch1.countDown()).join();
        String sub2 = ws.onNewBlock(h -> latch2.countDown()).join();

        assertNotEquals(sub1, sub2, "Two subscriptions should get different IDs");

        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));

        assertTrue(latch1.await(20, TimeUnit.SECONDS), "First subscription should fire");
        assertTrue(latch2.await(20, TimeUnit.SECONDS), "Second subscription should fire");
    }

    // ─── onPendingTransaction ──────────────────────────────────────────────────

    @Test
    @DisplayName("onPendingTransaction() receives tx hash before block mines")
    @Timeout(30)
    void on_pending_tx_fires() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> pendingHash = new AtomicReference<>();

        ws.onPendingTransaction(
                        hash -> {
                            pendingHash.set(hash);
                            latch.countDown();
                        })
                .join();

        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Should receive pending tx notification");
        String h = pendingHash.get();
        assertNotNull(h);
        assertTrue(h.startsWith("0x"), "Tx hash must be 0x-prefixed");
        assertEquals(66, h.length(), "Tx hash must be 32 bytes");
    }

    // ─── unsubscribe ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("unsubscribe() returns true for a valid subscription")
    @Timeout(15)
    void unsubscribe_valid() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        String subId = ws.onNewBlock(h -> {}).join();
        boolean result = ws.unsubscribe(subId).join();
        assertTrue(result, "unsubscribe() should return true for a valid subscription ID");
    }

    @Test
    @DisplayName("unsubscribe() stops the handler from firing on subsequent blocks")
    @Timeout(30)
    void unsubscribe_stops_handler() throws Exception {
        ws = WsProvider.connect(besu.wsUrl());
        var fireCount = new java.util.concurrent.atomic.AtomicInteger(0);
        CountDownLatch firstFire = new CountDownLatch(1);

        String subId =
                ws.onNewBlock(
                                h -> {
                                    fireCount.incrementAndGet();
                                    firstFire.countDown();
                                })
                        .join();

        // Mine a block, wait for it to be received
        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));
        assertTrue(firstFire.await(20, TimeUnit.SECONDS), "Handler should fire once before unsub");

        // Unsubscribe
        assertTrue(ws.unsubscribe(subId).join());

        int countAfterUnsub = fireCount.get();

        // Mine another block — handler should NOT fire again
        IntegrationTestBase.sendEth(http, dev, Wallet.create().getAddress(), Units.toWei("0.001"));
        Thread.sleep(2000); // give time for any spurious notifications

        assertEquals(countAfterUnsub, fireCount.get(), "Handler must not fire after unsubscribe()");
    }

    // ─── close ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("close() does not throw")
    @Timeout(10)
    void close_does_not_throw() {
        ws = WsProvider.connect(besu.wsUrl());
        assertDoesNotThrow(() -> ws.close());
    }

    @Test
    @DisplayName("RPC calls still work over WebSocket (not just subscriptions)")
    @Timeout(15)
    void rpc_calls_over_ws() {
        ws = WsProvider.connect(besu.wsUrl());
        EthClient wsClient = EthClient.of(ws);

        BigInteger balance = wsClient.getBalance(BesuContainer.DEV_ADDRESS).join();
        assertTrue(
                balance.compareTo(BigInteger.ZERO) > 0,
                "Dev address should have balance when queried over WebSocket");
    }

    @Test
    @DisplayName("WS and HTTP providers return consistent chainId")
    @Timeout(15)
    void ws_http_consistent() {
        ws = WsProvider.connect(besu.wsUrl());
        EthClient wsClient = EthClient.of(ws);
        assertEquals(http.getChainId().join(), wsClient.getChainId().join());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Send an ETH transfer via HTTP to trigger block mining. Returns tx hash. */
}
