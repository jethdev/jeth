/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import io.jeth.core.EthException;
import io.jeth.crypto.Wallet;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Untyped dynamic contract — no interface needed at all. Use when you just want to fire calls
 * without defining an interface.
 *
 * <pre>
 * var contract = ContractProxy.loadDynamic("0xAddress", abiJson, client);
 *
 * // Read
 * String greeting = (String) contract.call("getGreeting").join();
 *
 * // Write
 * String txHash = contract.send("setGreeting", wallet, "hiii").join();
 * </pre>
 */
public class DynamicContract {

    private final String address;
    private final Map<String, ContractFunction> fnMap;

    DynamicContract(String address, Map<String, ContractFunction> fnMap) {
        this.address = address;
        this.fnMap = fnMap;
    }

    /**
     * Call a view/pure function by name.
     *
     * @return the first decoded return value (cast it yourself)
     */
    public CompletableFuture<Object> call(String functionName, Object... args) {
        return get(functionName).call(args).raw().thenApply(arr -> arr.length == 1 ? arr[0] : arr);
    }

    /** Call and get all return values as Object[]. */
    public CompletableFuture<Object[]> callMulti(String functionName, Object... args) {
        return get(functionName).call(args).raw();
    }

    /**
     * Send a state-changing transaction.
     *
     * @return transaction hash
     */
    public CompletableFuture<String> send(String functionName, Wallet wallet, Object... args) {
        return get(functionName).send(wallet, args);
    }

    /** Send a payable transaction with ETH value. */
    public CompletableFuture<String> sendPayable(
            String functionName, Wallet wallet, BigInteger ethValue, Object... args) {
        return get(functionName).send(wallet, ethValue, args);
    }

    public Set<String> functions() {
        return fnMap.keySet();
    }

    public String getAddress() {
        return address;
    }

    private ContractFunction get(String name) {
        ContractFunction cf = fnMap.get(name);
        if (cf == null)
            throw new EthException(
                    "Unknown function: '" + name + "'. Available: " + fnMap.keySet());
        return cf;
    }
}
