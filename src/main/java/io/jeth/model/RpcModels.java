/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** JSON-RPC 2.0 request/response models. */
public class RpcModels {

    private static final AtomicLong ID_GEN = new AtomicLong(1);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RpcRequest {
        @JsonProperty("jsonrpc")
        public final String jsonrpc = "2.0";

        @SuppressWarnings("unused")
        public String getJsonrpc() {
            return jsonrpc;
        }

        @JsonProperty("id")
        public final long id = ID_GEN.getAndIncrement();

        @JsonProperty("method")
        public final String method;

        @JsonProperty("params")
        public final List<?> params;

        public RpcRequest(String method, List<?> params) {
            this.method = method;
            this.params = params;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RpcResponse {
        @JsonProperty("id")
        public long id;

        @JsonProperty("result")
        public JsonNode result;

        @JsonProperty("error")
        public JsonNode error;

        public RpcResponse() {}

        @SuppressWarnings("unused")
        public RpcResponse(long id, JsonNode result, JsonNode error) {
            this.id = id;
            this.result = result;
            this.error = error;
        }

        public RpcResponse(long id, String resultText, JsonNode error) {
            this.id = id;
            this.result =
                    resultText == null
                            ? null
                            : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(
                                    resultText.replace("\"", ""));
            this.error = error;
        }

        public boolean hasError() {
            return error != null && !error.isNull();
        }

        /** Get result as plain text (for hex strings, etc.). */
        public String resultAsText() {
            if (result == null || result.isNull()) return null;
            return result.isTextual() ? result.asText() : result.toString();
        }

        /** Get error message as human-readable string. */
        public String errorMessage() {
            if (error == null || error.isNull()) return null;
            if (error.isTextual()) return error.asText();
            if (error.has("message")) return error.get("message").asText();
            return error.toString();
        }

        /** If the error contains revert data (eth_call reverts), extract it. */
        public String revertData() {
            if (error == null) return null;
            JsonNode data = error.path("data");
            if (!data.isMissingNode() && data.isTextual()) return data.asText();
            return null;
        }
    }
}
