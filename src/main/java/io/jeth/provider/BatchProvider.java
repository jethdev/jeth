/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthException;
import io.jeth.model.RpcModels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JSON-RPC batch provider — combines multiple requests into a single HTTP call.
 *
 * <p>Reduces network round-trips dramatically. Instead of N sequential HTTP requests, everything is
 * bundled and sent at once, then results are dispatched back.
 *
 * <p>jeth automatically uses batching when you use {@link io.jeth.multicall.Multicall3} on-chain.
 * Use this provider when you want HTTP-level batching for arbitrary calls.
 *
 * <pre>
 * var provider = BatchProvider.of("https://mainnet.infura.io/v3/KEY")
 *     .maxBatchSize(20)
 *     .windowMs(10)   // collect requests for 10ms before sending
 *     .build();
 *
 * var client = EthClient.of(provider);
 *
 * // These 3 calls will be batched into 1 HTTP request automatically:
 * var f1 = client.getBalance("0xAddress1");
 * var f2 = client.getBalance("0xAddress2");
 * var f3 = client.getBlockNumber();
 * CompletableFuture.allOf(f1, f2, f3).join();
 * </pre>
 */
public class BatchProvider implements Provider {

    private final URI url;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final int maxBatchSize;
    private final long windowMs;

    private final Map<Long, CompletableFuture<RpcModels.RpcResponse>> pending =
            new ConcurrentHashMap<>();
    private final List<RpcModels.RpcRequest> queue =
            Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "jeth-batch-flush");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile ScheduledFuture<?> flushTask;

    private BatchProvider(Builder b) {
        this.url = URI.create(b.url);
        this.maxBatchSize = b.maxBatchSize;
        this.windowMs = b.windowMs;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static Builder of(String url) {
        return new Builder(url);
    }

    @Override
    public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest request) {
        var future = new CompletableFuture<RpcModels.RpcResponse>();
        pending.put(request.id, future);
        queue.add(request);

        if (queue.size() >= maxBatchSize) {
            if (flushTask != null) flushTask.cancel(false);
            scheduler.execute(this::flush);
        } else {
            if (flushTask == null || flushTask.isDone()) {
                flushTask = scheduler.schedule(this::flush, windowMs, TimeUnit.MILLISECONDS);
            }
        }
        return future;
    }

    private synchronized void flush() {
        if (queue.isEmpty()) return;
        List<RpcModels.RpcRequest> batch = new ArrayList<>(queue);
        queue.clear();

        try {
            byte[] body = mapper.writeValueAsBytes(batch);
            HttpRequest req =
                    HttpRequest.newBuilder(url)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .timeout(Duration.ofSeconds(30))
                            .build();

            httpClient
                    .sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(
                            resp -> {
                                try {
                                    JsonNode root = mapper.readTree(resp.body());
                                    if (root.isArray()) {
                                        root.forEach(node -> dispatch(node));
                                    } else {
                                        dispatch(root);
                                    }
                                } catch (Exception e) {
                                    batch.forEach(r -> fail(r.id, e));
                                }
                            })
                    .exceptionally(
                            ex -> {
                                batch.forEach(r -> fail(r.id, ex));
                                return null;
                            });
        } catch (Exception e) {
            batch.forEach(r -> fail(r.id, e));
        }
    }

    private void dispatch(JsonNode node) {
        try {
            RpcModels.RpcResponse resp = mapper.treeToValue(node, RpcModels.RpcResponse.class);
            var future = pending.remove(resp.id);
            if (future != null) future.complete(resp);
        } catch (Exception e) {
            throw new EthException("Failed to parse batch response", e);
        }
    }

    private void fail(long id, Throwable ex) {
        var f = pending.remove(id);
        if (f != null) f.completeExceptionally(ex);
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }

    public static class Builder {
        private final String url;
        private int maxBatchSize = 20;
        private long windowMs = 10;

        Builder(String url) {
            this.url = url;
        }

        public Builder maxBatchSize(int n) {
            this.maxBatchSize = n;
            return this;
        }

        public Builder windowMs(long ms) {
            this.windowMs = ms;
            return this;
        }

        public BatchProvider build() {
            return new BatchProvider(this);
        }
    }
}
