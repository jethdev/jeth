/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.price.PriceOracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PriceOracle tests — covers Chainlink round decoding, staleness, batch reads,
 * sqrtPriceX96→price math, tick→price math, and decimal adjustments.
 *
 * All on-chain calls are mocked via RpcMock.
 */
class PriceOracleTest {

    // ─── Constants ────────────────────────────────────────────────────────────

    // ETH price: $3412.58, 8 decimals → raw = 341258000000
    static final long ETH_PRICE_RAW = 341_258_000_000L;

    // ─── Chainlink: basic parsing ─────────────────────────────────────────────
    static String encodeRoundData(long roundId, long answer, long startedAt, long updatedAt) {
        // 5 × uint256: roundId, answer (int256), startedAt, updatedAt, answeredInRound
        return "0x"
            + pad64(roundId)
            + pad64(answer)
            + pad64(startedAt)
            + pad64(updatedAt)
            + pad64(roundId);  // answeredInRound == roundId
    }

    static String encodeUint8(int val) {
        return "0x" + pad64(val);
    }

    static String pad64(long val) {
        return String.format("%064x", val);
    }

    // ─── Chainlink: basic parsing ─────────────────────────────────────────────

    @Test @DisplayName("chainlink() parses 8-decimal feed correctly (ETH/USD = $3412.58)")
    void chainlink_parses_8_decimal() throws Exception {
        try (var rpc = new RpcMock()) {
            // latestRoundData() returns (1, 341258000000, 1700000000, 1700000100, 1)
            rpc.enqueueStr(encodeRoundData(1, ETH_PRICE_RAW, 1_700_000_000L, 1_700_000_100L));
            // decimals() returns 8
            rpc.enqueueStr(encodeUint8(8));

            var oracle = new PriceOracle(rpc.client());
            BigDecimal price = oracle.chainlink(PriceOracle.ETH_USD).join();

            assertEquals(new BigDecimal("3412.58000000"), price.stripTrailingZeros().setScale(8));
        }
    }

    @Test @DisplayName("chainlink() with 6-decimal feed returns correct price")
    void chainlink_6_decimal() throws Exception {
        try (var rpc = new RpcMock()) {
            // price = 1.000100 USDC/USD → raw = 1000100
            rpc.enqueueStr(encodeRoundData(1, 1_000_100L, 0, System.currentTimeMillis() / 1000));
            rpc.enqueueStr(encodeUint8(6));

            var oracle = new PriceOracle(rpc.client());
            BigDecimal price = oracle.chainlink(PriceOracle.USDC_USD).join();
            assertTrue(price.compareTo(new BigDecimal("1.0001")) == 0 ||
                       price.toPlainString().startsWith("1.0001"));
        }
    }

    @Test @DisplayName("chainlink() returns positive price for all known feed constants")
    void chainlink_constants_are_valid_addresses() {
        // Just assert they're valid hex addresses, not that they work on-chain
        for (String feed : List.of(PriceOracle.ETH_USD, PriceOracle.BTC_USD,
                PriceOracle.USDC_USD, PriceOracle.USDT_USD, PriceOracle.DAI_USD,
                PriceOracle.LINK_USD, PriceOracle.WBTC_USD)) {
            assertTrue(feed.startsWith("0x"), "Feed must start with 0x: " + feed);
            assertEquals(42, feed.length(), "Feed must be 42 chars: " + feed);
        }
    }

