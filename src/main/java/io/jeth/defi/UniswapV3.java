/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.defi;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.contract.Contract;
import io.jeth.contract.ContractFunction;
import io.jeth.core.EthClient;
import io.jeth.core.EthException;
import io.jeth.crypto.Wallet;
import io.jeth.multicall.Multicall3;
import io.jeth.util.Address;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Uniswap V3 integration: quotes, pool state, swaps.
 *
 * <pre>
 * var uni = new UniswapV3(client);
 *
 * // Quote: how much USDC do I get for 1 ETH?
 * BigInteger out = uni.quoteExactInputSingle(WETH, USDC, 3000, Units.toWei("1")).join();
 *
 * // Pool state
 * UniswapV3.PoolState pool = uni.getPoolState(WETH, USDC, 3000).join();
 *
 * // Swap 0.1 ETH → USDC
 * String txHash = uni.swapExactInputSingle(wallet, WETH, USDC, 3000,
 *     Units.toWei("0.1"), BigInteger.ZERO).join();
 * </pre>
 */
public class UniswapV3 {

    public static final String FACTORY_ADDRESS = "0x1F98431c8aD98523631AE4a59f267346ea31F984";
    public static final String QUOTER_V2_ADDRESS = "0x61fFE014bA17989E743c5F6cB21bF9697530B21e";
    public static final String SWAP_ROUTER_02_ADDRESS =
            "0x68b3465833fb72A70ecDF485E0e4C7bD8665Fc45";

    public static final int FEE_LOWEST = 100; // 0.01%
    public static final int FEE_LOW = 500; // 0.05%
    public static final int FEE_MEDIUM = 3000; // 0.30%
    public static final int FEE_HIGH = 10000; // 1.00%

    private final EthClient client;
    private final Contract quoter;
    private final Contract router;
    private final Contract factory;
    private final ContractFunction fnQuoteExactInputSingle;
    private final ContractFunction fnGetPool;

    public UniswapV3(EthClient client) {
        this.client = client;
        this.quoter = new Contract(QUOTER_V2_ADDRESS, client);
        this.router = new Contract(SWAP_ROUTER_02_ADDRESS, client);
        this.factory = new Contract(FACTORY_ADDRESS, client);
        this.fnQuoteExactInputSingle =
                quoter.fn("quoteExactInputSingle((address,address,uint256,uint24,uint160))")
                        .returns("uint256", "uint160", "uint32", "uint256");
        this.fnGetPool = factory.fn("getPool(address,address,uint24)").returns("address");
    }

    /** Quote exact-input swap. Returns expected amountOut. */
    public CompletableFuture<BigInteger> quoteExactInputSingle(
            String tokenIn, String tokenOut, int fee, BigInteger amountIn) {
        Object[] tuple = {tokenIn, tokenOut, amountIn, BigInteger.valueOf(fee), BigInteger.ZERO};
        return fnQuoteExactInputSingle.call(new Object[] {tuple}).as(0, BigInteger.class);
    }

    /** Get pool contract address. Returns zero address if pool doesn't exist. */
    public CompletableFuture<String> getPoolAddress(String tokenA, String tokenB, int fee) {
        return fnGetPool.call(tokenA, tokenB, BigInteger.valueOf(fee)).as(String.class);
    }

    /** Get full pool state (sqrtPriceX96, tick, liquidity). */
    public CompletableFuture<PoolState> getPoolState(String tokenA, String tokenB, int fee) {
        return getPoolAddress(tokenA, tokenB, fee)
                .thenCompose(
                        poolAddr -> {
                            if (poolAddr == null || Address.isZero(poolAddr))
                                return CompletableFuture.failedFuture(
                                        new EthException(
                                                "Pool does not exist for "
                                                        + tokenA
                                                        + "/"
                                                        + tokenB
                                                        + "/"
                                                        + fee));
                            var pool = new Contract(poolAddr, client);
                            var slot0 =
                                    pool.fn("slot0()")
                                            .returns(
                                                    "uint160", "int24", "uint16", "uint16",
                                                    "uint16", "uint8", "bool");
                            var liq = pool.fn("liquidity()").returns("uint128");
                            return slot0.call()
                                    .raw()
                                    .thenCompose(
                                            s0 ->
                                                    liq.call()
                                                            .as(BigInteger.class)
                                                            .thenApply(
                                                                    liquidity ->
                                                                            new PoolState(
                                                                                    poolAddr,
                                                                                    (BigInteger)
                                                                                            s0[0],
                                                                                    ((BigInteger)
                                                                                                    s0[
                                                                                                            1])
                                                                                            .intValue(),
                                                                                    liquidity)));
                        });
    }

    /** Execute exact-input single swap. */
    public CompletableFuture<String> swapExactInputSingle(
            Wallet wallet,
            String tokenIn,
            String tokenOut,
            int fee,
            BigInteger amountIn,
            BigInteger amountOutMinimum) {
        Object[] params = {
            tokenIn,
            tokenOut,
            BigInteger.valueOf(fee),
            wallet.getAddress(),
            amountIn,
            amountOutMinimum,
            BigInteger.ZERO
        };
        return router.fn(
                        "exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))")
                .send(wallet, params);
    }

    /** Batch-quote many pools in one eth_call via Multicall3. */
    public CompletableFuture<List<BigInteger>> batchQuote(List<QuoteParams> quotes) {
        var mc = new Multicall3(client);
        Function qFn =
                Function.of(
                                "quoteExactInputSingle",
                                AbiType.of("(address,address,uint256,uint24,uint160)"))
                        .withReturns(AbiType.UINT256);
        for (QuoteParams q : quotes) {
            Object[] tuple = {
                q.tokenIn(),
                q.tokenOut(),
                q.amountIn(),
                BigInteger.valueOf(q.fee()),
                BigInteger.ZERO
            };
            mc.addAllowFailure(QUOTER_V2_ADDRESS, qFn, new Object[] {tuple});
        }
        return mc.execute()
                .thenApply(
                        results ->
                                results.stream()
                                        .map(r -> r instanceof BigInteger bi ? bi : BigInteger.ZERO)
                                        .toList());
    }

    public record PoolState(
            String address, BigInteger sqrtPriceX96, int tick, BigInteger liquidity) {
        /** Approximate price of token0/token1 (not adjusted for decimals). */
        public double price() {
            double sq = sqrtPriceX96.doubleValue() / Math.pow(2, 96);
            return sq * sq;
        }
    }

    public record QuoteParams(String tokenIn, String tokenOut, int fee, BigInteger amountIn) {}
}
