/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthException;
import io.jeth.model.RpcModels;
import io.jeth.provider.Provider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * WebSocket provider with auto-reconnect and full eth_subscribe support.
 *
 * <pre>
 * var ws = WsProvider.connect("wss://mainnet.infura.io/ws/v3/YOUR_KEY");
 * var client = EthClient.of(ws);
 *
 * // Subscribe to new blocks
 * ws.onNewBlock(block -> System.out.println("New block: " + block.get("number").asText()));
 *
 * // Subscribe to pending transactions
 * ws.onPendingTransaction(hash -> System.out.println("Pending: " + hash));
 *
 * // Subscribe to logs (contract events)
 * ws.onLogs(Map.of("address", "0xToken", "topics", List.of(TRANSFER_TOPIC)), log -> ...);
 *
 * ws.close(); // when done
 * </pre>
 */
public class WsProvider implements Provider, WebSocket.Listener {

    private static final Logger log = Logger.getLogger(WsProvider.class.getName());

    private final String url;
    private final ObjectMapper mapper;
    private final Duration connectTimeout;
    private final int maxReconnectAttempts;
    private final Duration reconnectDelay;

    private volatile WebSocket socket;
    // StringBuffer used intentionally: onText may be called from different threads
    private final StringBuffer msgBuf = new StringBuffer();

    private final Map<Long, CompletableFuture<RpcModels.RpcResponse>> pending =
            new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, List<?>> subscriptionParams = new ConcurrentHashMap<>();

    private final AtomicLong idGen = new AtomicLong(1);
    private final AtomicInteger reconnects = new AtomicInteger(0);
    private volatile boolean closed = false;

    // Heartbeat (ping) state
    private final Duration heartbeatInterval;
    private volatile ScheduledFuture<?> heartbeatTask;
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "ws-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });

    private WsProvider(
            String url,
            ObjectMapper mapper,
            Duration connectTimeout,
            int maxReconnectAttempts,
            Duration reconnectDelay,
            Duration heartbeatInterval) {
        this.url = url;
        this.mapper = mapper;
        this.connectTimeout = connectTimeout;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelay = reconnectDelay;
        this.heartbeatInterval = heartbeatInterval;
    }

