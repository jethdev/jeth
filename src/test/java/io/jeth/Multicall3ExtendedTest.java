/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.abi.HumanAbi;
import io.jeth.multicall.Multicall3;
import io.jeth.util.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended Multicall3 tests covering executeWithResults, tryExecute,
 * getEthBalances, getTokenBalances, and FluentBuilder.executeAs.
 */
class Multicall3ExtendedTest {

    static final String ADDR_A = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String ADDR_B = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    static final Function BALANCE_OF = HumanAbi.parseFunction("balanceOf(address) returns (uint256)");

    static String encodeUint(BigInteger v) {
        return Hex.encodeNoPrefx(AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{v}));
    }

    // ─── executeWithResults ────────────────────────────────────────────────────

    @Test @DisplayName("executeWithResults() returns empty list when no calls added")
    void execute_with_results_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            Multicall3 mc = new Multicall3(rpc.client());
            List<Multicall3.Result> results = mc.executeWithResults().join();
            assertTrue(results.isEmpty());
            assertEquals(0, rpc.requestCount(), "Empty multicall must make 0 RPC calls");
        }
    }

    @Test @DisplayName("executeWithResults() returns Result with success=true and decoded value")
    void execute_with_results_success() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger bal = new BigInteger("1000000000000000000");
            // Multicall3 returns (bool success, bytes returnData)[] encoded
            // For simplicity: encode a response that decodes to one successful call
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{bal}))
            )));

            Multicall3 mc = new Multicall3(rpc.client());
            mc.add("0xToken", BALANCE_OF, ADDR_A);

            List<Multicall3.Result> results = mc.executeWithResults().join();
            assertEquals(1, results.size());
            Multicall3.Result r = results.get(0);
            assertTrue(r.success(), "Successful call must have success=true");
            assertEquals(bal, r.value(), "Result value must equal returned balance");
        }
    }

    @Test @DisplayName("executeWithResults() Result: index matches insertion order")
    void execute_with_results_index() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger v1 = BigInteger.valueOf(100);
            BigInteger v2 = BigInteger.valueOf(200);
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{v1})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{v2}))
            )));

            Multicall3 mc = new Multicall3(rpc.client());
            mc.add("0xToken1", BALANCE_OF, ADDR_A);
            mc.add("0xToken2", BALANCE_OF, ADDR_B);

            List<Multicall3.Result> results = mc.executeWithResults().join();
            assertEquals(2, results.size());
            assertEquals(0, results.get(0).index());
            assertEquals(1, results.get(1).index());
        }
    }

    @Test @DisplayName("executeWithResults() failed optional call: success=false, value=null")
    void execute_with_results_optional_failure() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(false, new byte[0])   // failed call
            )));

            Multicall3 mc = new Multicall3(rpc.client());
            mc.addOptional("0xBadContract", BALANCE_OF, ADDR_A);

            List<Multicall3.Result> results = mc.executeWithResults().join();
            assertEquals(1, results.size());
            assertFalse(results.get(0).success(), "Failed optional call must have success=false");
            assertNull(results.get(0).value(), "Failed call must have null value");
        }
    }

    @Test @DisplayName("executeWithResults(blockTag) forwards block parameter")
    void execute_with_results_block_tag() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger bal = BigInteger.valueOf(999);
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{bal}))
            )));

            Multicall3 mc = new Multicall3(rpc.client());
            mc.add("0xToken", BALANCE_OF, ADDR_A);

            List<Multicall3.Result> results = mc.executeWithResults("0x1000").join();
            assertEquals(1, results.size());
            assertEquals(bal, results.get(0).value());
        }
    }

    // ─── getEthBalances ────────────────────────────────────────────────────────

    @Test @DisplayName("getEthBalances() returns empty list for empty input")
    void get_eth_balances_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            List<BigInteger> bals = Multicall3.getEthBalances(rpc.client(), List.of()).join();
            assertTrue(bals.isEmpty());
            assertEquals(0, rpc.requestCount());
        }
    }

    @Test @DisplayName("getEthBalances() returns BigInteger balances in input order")
    void get_eth_balances_two_addresses() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger b1 = new BigInteger("1000000000000000000");
            BigInteger b2 = new BigInteger("500000000000000000");
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{b1})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{b2}))
            )));

            List<BigInteger> bals = Multicall3.getEthBalances(
                    rpc.client(), List.of(ADDR_A, ADDR_B)).join();

            assertEquals(2, bals.size());
            assertEquals(b1, bals.get(0));
            assertEquals(b2, bals.get(1));
        }
    }

    @Test @DisplayName("getEthBalances() makes exactly one RPC call for N addresses")
    void get_eth_balances_single_rpc() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.TEN})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.ONE})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.ZERO}))
            )));

            Multicall3.getEthBalances(rpc.client(), List.of(ADDR_A, ADDR_B, Wallet.create().getAddress())).join();
            assertEquals(1, rpc.requestCount(), "getEthBalances must use exactly 1 RPC call");
        }
    }

    // ─── getTokenBalances ──────────────────────────────────────────────────────

    @Test @DisplayName("getTokenBalances() returns empty list for empty token list")
    void get_token_balances_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            List<BigInteger> bals = Multicall3.getTokenBalances(rpc.client(), ADDR_A, List.of()).join();
            assertTrue(bals.isEmpty());
            assertEquals(0, rpc.requestCount());
        }
    }

    @Test @DisplayName("getTokenBalances() returns balances for multiple tokens in order")
    void get_token_balances_multiple() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger usdc = new BigInteger("5000000");   // 5 USDC (6 decimals)
            BigInteger weth = new BigInteger("1000000000000000000"); // 1 WETH
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{usdc})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{weth}))
            )));

            List<String> tokens = List.of(
                    "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",  // USDC
                    "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"   // WETH
            );
            List<BigInteger> bals = Multicall3.getTokenBalances(rpc.client(), ADDR_A, tokens).join();

            assertEquals(2, bals.size());
            assertEquals(usdc, bals.get(0));
            assertEquals(weth, bals.get(1));
        }
    }

    @Test @DisplayName("getTokenBalances() uses 1 RPC call for N tokens")
    void get_token_balances_single_rpc() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.valueOf(100)})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.valueOf(200)}))
            )));

            Multicall3.getTokenBalances(rpc.client(), ADDR_A,
                    List.of("0xToken1", "0xToken2")).join();
            assertEquals(1, rpc.requestCount());
        }
    }

    // ─── Fluent builder ───────────────────────────────────────────────────────

    @Test @DisplayName("FluentBuilder.executeAs(BigInteger.class) returns typed list")
    void fluent_execute_as() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger b1 = BigInteger.valueOf(100);
            BigInteger b2 = BigInteger.valueOf(200);
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{b1})),
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{b2}))
            )));

            List<BigInteger> results = Multicall3.builder(rpc.client())
                    .call("0xToken1", BALANCE_OF, ADDR_A)
                    .call("0xToken2", BALANCE_OF, ADDR_B)
                    .executeAs(BigInteger.class)
                    .join();

            assertEquals(2, results.size());
            assertEquals(b1, results.get(0));
            assertEquals(b2, results.get(1));
        }
    }

    @Test @DisplayName("FluentBuilder.optional() treats failure as null (not exception)")
    void fluent_optional_failure() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(false, new byte[0])
            )));

            List<BigInteger> results = Multicall3.builder(rpc.client())
                    .optional("0xBadContract", BALANCE_OF, ADDR_A)
                    .executeAs(BigInteger.class)
                    .join();

            assertEquals(1, results.size());
            assertNull(results.get(0), "Failed optional must yield null, not throw");
        }
    }

    @Test @DisplayName("FluentBuilder.executeWithResults() returns Result wrappers")
    void fluent_execute_with_results() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(encodeMulticallResponse(List.of(
                    new CallResult(true, AbiCodec.encode(new AbiType[]{AbiType.UINT256}, new Object[]{BigInteger.TEN}))
            )));

            List<Multicall3.Result> results = Multicall3.builder(rpc.client())
                    .call("0xToken", BALANCE_OF, ADDR_A)
                    .executeWithResults()
                    .join();

            assertEquals(1, results.size());
            assertTrue(results.get(0).success());
            assertEquals(BigInteger.TEN, results.get(0).value());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    record CallResult(boolean success, byte[] data) {}

    /**
     * Encode the Multicall3 aggregate3 return format:
     * (bool success, bytes returnData)[] ABI-encoded.
     */
    static String encodeMulticallResponse(List<CallResult> calls) {
        // The response is ABI-encoded as: (bool,bytes)[]
        // outer array offset (32) + length (32) + per-element offsets + data
        // Simplified: build it manually for the test responses
        int n = calls.size();
        // Each element: offset to (bool, bytes) tuple
        // For the mock, encode as: array length + n * (bool slot + bytes offset + bytes length + bytes data padded)
        // ABI for (bool,bytes)[]:
        //   offset to array = 0x20
        //   array length = n
        //   element offsets (relative to array start)
        //   per element: bool (32) + bytes offset (32) + bytes length (32) + data (padded)

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            // Write offset to array start: 0x20
            buf.write(pad32(0x20));
            // Write array length
            buf.write(pad32(n));
            // Pre-compute element sizes to write offsets
            // Each element is: 32 (bool) + 32 (offset to bytes) + 32 (len) + padded(data)
            int[] elemSizes = new int[n];
            for (int i = 0; i < n; i++) {
                int dataLen = calls.get(i).data().length;
                elemSizes[i] = 32 + 32 + 32 + ((dataLen + 31) / 32 * 32);
            }
            // Write relative offsets (from start of elements section)
            int offset = n * 32; // skip past all offset slots
            for (int i = 0; i < n; i++) {
                buf.write(pad32(offset));
                offset += elemSizes[i];
            }
            // Write each element
            for (CallResult cr : calls) {
                buf.write(pad32(cr.success() ? 1 : 0));        // bool
                buf.write(pad32(32));                           // offset to bytes data
                buf.write(pad32(cr.data().length));             // bytes length
                int padded = (cr.data().length + 31) / 32 * 32;
                byte[] dataPadded = new byte[padded];
                System.arraycopy(cr.data(), 0, dataPadded, 0, cr.data().length);
                buf.write(dataPadded);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "\"" + Hex.encodeNoPrefx(buf.toByteArray()) + "\"";
    }

    static byte[] pad32(int v) {
        byte[] b = new byte[32];
        b[31] = (byte)(v & 0xff);
        b[30] = (byte)((v >> 8) & 0xff);
        b[29] = (byte)((v >> 16) & 0xff);
        b[28] = (byte)((v >> 24) & 0xff);
        return b;
    }

    // Expose Wallet.create() for test
    static io.jeth.crypto.Wallet Wallet_create() { return io.jeth.crypto.Wallet.create(); }
    static class Wallet { static io.jeth.crypto.Wallet create() { return io.jeth.crypto.Wallet.create(); } }
}
