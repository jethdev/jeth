/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight mock JSON-RPC server backed by OkHttp MockWebServer.
 * Enqueue pre-canned responses; use url() to point jeth at it.
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
    private final MockWebServer       server = new MockWebServer();
    private final Queue<String>       queue  = new ArrayDeque<>();
    private final AtomicLong          ids    = new AtomicLong(1);

    public RpcMock() throws IOException {
        server.start();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                try {
                    long id = MAPPER.readTree(req.getBody().readUtf8()).path("id").asLong(ids.getAndIncrement());
                    String result = queue.poll();
                    if (result == null) return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"no mock queued\"}}");
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
    }

    /** Enqueue a raw JSON result string. */
    public RpcMock enqueue(String resultJson)            { queue.add(resultJson); return this; }
    /** Enqueue a JSON string (quoted). */
    public RpcMock enqueueStr(String s)                  { queue.add("\"" + s + "\""); return this; }
    /** Enqueue a hex long (e.g. "0x64" for 100). */
    public RpcMock enqueueHex(long value)                { queue.add("\"0x" + Long.toHexString(value) + "\""); return this; }
    /** Enqueue a hex BigInteger. */
    public RpcMock enqueueHex(BigInteger value)          { queue.add("\"0x" + value.toString(16) + "\""); return this; }
    /** Enqueue null result. */
    public RpcMock enqueueNull()                         { queue.add("null"); return this; }
    /** Enqueue a JSON object literal. */
    public RpcMock enqueueJson(String json)              { queue.add(json); return this; }

    public String url()                { return server.url("/").toString(); }
    public EthClient client()          { return EthClient.of(url()); }
    public int requestCount()          { return server.getRequestCount(); }
    public RecordedRequest takeRequest() throws InterruptedException { return server.takeRequest(); }

    @Override
    public void close() throws IOException { server.shutdown(); }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
