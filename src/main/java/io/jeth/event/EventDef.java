/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.event;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Define and decode Solidity events from raw logs.
 *
 * <pre>
 * var Transfer = EventDef.of("Transfer",
 *     EventDef.indexed("from",  "address"),
 *     EventDef.indexed("to",    "address"),
 *     EventDef.data(   "value", "uint256")
 * );
 *
 * // Decode a single log
 * var e = Transfer.decode(log);
 * String from       = e.address("from");
 * BigInteger amount = e.uint("value");
 *
 * // Filter + decode a list
 * Transfer.decodeAll(logs).forEach(e -> System.out.println(e.get("from")));
 *
 * // Log filter for getLogs / WsProvider
 * String topic0 = Transfer.topic0Hex();
 * </pre>
 */
public class EventDef {

    private final String      name;
    private final String      signature;
    private final byte[]      topic0;
    private final List<Param> params;

    private EventDef(String name, List<Param> params) {
        this.name   = name;
        this.params = params;
        this.signature = name + "(" + params.stream()
                .map(p -> p.solidityType).collect(Collectors.joining(",")) + ")";
        this.topic0 = Keccak.hash(signature.getBytes(StandardCharsets.UTF_8));
    }

    public static EventDef of(String name, Param... params) {
        return new EventDef(name, Arrays.asList(params));
    }

    public static Param indexed(String name, String solidityType) {
        return new Param(name, solidityType, true);
    }

    public static Param data(String name, String solidityType) {
        return new Param(name, solidityType, false);
    }

    // ─── Decode ──────────────────────────────────────────────────────────────

    /** Decode a log. Returns null if topic0 doesn't match this event. */
    public DecodedEvent decode(EthModels.Log log) {
        if (log == null || log.topics == null || log.topics.isEmpty()) return null; // non-matching log
        if (!log.topics.get(0).equalsIgnoreCase("0x" + Hex.encodeNoPrefx(topic0))) return null; // wrong event

        var values = new LinkedHashMap<String, Object>();
        var indexed    = params.stream().filter(p -> p.indexed).toList();
        var nonIndexed = params.stream().filter(p -> !p.indexed).toList();

        // Indexed params come from topics[1..n]
        for (int i = 0; i < indexed.size(); i++) {
            Param p = indexed.get(i);
            if (i + 1 < log.topics.size()) {
                values.put(p.name, decodeTopicValue(p.solidityType, log.topics.get(i + 1)));
            }
        }

        // Non-indexed params come from log.data
        if (!nonIndexed.isEmpty() && log.data != null && log.data.length() > 2) {
            AbiType[] types = nonIndexed.stream()
                    .map(p -> AbiType.of(p.solidityType)).toArray(AbiType[]::new);
            byte[] data = Hex.decode(log.data);
            Object[] decoded = AbiCodec.decode(types, data);
            for (int i = 0; i < nonIndexed.size(); i++) {
                values.put(nonIndexed.get(i).name, decoded[i]);
            }
        }

        return new DecodedEvent(log, name, values);
    }

    private Object decodeTopicValue(String type, String topicHex) {
        byte[] bytes = Hex.decode(topicHex);
        AbiType abiType = AbiType.of(type);
        return switch (abiType.baseType()) {
            case "address" -> AbiCodec.decodeAddress(bytes, 0);
            case "bool"    -> bytes[31] != 0;
            case "uint"    -> AbiCodec.decodeBigInt(bytes, 0);
            case "int"     -> AbiCodec.decodeSignedInt(bytes, 0, abiType.size());
            default        -> Hex.encode(bytes); // reference types are hashed — return raw
        };
    }

    /** Filter and decode matching logs from a list. */
    public List<DecodedEvent> decodeAll(List<EthModels.Log> logs) {
        return logs.stream()
                .map(this::decode)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    public String getName()       { return name; }
    public String getSignature()  { return signature; }
    public byte[] getTopic0()     { return Arrays.copyOf(topic0, topic0.length); }
    public String topic0Hex()     { return "0x" + Hex.encodeNoPrefx(topic0); }

    // ─── Records ─────────────────────────────────────────────────────────────

    public record Param(String name, String solidityType, boolean indexed) {}

    public static class DecodedEvent {
        private final EthModels.Log       log;
        private final String              eventName;
        private final Map<String, Object> values;

        public DecodedEvent(EthModels.Log log, String eventName, Map<String, Object> values) {
            this.log       = log;
            this.eventName = eventName;
            this.values    = values;
        }

        /** Get any decoded value by name. */
        public Object get(String name)              { return values.get(name); }

        /** Typed getters. */
        public String     address(String name)      { return (String) values.get(name); }
        public BigInteger uint(String name)         { return (BigInteger) values.get(name); }
        public Boolean    bool(String name)         { return (Boolean) values.get(name); }
        public String     bytes32(String name)      { return (String) values.get(name); }
        public String     str(String name)          { return (String) values.get(name); }

        public EthModels.Log       getLog()         { return log; }
        public String              getEventName()   { return eventName; }
        public Map<String, Object> getValues()      { return Collections.unmodifiableMap(values); }

        @Override public String toString() {
            return eventName + values.toString();
        }
    }
}
