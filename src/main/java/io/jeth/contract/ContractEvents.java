/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import io.jeth.ws.WsProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Contract-level event subscriptions via WebSocket.
 *
 * <p>Provides an ethers.js-style {@code contract.on("Transfer", handler)} API backed by jeth's
 * {@link WsProvider} and {@link EventDef} for typed decoding.
 *
 * <pre>
 * var ws       = WsProvider.connect("wss://mainnet.infura.io/ws/v3/KEY");
 * var token    = new Contract("0xUSDC", EthClient.of(ws));
 * var events   = new ContractEvents(token.getAddress(), ws);
 *
 * // Listen to all Transfer events — decoded, typed
 * var Transfer = EventDef.of("Transfer",
 *     EventDef.indexed("from",  "address"),
 *     EventDef.indexed("to",    "address"),
 *     EventDef.data(   "value", "uint256"));
 *
 * events.on(Transfer, decoded -> {
 *     System.out.println(decoded.address("from") + " → " + decoded.address("to")
 *                       + " : " + decoded.uint("value"));
 * });
 *
 * // Raw log handler
 * events.onRaw(Transfer.topic0Hex(), log -> System.out.println(log));
 *
 * // All events from this contract
 * events.onAny(log -> System.out.println("Log: " + log.get("topics")));
 *
 * // Remove all listeners
 * events.removeAllListeners();
 * </pre>
 */
public class ContractEvents implements AutoCloseable {

    private final String address;
    private final WsProvider ws;
    private final List<String> subscriptionIds = new ArrayList<>();

    public ContractEvents(String contractAddress, WsProvider ws) {
        this.address = contractAddress;
        this.ws = ws;
    }

    // ─── Typed event subscriptions ────────────────────────────────────────────

    /**
     * Subscribe to a typed event. The handler receives a decoded {@link EventDef.DecodedEvent} with
     * typed accessors: {@code decoded.address("from")}, {@code decoded.uint("value")}, etc.
     *
     * @return subscription ID (use with {@link #off(String)} to remove)
     */
    public CompletableFuture<String> on(
            EventDef eventDef, Consumer<EventDef.DecodedEvent> handler) {
        return onRaw(
                eventDef.topic0Hex(),
                rawLog -> {
                    EthModels.Log log = toLog(rawLog);
                    EventDef.DecodedEvent decoded = eventDef.decode(log);
                    if (decoded != null) handler.accept(decoded);
                });
    }

    /**
     * Subscribe once — handler is called for the first matching event then automatically removed.
     */
    public CompletableFuture<String> once(
            EventDef eventDef, Consumer<EventDef.DecodedEvent> handler) {
        final String[] subId = {null};
        return on(
                        eventDef,
                        decoded -> {
                            handler.accept(decoded);
                            if (subId[0] != null) off(subId[0]);
                        })
                .whenComplete((id, ex) -> subId[0] = id);
    }

    // ─── Raw log subscriptions ────────────────────────────────────────────────

    /**
     * Subscribe to a specific topic0 (event signature hash) from this contract. Handler receives
     * raw JsonNode log objects.
     */
    public CompletableFuture<String> onRaw(String topic0Hex, Consumer<JsonNode> handler) {
        Map<String, Object> filter = new LinkedHashMap<>();
        if (address != null) filter.put("address", address);
        filter.put("topics", List.of(topic0Hex));
        return subscribe(filter, handler);
    }

    /** Subscribe to ALL events from this contract address, regardless of event type. */
    @SuppressWarnings("unused")
    public CompletableFuture<String> onAny(Consumer<JsonNode> handler) {
        Map<String, Object> filter = new LinkedHashMap<>();
        if (address != null) filter.put("address", address);
        return subscribe(filter, handler);
    }

    // ─── Unsubscribe ─────────────────────────────────────────────────────────

    /** Remove a single event listener by subscription ID. */
    public CompletableFuture<Boolean> off(String subscriptionId) {
        subscriptionIds.remove(subscriptionId);
        return ws.unsubscribe(subscriptionId);
    }

    public void removeAllListeners() {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (String id : new ArrayList<>(subscriptionIds)) futures.add(ws.unsubscribe(id));
        subscriptionIds.clear();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /** How many active subscriptions this instance manages. */
    @SuppressWarnings("unused")
    public int listenerCount() {
        return subscriptionIds.size();
    }

    @Override
    public void close() {
        removeAllListeners();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CompletableFuture<String> subscribe(
            Map<String, Object> filter, Consumer<JsonNode> handler) {
        return ws.onLogs(filter, handler)
                .whenComplete(
                        (id, ex) -> {
                            if (id != null) subscriptionIds.add(id);
                        });
    }

    private static EthModels.Log toLog(JsonNode node) {
        EthModels.Log log = new EthModels.Log();
        log.address = node.path("address").asText(null);
        log.transactionHash = node.path("transactionHash").asText(null);
        log.blockHash = node.path("blockHash").asText(null);
        log.data = node.path("data").asText("0x");
        log.removed = node.path("removed").asBoolean(false);
        JsonNode topics = node.path("topics");
        if (topics.isArray()) {
            List<String> topicList = new ArrayList<>();
            topics.forEach(t -> topicList.add(t.asText()));
            log.topics = topicList;
        }
        return log;
    }
}
