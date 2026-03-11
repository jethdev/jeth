/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.defi;

import io.jeth.contract.Contract;
import io.jeth.contract.ContractFunction;
import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Aave V3 integration: supply, borrow, repay, withdraw, account data.
 *
 * <pre>
 * var aave = new AaveV3(client);  // Mainnet defaults
 *
 * // Get your health factor and borrowing power
 * AaveV3.AccountData data = aave.getUserAccountData("0xAddress").join();
 * System.out.println("Health factor: " + data.healthFactorEther());
 *
 * // Supply 1000 USDC as collateral
 * aave.supply(wallet, USDC_ADDRESS, Units.parseToken("1000", 6)).join();
 *
 * // Borrow 0.1 ETH at variable rate
 * aave.borrow(wallet, WETH_ADDRESS, Units.toWei("0.1"), 2).join();
 * </pre>
 */
public class AaveV3 {

    /** Aave V3 Pool proxy addresses */
    public static final String POOL_MAINNET = "0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2";

    public static final String POOL_ARBITRUM = "0x794a61358D6845594F94dc1DB02A252b5b4814aD";
    public static final String POOL_OPTIMISM = "0x794a61358D6845594F94dc1DB02A252b5b4814aD";
    public static final String POOL_BASE = "0xA238Dd80C259a72e81d7e4664a9801593F98d1c5";
    public static final String POOL_POLYGON = "0x794a61358D6845594F94dc1DB02A252b5b4814aD";

    public static final int RATE_STABLE = 1;
    public static final int RATE_VARIABLE = 2;

    private final Contract pool;
    private final ContractFunction fnSupply;
    private final ContractFunction fnBorrow;
    private final ContractFunction fnRepay;
    private final ContractFunction fnWithdraw;
    private final ContractFunction fnUserData;
    private final ContractFunction fnGetReserveData;

    public AaveV3(EthClient client) {
        this(client, POOL_MAINNET);
    }

    public AaveV3(EthClient client, String poolAddress) {
        this.pool = new Contract(poolAddress, client);
        this.fnSupply = pool.fn("supply(address,uint256,address,uint16)").build();
        this.fnBorrow = pool.fn("borrow(address,uint256,uint256,uint16,address)").build();
        this.fnRepay = pool.fn("repay(address,uint256,uint256,address)").returns("uint256");
        this.fnWithdraw = pool.fn("withdraw(address,uint256,address)").returns("uint256");
        this.fnUserData =
                pool.fn("getUserAccountData(address)")
                        .returns("uint256", "uint256", "uint256", "uint256", "uint256", "uint256");
        this.fnGetReserveData =
                pool.fn("getReserveData(address)")
                        .returns(
                                "uint256", "uint128", "uint128", "uint128", "uint128", "uint128",
                                "uint40", "uint16", "address", "address", "address", "address",
                                "uint128", "uint128", "uint128");
    }

    /** Supply (deposit) an asset as collateral. */
    public CompletableFuture<String> supply(Wallet wallet, String asset, BigInteger amount) {
        return fnSupply.send(wallet, asset, amount, wallet.getAddress(), BigInteger.ZERO);
    }

    /** Borrow an asset. interestRateMode: 1=stable, 2=variable. */
    public CompletableFuture<String> borrow(
            Wallet wallet, String asset, BigInteger amount, int interestRateMode) {
        return fnBorrow.send(
                wallet,
                asset,
                amount,
                BigInteger.valueOf(interestRateMode),
                BigInteger.ZERO,
                wallet.getAddress());
    }

    /** Repay a borrowed asset. Pass type(uint256).max to repay all. */
    public CompletableFuture<String> repay(
            Wallet wallet, String asset, BigInteger amount, int interestRateMode) {
        return fnRepay.send(
                wallet, asset, amount, BigInteger.valueOf(interestRateMode), wallet.getAddress());
    }

    /** Withdraw collateral. Pass type(uint256).max to withdraw all. */
    public CompletableFuture<String> withdraw(Wallet wallet, String asset, BigInteger amount) {
        return fnWithdraw.send(wallet, asset, amount, wallet.getAddress());
    }

    /** Get account health factor, collateral, debt. */
    public CompletableFuture<AccountData> getUserAccountData(String user) {
        return fnUserData
                .call(user)
                .raw()
                .thenApply(
                        arr ->
                                new AccountData(
                                        (BigInteger) arr[0], // totalCollateralBase
                                        (BigInteger) arr[1], // totalDebtBase
                                        (BigInteger) arr[2], // availableBorrowsBase
                                        (BigInteger) arr[3], // currentLiquidationThreshold
                                        (BigInteger) arr[4], // ltv
                                        (BigInteger) arr[5] // healthFactor
                                        ));
    }

    public record AccountData(
            BigInteger totalCollateralBase,
            BigInteger totalDebtBase,
            BigInteger availableBorrowsBase,
            BigInteger currentLiquidationThreshold,
            BigInteger ltv,
            BigInteger healthFactor) {
        /** Health factor as human-readable decimal (< 1.0 = liquidatable). */
        public double healthFactorEther() {
            return healthFactor.doubleValue() / 1e18;
        }

        public boolean isLiquidatable() {
            return healthFactorEther() < 1.0;
        }
    }

    public String getPoolAddress() {
        return pool.getAddress();
    }
}
