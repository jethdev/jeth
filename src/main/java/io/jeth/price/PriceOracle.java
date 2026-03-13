/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.price;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.contract.Contract;
import io.jeth.core.EthClient;
import io.jeth.multicall.Multicall3;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * On-chain token price oracle — Chainlink feeds + Uniswap V3 TWAP + spot.
 *
 * <p>Reads prices directly from on-chain contracts. No API keys, no off-chain services.
 *
 * <pre>
 * var oracle = new PriceOracle(client);
 *
 * // Chainlink — most reliable, uses dedicated price feeds
 * BigDecimal ethUsd = oracle.chainlink(PriceOracle.ETH_USD).join();  // e.g. 3412.58
 * BigDecimal btcUsd = oracle.chainlink(PriceOracle.BTC_USD).join();
 *
 * // Batch multiple Chainlink feeds in a single eth_call (via Multicall3)
 * Map&lt;String, BigDecimal&gt; prices = oracle.batchChainlink(List.of(
 *     PriceOracle.ETH_USD, PriceOracle.BTC_USD, PriceOracle.LINK_USD
 * )).join();
 *
 * // Uniswap V3 TWAP — time-weighted, manipulation-resistant
 * BigDecimal twap = oracle.twap(WETH, USDC, 3000, 1800).join(); // 30-min TWAP
 *
 * // Uniswap V3 spot price (instantaneous)
 * BigDecimal spot = oracle.spot(WETH, USDC, 3000).join();
 *
 * // Check feed freshness before trusting a price
 * boolean stale = oracle.isStale(PriceOracle.ETH_USD, 3600).join(); // stale if > 1h
 * </pre>
 */
public class PriceOracle {

    // ─── Chainlink feed addresses (Ethereum Mainnet) ─────────────────────────

    /**
     * ETH/USD Chainlink feed on Ethereum mainnet. 8 decimals — divide answer by 1e8 for USD price.
     * Heartbeat: 1 hour. Deviation threshold: 0.5%.
     */
    public static final String ETH_USD = "0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419";

    /**
     * BTC/USD Chainlink feed on Ethereum mainnet. 8 decimals. Heartbeat: 1 hour. Deviation: 0.5%.
     */
    public static final String BTC_USD = "0xF4030086522a5bEEa4988F8cA5B36dbC97BeE88b";

    /**
     * USDC/USD Chainlink feed. Should stay near $1.00. 8 decimals. Heartbeat: 24 hours (updates
     * only on 0.1% deviation otherwise).
     */
    public static final String USDC_USD = "0x8fFfFfd4AfB6115b954Bd326cbe7B4BA576818f6";

    /** USDT/USD — 8 decimals. Heartbeat: 24h. */
    public static final String USDT_USD = "0x3E7d1eAB13ad0104d2750B8863b489D65364e32D";

    /** DAI/USD — 8 decimals. Heartbeat: 1h. */
    public static final String DAI_USD = "0xAed0c38402a5d19df6E4c03F4E2DceD6e29c1ee9";

    /** LINK/USD — 8 decimals. Heartbeat: 1h. */
    public static final String LINK_USD = "0x2c1d072e956AFFC0D435Cb7AC38EF18d24d9127c";

    /** WBTC/USD — 8 decimals. Heartbeat: 1h. */
    public static final String WBTC_USD = "0xfdFD9C85aD200c506Cf9e21F1FD8dd01932FBB23";

    @SuppressWarnings("unused")
    public static final String SOL_USD = "0x4ffC43a60e009B551865A93d232E33Fce9f01507";

    @SuppressWarnings("unused")
    public static final String MATIC_USD = "0x7bAC85A8a13A4BcD8abb3eB7d6b4d632c895a1c0";

    // ─── Known ERC-20 addresses (Mainnet) ────────────────────────────────────

    public static final String WETH = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
    public static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    public static final String USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    public static final String DAI = "0x6B175474E89094C44Da98b954EedeAC495271d0F";
    public static final String WBTC = "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599";
    public static final String LINK = "0x514910771AF9Ca656af840dff83E8264EcF986CA";

    // ─── Uniswap V3 Factory (same address on mainnet + major L2s) ────────────

