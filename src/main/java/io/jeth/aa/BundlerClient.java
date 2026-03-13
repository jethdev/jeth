/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.aa;

import com.fasterxml.jackson.databind.JsonNode;
import io.jeth.core.EthException;
import io.jeth.model.RpcModels;
import io.jeth.provider.HttpProvider;
import io.jeth.provider.Provider;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ERC-4337 Bundler client — sends UserOperations to a bundler node.
 *
 * <p>Compatible with any ERC-4337 bundler: Pimlico, Stackup, Alchemy, Biconomy, etc. The bundler
 * exposes the same JSON-RPC port with additional methods.
 *
 * <pre>{@code
 * var bundler = BundlerClient.of("https://api.pimlico.io/v1/sepolia/rpc?apikey=KEY");
 * }</pre>
 */
public class BundlerClient {

    private final Provider provider;

    private BundlerClient(Provider provider) {
        this.provider = provider;
    }

    public static BundlerClient of(String bundlerUrl) {
        return new BundlerClient(HttpProvider.of(bundlerUrl));
    }

    public static BundlerClient of(Provider provider) {
        return new BundlerClient(provider);
    }

    /**
     * eth_sendUserOperation — submit a UserOperation to the bundler.
     *
     * @return The userOpHash (not a tx hash — use waitForReceipt to get the tx hash)
     */
    public CompletableFuture<String> sendUserOperation(UserOperation op, String entryPoint) {
        return send("eth_sendUserOperation", List.of(op.toMap(), entryPoint))
                .thenApply(
                        r -> {
                            checkError(r);
                            return r.resultAsText();
                        });
    }

    /**
     * eth_estimateUserOperationGas — ask bundler to estimate gas fields. Returns a GasEstimate with
     * callGasLimit, verificationGasLimit, preVerificationGas.
     */
    public CompletableFuture<GasEstimate> estimateUserOperationGas(
            UserOperation op, String entryPoint) {
        return send("eth_estimateUserOperationGas", List.of(op.toMap(), entryPoint))
                .thenApply(
                        r -> {
                            checkError(r);
                            JsonNode result = r.result;
                            return new GasEstimate(
                                    hexToBigInt(result, "callGasLimit"),
                                    hexToBigInt(result, "verificationGasLimit"),
                                    hexToBigInt(result, "preVerificationGas"));
                        });
    }

    /** eth_getUserOperationByHash — get a UserOperation by its hash. */
    public CompletableFuture<JsonNode> getUserOperationByHash(String userOpHash) {
        return send("eth_getUserOperationByHash", List.of(userOpHash))
                .thenApply(
                        r -> {
                            checkError(r);
                            return r.result;
                        });
    }

    /**
     * eth_getUserOperationReceipt — get the receipt for a mined UserOperation. Returns null if not
     * yet mined.
     */
    public CompletableFuture<JsonNode> getUserOperationReceipt(String userOpHash) {
        return send("eth_getUserOperationReceipt", List.of(userOpHash))
                .thenApply(
                        r -> {
                            checkError(r);
                            return r.result;
                        });
    }

    /** Poll for the UserOperation receipt until mined (or timeout). */
    public CompletableFuture<JsonNode> waitForUserOperationReceipt(String userOpHash) {
        return waitForUserOperationReceipt(userOpHash, 60_000, 2_000);
    }

    public CompletableFuture<JsonNode> waitForUserOperationReceipt(
            String userOpHash, long timeoutMs, long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        return poll(userOpHash, deadline, pollIntervalMs);
    }

    private CompletableFuture<JsonNode> poll(String hash, long deadline, long intervalMs) {
        return getUserOperationReceipt(hash)
                .thenCompose(
                        result -> {
                            if (result != null && !result.isNull()) {
                                return CompletableFuture.completedFuture(result);
                            }
                            if (System.currentTimeMillis() > deadline) {
                                return CompletableFuture.failedFuture(
                                        new EthException(
                                                "Timeout waiting for UserOperation receipt: "
                                                        + hash));
                            }
                            return CompletableFuture.supplyAsync(
                                            () -> null,
                                            CompletableFuture.delayedExecutor(
                                                    intervalMs, TimeUnit.MILLISECONDS))
                                    .thenCompose(__ -> poll(hash, deadline, intervalMs));
                        });
    }

    /** eth_supportedEntryPoints — which EntryPoint versions the bundler supports. */
    public CompletableFuture<List<String>> getSupportedEntryPoints() {
        return send("eth_supportedEntryPoints", List.of())
                .thenApply(
                        r -> {
                            checkError(r);
                            var list = new ArrayList<String>();
                            if (r.result != null && r.result.isArray()) {
                                for (JsonNode n : r.result) {
                                    list.add(n.asText());
                                }
                            }
                            return list;
                        });
    }

    /**
     * pm_sponsorUserOperation — request paymaster sponsorship (Pimlico/Stackup format).
     *
     * @param policyId Paymaster policy ID from your paymaster provider
     */
    @SuppressWarnings("unused")
    public CompletableFuture<PaymasterData> sponsorUserOperation(
            UserOperation op, String entryPoint, String policyId) {
        return sponsorUserOperationFull(op, entryPoint, Map.of("sponsorshipPolicyId", policyId));
    }

    /** Legacy support for sponsorUserOperation returning String (just the paymasterAndData). */
    public CompletableFuture<PaymasterData> sponsorUserOperation(
            UserOperation op, String entryPoint, Map<String, Object> context) {
        return send("pm_sponsorUserOperation", List.of(op.toMap(), entryPoint, context))
                .thenApply(
                        r -> {
                            checkError(r);
                            JsonNode res = r.result;
                            return new PaymasterData(
                                    res.path("paymasterAndData").asText(),
                                    hexToBigInt(res, "callGasLimit"),
                                    hexToBigInt(res, "verificationGasLimit"),
                                    hexToBigInt(res, "preVerificationGas"));
                        });
    }

    public CompletableFuture<PaymasterData> sponsorUserOperationFull(
            UserOperation op, String entryPoint, Map<String, Object> context) {
        return sponsorUserOperation(op, entryPoint, context);
    }

    public CompletableFuture<RpcModels.RpcResponse> send(String method, List<?> params) {
        return provider.send(new RpcModels.RpcRequest(method, params));
    }

    private void checkError(RpcModels.RpcResponse r) {
        if (r.hasError()) throw new EthException("Bundler RPC error: " + r.error);
    }

    private BigInteger hexToBigInt(JsonNode node, String field) {
        String hex = node.path(field).asText("0x0");
        return hex.startsWith("0x") ? new BigInteger(hex.substring(2), 16) : BigInteger.ZERO;
    }

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    public record GasEstimate(
            BigInteger callGasLimit,
            BigInteger verificationGasLimit,
            BigInteger preVerificationGas) {}

    public record PaymasterData(
            String paymasterAndData,
            BigInteger callGasLimit,
            BigInteger verificationGasLimit,
            BigInteger preVerificationGas) {}
}