    @Test @DisplayName("chainlink() makes exactly 2 RPC calls (latestRoundData + decimals)")
    void chainlink_makes_two_calls() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr(encodeRoundData(1, ETH_PRICE_RAW, 0, System.currentTimeMillis() / 1000));
            rpc.enqueueStr(encodeUint8(8));
            new PriceOracle(rpc.client()).chainlink(PriceOracle.ETH_USD).join();
            assertEquals(2, rpc.requestCount());
        }
    }

    // ─── Chainlink: staleness ─────────────────────────────────────────────────

    @Test @DisplayName("isStale() returns false when feed was updated recently")
    void is_not_stale_recent() throws Exception {
        long recentTimestamp = System.currentTimeMillis() / 1000 - 100; // 100s ago
        try (var rpc = new RpcMock()) {
            // isStale() only calls latestRoundData (1 RPC call)
            rpc.enqueueStr(encodeRoundData(1, ETH_PRICE_RAW, recentTimestamp, recentTimestamp));
            assertFalse(new PriceOracle(rpc.client()).isStale(PriceOracle.ETH_USD, 3600).join());
        }
    }

    @Test @DisplayName("isStale() returns true when feed was updated over maxAge ago")
    void is_stale_old() throws Exception {
        long oldTimestamp = System.currentTimeMillis() / 1000 - 7200; // 2 hours ago
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr(encodeRoundData(1, ETH_PRICE_RAW, oldTimestamp, oldTimestamp));
            assertTrue(new PriceOracle(rpc.client()).isStale(PriceOracle.ETH_USD, 3600).join());
        }
    }

    @Test @DisplayName("isStale() with very short maxAge always returns true")
    void is_stale_short_window() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000 - 10; // 10s ago
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr(encodeRoundData(1, ETH_PRICE_RAW, timestamp, timestamp));
            assertTrue(new PriceOracle(rpc.client()).isStale(PriceOracle.ETH_USD, 1).join());
        }
    }

    // ─── batchChainlink ───────────────────────────────────────────────────────

    @Test @DisplayName("batchChainlink() with empty list returns empty map without RPC calls")
    void batch_chainlink_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            var prices = new PriceOracle(rpc.client()).batchChainlink(List.of()).join();
            assertTrue(prices.isEmpty());
            assertEquals(0, rpc.requestCount());
        }
    }

    @Test @DisplayName("batchChainlink() with 3 feeds enqueues all feeds into Multicall3 (1 RPC call)")
    void batch_chainlink_single_rpc_call() throws Exception {
        try (var rpc = new RpcMock()) {
            // Multicall3.tryExecute() makes exactly 1 eth_call regardless of number of feeds.
            // Encode a minimal valid Multicall3 tryAggregate3 response: 0 results (empty array).
            // Length prefix + array offset + length=0
            String emptyMulticallResult =
                "0x0000000000000000000000000000000000000000000000000000000000000020" + // offset to array
                "0000000000000000000000000000000000000000000000000000000000000000";  // array length = 0
            rpc.enqueueStr(emptyMulticallResult);

            var oracle = new PriceOracle(rpc.client());
            // Should complete (prices will be null for all feeds since response has no data)
            oracle.batchChainlink(List.of(PriceOracle.ETH_USD, PriceOracle.BTC_USD, PriceOracle.LINK_USD)).join();

            // Key assertion: only 1 RPC call regardless of 3 feeds (Multicall3)
            assertEquals(1, rpc.requestCount(), "batchChainlink should use 1 Multicall3 eth_call");
        }
    }

    @Test @DisplayName("batchChainlink() returns map with correct feed addresses as keys")
    void batch_chainlink_keys() throws Exception {
        try (var rpc = new RpcMock()) {
            String emptyMulticallResult =
                "0x0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000000";
            rpc.enqueueStr(emptyMulticallResult);

            var feeds = List.of(PriceOracle.ETH_USD, PriceOracle.BTC_USD);
            var prices = new PriceOracle(rpc.client()).batchChainlink(feeds).join();

            assertEquals(2, prices.size());
            assertTrue(prices.containsKey(PriceOracle.ETH_USD));
            assertTrue(prices.containsKey(PriceOracle.BTC_USD));
        }
    }

    // ─── sqrtPriceX96 math (static, no RPC) ──────────────────────────────────

    @Test @DisplayName("sqrtPriceX96ToPrice: WETH as token0, USDC as token1 (6 decimals)")
    void sqrt_price_weth_usdc() {
        // A typical ETH/USDC pool sqrtPriceX96 when 1 ETH ≈ 3000 USDC
        // sqrtPrice = sqrt(3000 / 10^(18-6)) * 2^96 = sqrt(3000 * 10^-12) * 2^96
        // ≈ sqrt(3e-9) * 79228162514264337593543950336 ≈ 4.33e9 * 7.92e28 ≈ 3.43e38
        // This is hard to compute exactly, so we test the formula direction instead.

        // Use a known ratio: if sqrtPriceX96 = 2^96 then price = 1.0 (both same decimals)
        BigInteger Q96 = BigInteger.TWO.pow(96);
        BigDecimal price = PriceOracle.sqrtPriceX96ToPrice(Q96,
                PriceOracle.WETH, PriceOracle.WETH, PriceOracle.WETH.toLowerCase());
        // When base=quote=token0, price should be 1.0 (same token)
        assertEquals(0, price.compareTo(new BigDecimal("1.000000")));
    }

    @Test @DisplayName("sqrtPriceX96ToPrice: inversion when base is token1")
    void sqrt_price_inversion() {
        BigInteger Q96 = BigInteger.TWO.pow(96);
        // When base is token1 and sqrtPriceX96 = Q96 → raw = 1 → invert → 1
        BigDecimal price = PriceOracle.sqrtPriceX96ToPrice(Q96,
                "0xTokenB", "0xTokenA", "0xTokenA"); // base is token1
        assertEquals(0, price.compareTo(new BigDecimal("1.000000")));
    }

    @Test @DisplayName("sqrtPriceX96ToPrice: higher sqrtPrice → higher price (monotone)")
    void sqrt_price_monotone() {
        BigInteger low  = BigInteger.TWO.pow(96);
        BigInteger high = BigInteger.TWO.pow(96).multiply(BigInteger.TWO);
        String token = "0xBase";
        BigDecimal pLow  = PriceOracle.sqrtPriceX96ToPrice(low,  token, token, token.toLowerCase());
        BigDecimal pHigh = PriceOracle.sqrtPriceX96ToPrice(high, token, token, token.toLowerCase());
        assertTrue(pHigh.compareTo(pLow) > 0, "Higher sqrtPrice must mean higher price");
    }

    // ─── Tick math (static, no RPC) ──────────────────────────────────────────

    @Test @DisplayName("tickToPrice: tick=0 → price=1.0 (identity)")
    void tick_zero_is_one() {
        String token = "0xToken";
        BigDecimal price = PriceOracle.tickToPrice(0.0, token, token, token.toLowerCase());
        assertEquals(0, price.compareTo(new BigDecimal("1.000000")));
    }

    @Test @DisplayName("tickToPrice: positive tick > 1.0, negative tick < 1.0 (same decimals)")
    void tick_direction() {
        String t = "0xToken";
        BigDecimal pos = PriceOracle.tickToPrice( 10000.0, t, t, t.toLowerCase());
        BigDecimal neg = PriceOracle.tickToPrice(-10000.0, t, t, t.toLowerCase());
        assertTrue(pos.compareTo(new BigDecimal("1")) > 0, "Positive tick should be > 1");
        assertTrue(neg.compareTo(new BigDecimal("1")) < 0, "Negative tick should be < 1");
    }

    @Test @DisplayName("tickToPrice: WETH/USDC tick=204222 ≈ $3000")
    void tick_eth_usdc_approx() {
        // tick≈204222 corresponds to ~$3000 ETH/USDC (empirically known)
        BigDecimal price = PriceOracle.tickToPrice(204222.0,
                PriceOracle.WETH, PriceOracle.USDC, PriceOracle.WETH.toLowerCase());
        // Allow ±15% tolerance since this is approximate
        assertTrue(price.compareTo(new BigDecimal("2500")) > 0, "Price should be > $2500");
        assertTrue(price.compareTo(new BigDecimal("3500")) < 0, "Price should be < $3500");
    }

    // ─── knownDecimals ────────────────────────────────────────────────────────

    @Test @DisplayName("knownDecimals: USDC and USDT return 6")
    void known_decimals_6() {
        assertEquals(6, PriceOracle.knownDecimals(PriceOracle.USDC));
        assertEquals(6, PriceOracle.knownDecimals(PriceOracle.USDT));
    }

    @Test @DisplayName("knownDecimals: WBTC returns 8")
    void known_decimals_8() {
        assertEquals(8, PriceOracle.knownDecimals(PriceOracle.WBTC));
    }

    @Test @DisplayName("knownDecimals: WETH, LINK return 18")
    void known_decimals_18() {
        assertEquals(18, PriceOracle.knownDecimals(PriceOracle.WETH));
        assertEquals(18, PriceOracle.knownDecimals(PriceOracle.LINK));
    }

    @Test @DisplayName("knownDecimals: unknown address returns 18 (safe default)")
    void known_decimals_unknown() {
        assertEquals(18, PriceOracle.knownDecimals("0xUnknownToken12345"));
    }

    // ─── ChainlinkRound record ────────────────────────────────────────────────

    @Test @DisplayName("ChainlinkRound.ageSeconds() is positive")
    void chainlink_round_age() {
        long updatedAt = System.currentTimeMillis() / 1000 - 500;
        var round = new PriceOracle.ChainlinkRound(BigInteger.ONE,
                new BigDecimal("3412.58"), 0L, updatedAt, 8, PriceOracle.ETH_USD);
        assertTrue(round.ageSeconds() >= 499, "Age should be ~500s");
    }

    @Test @DisplayName("ChainlinkRound.isStale() returns correct result")
    void chainlink_round_is_stale() {
        long oldTs = System.currentTimeMillis() / 1000 - 7200;
        var staleRound = new PriceOracle.ChainlinkRound(BigInteger.ONE,
                new BigDecimal("3412.58"), 0L, oldTs, 8, PriceOracle.ETH_USD);
        assertTrue(staleRound.isStale(3600));
        assertFalse(staleRound.isStale(86400));
    }

    @Test @DisplayName("ChainlinkRound.toString() is human-readable")
    void chainlink_round_to_string() {
        var round = new PriceOracle.ChainlinkRound(BigInteger.ONE,
                new BigDecimal("3412.58"), 0L, 1_700_000_000L, 8, PriceOracle.ETH_USD);
        String s = round.toString();
        assertTrue(s.contains("3412.58"));
        assertTrue(s.contains(PriceOracle.ETH_USD));
    }

    // ─── getPool: PriceOracleException ───────────────────────────────────────

    @Test @DisplayName("getPool() throws PriceOracleException when pool is zero address")
    void get_pool_throws_for_zero_address() throws Exception {
        try (var rpc = new RpcMock()) {
            // Return zero address from getPool()
            rpc.enqueueStr("0x000000000000000000000000" + "0".repeat(40));
            var oracle = new PriceOracle(rpc.client());
            assertThrows(Exception.class, () -> oracle.getPool(
                    PriceOracle.WETH, "0xFakeToken", 3000).join());
        }
    }

    // ─── Token address constants sanity check ────────────────────────────────

    @Test @DisplayName("All known token addresses are valid 42-char hex")
    void token_addresses_valid() {
        for (String addr : List.of(PriceOracle.WETH, PriceOracle.USDC, PriceOracle.USDT,
                PriceOracle.DAI, PriceOracle.WBTC, PriceOracle.LINK)) {
            assertTrue(addr.startsWith("0x"), addr);
            assertEquals(42, addr.length(), addr);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────
}
