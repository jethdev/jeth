/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.flashbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Flashbots relay client — private transaction bundles for MEV protection.
 *
 * <p>Flashbots relay client for MEV protection. Bundles are sent directly to block builders,
 * bypassing the public mempool entirely. This protects against:
 *
 * <ul>
 *   <li>Frontrunning — sandwich attacks on DEX trades
 *   <li>Backrunning — value extraction after your transaction
 *   <li>Transaction ordering manipulation
 *   <li>Failed transaction spam (bundles are atomic — all-or-nothing)
 * </ul>
 *
 * <pre>
 * var fb = FlashbotsClient.mainnet(signerWallet);
 *
 * // Bundle: approve USDC then swap, atomically in the same block
 * var bundle = FlashbotsBundle.of()
 *     .tx(signedApproveTx)   // 0x-prefixed signed raw tx
 *     .tx(signedSwapTx)
 *     .build();
 *
 * // Simulate first
 * FlashbotsSimResult sim = fb.simulate(bundle, targetBlockNumber).join();
 * System.out.println("Would pay " + sim.totalMinerPayment() + " wei to miner");
 * System.out.println("Would use " + sim.totalGasUsed() + " gas");
 *
 * // Send to next 5 blocks (increases inclusion probability)
 * fb.sendBundle(bundle, targetBlock, targetBlock + 5).join();
 *
 * // Private single tx (no bundle — simplest MEV protection)
 * String txHash = fb.sendPrivateTransaction(signedRawTx).join();
 * </pre>
 *
 * Compatible with: Flashbots Relay (mainnet/Goerli), MEV Blocker, Titan, BeaverBuild.
 */
public class FlashbotsClient {

    // ─── Known relay endpoints ────────────────────────────────────────────────

    /**
     * Flashbots mainnet relay — the canonical MEV-Share / Flashbots Protect endpoint. Requires an
     * {@code X-Flashbots-Signature} header signed by {@code authSigner}.
     */
    public static final String RELAY_MAINNET = "https://relay.flashbots.net";

    /** Flashbots Goerli relay — testnet only, kept for reference. */
    public static final String RELAY_GOERLI = "https://relay-goerli.flashbots.net";

    /**
     * MEV Blocker relay — routes to multiple builders simultaneously. Does not require a Flashbots
     * account; any wallet can sign the auth header.
     */
    public static final String MEV_BLOCKER = "https://rpc.mevblocker.io";

    /** Titan Builder relay — independent builder with significant block share. */
    public static final String TITAN_RELAY = "https://titanbuilder.xyz";

    /** BeaverBuild relay — another independent builder. */
    public static final String BEAVER_RELAY = "https://rpc.beaverbuild.org";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String relayUrl;
    private final Wallet authSigner; // signs the X-Flashbots-Signature header
    private final HttpClient http;
    private final Duration timeout;

