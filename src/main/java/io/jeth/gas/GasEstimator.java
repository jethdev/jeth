/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.gas;

import io.jeth.core.EthClient;
import io.jeth.util.Hex;
import io.jeth.model.EthModels;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Smart gas estimation — goes beyond a single eth_gasPrice call.
 *
 * Uses EIP-1559 fee history to compute optimal fee suggestions
 * with low/medium/high confidence tiers (like MetaMask / Blocknative).
 *
 * <pre>
 * var gas = GasEstimator.of(client);
 *
 * GasEstimator.Fees fees = gas.suggest().join();
 * System.out.println("Low:    " + fees.low().maxFeeGwei()    + " gwei");
 * System.out.println("Medium: " + fees.medium().maxFeeGwei() + " gwei");
 * System.out.println("High:   " + fees.high().maxFeeGwei()   + " gwei");
 *
 * // Use in a transaction:
 * tx.maxFeePerGas(fees.medium().maxFeePerGas())
 *   .maxPriorityFeePerGas(fees.medium().maxPriorityFeePerGas());
 * </pre>
 */
public class GasEstimator {

    private static final BigInteger GWEI = BigInteger.TEN.pow(9);

    private final EthClient client;

    private GasEstimator(EthClient client) { this.client = client; }

    public static GasEstimator of(EthClient client) { return new GasEstimator(client); }

    /**
     * Suggest EIP-1559 gas fees in three confidence tiers.
     * Analyzes the last 10 blocks of fee history.
     */
    public CompletableFuture<Fees> suggest() {
        return client.getFeeHistory(10, "latest", List.of(10, 50, 90))
                .thenCombine(client.getBlock("latest"), (history, block) -> {
                    BigInteger baseFee = block.baseFeePerGas != null ? block.baseFeePerGas : BigInteger.ZERO;
                    // Next block baseFee: roughly current + 12.5% if full
                    BigInteger nextBase = baseFee.multiply(BigInteger.valueOf(112)).divide(BigInteger.valueOf(100));

                    BigInteger tipLow    = medianTip(history, 0);
                    BigInteger tipMedium = medianTip(history, 1);
                    BigInteger tipHigh   = medianTip(history, 2);

                    return new Fees(
                        new FeeOption(nextBase.add(tipLow),    tipLow,    "slow",   "~60s"),
                        new FeeOption(nextBase.add(tipMedium), tipMedium, "medium", "~15s"),
                        new FeeOption(nextBase.add(tipHigh),   tipHigh,   "fast",   "~6s"),
                        nextBase
                    );
                });
    }

    /**
     * Estimate total gas cost for a transaction (baseFee + tip).
     * Uses current baseFee and the node's suggested priority fee.
     */
    public CompletableFuture<BigInteger> estimateGasCost(EthModels.CallRequest req) {
        return client.estimateGas(req).thenCompose(gasLimit ->
                client.getBlock("latest").thenCombine(client.getMaxPriorityFeePerGas(), (block, tip) -> {
                    BigInteger base = block.baseFeePerGas != null ? block.baseFeePerGas : BigInteger.ZERO;
                    return gasLimit.multiply(base.add(tip));
                }));
    }

    private static BigInteger medianTip(EthModels.FeeHistory history, int percentileIndex) {
        if (history.reward == null || history.reward.isEmpty()) return GWEI.multiply(BigInteger.TWO);
        List<BigInteger> tips = history.reward.stream()
                .filter(r -> r != null && r.size() > percentileIndex)
                .map(r -> Hex.toBigInteger(r.get(percentileIndex)))
                .sorted()
                .toList();
        if (tips.isEmpty()) return GWEI.multiply(BigInteger.TWO);
        return tips.get(tips.size() / 2);
    }


    // ─── Records ─────────────────────────────────────────────────────────────

    public record FeeOption(BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas, String label, String eta) {
        public double maxFeeGwei()      { return maxFeePerGas.doubleValue() / 1e9; }
        public double priorityFeeGwei() { return maxPriorityFeePerGas.doubleValue() / 1e9; }

        @Override public String toString() {
            return String.format("FeeOption{%s, maxFee=%.2f gwei, priority=%.2f gwei, eta=%s}",
                    label, maxFeeGwei(), priorityFeeGwei(), eta);
        }
    }

    public record Fees(FeeOption low, FeeOption medium, FeeOption high, BigInteger nextBaseFee) {
        public double baseFeeGwei() { return nextBaseFee.doubleValue() / 1e9; }
    }
}
