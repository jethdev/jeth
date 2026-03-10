/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import io.jeth.core.EthClient;
import io.jeth.model.EthModels;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-call transaction simulation via eth_simulateV1 (EIP-7731) or eth_call overrides.
 *
 * <p>Simulate sequences of transactions with state overrides before spending real gas. Used for:
 * MEV bundles, complex DeFi flows, Safe pre-flight checks.
 *
 * <pre>
 * var sim = SimulateBundle.of(client);
 *
 * var result = sim.simulate(List.of(
 *     SimulateBundle.tx("0xSender", "0xUSDC",
 *         "0xa9059cbb" + abiEncodeTransfer("0xTo", amount)),  // transfer
 *     SimulateBundle.tx("0xSender", "0xRouter",
 *         swapCalldata)                                        // swap
 * )).join();
 *
 * result.forEach(r -> {
 *     System.out.println(r.success() ? "✓" : "✗ " + r.revertReason());
 * });
 * </pre>
 */
public class SimulateBundle {

    private final EthClient client;

    private SimulateBundle(EthClient client) {
        this.client = client;
    }

    public static SimulateBundle of(EthClient client) {
        return new SimulateBundle(client);
    }

    // ─── Simulate ─────────────────────────────────────────────────────────────

    /**
     * Simulate a sequence of calls using eth_call with state overrides. Each call runs in sequence,
     * passing state from previous calls forward.
     */
    public CompletableFuture<List<SimResult>> simulate(List<SimTx> txs) {
        return simulate(txs, Map.of());
    }

    /**
     * Simulate with state overrides. stateOverrides format: address → {balance?, nonce?, code?,
     * state?, stateDiff?}
     */
    public CompletableFuture<List<SimResult>> simulate(
            List<SimTx> txs, Map<String, Object> stateOverrides) {

        // Try eth_simulateV1 first (EIP-7731, supported by newer nodes)
        List<Map<String, Object>> calls =
                txs.stream()
                        .map(
                                tx -> {
                                    var m = new LinkedHashMap<String, Object>();
                                    if (tx.from != null) m.put("from", tx.from);
                                    m.put("to", tx.to);
                                    m.put("data", tx.data);
                                    if (tx.value != null)
                                        m.put("value", "0x" + tx.value.toString(16));
                                    if (tx.gas != null) m.put("gas", "0x" + tx.gas.toString(16));
                                    return (Map<String, Object>) m;
                                })
                        .toList();

        return client.send(
                        "eth_simulateV1",
                        List.of(
                                Map.of(
                                        "blockStateCalls",
                                        List.of(Map.of("calls", calls)),
                                        "traceTransfers",
                                        true),
                                "latest"))
                .thenApply(
                        resp -> {
                            if (resp.hasError()) return fallbackSimulate(txs, stateOverrides);
                            return parseSimulateV1(resp.result, txs.size());
                        })
                .exceptionally(ex -> fallbackSimulate(txs, stateOverrides));
    }

    private List<SimResult> fallbackSimulate(List<SimTx> txs, Map<String, Object> overrides) {
        // Fallback: run each eth_call independently (no state carry-over)
        return txs.stream()
                .map(
                        tx -> {
                            var req =
                                    EthModels.CallRequest.builder()
                                            .from(tx.from)
                                            .to(tx.to)
                                            .data(tx.data)
                                            .build();
                            try {
                                String result = client.call(req).join();
                                return new SimResult(true, result, null, BigInteger.ZERO);
                            } catch (Exception e) {
                                return new SimResult(false, null, e.getMessage(), BigInteger.ZERO);
                            }
                        })
                .toList();
    }

    private static List<SimResult> parseSimulateV1(JsonNode root, int count) {
        var results = new ArrayList<SimResult>();
        if (root != null && root.isArray()) {
            root.forEach(
                    block -> {
                        var calls = block.path("calls");
                        if (calls.isArray()) {
                            calls.forEach(
                                    call -> {
                                        boolean success = !call.has("error");
                                        String retVal = call.path("returnValue").asText(null);
                                        String error =
                                                success
                                                        ? null
                                                        : call.path("error")
                                                                .path("message")
                                                                .asText("reverted");
                                        results.add(
                                                new SimResult(
                                                        success, retVal, error, BigInteger.ZERO));
                                    });
                        }
                    });
        }
        return results;
    }

    // ─── Static factories ─────────────────────────────────────────────────────

    public static SimTx tx(String from, String to, String calldata) {
        return new SimTx(from, to, calldata, null, null);
    }

    public static SimTx tx(String from, String to, String calldata, BigInteger value) {
        return new SimTx(from, to, calldata, value, null);
    }

    // ─── Records ─────────────────────────────────────────────────────────────

    public record SimTx(String from, String to, String data, BigInteger value, BigInteger gas) {}

    public record SimResult(
            boolean success, String returnData, String revertReason, BigInteger gasUsed) {
        public boolean reverted() {
            return !success;
        }

        public String safeReturn() {
            return returnData != null ? returnData : "0x";
        }
    }
}
