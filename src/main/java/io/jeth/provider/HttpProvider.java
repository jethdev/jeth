/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthException;
import io.jeth.model.RpcModels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * HTTP JSON-RPC provider. Zero external HTTP dependencies (uses Java 11+ built-in HttpClient).
 *
 * <pre>
 * var provider = HttpProvider.of("https://mainnet.infura.io/v3/KEY");
 *
 * // With auth header (some nodes require it)
 * var provider = HttpProvider.builder("https://...")
 *     .header("Authorization", "Bearer TOKEN")
 *     .timeout(Duration.ofSeconds(60))
 *     .build();
 * </pre>
 */
public class HttpProvider implements Provider {

    private final URI rpcUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final Map<String, String> headers;

    private HttpProvider(Builder b) {
        this.rpcUrl = b.uri;
        this.timeout = b.timeout;
        this.headers = b.headers != null ? Map.copyOf(b.headers) : Map.of();
        this.httpClient =
                b.httpClient != null
                        ? b.httpClient
                        : HttpClient.newBuilder()
                                .connectTimeout(b.timeout)
                                .executor(
                                        b.executor != null ? b.executor : ForkJoinPool.commonPool())
                                .build();
        this.mapper = b.mapper != null ? b.mapper : defaultMapper();
    }

    public static HttpProvider of(String url) {
        return new Builder(url).build();
    }

    public static Builder builder(String url) {
        return new Builder(url);
    }

    @Override
    public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest request) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(request);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new EthException("Failed to serialize RPC request", e));
        }

        HttpRequest.Builder reqBuilder =
                HttpRequest.newBuilder()
                        .uri(rpcUrl)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        headers.forEach(reqBuilder::header);

        return httpClient
                .sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(
                        resp -> {
                            int status = resp.statusCode();
                            if (status == 429)
                                throw new EthException("Rate limited (429) by " + rpcUrl.getHost());
                            if (status == 401)
                                throw new EthException(
                                        "Unauthorized (401) — check API key for "
                                                + rpcUrl.getHost());
                            if (status != 200)
                                throw new EthException(
                                        "HTTP " + status + " from " + rpcUrl.getHost());
                            try {
                                return mapper.readValue(resp.body(), RpcModels.RpcResponse.class);
                            } catch (Exception e) {
                                throw new EthException("Failed to parse RPC response", e);
                            }
                        });
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public void close() {} // HttpClient is not closeable in Java 11-17

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
    }

    public static class Builder {
        private final URI uri;
        private Duration timeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private ObjectMapper mapper;
        private Executor executor;
        private HashMap<String, String> headers;

        Builder(String url) {
            this.uri = URI.create(url);
        }

        public Builder timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        public Builder httpClient(HttpClient c) {
            this.httpClient = c;
            return this;
        }

        public Builder objectMapper(ObjectMapper m) {
            this.mapper = m;
            return this;
        }

        public Builder executor(Executor e) {
            this.executor = e;
            return this;
        }

        public Builder header(String k, String v) {
            if (this.headers == null) this.headers = new HashMap<>();
            this.headers.put(k, v);
            return this;
        }

        public HttpProvider build() {
            return new HttpProvider(this);
        }
    }
}