    public static WsProvider connect(String wsUrl) {
        WsProvider p =
                new WsProvider(
                        wsUrl,
                        new ObjectMapper(),
                        Duration.ofSeconds(10),
                        10,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30));
        p.doConnect();
        return p;
    }

    public static Builder builder(String wsUrl) {
        return new Builder(wsUrl);
    }

    // ─── Provider ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest request) {
        long id = idGen.getAndIncrement();
        var future = new CompletableFuture<RpcModels.RpcResponse>();
        pending.put(id, future);
        try {
            String json =
                    mapper.writeValueAsString(
                            Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    id,
                                    "method",
                                    request.method,
                                    "params",
                                    request.params));
            socket.sendText(json, true)
                    .whenComplete(
                            (ws, ex) -> {
                                if (ex != null) {
                                    pending.remove(id);
                                    future.completeExceptionally(ex);
                                }
                            });
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public void close() {
        closed = true;
        stopHeartbeat();
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    /** Subscribe to new block headers. Handler receives the block header as JsonNode. */
    public CompletableFuture<String> onNewBlock(Consumer<JsonNode> handler) {
        return doSubscribe("newHeads", List.of(), handler);
    }

    /** Subscribe to new pending transaction hashes. */
    public CompletableFuture<String> onPendingTransaction(Consumer<String> handler) {
        return doSubscribe(
                "newPendingTransactions",
                List.of(),
                node -> handler.accept(node.isTextual() ? node.asText() : node.toString()));
    }

    /** Subscribe to contract event logs matching the given filter. */
    public CompletableFuture<String> onLogs(
            Map<String, Object> filter, Consumer<JsonNode> handler) {
        return doSubscribe("logs", List.of(filter), handler);
    }

    /** Generic eth_subscribe. Returns the subscription ID. */
    public CompletableFuture<String> subscribe(
            String type, List<?> extraParams, Consumer<JsonNode> handler) {
        return doSubscribe(type, extraParams, handler);
    }

    /** Cancel a subscription by ID. */
    public CompletableFuture<Boolean> unsubscribe(String subId) {
        subscriptions.remove(subId);
        subscriptionParams.remove(subId);
        return rawSend("eth_unsubscribe", List.of(subId))
                .thenApply(r -> r.result != null && r.result.asBoolean());
    }

    private CompletableFuture<String> doSubscribe(
            String type, List<?> extra, Consumer<JsonNode> handler) {
        List<Object> params = new ArrayList<>();
        params.add(type);
        params.addAll(extra);
        return rawSend("eth_subscribe", params)
                .thenApply(
                        resp -> {
                            String subId = resp.resultAsText();
                            subscriptions.put(subId, handler);
                            subscriptionParams.put(subId, params);
                            return subId;
                        });
    }

    private CompletableFuture<RpcModels.RpcResponse> rawSend(String method, List<?> params) {
        long id = idGen.getAndIncrement();
        var future = new CompletableFuture<RpcModels.RpcResponse>();
        pending.put(id, future);
        try {
            String json =
                    mapper.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            socket.sendText(json, true)
                    .whenComplete(
                            (ws, ex) -> {
                                if (ex != null) {
                                    pending.remove(id);
                                    future.completeExceptionally(ex);
                                }
                            });
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    // ─── WebSocket.Listener ───────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket ws) {
        reconnects.set(0);
        log.fine("WsProvider connected");
        ws.request(1);
        startHeartbeat();
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        msgBuf.append(data);
        if (last) {
            dispatch(msgBuf.toString());
            msgBuf.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        log.warning("WsProvider closed: " + code + " " + reason);
        if (!closed) scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable err) {
        log.warning("WsProvider error: " + err.getMessage());
        if (!closed) scheduleReconnect();
    }

    private void dispatch(String msg) {
        try {
            JsonNode node = mapper.readTree(msg);
            // Subscription notification
            if ("eth_subscription".equals(node.path("method").asText())) {
                String subId = node.path("params").path("subscription").asText();
                JsonNode result = node.path("params").path("result");
                Consumer<JsonNode> h = subscriptions.get(subId);
                if (h != null)
                    try {
                        h.accept(result);
                    } catch (Exception e) {
                        log.warning("Subscription handler threw: " + e.getMessage());
                    }
                return;
            }
            // RPC response
            if (node.has("id") && !node.get("id").isNull()) {
                long id = node.get("id").asLong();
                var future = pending.remove(id);
                if (future != null)
                    future.complete(mapper.treeToValue(node, RpcModels.RpcResponse.class));
            }
        } catch (Exception e) {
            log.warning("Failed to parse ws message: " + e.getMessage());
        }
    }

    // ─── Reconnect ────────────────────────────────────────────────────────────

    private void scheduleReconnect() {
        int attempt = reconnects.incrementAndGet();
        if (maxReconnectAttempts > 0 && attempt > maxReconnectAttempts) {
            pending.values()
                    .forEach(
                            f ->
                                    f.completeExceptionally(
                                            new EthException(
                                                    "WebSocket max reconnect attempts exceeded")));
            return;
        }
        long delayMs = reconnectDelay.toMillis() * (long) Math.min(attempt, 8);
        log.info("WsProvider reconnecting in " + delayMs + "ms (attempt " + attempt + ")");
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(
                        () -> {
                            doConnect();
                            resubscribeAll();
                        });
    }

    private void doConnect() {
        try {
            socket =
                    HttpClient.newHttpClient()
                            .newWebSocketBuilder()
                            .connectTimeout(connectTimeout)
                            .buildAsync(URI.create(url), this)
                            .join();
        } catch (Exception e) {
            log.warning("WsProvider connect failed: " + e.getMessage());
            if (!closed) scheduleReconnect();
        }
    }

    private void resubscribeAll() {
        var toResub = Map.copyOf(subscriptionParams);
        var handlers = Map.copyOf(subscriptions);
        subscriptions.clear();
        subscriptionParams.clear();
        toResub.forEach(
                (oldId, params) -> {
                    Consumer<JsonNode> h = handlers.get(oldId);
                    if (h == null) return;
                    long id = idGen.getAndIncrement();
                    var future = new CompletableFuture<RpcModels.RpcResponse>();
                    pending.put(id, future);
                    try {
                        String json =
                                mapper.writeValueAsString(
                                        Map.of(
                                                "jsonrpc",
                                                "2.0",
                                                "id",
                                                id,
                                                "method",
                                                "eth_subscribe",
                                                "params",
                                                params));
                        socket.sendText(json, true);
                        future.thenAccept(
                                r -> {
                                    String newId = r.resultAsText();
                                    subscriptions.put(newId, h);
                                    subscriptionParams.put(newId, (List<?>) params);
                                });
                    } catch (Exception e) {
                        log.warning("Re-subscribe failed: " + e.getMessage());
                    }
                });
    }

    // ─── Heartbeat ───────────────────────────────────────────────────────────────

    private void startHeartbeat() {
        stopHeartbeat();
        if (heartbeatInterval == null || heartbeatInterval.isZero()) return;
        long ms = heartbeatInterval.toMillis();
        heartbeatTask =
                SCHEDULER.scheduleAtFixedRate(this::sendPing, ms, ms, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendPing() {
        try {
            if (socket != null && !closed) socket.sendPing(ByteBuffer.wrap(new byte[0]));
        } catch (Exception e) {
            log.warning("Heartbeat ping failed: " + e.getMessage());
        }
    }

    @Override
    public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
        ws.sendPong(message);
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
        ws.request(1);
        return null;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {
        private final String url;
        private ObjectMapper mapper = new ObjectMapper();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxReconnectAttempts = 10;
        private Duration reconnectDelay = Duration.ofSeconds(2);
        private Duration heartbeatInterval = Duration.ofSeconds(30);

        Builder(String url) {
            this.url = url;
        }

        public Builder objectMapper(ObjectMapper m) {
            this.mapper = m;
            return this;
        }

        public Builder connectTimeout(Duration d) {
            this.connectTimeout = d;
            return this;
        }

        public Builder maxReconnectAttempts(int n) {
            this.maxReconnectAttempts = n;
            return this;
        }

        public Builder reconnectDelay(Duration d) {
            this.reconnectDelay = d;
            return this;
        }

        /**
         * Set the WebSocket ping interval (default: 30s). Prevents connection drops on idle
         * connections. Set to {@code Duration.ZERO} to disable.
         */
        public Builder heartbeatInterval(Duration d) {
            this.heartbeatInterval = d;
            return this;
        }

        public Builder noHeartbeat() {
            this.heartbeatInterval = Duration.ZERO;
            return this;
        }

        public WsProvider connect() {
            WsProvider p =
                    new WsProvider(
                            url,
                            mapper,
                            connectTimeout,
                            maxReconnectAttempts,
                            reconnectDelay,
                            heartbeatInterval);
            p.doConnect();
            return p;
        }
    }
}
