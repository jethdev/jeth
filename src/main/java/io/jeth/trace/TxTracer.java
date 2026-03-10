/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.trace;

import com.fasterxml.jackson.databind.JsonNode;
import io.jeth.core.EthClient;
import io.jeth.model.EthModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Transaction trace decoder — human-readable call traces.
 *
 * Decodes debug_traceTransaction into a tree of contract calls,
 * showing exactly what happened inside a transaction.
 *
 * <pre>
 * var tracer = TxTracer.of(client);
 *
 * CallTrace trace = tracer.trace("0xTxHash").join();
 * System.out.println(trace.render());
 * // Output:
 * // CALL  0xRouter → 0xPool  [exactInputSingle] 0.1 ETH
 * //   CALL  0xPool → 0xToken0  [transfer] 0 ETH  ✓
 * //   CALL  0xPool → 0xToken1  [transfer] 0 ETH  ✓
 *
 * // Check if a tx reverted and why
 * if (!trace.success()) System.out.println("Revert: " + trace.revertReason());
 * </pre>
 */
public class TxTracer {

    private final EthClient client;

    private TxTracer(EthClient client) { this.client = client; }

    public static TxTracer of(EthClient client) { return new TxTracer(client); }

    /**
     * Trace a transaction — requires archive node or debug namespace.
     */
    public CompletableFuture<CallTrace> trace(String txHash) {
        return client.traceTransaction(txHash, Map.of("tracer", "callTracer"))
                .thenApply(TxTracer::parseTrace);
    }

    /**
     * Trace with a custom JS tracer string.
     */
    public CompletableFuture<JsonNode> traceRaw(String txHash, String jsTracer) {
        return client.traceTransaction(txHash, Map.of("tracer", jsTracer));
    }

    /**
     * Trace an eth_call before broadcasting — simulate a tx and get its full call tree.
     */
    public CompletableFuture<CallTrace> simulateTrace(EthModels.CallRequest req) {
        return client.traceCall(req, "latest", Map.of("tracer", "callTracer"))
                .thenApply(TxTracer::parseTrace);
    }

    private static CallTrace parseTrace(JsonNode node) {
        if (node == null || node.isNull()) return CallTrace.empty();
        String type    = node.path("type").asText("CALL");
        String from    = node.path("from").asText();
        String to      = node.path("to").asText();
        String input   = node.path("input").asText("0x");
        String output  = node.path("output").asText("0x");
        String value   = node.path("value").asText("0x0");
        String gas     = node.path("gas").asText("0x0");
        String gasUsed = node.path("gasUsed").asText("0x0");
        String error   = node.path("error").asText(null);
        String revertReason = node.path("revertReason").asText(null);

        List<CallTrace> calls = new ArrayList<>();
        JsonNode callsNode = node.path("calls");
        if (callsNode.isArray()) callsNode.forEach(c -> calls.add(parseTrace(c)));

        String selector = input.length() >= 10 ? input.substring(0, 10) : input;
        return new CallTrace(type, from, to, selector, value, gas, gasUsed,
                error, revertReason, output, calls);
    }

    // ─── CallTrace ───────────────────────────────────────────────────────────

    public record CallTrace(
            String type, String from, String to, String selector,
            String value, String gas, String gasUsed,
            String error, String revertReason, String output,
            List<CallTrace> calls
    ) {
        public static CallTrace empty() {
            return new CallTrace("CALL","","","0x","0x0","0x0","0x0", null, null, "0x", List.of());
        }

        public boolean success()   { return error == null || error.isEmpty(); }
        public boolean isReverted(){ return !success(); }
        public int     depth()     { return calls.isEmpty() ? 0 : 1 + calls.stream().mapToInt(CallTrace::depth).max().orElse(0); }
        public int     callCount() { return calls.size() + calls.stream().mapToInt(CallTrace::callCount).sum(); }

        /** Render as human-readable indented call tree. */
        public String render() {
            var sb = new StringBuilder();
            render(sb, 0);
            return sb.toString();
        }

        private void render(StringBuilder sb, int indent) {
            sb.append("  ".repeat(indent));
            sb.append(type).append("  ");
            sb.append(abbrev(from)).append(" → ").append(abbrev(to));
            if (!selector.isBlank() && selector.length() >= 10)
                sb.append("  [").append(selector).append("]");
            if (!value.equals("0x0") && !value.equals("0x"))
                sb.append("  value=").append(value);
            sb.append("  gas=").append(gasUsed);
            sb.append(success() ? "  ✓" : "  ✗ " + (error != null ? error : "reverted"));
            if (revertReason != null) sb.append(" (").append(revertReason).append(")");
            sb.append("\n");
            calls.forEach(c -> c.render(sb, indent + 1));
        }

        private static String abbrev(String addr) {
            if (addr == null || addr.length() < 10) return addr;
            return addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4);
        }
    }
}