    public static final String UNISWAP_V3_FACTORY = "0x1F98431c8aD98523631AE4a59f267346ea31F984";

    // ─── ABI fragments ───────────────────────────────────────────────────────

    // Chainlink AggregatorV3Interface
    // latestRoundData() returns (uint80 roundId, int256 answer, uint256 startedAt, uint256
    // updatedAt, uint80 answeredInRound)
    private static final Function FN_LATEST_ROUND =
            Function.of("latestRoundData")
                    .withReturns(
                            AbiType.UINT256,
                            AbiType.INT256,
                            AbiType.UINT256,
                            AbiType.UINT256,
                            AbiType.UINT256);

    private static final Function FN_DECIMALS = Function.of("decimals").withReturns(AbiType.UINT8);

    // IUniswapV3Factory: getPool(address,address,uint24) → address
    private static final Function FN_GET_POOL =
            Function.of("getPool", AbiType.ADDRESS, AbiType.ADDRESS, AbiType.of("uint24"))
                    .withReturns(AbiType.ADDRESS);

    // IUniswapV3Pool: slot0() → (uint160 sqrtPriceX96, int24 tick, ...)
    private static final Function FN_SLOT0 =
            Function.of("slot0")
                    .withReturns(
                            AbiType.UINT256,
                            AbiType.INT256,
                            AbiType.UINT256,
                            AbiType.UINT256,
                            AbiType.UINT256,
                            AbiType.UINT256,
                            AbiType.BOOL);

    private static final Function FN_TOKEN0 = Function.of("token0").withReturns(AbiType.ADDRESS);

    // observe(uint32[]) → (int56[] tickCumulatives, uint160[] secondsPerLiquidityX128s)
    private static final Function FN_OBSERVE =
            Function.of("observe", AbiType.of("uint32[]"))
                    .withReturns(AbiType.of("int56[]"), AbiType.of("uint160[]"));

    // ─── State ────────────────────────────────────────────────────────────────

    private final EthClient client;

    public PriceOracle(EthClient client) {
        this.client = client;
    }

    // ─── Chainlink ────────────────────────────────────────────────────────────

    /**
     * Read the latest price from a Chainlink aggregator feed.
     *
     * @param feedAddress a Chainlink feed address (use constants like {@link #ETH_USD})
     * @return price as BigDecimal in USD (e.g. 3412.58 for ETH/USD)
     */
    public CompletableFuture<BigDecimal> chainlink(String feedAddress) {
        Contract feed = new Contract(feedAddress, client);
        CompletableFuture<Object[]> roundFuture = feed.fn(FN_LATEST_ROUND).call().raw();
        CompletableFuture<Object[]> decimalsFuture = feed.fn(FN_DECIMALS).call().raw();

        return roundFuture.thenCombine(
                decimalsFuture,
                (round, dec) -> {
                    BigInteger answer = (BigInteger) round[1]; // index 1 = int256 answer
                    int decimals = ((BigInteger) dec[0]).intValue();
                    return new BigDecimal(answer)
                            .divide(BigDecimal.TEN.pow(decimals), decimals, RoundingMode.HALF_UP);
                });
    }

    /**
     * Batch-read multiple Chainlink feeds in a single Multicall3 eth_call. Typically 10–50x faster
     * than individual calls for 3+ feeds.
     *
     * <p>Failed reads (e.g. wrong address) appear as {@code null} in the result map.
     *
     * @param feedAddresses list of Chainlink feed addresses
     * @return map of feedAddress → price (null if the call failed)
     */
    public CompletableFuture<Map<String, BigDecimal>> batchChainlink(List<String> feedAddresses) {
        var mc = new Multicall3(client);
        for (String addr : feedAddresses) {
            mc.addOptional(addr, FN_LATEST_ROUND);
            mc.addOptional(addr, FN_DECIMALS);
        }

        return mc.tryExecute()
                .thenApply(
                        results -> {
                            Map<String, BigDecimal> prices = new LinkedHashMap<>();
                            for (int i = 0; i < feedAddresses.size(); i++) {
                                Multicall3.TryResult roundRes = results.get(i * 2);
                                Multicall3.TryResult decimalsRes = results.get(i * 2 + 1);

                                if (!roundRes.success() || !decimalsRes.success()) {
                                    prices.put(feedAddresses.get(i), null);
                                    continue;
                                }
                                try {
                                    // roundRes has 5 return values → value() is Object[]
                                    BigInteger answer =
                                            (BigInteger) ((Object[]) roundRes.value())[1];
                                    // decimalsRes has 1 return value → value() is unwrapped
                                    // BigInteger
                                    int decimals = ((BigInteger) decimalsRes.value()).intValue();
                                    prices.put(
                                            feedAddresses.get(i),
                                            new BigDecimal(answer)
                                                    .divide(
                                                            BigDecimal.TEN.pow(decimals),
                                                            decimals,
                                                            RoundingMode.HALF_UP));
                                } catch (Exception e) {
                                    prices.put(feedAddresses.get(i), null);
                                }
                            }
                            return prices;
                        });
    }

