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
     * Parse an ABI JSON string.
     *
     * @param json the JSON string to parse
     * @return the list of ABI entries
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

    /** ABI entry definition. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** type (e.g. "function", "event", "constructor", "error") */
        @JsonProperty("type")
        public String type;

        /** name */
        @JsonProperty("name")
        public String name;

        /** inputs */
        @JsonProperty("inputs")
        public List<Param> inputs;

        /** outputs */
        @JsonProperty("outputs")
        public List<Param> outputs;

        /** stateMutability (e.g. "pure", "view", "nonpayable", "payable") */
        @JsonProperty("stateMutability")
        public String stateMutability;

        /** legacy constant flag */
        @JsonProperty("constant")
        public Boolean constant;

        /** legacy payable flag */
        @JsonProperty("payable")
        public Boolean payable;

        /**
         * @return inputs
         */
        public List<Param> inputs() {
            return inputs;
        }

        /**
         * @return outputs
         */
        @SuppressWarnings("unused")
        public List<Param> outputs() {
            return outputs;
        }

        /**
         * @return true if function
         */
        public boolean isFunction() {
            return "function".equals(type);
        }

        /**
         * @return true if constructor
         */
        public boolean isConstructor() {
            return "constructor".equals(type);
        }

        /**
         * @return true if event
         */
        public boolean isEvent() {
            return "event".equals(type);
        }

        /**
         * @return true if error
         */
        public boolean isError() {
            return "error".equals(type);
        }

        /**
         * @return true if view or pure
         */
        public boolean isView() {
            return "view".equals(stateMutability)
                    || "pure".equals(stateMutability)
                    || Boolean.TRUE.equals(constant);
        }

        /**
         * @return true if payable
         */
        public boolean isPayable() {
            return "payable".equals(stateMutability) || Boolean.TRUE.equals(payable);
        }
    }

    /** ABI parameter definition. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Param {
        /** name */
        @JsonProperty("name")
        public String name;

        /**
         * @return name
         */
        public String name() {
            return name;
        }

        /** type */
        @JsonProperty("type")
        public String type;

        /**
         * @return type
         */
        public String type() {
            return type;
        }

        /** internalType (from compiler) */
        @JsonProperty("internalType")
        public String internalType;

        /**
         * @return internalType
         */
        @SuppressWarnings("unused")
        public String internalType() {
            return internalType;
        }

        /** components (for tuples) */
        @JsonProperty("components")
        public List<Param> components;

        /**
         * @return components
         */
        @SuppressWarnings("unused")
        public List<Param> components() {
            return components;
        }

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