    private FlashbotsClient(String relayUrl, Wallet authSigner, Duration timeout) {
        this.relayUrl = relayUrl;
        this.authSigner = authSigner;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Create a mainnet Flashbots client.
     *
     * @param authSigner any wallet — used only to sign the request header (no ETH needed on it)
     */
    public static FlashbotsClient mainnet(Wallet authSigner) {
        return new FlashbotsClient(RELAY_MAINNET, authSigner, Duration.ofSeconds(10));
    }

    /** Create with a custom relay URL (MEV Blocker, Titan, BeaverBuild, etc.). */
    public static FlashbotsClient of(String relayUrl, Wallet authSigner) {
        return new FlashbotsClient(relayUrl, authSigner, Duration.ofSeconds(10));
    }

    public static FlashbotsClient of(String relayUrl, Wallet authSigner, Duration timeout) {
        return new FlashbotsClient(relayUrl, authSigner, timeout);
    }

    // ─── Bundle sending ───────────────────────────────────────────────────────

    /**
     * Send a Flashbots bundle targeting a specific block.
     *
     * <p>Bundles are atomic: either all transactions are included, or none are. This prevents
     * partial execution (e.g. approval included but swap reverted).
     *
     * @param bundle the bundle to send
     * @param targetBlock the block number to target for inclusion
     * @return the bundle hash (use with {@link #getBundleStats} to track inclusion)
     */
    public CompletableFuture<String> sendBundle(FlashbotsBundle bundle, long targetBlock) {
        return sendBundle(bundle, targetBlock, targetBlock);
    }

    /**
     * Send a bundle targeting a range of blocks. Increases inclusion probability at the cost of
     * exposure across more blocks. Typical range: target to target+3.
     *
     * @param minBlock first block where the bundle can be included
     * @param maxBlock last block where the bundle can be included (max minBlock+25)
     */
    public CompletableFuture<String> sendBundle(
            FlashbotsBundle bundle, long minBlock, long maxBlock) {
        var params = new LinkedHashMap<String, Object>();
        params.put("txs", bundle.rawTxs());
        params.put("blockNumber", "0x" + Long.toHexString(minBlock));
        if (maxBlock > minBlock) params.put("maxBlock", "0x" + Long.toHexString(maxBlock));
        if (bundle.revertingTxHashes() != null && !bundle.revertingTxHashes().isEmpty())
            params.put("revertingTxHashes", bundle.revertingTxHashes());

        return send("eth_sendBundle", List.of(params))
                .thenApply(
                        node -> {
                            checkError(node);
                            JsonNode result = node.path("result");
                            return result.path("bundleHash").asText();
                        });
    }

    /**
     * Simulate a bundle — dry-run it against a pending block. Always simulate before sending to
     * verify no reverts and check miner payment.
     *
     * @param bundle the bundle to simulate
     * @param targetBlockNumber the block to simulate against (use currentBlock + 1)
     */
    public CompletableFuture<FlashbotsSimResult> simulate(
            FlashbotsBundle bundle, long targetBlockNumber) {
        var params = new LinkedHashMap<String, Object>();
        params.put("txs", bundle.rawTxs());
        params.put("blockNumber", "0x" + Long.toHexString(targetBlockNumber));
        params.put("stateBlockNumber", "latest");

        return send("eth_callBundle", List.of(params))
                .thenApply(
                        node -> {
                            checkError(node);
                            return parseSimResult(node.path("result"));
                        });
    }

    /**
     * Send a private single transaction — simplest MEV protection. Bypasses the public mempool
     * completely. No bundling required.
     *
     * @param signedRawTx 0x-prefixed signed raw transaction hex
     * @return the transaction hash
     */
    public CompletableFuture<String> sendPrivateTransaction(String signedRawTx) {
        return sendPrivateTransaction(signedRawTx, null, null);
    }

    /**
     * Send a private transaction with optional target block range and fast mode.
     *
     * @param signedRawTx 0x-prefixed signed raw transaction
     * @param maxBlockNumber maximum block to try inclusion (null = current + 25)
     * @param fast if true, send to all registered builders simultaneously
     */
    public CompletableFuture<String> sendPrivateTransaction(
            String signedRawTx, Long maxBlockNumber, Boolean fast) {
        var preferences = new LinkedHashMap<String, Object>();
        if (fast != null && fast) preferences.put("fast", true);

        var params = new LinkedHashMap<String, Object>();
        params.put("tx", signedRawTx);
        if (maxBlockNumber != null)
            params.put("maxBlockNumber", "0x" + Long.toHexString(maxBlockNumber));
        if (!preferences.isEmpty()) params.put("preferences", preferences);

        return send("eth_sendPrivateTransaction", List.of(params))
                .thenApply(
                        node -> {
                            checkError(node);
                            return node.path("result").asText();
                        });
    }

    /**
     * Cancel a pending private transaction before it's included.
     *
     * @param txHash the hash returned by {@link #sendPrivateTransaction}
     */
    public CompletableFuture<Boolean> cancelPrivateTransaction(String txHash) {
        return send("eth_cancelPrivateTransaction", List.of(Map.of("txHash", txHash)))
                .thenApply(node -> node.path("result").asBoolean(false));
    }

    /**
     * Get inclusion statistics for a previously sent bundle.
     *
     * @param bundleHash the hash returned by {@link #sendBundle}
     * @param blockNumber the target block the bundle was sent for
     */
    @SuppressWarnings("unused")
    public CompletableFuture<JsonNode> getBundleStats(String bundleHash, long blockNumber) {
        return send(
                        "flashbots_getBundleStatsV2",
                        List.of(
                                Map.of(
                                        "bundleHash",
                                        bundleHash,
                                        "blockNumber",
                                        "0x" + Long.toHexString(blockNumber))))
                .thenApply(
                        node -> {
                            checkError(node);
                            return node.path("result");
                        });
    }

    /** Get the user's stats on the Flashbots relay (total bundles, inclusion rate, etc.). */
    @SuppressWarnings("unused")
    public CompletableFuture<JsonNode> getUserStats() {
        return send("flashbots_getUserStatsV2", List.of())
                .thenApply(
                        node -> {
                            checkError(node);
                            return node.path("result");
                        });
    }

    // ─── HTTP + signing ───────────────────────────────────────────────────────

    private CompletableFuture<JsonNode> send(String method, List<Object> params) {
        try {
            var requestBody =
                    MAPPER.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));

            String signature = sign(requestBody);

            var request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(relayUrl))
                            .header("Content-Type", "application/json")
                            .header(
                                    "X-Flashbots-Signature",
                                    authSigner.getAddress() + ":" + signature)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .timeout(timeout)
                            .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(
                            resp -> {
                                if (resp.statusCode() >= 400)
                                    throw new FlashbotsException(
                                            "HTTP " + resp.statusCode() + ": " + resp.body());
                                try {
                                    return MAPPER.readTree(resp.body());
                                } catch (Exception e) {
                                    throw new FlashbotsException(
                                            "Failed to parse response: " + resp.body(), e);
                                }
                            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new FlashbotsException("Failed to build request", e));
        }
    }

    /**
     * Sign the request body for the X-Flashbots-Signature header. Signature = sign(keccak256(body))
     * using EIP-191 personal_sign prefix.
     */
    public String sign(String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] hash = Keccak.hash(bodyBytes);
        // EIP-191 prefix: "\x19Ethereum Signed Message:\n32"
        byte[] prefix = "\u0019Ethereum Signed Message:\n32".getBytes(StandardCharsets.UTF_8);
        byte[] prefixed = new byte[prefix.length + 32];
        System.arraycopy(prefix, 0, prefixed, 0, prefix.length);
        System.arraycopy(hash, 0, prefixed, prefix.length, 32);
        byte[] finalHash = Keccak.hash(prefixed);
        var sig = authSigner.sign(finalHash);
        // Flashbots relay expects v = 27 or 28 (EIP-191 convention)
        var normalized = new Signature(sig.r, sig.s, sig.v + 27);
        return Hex.encode(normalized.toBytes());
    }

    private void checkError(JsonNode node) {
        JsonNode err = node.path("error");
        if (!err.isMissingNode() && !err.isNull())
            throw new FlashbotsException("Relay error: " + err.asText());
    }

    private FlashbotsSimResult parseSimResult(JsonNode result) {
        long totalGasUsed = 0;
        long minerPayment = 0;
        List<FlashbotsSimResult.TxResult> txResults = new ArrayList<>();

        JsonNode results = result.path("results");
        if (results.isArray()) {
            for (JsonNode tx : results) {
                boolean success = tx.path("error").isMissingNode() || tx.path("error").isNull();
                long gasUsed = tx.path("gasUsed").asLong(0);
                long gasFees = tx.path("gasFees").asLong(0);
                String from = tx.path("fromAddress").asText("");
                String to = tx.path("toAddress").asText("");
                String revert = tx.path("error").asText("");
                totalGasUsed += gasUsed;
                txResults.add(
                        new FlashbotsSimResult.TxResult(
                                success, gasUsed, gasFees, from, to, revert));
            }
        }

        String coinbaseDiff = result.path("coinbaseDiff").asText("0");
        try {
            minerPayment = Long.parseLong(coinbaseDiff);
        } catch (Exception ignored) {
        }

        return new FlashbotsSimResult(totalGasUsed, minerPayment, txResults);
    }

    // ─── Records ─────────────────────────────────────────────────────────────

    /**
     * Result of a Flashbots bundle simulation.
     *
     * @param totalGasUsed total gas across all bundle transactions
     * @param totalMinerPayment ETH paid to the miner/validator (coinbase diff) in wei
     * @param txResults per-transaction results
     */
    public record FlashbotsSimResult(
            long totalGasUsed, long totalMinerPayment, List<TxResult> txResults) {

        /** True if all transactions in the bundle succeed. */
        public boolean allSucceeded() {
            return txResults.stream().allMatch(TxResult::success);
        }

        /** True if any transaction reverted. */
        public boolean anyReverted() {
            return txResults.stream().anyMatch(r -> !r.success());
        }

        /** ETH paid to miner as a human-readable string. */
        public String minerPaymentEth() {
            return String.format("%.8f ETH", (double) totalMinerPayment / 1e18);
        }

        public record TxResult(
                boolean success,
                long gasUsed,
                long gasFees,
                String from,
                String to,
                String revertReason) {}
    }

    /** Exception thrown when the Flashbots relay returns an error. */
    public static class FlashbotsException extends RuntimeException {
        public FlashbotsException(String msg) {
            super(msg);
        }

        public FlashbotsException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
