/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthClient;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Lightweight mock JSON-RPC server backed by OkHttp MockWebServer. Enqueue pre-canned responses;
 * use url() to point jeth at it.
 *
 * <pre>
 * try (var rpc = new RpcMock()) {
 *     rpc.enqueueHex("eth_blockNumber", 100L);
 *     assertEquals(100L, EthClient.of(rpc.url()).getBlockNumber().join());
 * }
 * </pre>
 */
public class RpcMock implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MockWebServer server = new MockWebServer();
    private final Queue<String> queue = new ArrayDeque<>();
    private final Queue<RecordedRequest> requests =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private final AtomicLong ids = new AtomicLong(1);

    public RpcMock() throws IOException {
        server.start();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest req) {
                        requests.add(req);
                        try {
                            String body = req.getBody().clone().readUtf8();
                            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(body);

                            if (node.isArray()) {
                                StringBuilder sb = new StringBuilder("[");
                                for (int i = 0; i < node.size(); i++) {
                                    if (i > 0) sb.append(",");
                                    long id = node.get(i).path("id").asLong();
                                    if (id == 0 && !node.get(i).has("id"))
                                        id = ids.getAndIncrement();
                                    String result = queue.poll();
                                    if (result == null) {
                                        sb.append("{\"jsonrpc\":\"2.0\",\"id\":")
                                                .append(id)
                                                .append(
                                                        ",\"error\":{\"code\":-32000,\"message\":\"no mock queued\"}}");
                                    } else {
                                        sb.append("{\"jsonrpc\":\"2.0\",\"id\":")
                                                .append(id)
                                                .append(",\"result\":")
                                                .append(result)
                                                .append("}");
                                    }
                                }
                                sb.append("]");
                                return json(sb.toString());
                            } else {
                                long id = node.path("id").asLong();
                                if (id == 0 && !node.has("id")) id = ids.getAndIncrement();
                                String result = queue.poll();
                                if (result == null)
                                    return json(
                                            "{\"jsonrpc\":\"2.0\",\"id\":"
                                                    + id
                                                    + ",\"error\":{\"code\":-32000,\"message\":\"no mock queued\"}}");
                                return json(
                                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                                + id
                                                + ",\"result\":"
                                                + result
                                                + "}");
                            }
                        } catch (Exception e) {
                            return new MockResponse().setResponseCode(500);
                        }
                    }
                });
    }

    /** Enqueue a raw JSON result string. */
    public RpcMock enqueue(String resultJson) {
        queue.add(resultJson);
        return this;
    }

    /** Enqueue a JSON string (quoted). */
    public RpcMock enqueueStr(String s) {
        queue.add("\"" + s + "\"");
        return this;
    }

    /** Enqueue a hex long (e.g. "0x64" for 100). */
    public RpcMock enqueueHex(long value) {
        queue.add("\"0x" + Long.toHexString(value) + "\"");
        return this;
    }

    /** Enqueue a hex BigInteger. */
    public RpcMock enqueueHex(BigInteger value) {
        queue.add("\"0x" + value.toString(16) + "\"");
        return this;
    }

    /** Enqueue null result. */
    public RpcMock enqueueNull() {
        queue.add("null");
        return this;
    }

    /** Enqueue a JSON object literal. */
    public RpcMock enqueueJson(String json) {
        queue.add(json);
        return this;
    }

    public String url() {
        return server.url("/").toString();
    }

    public EthClient client() {
        return EthClient.of(url());
    }

    public int requestCount() {
        return server.getRequestCount();
    }

    public RecordedRequest takeRequest() throws InterruptedException {
        return ((java.util.concurrent.BlockingQueue<RecordedRequest>) requests)
                .poll(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
