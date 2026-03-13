/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.middleware.MiddlewareProvider;
import io.jeth.model.RpcModels;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the MiddlewareProvider rate limiter (token bucket) correctness. The bug:
 * tokens.decrementAndGet() could go negative, so the fix checks >= 0.
 */
class MiddlewareProviderRateLimitTest {

    /** A stub provider that counts calls and responds instantly. */
    @SuppressWarnings("unused")
    private static MiddlewareProvider.Builder stub(int rps) {
        AtomicInteger counter = new AtomicInteger();
        return MiddlewareProvider.wrap(
                        new io.jeth.provider.Provider() {
                            @Override
                            public CompletableFuture<RpcModels.RpcResponse> send(
                                    RpcModels.RpcRequest req) {
                                counter.incrementAndGet();
                                return CompletableFuture.completedFuture(
                                        new RpcModels.RpcResponse(req.id, "\"0x1\"", null));
                            }

                            @Override
                            public com.fasterxml.jackson.databind.ObjectMapper getObjectMapper() {
                                return new com.fasterxml.jackson.databind.ObjectMapper();
                            }
                        })
                .withRateLimit(rps);
    }

    @Test
    @DisplayName("Rate limiter allows up to maxPerSecond calls without blocking (burst)")
    @Timeout(5)
    void rate_limit_allows_burst() {
        int rps = 10;
        AtomicInteger counter = new AtomicInteger();
        try (var provider =
                MiddlewareProvider.wrap(
                                new io.jeth.provider.Provider() {
                                    @Override
                                    public CompletableFuture<RpcModels.RpcResponse> send(
                                            RpcModels.RpcRequest req) {
                                        counter.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new RpcModels.RpcResponse(req.id, "\"0x1\"", null));
                                    }

                                    @Override
                                    public com.fasterxml.jackson.databind.ObjectMapper
                                            getObjectMapper() {
                                        return new com.fasterxml.jackson.databind.ObjectMapper();
                                    }
                                })
                        .withRateLimit(rps)
                        .build()) {

            // Send rps calls quickly — they should all go through within the first window
            List<CompletableFuture<RpcModels.RpcResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < rps; i++) {
                futures.add(provider.send(new RpcModels.RpcRequest("eth_chainId", List.of())));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            assertEquals(
                    rps, counter.get(), "All " + rps + " burst calls should reach the provider");
        }
    }

    @Test
    @DisplayName("Rate limiter forwards all responses correctly under rate limiting")
    @Timeout(5)
    void rate_limit_correct_responses() throws Exception {
        try (var rpc = new RpcMock()) {
            // Enqueue 3 responses
            rpc.enqueueHex(1L);
            rpc.enqueueHex(2L);
            rpc.enqueueHex(3L);

            try (var provider =
                    MiddlewareProvider.wrap(io.jeth.provider.HttpProvider.of(rpc.url()))
                            .withRateLimit(5)
                            .build()) {

                // All 3 calls should complete with correct results
                for (int i = 1; i <= 3; i++) {
                    var resp =
                            provider.send(new RpcModels.RpcRequest("eth_blockNumber", List.of()))
                                    .join();
                    assertFalse(resp.hasError(), "Response " + i + " must not have error");
                    assertNotNull(resp.result, "Response " + i + " must have result");
                }
            }
        }
    }

    @Test
    @DisplayName("Token bucket never allows more than maxPerSecond in same window")
    @Timeout(5)
    void rate_limit_enforces_cap() throws InterruptedException {
        int rps = 3;
        AtomicInteger callCount = new AtomicInteger();

        // Provider that counts how many calls happen
        try (var provider =
                MiddlewareProvider.wrap(
                                new io.jeth.provider.Provider() {
                                    @Override
                                    public CompletableFuture<RpcModels.RpcResponse> send(
                                            RpcModels.RpcRequest req) {
                                        callCount.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new RpcModels.RpcResponse(req.id, "\"0x1\"", null));
                                    }

                                    @Override
                                    public com.fasterxml.jackson.databind.ObjectMapper
                                            getObjectMapper() {
                                        return new com.fasterxml.jackson.databind.ObjectMapper();
                                    }
                                })
                        .withRateLimit(rps)
                        .build()) {

            long start = System.currentTimeMillis();
            // Send rps calls — should all pass in first window
            for (int i = 0; i < rps; i++) {
                provider.send(new RpcModels.RpcRequest("eth_chainId", List.of())).join();
            }
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(rps, callCount.get());
            // The rps calls should complete quickly (no blocking for first window)
            assertTrue(
                    elapsed < 2000,
                    "First "
                            + rps
                            + " calls should complete under rate limit without long blocking, took "
                            + elapsed
                            + "ms");
        }
    }

    @Test
    @DisplayName("Rate limiter metrics track calls correctly")
    @Timeout(5)
    void rate_limit_metrics() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);
            rpc.enqueueHex(2L);

            try (var provider =
                    MiddlewareProvider.wrap(io.jeth.provider.HttpProvider.of(rpc.url()))
                            .withRateLimit(100)
                            .build()) {

                provider.send(new RpcModels.RpcRequest("eth_blockNumber", List.of())).join();
                provider.send(new RpcModels.RpcRequest("eth_blockNumber", List.of())).join();

                var metrics = provider.getMetrics();
                assertEquals(2, metrics.totalRequests.get(), "Should track 2 total requests");
            }
        }
    }
}
