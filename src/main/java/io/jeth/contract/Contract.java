/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.abi.HumanAbi;
import io.jeth.core.EthClient;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.event.EventDef;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Units;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Low-level contract wrapper: define functions from human-readable Solidity signatures, then call
 * or send them without needing an ABI JSON file.
 *
 * <p>For a fully typed experience with auto-mapped return types, use {@link ContractProxy} instead.
 * {@code Contract} is useful when you only need one or two functions and don't want to define a
 * Java interface.
 *
 * <pre>
 * var c = new Contract("0xUSDC", client);
 *
 * // Read — call() decodes return values automatically
 * BigInteger bal = c.fn("balanceOf(address)").returns("uint256")
 *                   .call("0xOwner").as(BigInteger.class).join();
 *
 * // Write — send() builds, signs, and broadcasts the transaction
 * String txHash = c.fn("transfer(address,uint256)")
 *                  .send(wallet, "0xTo", amount).join();
 *
 * // Payable — extra BigInteger after wallet = ETH value in wei
 * String txHash = c.fn("deposit()").send(wallet, Units.toWei("0.1")).join();
 *
 * // Simulate — dry-run without spending gas
 * boolean ok = c.fn("transfer(address,uint256)").simulate(wallet, "0xTo", amount).join();
 * </pre>
 *
 * @see ContractProxy
 * @see io.jeth.abi.HumanAbi
 */
public class Contract {

    private final String address;
    private final EthClient client;

    public Contract(String address, EthClient client) {
        this.address = address;
        this.client = client;
    }

    /**
     * Defines a contract function from its Solidity signature.
     *
     * <p>Both shorthand and full forms are accepted:
     *
     * <pre>
     * c.fn("transfer(address,uint256)")                         // shorthand
     * c.fn("function transfer(address to, uint256 amount)")     // full (param names ignored)
     * </pre>
     *
     * <p>The returned {@link FunctionBuilder} lets you specify return types before calling or
     * sending.
     *
     * @param signature Solidity function signature
     */
    public FunctionBuilder fn(String signature) {
        return new FunctionBuilder(signature);
    }

    /**
     * Bind a pre-built {@link Function} to this contract. Avoids re-parsing the signature each time
     * — useful for frequently-called ABIs stored as static constants (e.g. in {@link
     * io.jeth.price.PriceOracle}).
     */
    public ContractFunction fn(Function function) {
        return new ContractFunction(address, client, function);
    }

    /**
     * Send ETH.
     *
     * @param client the client
     * @param wallet the wallet
     * @param to recipient
     * @param value amount in ETH
     * @return transaction hash
     */
    public static CompletableFuture<String> sendEth(
            EthClient client, Wallet wallet, String to, java.math.BigDecimal value) {
        return sendEth(client, wallet, to, Units.toWei(value.toPlainString()));
    }

