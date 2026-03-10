/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.contract.Contract;
import io.jeth.contract.ContractEvents;
import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;
import io.jeth.event.EventDef;
import io.jeth.subscribe.EventSubscriber;
import io.jeth.util.Units;
import io.jeth.ws.WsProvider;
import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link EventSubscriber} and {@link ContractEvents} — subscribe to real
 * contract logs over WebSocket.
 *
 * <p>Uses TestCounter (simpler, fewer setup dependencies) for increment/event tests and TestToken
 * for Transfer log tests.
 *
 * <p>Run: {@code ./gradlew integrationTest} (requires Docker)
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventSubscriberIntegrationTest {

    @Container static BesuContainer besu = new BesuContainer();

    static EthClient client;
    static Wallet dev;
    static String counterAddr;
    static String tokenAddr;
    static Contract counter;
    static Contract token;

    WsProvider ws;

    @BeforeAll
    static void setup() {
        client = EthClient.of(besu.httpUrl());
        dev = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);
        counterAddr = IntegrationTestBase.deploy(client, dev, SolcContainer.compile("TestCounter"));
        tokenAddr = IntegrationTestBase.deploy(client, dev, SolcContainer.compile("TestToken"));
        counter = new Contract(counterAddr, client);
        token = new Contract(tokenAddr, client);
    }

    @AfterEach
    void teardown() {
        if (ws != null) {
            try {
                ws.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ─── ContractEvents (preferred API) ───────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("ContractEvents.on() receives Incremented event from real tx")
    @Timeout(30)
    void contract_events_on_incremented() throws Exception {
        EventDef Incremented =
                EventDef.of(
                        "Incremented",
                        EventDef.indexed("newCount", "uint256"),
                        EventDef.indexed("by", "address"));

        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BigInteger> receivedCount = new AtomicReference<>();

        ContractEvents events = new ContractEvents(counterAddr, ws);
        events.on(
                Incremented,
                decoded -> {
                    receivedCount.set(decoded.uint("newCount"));
                    latch.countDown();
                });

        // Trigger the event via HTTP
        String txHash = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(
                latch.await(20, TimeUnit.SECONDS), "Should receive Incremented event within 20s");
        assertNotNull(receivedCount.get(), "receivedCount must not be null");
        assertTrue(receivedCount.get().signum() > 0, "newCount must be > 0 after increment");
    }

    @Test
    @Order(2)
    @DisplayName("ContractEvents.on() receives ValueSet event with correct values")
    @Timeout(30)
    void contract_events_on_value_set() throws Exception {
        EventDef ValueSet =
                EventDef.of(
                        "ValueSet",
                        EventDef.indexed("who", "address"),
                        EventDef.data("value", "uint256"));

        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BigInteger> receivedValue = new AtomicReference<>();
        AtomicReference<String> receivedWho = new AtomicReference<>();

        ContractEvents events = new ContractEvents(counterAddr, ws);
        events.on(
                ValueSet,
                decoded -> {
                    receivedValue.set(decoded.uint("value"));
                    receivedWho.set(decoded.address("who"));
                    latch.countDown();
                });

        BigInteger setVal = BigInteger.valueOf(12345);
        String txHash = counter.fn("setValue(uint256)").send(dev, setVal).join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Should receive ValueSet event within 20s");
        assertEquals(setVal, receivedValue.get(), "Received value must match sent value");
        assertTrue(
                dev.getAddress().equalsIgnoreCase(receivedWho.get()),
                "Received 'who' must match sender");
    }

    @Test
    @Order(3)
    @DisplayName("ContractEvents.on() receives Transfer log from TestToken")
    @Timeout(30)
    void contract_events_on_transfer() throws Exception {
        EventDef Transfer =
                EventDef.of(
                        "Transfer",
                        EventDef.indexed("from", "address"),
                        EventDef.indexed("to", "address"),
                        EventDef.data("value", "uint256"));

        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BigInteger> receivedAmount = new AtomicReference<>();
        AtomicReference<String> receivedTo = new AtomicReference<>();

        ContractEvents events = new ContractEvents(tokenAddr, ws);
        events.on(
                Transfer,
                decoded -> {
                    receivedAmount.set(decoded.uint("value"));
                    receivedTo.set(decoded.address("to"));
                    latch.countDown();
                });

        BigInteger amount = Units.toWei("10");
        Wallet recipient = Wallet.create();
        String txHash =
                token.fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), amount)
                        .join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Should receive Transfer event within 20s");
        assertEquals(amount, receivedAmount.get(), "Received amount must match sent amount");
        assertTrue(
                recipient.getAddress().equalsIgnoreCase(receivedTo.get()),
                "Received 'to' must match recipient");
    }

    @Test
    @Order(4)
    @DisplayName("ContractEvents.once() fires exactly once then stops")
    @Timeout(30)
    void contract_events_once_fires_once() throws Exception {
        EventDef Incremented =
                EventDef.of(
                        "Incremented",
                        EventDef.indexed("newCount", "uint256"),
                        EventDef.indexed("by", "address"));

        ws = WsProvider.connect(besu.wsUrl());
        var fireCount = new java.util.concurrent.atomic.AtomicInteger(0);

        ContractEvents events = new ContractEvents(counterAddr, ws);
        events.once(Incremented, decoded -> fireCount.incrementAndGet());

        // Trigger twice
        for (int i = 0; i < 2; i++) {
            String txHash = counter.fn("increment()").send(dev).join();
            client.waitForTransaction(txHash, 30_000, 500).join();
        }
        Thread.sleep(3000); // Wait for any delayed notifications

        assertEquals(1, fireCount.get(), "once() handler should fire exactly once");
    }

    @Test
    @Order(5)
    @DisplayName("Two ContractEvents subscriptions on same contract are independent")
    @Timeout(30)
    void two_subscriptions_independent() throws Exception {
        EventDef Incremented =
                EventDef.of(
                        "Incremented",
                        EventDef.indexed("newCount", "uint256"),
                        EventDef.indexed("by", "address"));

        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        ContractEvents events = new ContractEvents(counterAddr, ws);
        events.on(Incremented, d -> latch1.countDown());
        events.on(Incremented, d -> latch2.countDown());

        String txHash = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(latch1.await(20, TimeUnit.SECONDS), "First subscription must fire");
        assertTrue(latch2.await(20, TimeUnit.SECONDS), "Second subscription must fire");
    }

    // ─── EventSubscriber (deprecated API — backward compat) ───────────────────

    @Test
    @Order(10)
    @DisplayName("EventSubscriber.on() receives Transfer events (deprecated API)")
    @Timeout(30)
    void event_subscriber_on_transfer() throws Exception {
        EventDef Transfer =
                EventDef.of(
                        "Transfer",
                        EventDef.indexed("from", "address"),
                        EventDef.indexed("to", "address"),
                        EventDef.data("value", "uint256"));

        ws = WsProvider.connect(besu.wsUrl());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BigInteger> receivedAmount = new AtomicReference<>();

        // EventSubscriber is the deprecated API — still must work
        EventSubscriber subscriber = EventSubscriber.of(ws);
        subscriber.on(
                Transfer,
                tokenAddr,
                decoded -> {
                    receivedAmount.set(decoded.uint("value"));
                    latch.countDown();
                });

        BigInteger amount = Units.toWei("5");
        Wallet recipient = Wallet.create();
        String txHash =
                token.fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), amount)
                        .join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(latch.await(20, TimeUnit.SECONDS), "EventSubscriber.on() must receive event");
        assertEquals(amount, receivedAmount.get(), "Amount must match");
    }

    @Test
    @Order(11)
    @DisplayName("EventSubscriber.off() stops receiving events")
    @Timeout(30)
    void event_subscriber_off_stops_events() throws Exception {
        EventDef Incremented =
                EventDef.of(
                        "Incremented",
                        EventDef.indexed("newCount", "uint256"),
                        EventDef.indexed("by", "address"));

        ws = WsProvider.connect(besu.wsUrl());
        var fireCount = new java.util.concurrent.atomic.AtomicInteger(0);
        CountDownLatch firstFire = new CountDownLatch(1);

        EventSubscriber subscriber = EventSubscriber.of(ws);
        String subId =
                subscriber
                        .on(
                                Incremented,
                                null,
                                decoded -> {
                                    fireCount.incrementAndGet();
                                    firstFire.countDown();
                                })
                        .join();

        // Trigger once — should fire
        String txHash = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash, 30_000, 500).join();
        assertTrue(firstFire.await(20, TimeUnit.SECONDS), "Should fire before unsubscribe");

        // Unsubscribe
        subscriber.off(subId).join();
        int countAfterOff = fireCount.get();

        // Trigger again — should NOT fire
        String txHash2 = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash2, 30_000, 500).join();
        Thread.sleep(2000);

        assertEquals(countAfterOff, fireCount.get(), "Must not receive events after off()");
    }

    // ─── Cross-contract isolation ─────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Contract-specific subscription does not receive events from other contract")
    @Timeout(30)
    void subscription_is_contract_specific() throws Exception {
        EventDef Incremented =
                EventDef.of(
                        "Incremented",
                        EventDef.indexed("newCount", "uint256"),
                        EventDef.indexed("by", "address"));

        ws = WsProvider.connect(besu.wsUrl());
        // Deploy a SECOND counter — subscribe only to the first
        String counter2Addr =
                IntegrationTestBase.deploy(client, dev, SolcContainer.compile("TestCounter"));
        Contract counter2 = new Contract(counter2Addr, client);

        var fireCount = new java.util.concurrent.atomic.AtomicInteger(0);
        CountDownLatch expectedFire = new CountDownLatch(1);

        // Subscribe only to counter (not counter2)
        ContractEvents events = new ContractEvents(counterAddr, ws);
        events.on(
                Incremented,
                d -> {
                    fireCount.incrementAndGet();
                    expectedFire.countDown();
                });

        // Increment counter2 first (should NOT trigger our subscription)
        String txHash2 = counter2.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash2, 30_000, 500).join();
        Thread.sleep(1000); // Give time for spurious notification

        assertEquals(
                0, fireCount.get(), "Subscription must not fire for events from another contract");

        // Increment counter (SHOULD trigger)
        String txHash = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(
                expectedFire.await(20, TimeUnit.SECONDS), "Should fire for own contract's event");
        assertEquals(1, fireCount.get(), "Should fire exactly once for own contract");
    }
}