    /**
     * Check whether a Chainlink feed has not been updated within {@code maxAgeSeconds}. Returns
     * {@code true} if the feed is stale (possibly offline or manipulated).
     *
     * <pre>
     * if (oracle.isStale(PriceOracle.ETH_USD, 3600).join()) {
     *     throw new RuntimeException("ETH price feed stale — aborting");
     * }
     * </pre>
     *
     * @param maxAgeSeconds e.g. 3600 for 1 hour, 86400 for 1 day
     */
    public CompletableFuture<Boolean> isStale(String feedAddress, long maxAgeSeconds) {
        return new Contract(feedAddress, client)
                .fn(FN_LATEST_ROUND)
                .call()
                .raw()
                .thenApply(
                        round -> {
                            BigInteger updatedAt = (BigInteger) round[3]; // index 3
                            long ageSeconds =
                                    (System.currentTimeMillis() / 1000L) - updatedAt.longValue();
                            return ageSeconds > maxAgeSeconds;
                        });
    }

    /**
     * Read the raw round data from a Chainlink feed without converting. Useful for auditing or when
     * you need the round ID / answer in raw form.
     */
    public CompletableFuture<ChainlinkRound> roundData(String feedAddress) {
        Contract feed = new Contract(feedAddress, client);
        CompletableFuture<Object[]> roundFuture = feed.fn(FN_LATEST_ROUND).call().raw();
        CompletableFuture<Object[]> decimalsFuture = feed.fn(FN_DECIMALS).call().raw();

        return roundFuture.thenCombine(
                decimalsFuture,
                (round, dec) -> {
                    BigInteger roundId = (BigInteger) round[0];
                    BigInteger answer = (BigInteger) round[1];
                    BigInteger startedAt = (BigInteger) round[2];
                    BigInteger updatedAt = (BigInteger) round[3];
                    int decimals = ((BigInteger) dec[0]).intValue();
                    BigDecimal price =
                            new BigDecimal(answer)
                                    .divide(
                                            BigDecimal.TEN.pow(decimals),
                                            decimals,
                                            RoundingMode.HALF_UP);
                    return new ChainlinkRound(
                            roundId,
                            price,
                            startedAt.longValue(),
                            updatedAt.longValue(),
                            decimals,
                            feedAddress);
                });
    }

    // ─── Uniswap V3 Spot ─────────────────────────────────────────────────────

    /**
     * Get the current spot price from a Uniswap V3 pool (instantaneous, from slot0).
     *
     * <p>Price is expressed as: how many {@code quoteToken} per 1 {@code baseToken}. Warning: spot
     * prices can be manipulated within a single block. Prefer {@link #twap} for financial
     * decisions.
     *
     * <pre>
     * // How many USDC per 1 WETH in the 0.3% pool?
     * BigDecimal price = oracle.spot(WETH, USDC, 3000).join();  // e.g. 3412.58
     * </pre>
     *
     * @param baseToken the token you're pricing (e.g. {@link #WETH})
     * @param quoteToken the denomination token (e.g. {@link #USDC})
     * @param feeTier pool fee tier in bps×100: 100=0.01%, 500=0.05%, 3000=0.3%, 10000=1%
     */
    public CompletableFuture<BigDecimal> spot(String baseToken, String quoteToken, int feeTier) {
        return getPool(baseToken, quoteToken, feeTier)
                .thenCompose(
                        poolAddress -> {
                            Contract pool = new Contract(poolAddress, client);
                            CompletableFuture<Object[]> slot0 = pool.fn(FN_SLOT0).call().raw();
                            CompletableFuture<Object[]> token0 = pool.fn(FN_TOKEN0).call().raw();

                            return slot0.thenCombine(
                                    token0,
                                    (s0, t0) -> {
                                        BigInteger sqrtPriceX96 = (BigInteger) s0[0];
                                        String token0Addr = (String) t0[0];
                                        return sqrtPriceX96ToPrice(
                                                sqrtPriceX96, baseToken, quoteToken, token0Addr);
                                    });
                        });
    }

