/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import io.jeth.core.EthClient;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.model.EthModels;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helpers for integration tests — deploy contracts, send transactions, etc.
 */
class IntegrationTestBase {

    /**
     * Deploy a contract from 0x-prefixed bytecode using the dev wallet.
     * Returns the deployed contract address.
     */
    static String deploy(EthClient client, Wallet deployer, String bytecode) {
        long       nonce   = client.getTransactionCount(deployer.getAddress()).join();
        long       chainId = client.getChainId().join();
        BigInteger tip     = client.getMaxPriorityFeePerGas().join();
        var blk = client.getBlock("latest").join();
        BigInteger base    = blk.baseFeePerGas != null ? blk.baseFeePerGas : BigInteger.ZERO;
        BigInteger maxFee  = base.multiply(BigInteger.TWO).add(tip).add(BigInteger.ONE);
        BigInteger gas     = client.estimateGas(EthModels.CallRequest.builder()
                .from(deployer.getAddress()).to(null).value(BigInteger.ZERO).data(bytecode).build())
                .join().multiply(BigInteger.valueOf(130)).divide(BigInteger.valueOf(100));
        var tx = EthModels.TransactionRequest.builder()
                .from(deployer.getAddress()).to(null).value(BigInteger.ZERO).gas(gas)
                .maxFeePerGas(maxFee).maxPriorityFeePerGas(tip)
                .nonce(nonce).chainId(chainId).data(bytecode).build();
        String txHash  = client.sendRawTransaction(TransactionSigner.signEip1559(tx, deployer)).join();
        var    receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        assertTrue(receipt.isSuccess(), "Deploy transaction must succeed");
        assertNotNull(receipt.contractAddress, "Deployed contract address must not be null");
        return receipt.contractAddress;
    }

    /**
     * Send an ETH transfer and return the tx hash (does NOT wait for receipt).
     */
    static String sendEth(EthClient client, Wallet from, String to, BigInteger value) {
        long       nonce   = client.getTransactionCount(from.getAddress()).join();
        long       chainId = client.getChainId().join();
        BigInteger tip     = client.getMaxPriorityFeePerGas().join();
        var blk = client.getBlock("latest").join();
        BigInteger base    = blk.baseFeePerGas != null ? blk.baseFeePerGas : BigInteger.ZERO;
        BigInteger maxFee  = base.multiply(BigInteger.TWO).add(tip).add(BigInteger.ONE);
        var tx = EthModels.TransactionRequest.builder()
                .from(from.getAddress()).to(to).value(value)
                .gas(BigInteger.valueOf(21_000))
                .maxFeePerGas(maxFee).maxPriorityFeePerGas(tip)
                .nonce(nonce).chainId(chainId).build();
        return client.sendRawTransaction(TransactionSigner.signEip1559(tx, from)).join();
    }

    private static void assertNotNull(Object v, String msg) {
        assertTrue(v != null, msg);
    }
}