    /** Send raw ETH — no contract call. */
    public static CompletableFuture<String> sendEth(
            EthClient client, Wallet wallet, String to, BigInteger valueWei) {
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
                                                                                                            tx =
                                                                                                                    EthModels
                                                                                                                            .TransactionRequest
                                                                                                                            .builder()
                                                                                                                            .from(
                                                                                                                                    wallet
                                                                                                                                            .getAddress())
                                                                                                                            .to(
                                                                                                                                    to)
                                                                                                                            .value(
                                                                                                                                    valueWei)
                                                                                                                            .gas(
                                                                                                                                    BigInteger
                                                                                                                                            .valueOf(
                                                                                                                                                    21_000))
                                                                                                                            .maxFeePerGas(
                                                                                                                                    maxFee)
                                                                                                                            .maxPriorityFeePerGas(
                                                                                                                                    tip)
                                                                                                                            .nonce(
                                                                                                                                    nonce)
                                                                                                                            .chainId(
                                                                                                                                    chainId)
                                                                                                                            .build();
                                                                                                    return client
                                                                                                            .sendRawTransaction(
                                                                                                                    TransactionSigner
                                                                                                                            .signEip1559(
                                                                                                                                    tx,
                                                                                                                                    wallet));
                                                                                                }))));
    }

    /** Deploy a contract: bytecode + constructor args. Returns deployed address. */
    public static CompletableFuture<String> deploy(
            EthClient client,
            Wallet wallet,
            String bytecodeHex,
            AbiType[] ctorTypes,
            Object[] ctorArgs) {
        byte[] initcode;
        if (ctorTypes != null && ctorTypes.length > 0) {
            byte[] params = AbiCodec.encode(ctorTypes, ctorArgs);
            byte[] bc = Hex.decode(bytecodeHex);
            initcode = new byte[bc.length + params.length];
            System.arraycopy(bc, 0, initcode, 0, bc.length);
            System.arraycopy(params, 0, initcode, bc.length, params.length);
        } else {
            initcode = Hex.decode(bytecodeHex);
        }
        final String dataHex = Hex.encode(initcode);

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
                                                                                                                            .data(
                                                                                                                                    dataHex)
                                                                                                                            .build();
                                                                                                    return client.estimateGas(
                                                                                                                    estimateReq)
                                                                                                            .thenCompose(
                                                                                                                    gas -> {
                                                                                                                        BigInteger
                                                                                                                                gasWithBuffer =
                                                                                                                                        gas.multiply(
                                                                                                                                                        BigInteger
                                                                                                                                                                .valueOf(
                                                                                                                                                                        130))
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
                                                                                                                                                .value(
                                                                                                                                                        BigInteger
                                                                                                                                                                .ZERO)
                                                                                                                                                .gas(
                                                                                                                                                        gasWithBuffer)
                                                                                                                                                .maxFeePerGas(
                                                                                                                                                        maxFee)
                                                                                                                                                .maxPriorityFeePerGas(
                                                                                                                                                        tip)
                                                                                                                                                .nonce(
                                                                                                                                                        nonce)
                                                                                                                                                .chainId(
                                                                                                                                                        chainId)
                                                                                                                                                .data(
                                                                                                                                                        dataHex)
                                                                                                                                                .build();
                                                                                                                        String
                                                                                                                                signed =
                                                                                                                                        TransactionSigner
                                                                                                                                                .signEip1559(
                                                                                                                                                        tx,
                                                                                                                                                        wallet);
                                                                                                                        return client.sendRawTransaction(
                                                                                                                                        signed)
                                                                                                                                .thenCompose(
                                                                                                                                        client
                                                                                                                                                ::waitForTransaction)
                                                                                                                                .thenApply(
                                                                                                                                        receipt ->
                                                                                                                                                receipt.contractAddress);
                                                                                                                    });
                                                                                                }))));
    }

    /** Deploy without constructor args. */
    @SuppressWarnings("unused")
    public static CompletableFuture<String> deploy(
            EthClient client, Wallet wallet, String bytecodeHex) {
        return deploy(client, wallet, bytecodeHex, null, null);
    }

    public String getAddress() {
        return address;
    }

    public EthClient getClient() {
        return client;
    }

    // ─── Event queries ────────────────────────────────────────────────────────

    /**
     * Query historical events from this contract — like ethers.js {@code contract.queryFilter()}.
     *
     * <p>Auto-chunks large block ranges to avoid RPC "response size exceeded" errors.
     *
     * <pre>
     * var Transfer = EventDef.of("Transfer",
     *     EventDef.indexed("from", "address"),
     *     EventDef.indexed("to",   "address"),
     *     EventDef.data("value",   "uint256"));
     *
     * long tip  = client.getBlockNumber().join();
     * long from = tip - 1000;
     * List&lt;EventDef.DecodedEvent&gt; events = token.queryFilter(Transfer, from, tip).join();
     * events.forEach(e -> System.out.println(e.address("from") + " sent " + e.uint("value")));
     * </pre>
     */
    public CompletableFuture<List<EventDef.DecodedEvent>> queryFilter(
            EventDef event, long fromBlock, long toBlock) {
        return client.getLogs(
                        Hex.fromLong(fromBlock),
                        Hex.fromLong(toBlock),
                        address,
                        List.of(event.topic0Hex()))
                .thenApply(event::decodeAll);
    }

    /** Query events from {@code fromBlock} to "latest". */
    public CompletableFuture<List<EventDef.DecodedEvent>> queryFilter(
            EventDef event, long fromBlock) {
        return client.getLogs(
                        Hex.fromLong(fromBlock), "latest", address, List.of(event.topic0Hex()))
                .thenApply(event::decodeAll);
    }

    // ─── FunctionBuilder ─────────────────────────────────────────────────────

    public class FunctionBuilder {

        private final String name;
        private final AbiType[] inputTypes;

        FunctionBuilder(String signature) {
            int paren = signature.indexOf('(');
            if (paren < 0) throw new IllegalArgumentException("Missing parentheses: " + signature);
            this.name = signature.substring(0, paren).trim();
            String params = signature.substring(paren + 1, signature.lastIndexOf(')')).trim();
            this.inputTypes = params.isEmpty() ? new AbiType[0] : parseParams(params);
        }

        private AbiType[] parseParams(String params) {
            // Respect nested parens when splitting by comma
            List<String> parts = new ArrayList<>();
            int depth = 0, start = 0;
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(params.substring(start, i).trim());
                    start = i + 1;
                }
            }
            parts.add(params.substring(start).trim());
            // Strip parameter names and modifiers (e.g. "address to" → "address")
            return parts.stream()
                    .map(HumanAbi::extractType)
                    .map(AbiType::of)
                    .toArray(AbiType[]::new);
        }

        public ContractFunction returns(String... types) {
            AbiType[] out = Arrays.stream(types).map(AbiType::of).toArray(AbiType[]::new);
            return new ContractFunction(
                    address, client, Function.of(name, inputTypes).withReturns(out));
        }

        public ContractFunction returns(AbiType... types) {
            return new ContractFunction(
                    address, client, Function.of(name, inputTypes).withReturns(types));
        }

        public ContractFunction build() {
            return new ContractFunction(address, client, Function.of(name, inputTypes));
        }

        public CompletableFuture<String> send(Wallet wallet, Object... args) {
            return build().send(wallet, args);
        }

        public CompletableFuture<String> send(Wallet wallet, BigInteger ethValue, Object... args) {
            return build().send(wallet, ethValue, args);
        }
    }
}
