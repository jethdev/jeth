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
}
