/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.model.RpcModels;
import io.jeth.provider.BatchProvider;
import io.jeth.util.Hex;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchProviderTest {

    @Test
    @DisplayName("builder() creates provider without throwing")
    void builder_creates() throws Exception {
        try (var rpc = new RpcMock()) {
            var provider = BatchProvider.of(rpc.url()).maxBatchSize(5).windowMs(10).build();
            assertNotNull(provider);
            provider.close();
        }
    }

    @Test
    @DisplayName("single request still returns correct result")
    void single_request() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(42L);
            try (var provider = BatchProvider.of(rpc.url()).windowMs(10).build()) {
                var req = new RpcModels.RpcRequest("eth_blockNumber", List.of());
                var resp = provider.send(req).join();
                assertNotNull(resp);
                assertNotNull(resp.result);
                assertEquals(42L, Hex.toBigInteger(resp.result.asText()).longValue());
            }
        }
    }

    @Test
    @DisplayName("maxBatchSize(1) sends each request individually")
    void batch_size_1() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);
            rpc.enqueueHex(2L);
            try (var provider = BatchProvider.of(rpc.url()).maxBatchSize(1).windowMs(5).build()) {
                var r1 = provider.send(new RpcModels.RpcRequest("eth_chainId", List.of()));
                var r2 = provider.send(new RpcModels.RpcRequest("eth_blockNumber", List.of()));
                assertNotNull(r1.join());
                assertNotNull(r2.join());
            }
        }
    }

    @Test
    @DisplayName("large batch is split correctly")
    void large_batch_split() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);
            rpc.enqueueHex(2L);
            rpc.enqueueHex(3L);
            try (var provider = BatchProvider.of(rpc.url()).maxBatchSize(2).windowMs(5).build()) {
                var r1 = provider.send(new RpcModels.RpcRequest("m1", List.of()));
                var r2 = provider.send(new RpcModels.RpcRequest("m2", List.of()));
                var r3 = provider.send(new RpcModels.RpcRequest("m3", List.of()));
                assertNotNull(r1.join());
                assertNotNull(r2.join());
                assertNotNull(r3.join());
                assertEquals(2, rpc.requestCount());
            }
        }
    }

    @Test
    @DisplayName("reproduce_timeout handles many requests correctly")
    void many_requests_load() throws Exception {
        try (var rpc = new RpcMock()) {
            int count = 50;
            for (int i = 0; i < count; i++) {
                rpc.enqueueHex(i);
            }

            try (var provider = BatchProvider.of(rpc.url()).maxBatchSize(10).windowMs(5).build()) {
                java.util.List<java.util.concurrent.CompletableFuture<?>> futures =
                        new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    futures.add(
                            provider.send(
                                    new io.jeth.model.RpcModels.RpcRequest(
                                            "eth_blockNumber", java.util.Collections.emptyList())));
                }
                java.util.concurrent.CompletableFuture.allOf(
                                futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