    /**
     * Get a Uniswap V3 Time-Weighted Average Price (TWAP).
     *
     * <p>TWAP over {@code periodSeconds} is much harder to manipulate than a spot price. Use at
     * least 1800 seconds (30 minutes) for any security-sensitive use.
     *
     * <pre>
     * BigDecimal price = oracle.twap(WETH, USDC, 3000, 1800).join(); // 30-min TWAP
     * </pre>
     *
     * @param baseToken the token being priced
     * @param quoteToken the denomination token
     * @param feeTier pool fee tier
     * @param periodSeconds length of the TWAP window (e.g. 1800 = 30 minutes)
     */
    public CompletableFuture<BigDecimal> twap(
            String baseToken, String quoteToken, int feeTier, int periodSeconds) {
        return getPool(baseToken, quoteToken, feeTier)
                .thenCompose(
                        poolAddress -> {
                            Contract pool = new Contract(poolAddress, client);

                            // observe([periodSeconds, 0]) → tick cumulatives at (now - period) and
                            // now
                            BigInteger[] secondsAgos = {
                                BigInteger.valueOf(periodSeconds), BigInteger.ZERO
                            };
                            CompletableFuture<Object[]> observeFut =
                                    pool.fn(FN_OBSERVE).call((Object) secondsAgos).raw();
                            CompletableFuture<Object[]> token0Fut = pool.fn(FN_TOKEN0).call().raw();

                            return observeFut.thenCombine(
                                    token0Fut,
                                    (obs, t0) -> {
                                        // obs[0] is Object[] (from ABI decode of int56[])
                                        // containing BigInteger elements
                                        Object[] ticksRaw = (Object[]) obs[0];
                                        // Average tick = (tickCumulative[now] -
                                        // tickCumulative[then]) / period
                                        long tickCumulativeOld =
                                                ((BigInteger) ticksRaw[0]).longValue();
                                        long tickCumulativeNow =
                                                ((BigInteger) ticksRaw[1]).longValue();
                                        double avgTick =
                                                (double) (tickCumulativeNow - tickCumulativeOld)
                                                        / periodSeconds;
                                        String token0Addr = (String) t0[0];
                                        return tickToPrice(
                                                avgTick, baseToken, quoteToken, token0Addr);
                                    });
                        });
    }

    /**
     * Get the Uniswap V3 pool address for a token pair and fee tier. Returns an exceptional future
     * if the pool does not exist.
     */
    public CompletableFuture<String> getPool(String tokenA, String tokenB, int feeTier) {
        Contract factory = new Contract(UNISWAP_V3_FACTORY, client);
        return factory.fn(FN_GET_POOL)
                .call(tokenA, tokenB, BigInteger.valueOf(feeTier))
                .as(String.class)
                .thenApply(
                        addr -> {
                            if (addr == null
                                    || addr.equals("0x0000000000000000000000000000000000000000"))
                                throw new PriceOracleException(
                                        "No Uniswap V3 pool: "
                                                + tokenA
                                                + "/"
                                                + tokenB
                                                + " fee="
                                                + feeTier);
                            return addr;
                        });
    }

    // ─── Math helpers (package-private for testing) ──────────────────────────

