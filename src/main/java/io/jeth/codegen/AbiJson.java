/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.codegen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthException;
import java.util.List;

/** Solidity ABI JSON models — matches the exact JSON output of solc / Hardhat / Foundry. */
public class AbiJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse an ABI JSON string. Accepts both raw array {@code [...]} and Hardhat/Foundry artifact
     * {@code {"abi":[...]}}.
     */
    public static List<Entry> parse(String json) {
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("{")) {
                var node = MAPPER.readTree(trimmed);
                if (node.has("abi")) trimmed = node.get("abi").toString();
            }
            return MAPPER.readValue(trimmed, new TypeReference<>() {});
        } catch (Exception e) {
            throw new EthException("Failed to parse ABI JSON: " + e.getMessage(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        @JsonProperty("type")
        public String type;

        @JsonProperty("name")
        public String name;

        @JsonProperty("inputs")
        public List<Param> inputs;

        @JsonProperty("outputs")
        public List<Param> outputs;

        @JsonProperty("stateMutability")
        public String stateMutability;

        @JsonProperty("constant")
        public Boolean constant;

        @JsonProperty("payable")
        public Boolean payable;

        public boolean isFunction() {
            return "function".equals(type);
        }

        public boolean isConstructor() {
            return "constructor".equals(type);
        }

        public boolean isEvent() {
            return "event".equals(type);
        }

        public boolean isError() {
            return "error".equals(type);
        }

        public boolean isView() {
            return "view".equals(stateMutability)
                    || "pure".equals(stateMutability)
                    || Boolean.TRUE.equals(constant);
        }

        public boolean isPayable() {
            return "payable".equals(stateMutability) || Boolean.TRUE.equals(payable);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Param {
        @JsonProperty("name")
        public String name;

        @JsonProperty("type")
        public String type;

        @JsonProperty("internalType")
        public String internalType;

        @JsonProperty("components")
        public List<Param> components;

        public String canonicalType() {
            if (type != null && type.startsWith("tuple")) {
                String inner =
                        components == null
                                ? ""
                                : components.stream()
                                        .map(Param::canonicalType)
                                        .reduce((a, b) -> a + "," + b)
                                        .orElse("");
                return "(" + inner + ")" + type.substring("tuple".length());
            }
            return type;
        }

        public String safeName(int index) {
            if (name == null || name.isBlank()) return "arg" + index;
            return switch (name) {
                case "from",
                        "to",
                        "value",
                        "data",
                        "class",
                        "return",
                        "new",
                        "this",
                        "super",
                        "int",
                        "long",
                        "boolean" ->
                        name + "_";
                default -> name;
            };
        }
    }
}
