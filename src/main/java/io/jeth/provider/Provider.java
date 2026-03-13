/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.model.RpcModels;
import java.util.concurrent.CompletableFuture;

/**
 * Transport abstraction. Implement this to use any RPC transport (HTTP, WebSocket, IPC, batching
 * proxies, test mocks, etc.).
 */
public interface Provider extends AutoCloseable {

    /** Send a JSON-RPC request and return the response. */
    CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest request);

    /** The ObjectMapper configured for this provider. */
    ObjectMapper getObjectMapper();

    /** Close any underlying connections. Default no-op for stateless HTTP providers. */
    default void close() {}
}
