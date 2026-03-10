/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.contract.Contract;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;

class ContractTest {

    static final String ADDR    = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    static final Wallet WALLET  = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    static final String USER    = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    static String encodeUint256(BigInteger v) {
        return Hex.encode(AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{v}));
    }

    // ─── getAddress / getClient ───────────────────────────────────────────────

    @Test @DisplayName("getAddress returns correct address")
    void get_address() throws Exception {
        try (var rpc = new RpcMock()) {
            assertEquals(ADDR, new Contract(ADDR, rpc.client()).getAddress());
        }
    }

    // ─── fn().call() ─────────────────────────────────────────────────────────

    @Test @DisplayName("fn().returns().call().as(BigInteger) decodes uint256")
    void call_uint256() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"" + encodeUint256(BigInteger.valueOf(1_000_000)) + "\"");
            var contract = new Contract(ADDR, rpc.client());
            BigInteger bal = contract.fn("balanceOf(address)").returns("uint256")
                .call(USER).as(BigInteger.class).join();
            assertEquals(BigInteger.valueOf(1_000_000), bal);
        }
    }

    @Test @DisplayName("fn().call() encodes correct function selector in request")
    void call_selector_in_request() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"" + encodeUint256(BigInteger.ZERO) + "\"");
            var contract = new Contract(ADDR, rpc.client());
            contract.fn("balanceOf(address)").returns("uint256").call(USER).join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("70a08231"), "balanceOf selector must be in calldata");
        }
    }

    @Test @DisplayName("fn().call() targeting correct contract address")
    void call_target_address() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"" + encodeUint256(BigInteger.ZERO) + "\"");
            new Contract(ADDR, rpc.client()).fn("totalSupply()").returns("uint256").call().join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.toLowerCase().contains(ADDR.toLowerCase().substring(2)),
                "Contract address must appear in eth_call params");
        }
    }

    @Test @DisplayName("fn().returns(string).call() decodes string")
    void call_string() throws Exception {
        // Encode "USD Coin" as ABI string
        byte[] enc = AbiCodec.encode(new AbiType[]{AbiType.STRING}, new Object[]{"USD Coin"});
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"" + Hex.encode(enc) + "\"");
            String name = new Contract(ADDR, rpc.client()).fn("name()").returns("string")
                .call().as(String.class).join();
            assertEquals("USD Coin", name);
        }
    }

    @Test @DisplayName("fn().returns(bool).call() decodes boolean true")
    void call_bool() throws Exception {
        byte[] enc = AbiCodec.encode(new AbiType[]{AbiType.BOOL}, new Object[]{true});
        try (var rpc = new RpcMock()) {
            rpc.enqueue("\"" + Hex.encode(enc) + "\"");
            Boolean result = new Contract(ADDR, rpc.client()).fn("paused()").returns("bool")
                .call().as(Boolean.class).join();
            assertTrue(result);
        }
    }

    // ─── fn().send() ──────────────────────────────────────────────────────────

    @Test @DisplayName("fn().send() enqueues nonce + gasPrice + sendRawTransaction")
    void send_fires_rpc() throws Exception {
        try (var rpc = new RpcMock()) {
            // nonce, gasPrice, estimateGas, sendRawTransaction
            rpc.enqueueHex(0L);                    // getTransactionCount
            rpc.enqueueHex(BigInteger.valueOf(30_000_000_000L)); // getGasPrice fallback
            rpc.enqueueHex(65000L);                // estimateGas
            rpc.enqueueStr("0xsentTxHash");        // sendRawTransaction
            var contract = new Contract(ADDR, rpc.client());
            String txHash = contract.fn("transfer(address,uint256)")
                .send(WALLET, USER, BigInteger.valueOf(1_000_000L)).join();
            assertNotNull(txHash);
        }
    }

    @Test @DisplayName("sendEth() sends ETH to address")
    void send_eth() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(0L);
            rpc.enqueueHex(BigInteger.valueOf(30_000_000_000L));
            rpc.enqueueHex(21000L);
            rpc.enqueueStr("0xtxhash");
            String txHash = Contract.sendEth(rpc.client(), WALLET, USER, BigDecimal.valueOf(0.1)).join();
            assertNotNull(txHash);
        }
    }
}
