/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.contract.ContractEvents;
import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import io.jeth.ws.WsProvider;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.List;

/**
 * @deprecated Use {@link io.jeth.contract.ContractEvents} instead.
 *             ContractEvents provides the same functionality with a cleaner API.
 *
 * <pre>
 * // Before (deprecated):
 * var subscriber = EventSubscriber.of(ws);
 * subscriber.on(Transfer, "0xUSDC", handler);
 *
 * // After (preferred):
 * var events = new ContractEvents("0xUSDC", ws);
 * events.on(Transfer, handler);
 * </pre>
 */
@Deprecated(since = "1.1.0", forRemoval = true)
public class EventSubscriber {

    private final WsProvider ws;

    private EventSubscriber(WsProvider ws) { this.ws = ws; }

    public static EventSubscriber of(WsProvider ws) { return new EventSubscriber(ws); }

    /**
     * Subscribe to a specific event from a specific contract.
     * @param event    Event definition (topic0 is computed from name+types)
     * @param address  Contract address filter, or null for any emitter
     * @param handler  Called for each decoded event
     * @return Subscription ID future (use to unsubscribe)
     */
    public CompletableFuture<String> on(
            EventDef event, String address, Consumer<EventDef.DecodedEvent> handler) {

        Map<String, Object> filter = address != null
                ? Map.of("address", address, "topics", List.of(event.topic0Hex()))
                : Map.of("topics", List.of(event.topic0Hex()));

        return ws.onLogs(filter, logNode -> {
            try {
                EthModels.Log log = new ObjectMapper()
                        .treeToValue(logNode, EthModels.Log.class);
                EventDef.DecodedEvent decoded = event.decode(log);
                if (decoded != null) handler.accept(decoded);
            } catch (Exception e) {
                // ignore malformed log
            }
        });
    }

    /** Unsubscribe by ID. */
    public CompletableFuture<Boolean> off(String subId) {
        return ws.unsubscribe(subId);
    }

    public WsProvider getWsProvider() { return ws; }
}
