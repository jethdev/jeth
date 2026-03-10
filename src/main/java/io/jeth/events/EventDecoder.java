/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.events;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decode Ethereum event logs into typed Java maps.
 *
 * <pre>
 * var transferEvent = EventDecoder.of("Transfer(address indexed from, address indexed to, uint256 value)");
 *
 * for (EthModels.Log log : receipt.logs) {
 *     if (transferEvent.matches(log)) {
 *         Map<String, Object> data = transferEvent.decode(log);
 *         String from     = (String)     data.get("from");
 *         String to       = (String)     data.get("to");
 *         BigInteger val  = (BigInteger) data.get("value");
 *     }
 * }
 * </pre>
 */
public class EventDecoder {

    /** Standard ERC-20 events */
    public static final EventDecoder ERC20_TRANSFER = EventDecoder.of(
        "Transfer(address indexed from, address indexed to, uint256 value)");
    public static final EventDecoder ERC20_APPROVAL = EventDecoder.of(
        "Approval(address indexed owner, address indexed spender, uint256 value)");

    /** Standard ERC-721 events */
    public static final EventDecoder ERC721_TRANSFER = EventDecoder.of(
        "Transfer(address indexed from, address indexed to, uint256 indexed tokenId)");

    private final String      signature;
    private final String      topic0;
    private final List<Param> indexed;
    private final List<Param> nonIndexed;

    private EventDecoder(String sig, List<Param> params) {
        this.signature  = sig;
        this.topic0     = Hex.encode(Keccak.hash(sig.getBytes(StandardCharsets.UTF_8)));
        this.indexed    = params.stream().filter(p -> p.indexed).toList();
        this.nonIndexed = params.stream().filter(p -> !p.indexed).toList();
    }

    public static EventDecoder of(String signature) {
        return new EventDecoder(normalizeSignature(signature), parseParams(signature));
    }

    public boolean matches(EthModels.Log log) {
        return log.topics != null && !log.topics.isEmpty()
            && topic0.equalsIgnoreCase(log.topics.get(0));
    }

    public Map<String, Object> decode(EthModels.Log log) {
        if (!matches(log)) throw new IllegalArgumentException("Log does not match event: " + signature);

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> topics = log.topics;
        int topicIdx = 1;
        for (Param p : indexed) {
            if (topicIdx >= topics.size()) break;
            byte[] topicBytes = Hex.decode(topics.get(topicIdx++));
            if (p.type.isDynamic()) {
                result.put(p.name, topics.get(topicIdx - 1)); // keccak256 hash — can't decode
            } else {
                result.put(p.name, AbiCodec.decode(new AbiType[]{p.type}, topicBytes)[0]);
            }
        }

        if (!nonIndexed.isEmpty() && log.data != null && log.data.length() > 2) {
            AbiType[] types = nonIndexed.stream().map(p -> p.type).toArray(AbiType[]::new);
            Object[] decoded = AbiCodec.decode(types, Hex.decode(log.data));
            for (int i = 0; i < nonIndexed.size(); i++) result.put(nonIndexed.get(i).name, decoded[i]);
        }

        return result;
    }

    public List<Map<String, Object>> decodeAll(List<EthModels.Log> logs) {
        return logs.stream().filter(this::matches).map(this::decode).toList();
    }

    public String getTopic0()    { return topic0; }
    public String getSignature() { return signature; }

    private static String normalizeSignature(String sig) {
        int paren = sig.indexOf('(');
        String name = sig.substring(0, paren).trim();
        String inner = sig.substring(paren + 1, sig.lastIndexOf(')'));
        if (inner.isBlank()) return name + "()";
        List<String> types = new ArrayList<>();
        for (String part : splitParams(inner)) {
            types.add(part.trim().split("\\s+")[0]);
        }
        return name + "(" + String.join(",", types) + ")";
    }

    private static List<Param> parseParams(String sig) {
        int paren = sig.indexOf('(');
        String inner = sig.substring(paren + 1, sig.lastIndexOf(')'));
        if (inner.isBlank()) return List.of();
        List<Param> params = new ArrayList<>();
        for (String part : splitParams(inner)) {
            String[] tokens = part.trim().split("\\s+");
            String type = tokens[0];
            boolean indexed = List.of(tokens).contains("indexed");
            String name = tokens.length > 1 ? tokens[tokens.length - 1] : ("param" + params.size());
            if (name.equals("indexed")) name = "param" + params.size();
            params.add(new Param(AbiType.of(type), name, indexed));
        }
        return params;
    }

    private static List<String> splitParams(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : inner.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    private record Param(AbiType type, String name, boolean indexed) {}
}
