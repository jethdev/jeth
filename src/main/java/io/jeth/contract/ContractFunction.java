/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.core.EthClient;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.model.EthModels;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * A bound, ready-to-call contract function.
 *
 * <pre>
 * var balanceOf = contract.fn("balanceOf(address)").returns("uint256");
 * BigInteger bal = balanceOf.call("0xAddress").as(BigInteger.class).join();
 *
 * var transfer = contract.fn("transfer(address,uint256)");
 * String txHash = transfer.send(wallet, "0xTo", amount).join();
 * </pre>
 */
public class ContractFunction {

    private final String contractAddress;
    private final EthClient client;
    private final Function function;

    public ContractFunction(String contractAddress, EthClient client, Function function) {
        this.contractAddress = contractAddress;
        this.client = client;
        this.function = function;
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    public CallResult call(Object... args) {
        return callAt("latest", args);
    }

    public CallResult callAt(String blockTag, Object... args) {
        String calldata = function.encode(args);
        var req = EthModels.CallRequest.builder().to(contractAddress).data(calldata).build();
        CompletableFuture<Object[]> future =
                client.call(req, blockTag).thenApply(function::decodeReturn);
        return new CallResult(future);
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    /** Send a state-changing transaction. Auto-handles nonce, EIP-1559 fees, gas. */
    public CompletableFuture<String> send(Wallet wallet, Object... args) {
        return send(wallet, BigInteger.ZERO, args);
    }

    public CompletableFuture<String> send(Wallet wallet, BigInteger ethValue, Object... args) {
        String calldata = function.encode(args);
        return client.getChainId()
                .thenCompose(
                        chainId ->
                                client.getTransactionCount(wallet.getAddress())
                                        .thenCompose(
                                                nonce ->
                                                        client.getBlock("latest")
                                                                .thenCompose(
                                                                        block ->
                                                                                client.getMaxPriorityFeePerGas()
                                                                                        .thenCompose(
                                                                                                tip -> {
                                                                                                    BigInteger
                                                                                                            base =
                                                                                                                    block.baseFeePerGas
                                                                                                                                    != null
                                                                                                                            ? block.baseFeePerGas
                                                                                                                            : BigInteger
                                                                                                                                    .ZERO;
                                                                                                    BigInteger
                                                                                                            maxFee =
                                                                                                                    base.multiply(
                                                                                                                                    BigInteger
                                                                                                                                            .TWO)
                                                                                                                            .add(
                                                                                                                                    tip);

                                                                                                    var
                                                                                                            estimateReq =
                                                                                                                    EthModels
                                                                                                                            .CallRequest
                                                                                                                            .builder()
                                                                                                                            .from(
                                                                                                                                    wallet
                                                                                                                                            .getAddress())
                                                                                                                            .to(
                                                                                                                                    contractAddress)
                                                                                                                            .data(
                                                                                                                                    calldata)
                                                                                                                            .value(
                                                                                                                                    ethValue)
                                                                                                                            .build();

                                                                                                    return client.estimateGas(
                                                                                                                    estimateReq)
                                                                                                            .thenCompose(
                                                                                                                    gasEst -> {
                                                                                                                        BigInteger
                                                                                                                                gas =
                                                                                                                                        gasEst.multiply(
                                                                                                                                                        BigInteger
                                                                                                                                                                .valueOf(
                                                                                                                                                                        120))
                                                                                                                                                .divide(
                                                                                                                                                        BigInteger
                                                                                                                                                                .valueOf(
                                                                                                                                                                        100));
                                                                                                                        var
                                                                                                                                tx =
                                                                                                                                        EthModels
                                                                                                                                                .TransactionRequest
                                                                                                                                                .builder()
                                                                                                                                                .from(
                                                                                                                                                        wallet
                                                                                                                                                                .getAddress())
                                                                                                                                                .to(
                                                                                                                                                        contractAddress)
                                                                                                                                                .value(
                                                                                                                                                        ethValue)
                                                                                                                                                .gas(
                                                                                                                                                        gas)
                                                                                                                                                .maxFeePerGas(
                                                                                                                                                        maxFee)
                                                                                                                                                .maxPriorityFeePerGas(
                                                                                                                                                        tip)
                                                                                                                                                .nonce(
                                                                                                                                                        nonce)
                                                                                                                                                .chainId(
                                                                                                                                                        chainId)
                                                                                                                                                .data(
                                                                                                                                                        calldata)
                                                                                                                                                .build();
                                                                                                                        return client
                                                                                                                                .sendRawTransaction(
                                                                                                                                        TransactionSigner
                                                                                                                                                .signEip1559(
                                                                                                                                                        tx,
                                                                                                                                                        wallet));
                                                                                                                    });
                                                                                                }))));
    }

    /**
     * Simulate a transaction with eth_call to check for reverts before sending. Returns true if it
     * would succeed, throws with revert reason if not.
     */
    public CompletableFuture<Boolean> simulate(Wallet wallet, Object... args) {
        return simulate(wallet, BigInteger.ZERO, args);
    }

    public CompletableFuture<Boolean> simulate(Wallet wallet, BigInteger ethValue, Object... args) {
        String calldata = function.encode(args);
        var req =
                EthModels.CallRequest.builder()
                        .from(wallet.getAddress())
                        .to(contractAddress)
                        .data(calldata)
                        .value(ethValue)
                        .build();
        return client.call(req).thenApply(__ -> true);
    }

    // ─── Encode only ──────────────────────────────────────────────────────────

    /** Encode arguments to calldata hex without sending. Useful for Multicall3, Safe, etc. */
    public String encode(Object... args) {
        return function.encode(args);
    }

    public Function getFunction() {
        return function;
    }

    public String getSelector() {
        return function.getSelectorHex();
    }

    public String getSignature() {
        return function.getSignature();
    }

    public AbiType[] getInputTypes() {
        return function.getInputTypes();
    }

    // ─── CallResult ───────────────────────────────────────────────────────────

    public static class CallResult {
        private final CompletableFuture<Object[]> future;

        CallResult(CompletableFuture<Object[]> future) {
            this.future = future;
        }

        /** Cast first return value to type. */
        public <T> CompletableFuture<T> as(Class<T> type) {
            return future.thenApply(arr -> type.cast(arr[0]));
        }

        /** Cast return value at index. */
        public <T> CompletableFuture<T> as(int index, Class<T> type) {
            return future.thenApply(arr -> type.cast(arr[index]));
        }

        /** All decoded return values. */
        public CompletableFuture<Object[]> raw() {
            return future;
        }

        /** Block and return first value. Avoid in async code. */
        public Object join() {
            return future.join()[0];
        }
    }
}
