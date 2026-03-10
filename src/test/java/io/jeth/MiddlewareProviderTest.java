/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.middleware.MiddlewareProvider;
import io.jeth.model.RpcModels;
import io.jeth.provider.Provider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MiddlewareProviderTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Caching ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache hit: repeated cacheable call hits provider once")
    void cache_hit() {
        var calls = new AtomicInteger();
        var provider =
                MiddlewareProvider.wrap(stub(calls, "\"0x1\""))
                        .withCache(Duration.ofSeconds(60))
                        .build();
        var req = req("eth_chainId");
        provider.send(req).join();
        provider.send(req).join();
        provider.send(req).join();
        assertEquals(1, calls.get(), "Only first call should hit the underlying provider");
    }

    @Test
    @DisplayName("Cache miss: eth_sendRawTransaction always hits provider")
    void cache_miss_non_cacheable() {
        var calls = new AtomicInteger();
        var provider =
                MiddlewareProvider.wrap(stub(calls, "\"0xtxhash\""))
                        .withCache(Duration.ofSeconds(60))
                        .build();
        var req = req("eth_sendRawTransaction");
        provider.send(req).join();
        provider.send(req).join();
        assertEquals(2, calls.get(), "Writes must never be cached");
    }

    @Test
    @DisplayName("clearCache() forces fresh fetch on next call")
    void clear_cache() {
        var calls = new AtomicInteger();
        var provider =
                MiddlewareProvider.wrap(stub(calls, "\"0x1\""))
                        .withCache(Duration.ofSeconds(60))
                        .build();
        var req = req("eth_chainId");
        provider.send(req).join(); // miss
        provider.send(req).join(); // hit
        provider.clearCache();
        provider.send(req).join(); // miss again
        assertEquals(2, calls.get());
    }

    // ─── Metrics ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("totalRequests increments with each call")
    void metrics_total() {
        var provider = MiddlewareProvider.wrap(stub(new AtomicInteger(), "\"0x1\"")).build();
        provider.send(req("eth_blockNumber")).join();
        provider.send(req("eth_blockNumber")).join();
        assertEquals(2L, provider.getMetrics().totalRequests.get());
    }

    @Test
    @DisplayName("cacheHitRate() is 75% for 1 miss + 3 hits")
    void metrics_hit_rate() {
        var provider =
                MiddlewareProvider.wrap(stub(new AtomicInteger(), "\"0x1\""))
                        .withCache(Duration.ofSeconds(60))
                        .build();
        var req = req("eth_chainId");
        provider.send(req).join(); // miss
        provider.send(req).join(); // hit
        provider.send(req).join(); // hit
        provider.send(req).join(); // hit
        assertEquals(75.0, provider.getMetrics().cacheHitRate(), 0.01);
    }

    @Test
    @DisplayName("getMetrics().toString() has all fields")
    void metrics_to_string() {
        var provider = MiddlewareProvider.wrap(stub(new AtomicInteger(), "\"0x1\"")).build();
        provider.send(req("eth_blockNumber")).join();
        String s = provider.getMetrics().toString();
        assertTrue(s.contains("requests="));
        assertTrue(s.contains("cacheHits="));
    }

    // ─── Fallback ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("withFallback: uses first healthy provider")
    void fallback_first_ok() {
        var c1 = new AtomicInteger();
        var c2 = new AtomicInteger();
        var fp = MiddlewareProvider.withFallback(stub(c1, "\"p1\""), stub(c2, "\"p2\""));
        var resp = fp.send(req("eth_chainId")).join();
        assertEquals("p1", resp.result.asText());
        assertEquals(0, c2.get(), "Second provider must not be called");
    }

    @Test
    @DisplayName("withFallback: falls back when first provider throws")
    void fallback_second_on_error() {
        var c2 = new AtomicInteger();
        var fp = MiddlewareProvider.withFallback(failing(), stub(c2, "\"p2\""));
        var resp = fp.send(req("eth_chainId")).join();
        assertEquals("p2", resp.result.asText());
        assertEquals(1, c2.get());
    }

    @Test
    @DisplayName("withFallback: throws when ALL providers fail")
    void fallback_all_fail() {
        var fp = MiddlewareProvider.withFallback(failing(), failing());
        assertThrows(Exception.class, () -> fp.send(req("eth_chainId")).join());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static RpcModels.RpcRequest req(String method) {
        return new RpcModels.RpcRequest(method, List.of());
    }

    static Provider stub(AtomicInteger counter, String resultJson) {
        return new Provider() {
            public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest req) {
                counter.incrementAndGet();
                try {
                    var r = new RpcModels.RpcResponse();
                    r.id = req.id;
                    r.result = MAPPER.readTree(resultJson);
                    return CompletableFuture.completedFuture(r);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            public ObjectMapper getObjectMapper() {
                return MAPPER;
            }
        };
    }

    static Provider failing() {
        return new Provider() {
            public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest req) {
                return CompletableFuture.failedFuture(new RuntimeException("provider down"));
            }

            public ObjectMapper getObjectMapper() {
                return MAPPER;
            }
        };
    }
}
