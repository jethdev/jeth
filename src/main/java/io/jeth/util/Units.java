/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Ethereum unit conversions: wei ↔ ETH, token amounts, gwei, and gas cost formatting.
 *
 * <p>All arithmetic uses {@link java.math.BigDecimal} to avoid floating-point errors.
 * Rounding mode is always {@link java.math.RoundingMode#DOWN} (truncation) unless
 * otherwise noted — this is the safe direction for token math.
 *
 * <pre>
 * // ETH
 * BigInteger oneEth   = Units.toWei("1.5");
 * String     readable = Units.formatEther(balance);        // "1.234567890123456789"
 * String     pretty   = Units.formatEtherTrimmed(balance); // "1.2345" (max 6 decimals)
 *
 * // ERC-20 tokens (USDC has 6 decimals)
 * BigInteger raw = Units.parseToken("100.50", 6);  // 100_500_000
 * BigDecimal amt = Units.fromWei(raw, 6);          // 100.50
 * String     fmt = Units.formatToken(raw, 6, 2);   // "100.50"
 *
 * // Gas
 * BigInteger gwei = Units.gweiToWei(30);
 * String cost     = Units.formatGasCostEth(21_000, gwei); // "0.00063 ETH"
 * </pre>
 *
 * <p>ethers.js-compatible aliases are provided: {@link #parseUnits}, {@link #formatUnits},
 * {@link #parseEther}, {@link #formatEther(BigInteger)}.
 */
public final class Units {

    private Units() {}

    public static final BigInteger WEI_PER_ETHER  = BigInteger.TEN.pow(18);
    public static final BigInteger WEI_PER_GWEI   = BigInteger.TEN.pow(9);
    public static final BigDecimal ETHER_DECIMAL   = new BigDecimal(WEI_PER_ETHER);

    // ─── ETH ─────────────────────────────────────────────────────────────────

    /** Parse a decimal ETH string to wei. e.g. "1.5" → 1_500_000_000_000_000_000 */
    public static BigInteger toWei(String ethAmount) {
        return new BigDecimal(ethAmount).multiply(ETHER_DECIMAL).toBigInteger();
    }

    public static BigInteger toWei(double ethAmount) {
        return toWei(String.valueOf(ethAmount));
    }

    /** Convert gwei to wei. */
    public static BigInteger gweiToWei(long gwei) {
        return BigInteger.valueOf(gwei).multiply(WEI_PER_GWEI);
    }

    public static BigInteger gweiToWei(BigInteger gwei) {
        return gwei.multiply(WEI_PER_GWEI);
    }

    /** Convert wei to gwei (truncated). */
    public static BigInteger weiToGwei(BigInteger wei) {
        return wei.divide(WEI_PER_GWEI);
    }

    /** Convert wei to ETH as BigDecimal (full precision). */
    public static BigDecimal toEther(BigInteger wei) {
        return new BigDecimal(wei).divide(ETHER_DECIMAL, 18, RoundingMode.DOWN);
    }

    /** Format wei as a full-precision ETH string. e.g. "1.234567890123456789" */
    public static String formatEther(BigInteger wei) {
        return toEther(wei).toPlainString();
    }

    /**
     * Format wei as a trimmed ETH string with up to 6 significant decimal digits.
     * e.g. 1_234_500_000_000_000_000 → "1.2345"
     */
    public static String formatEtherTrimmed(BigInteger wei) {
        return formatEtherTrimmed(wei, 6);
    }

    public static String formatEtherTrimmed(BigInteger wei, int maxDecimals) {
        BigDecimal eth = toEther(wei).stripTrailingZeros();
        int scale = Math.min(eth.scale(), maxDecimals);
        return eth.setScale(scale, RoundingMode.DOWN).toPlainString();
    }

    // ─── ERC-20 Tokens ────────────────────────────────────────────────────────

    /**
     * Parse a human-readable token amount to its raw integer form.
     * e.g. parseToken("100.5", 6) → 100_500_000  (USDC)
     */
    public static BigInteger parseToken(String amount, int decimals) {
        BigDecimal factor = BigDecimal.TEN.pow(decimals);
        return new BigDecimal(amount).multiply(factor).toBigInteger();
    }

    /**
     * Convert a raw token amount to a human-readable BigDecimal.
     * e.g. fromWei(100_500_000, 6) → 100.500000
     */
    public static BigDecimal fromWei(BigInteger rawAmount, int decimals) {
        BigDecimal factor = BigDecimal.TEN.pow(decimals);
        return new BigDecimal(rawAmount).divide(factor, decimals, RoundingMode.DOWN);
    }

    /**
     * Format a raw token amount as a human-readable string with given decimal places.
     * e.g. formatToken(100_500_000, 6, 2) → "100.50"
     */
    public static String formatToken(BigInteger rawAmount, int decimals, int displayDecimals) {
        BigDecimal value = fromWei(rawAmount, decimals);
        return value.setScale(displayDecimals, RoundingMode.DOWN).toPlainString();
    }

    /** Auto-format token: strips trailing zeros, max 6 decimal places. */
    public static String formatToken(BigInteger rawAmount, int decimals) {
        BigDecimal value = fromWei(rawAmount, decimals).stripTrailingZeros();
        int scale = Math.min(value.scale(), Math.min(decimals, 6));
        return value.setScale(Math.max(0, scale), RoundingMode.DOWN).toPlainString();
    }

    // ─── Gas ─────────────────────────────────────────────────────────────────

    /**
     * Compute total gas cost: gasUsed × baseFeeWei.
     * e.g. formatGasCostEth(21000, gweiToWei(30)) → "0.00063 ETH"
     */
    public static String formatGasCostEth(long gasUsed, BigInteger gasPriceWei) {
        BigInteger totalWei = BigInteger.valueOf(gasUsed).multiply(gasPriceWei);
        return formatEtherTrimmed(totalWei, 8) + " ETH";
    }

    /** Format a gwei BigInteger as a human-readable string. e.g. "30.5 gwei" */
    public static String formatGwei(BigInteger wei) {
        BigDecimal gwei = new BigDecimal(wei).divide(new BigDecimal(WEI_PER_GWEI), 2, RoundingMode.HALF_UP);
        return gwei.stripTrailingZeros().toPlainString() + " gwei";
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    /** Max uint256 value (for unlimited approvals). */
    public static final BigInteger MAX_UINT256 =
            BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    // ─── ethers.js-compatible aliases ────────────────────────────────────────

    /**
     * Parse a decimal string to the smallest unit, given decimal places.
     * Equivalent to ethers.js {@code parseUnits(value, decimals)}.
     *
     * <pre>
     * Units.parseUnits("1.5", 18)  // 1_500_000_000_000_000_000 (ETH)
     * Units.parseUnits("100.5", 6) // 100_500_000              (USDC)
     * </pre>
     */
    public static BigInteger parseUnits(String value, int decimals) {
        return parseToken(value, decimals);
    }

    /**
     * Format a raw amount to a human-readable decimal string.
     * Equivalent to ethers.js {@code formatUnits(value, decimals)}.
     *
     * <pre>
     * Units.formatUnits(BigInteger.valueOf(1_500_000_000_000_000_000L), 18) // "1.5"
     * Units.formatUnits(BigInteger.valueOf(100_500_000), 6)                  // "100.5"
     * </pre>
     */
    public static String formatUnits(BigInteger rawAmount, int decimals) {
        return fromWei(rawAmount, decimals).stripTrailingZeros().toPlainString();
    }

    /** Alias for {@link #toWei(String)} — parseUnits with 18 decimals. */
    public static BigInteger parseEther(String ethAmount) { return toWei(ethAmount); }

    /** Alias for {@link #formatEther(BigInteger)} — formatUnits with 18 decimals. */
    public static String formatEther(BigInteger wei) {
        return toEther(wei).stripTrailingZeros().toPlainString();
    }
}