    /**
     * @param sqrtPriceX96 the raw sqrt price
     * @param baseToken base token address
     * @param quoteToken quote token address
     * @param token0 token0 address of the pool
     * @return human-readable price
     */
    public static BigDecimal sqrtPriceX96ToPrice(
            BigInteger sqrtPriceX96, String baseToken, String quoteToken, String token0) {
        // price = sqrtPriceX96^2 / 2^192
        BigDecimal sqrt = new BigDecimal(sqrtPriceX96);
        BigDecimal Q192 = new BigDecimal(BigInteger.TWO.pow(192));
        BigDecimal rawPrice = sqrt.pow(2).divide(Q192, 40, RoundingMode.HALF_UP);

        // Invert if base is token1 (pool stores price as token1/token0)
        boolean baseIsToken0 = baseToken.equalsIgnoreCase(token0);
        if (!baseIsToken0) rawPrice = BigDecimal.ONE.divide(rawPrice, 40, RoundingMode.HALF_UP);

        return applyDecimalAdjustment(rawPrice, baseToken, quoteToken);
    }

    /**
     * @param tick the raw tick
     * @param baseToken base token address
     * @param quoteToken quote token address
     * @param token0 token0 address of the pool
     * @return human-readable price
     */
    public static BigDecimal tickToPrice(
            double tick, String baseToken, String quoteToken, String token0) {
        double rawPrice = Math.pow(1.0001, tick);
        boolean baseIsToken0 = baseToken.equalsIgnoreCase(token0);
        if (!baseIsToken0) rawPrice = 1.0 / rawPrice;
        BigDecimal price = BigDecimal.valueOf(rawPrice);
        return applyDecimalAdjustment(price, baseToken, quoteToken);
    }

    /**
     * Adjust raw on-chain price for ERC-20 decimal differences. e.g. WETH=18 decimals, USDC=6
     * decimals → need to multiply by 10^(18-6)
     */
    private static BigDecimal applyDecimalAdjustment(
            BigDecimal rawPrice, String baseToken, String quoteToken) {
        int baseDecimals = knownDecimals(baseToken);
        int quoteDecimals = knownDecimals(quoteToken);
        int adj = baseDecimals - quoteDecimals; // compensate for different precisions
        if (adj > 0) rawPrice = rawPrice.divide(BigDecimal.TEN.pow(adj), 40, RoundingMode.HALF_UP);
        else if (adj < 0) rawPrice = rawPrice.multiply(BigDecimal.TEN.pow(-adj));
        return rawPrice.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * @return decimal places for known tokens (USDC=6, WBTC=8, etc), else 18.
     */
    public static int knownDecimals(String token) {
        String t = token.toLowerCase();
        if (t.equals(USDC.toLowerCase()) || t.equals(USDT.toLowerCase())) return 6;
        if (t.equals(WBTC.toLowerCase())) return 8;
        return 18; // default for WETH, LINK, UNI, etc.
    }

    // ─── Records ─────────────────────────────────────────────────────────────

    /**
     * Full round data from a Chainlink feed.
     *
     * @param roundId the Chainlink round ID
     * @param price the price as a human-readable BigDecimal
     * @param startedAt Unix timestamp when the round started
     * @param updatedAt Unix timestamp of the last update (used for staleness check)
     * @param decimals the feed's decimal places
     * @param feed the feed address
     */
    public record ChainlinkRound(
            BigInteger roundId,
            BigDecimal price,
            long startedAt,
            long updatedAt,
            int decimals,
            String feed) {

        /**
         * @return the raw unadjusted price from the feed
         */
        public BigInteger rawAnswer() {
            return price.multiply(BigDecimal.TEN.pow(decimals)).toBigInteger();
        }

        /** Age of the price data in seconds. */
        public long ageSeconds() {
            return System.currentTimeMillis() / 1000L - updatedAt;
        }

        /** True if the price has not been updated within {@code maxAgeSeconds}. */
        public boolean isStale(long maxAgeSeconds) {
            return ageSeconds() > maxAgeSeconds;
        }

        @Override
        public String toString() {
            return String.format(
                    "ChainlinkRound{price=%s, updatedAt=%d, ageSeconds=%d, feed=%s}",
                    price.toPlainString(), updatedAt, ageSeconds(), feed);
        }
    }

    /** Thrown when a requested pool doesn't exist or a feed address is wrong. */
    public static class PriceOracleException extends RuntimeException {
        public PriceOracleException(String msg) {
            super(msg);
        }
    }
}
