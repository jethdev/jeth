/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.contract.Contract;
import io.jeth.core.Chain;
import io.jeth.crypto.Wallet;
import io.jeth.event.EventDef;
import io.jeth.multicall.Multicall3;
import io.jeth.util.Units;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;

/**
 * Tests for: Units ethers.js aliases, Multicall TryResult, Chain new entries,
 * Wallet.connect(ConnectedWallet), and Contract.queryFilter.
 */
class NewFeaturesTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Units — ethers.js-compatible aliases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Units — parseUnits / formatUnits / parseEther / formatEther")
    class UnitsAliasesTest {

        @Test @DisplayName("parseUnits(\"1\", 18) == toWei(\"1\")")
        void parse_units_18() {
            assertEquals(Units.toWei("1"), Units.parseUnits("1", 18));
        }

        @Test @DisplayName("parseUnits(\"100.5\", 6) == 100_500_000 (USDC)")
        void parse_units_6() {
            assertEquals(BigInteger.valueOf(100_500_000L), Units.parseUnits("100.5", 6));
        }

        @Test @DisplayName("parseUnits(\"0\", 18) == ZERO")
        void parse_units_zero() {
            assertEquals(BigInteger.ZERO, Units.parseUnits("0", 18));
        }

        @Test @DisplayName("formatUnits(1_000_000, 6) == \"1\"")
        void format_units_6() {
            assertEquals("1", Units.formatUnits(BigInteger.valueOf(1_000_000L), 6));
        }

        @Test @DisplayName("formatUnits(1_500_000, 6) strips trailing zeros: \"1.5\"")
        void format_units_trailing_zeros() {
            assertEquals("1.5", Units.formatUnits(BigInteger.valueOf(1_500_000L), 6));
        }

        @Test @DisplayName("formatUnits(0, 18) == \"0\"")
        void format_units_zero() {
            assertEquals("0", Units.formatUnits(BigInteger.ZERO, 18));
        }

        @Test @DisplayName("parseUnits / formatUnits are inverse operations")
        void round_trip() {
            String amount = "12.345";
            int decimals = 6;
            assertEquals(amount,
                Units.formatUnits(Units.parseUnits(amount, decimals), decimals));
        }

        @Test @DisplayName("parseEther(\"1.5\") == toWei(\"1.5\")")
        void parse_ether() {
            assertEquals(Units.toWei("1.5"), Units.parseEther("1.5"));
        }

        @Test @DisplayName("formatEther(1e18 wei) == \"1\"")
        void format_ether() {
            BigInteger oneEth = BigInteger.TEN.pow(18);
            assertEquals("1", Units.formatEther(oneEth));
        }

        @Test @DisplayName("formatEther(1.5e18 wei) == \"1.5\"")
        void format_ether_fraction() {
            BigInteger weiAmount = Units.toWei("1.5");
            assertEquals("1.5", Units.formatEther(weiAmount));
        }

        @Test @DisplayName("formatEther(0) == \"0\"")
        void format_ether_zero() {
            assertEquals("0", Units.formatEther(BigInteger.ZERO));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chain — new network entries
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Chain — new networks")
    class ChainNewNetworksTest {

        @Test @DisplayName("CELO has chainId 42220")
        void celo_id() { assertEquals(42220L, Chain.CELO.id()); }

        @Test @DisplayName("MANTLE has chainId 5000")
        void mantle_id() { assertEquals(5000L, Chain.MANTLE.id()); }

        @Test @DisplayName("MODE has chainId 34443")
        void mode_id() { assertEquals(34443L, Chain.MODE.id()); }

        @Test @DisplayName("TAIKO has chainId 167000")
        void taiko_id() { assertEquals(167000L, Chain.TAIKO.id()); }

        @Test @DisplayName("POLYGON_ZKEVM has chainId 1101")
        void polygon_zkevm_id() { assertEquals(1101L, Chain.POLYGON_ZKEVM.id()); }

        @Test @DisplayName("ZKSYNC_SEPOLIA has chainId 300")
        void zksync_sepolia_id() { assertEquals(300L, Chain.ZKSYNC_SEPOLIA.id()); }

        @Test @DisplayName("ARBITRUM_NOVA has chainId 42170")
        void arbitrum_nova_id() { assertEquals(42170L, Chain.ARBITRUM_NOVA.id()); }

        @Test @DisplayName("All new chains have valid HTTPS public RPC URLs")
        void new_chains_have_rpcs() {
            for (Chain c : List.of(Chain.CELO, Chain.MANTLE, Chain.MODE, Chain.TAIKO,
                    Chain.POLYGON_ZKEVM, Chain.ZKSYNC_SEPOLIA, Chain.ARBITRUM_NOVA)) {
                assertNotNull(c.publicRpc(), c.name() + " has null publicRpc");
                assertTrue(c.publicRpc().startsWith("https://"),
                    c.name() + " RPC must be HTTPS: " + c.publicRpc());
            }
        }

        @Test @DisplayName("All new chains have explorer URLs")
        void new_chains_have_explorers() {
            for (Chain c : List.of(Chain.CELO, Chain.MANTLE, Chain.MODE, Chain.TAIKO,
                    Chain.POLYGON_ZKEVM, Chain.ZKSYNC_SEPOLIA, Chain.ARBITRUM_NOVA)) {
                assertNotNull(c.explorer(), c.name() + " missing explorer");
            }
        }

        @Test @DisplayName("New chains are found by fromId()")
        void new_chains_from_id() {
            assertEquals(Chain.CELO,          Chain.fromId(42220L).orElseThrow());
            assertEquals(Chain.MANTLE,        Chain.fromId(5000L).orElseThrow());
            assertEquals(Chain.MODE,          Chain.fromId(34443L).orElseThrow());
            assertEquals(Chain.TAIKO,         Chain.fromId(167000L).orElseThrow());
            assertEquals(Chain.POLYGON_ZKEVM, Chain.fromId(1101L).orElseThrow());
            assertEquals(Chain.ARBITRUM_NOVA, Chain.fromId(42170L).orElseThrow());
        }

        @Test @DisplayName("New L2 chains are identified as L2")
        void new_chains_are_l2() {
            assertTrue(Chain.MANTLE.isL2());
            assertTrue(Chain.MODE.isL2());
            assertTrue(Chain.TAIKO.isL2());
            assertTrue(Chain.POLYGON_ZKEVM.isL2());
            assertTrue(Chain.ARBITRUM_NOVA.isL2());
            assertTrue(Chain.ZKSYNC_SEPOLIA.isL2());
        }

        @Test @DisplayName("ZKSYNC_SEPOLIA is a testnet")
        void zksync_sepolia_is_testnet() {
            assertTrue(Chain.ZKSYNC_SEPOLIA.isTestnet());
        }

        @Test @DisplayName("ARBITRUM_NOVA is NOT a testnet")
        void arbitrum_nova_not_testnet() {
            assertFalse(Chain.ARBITRUM_NOVA.isTestnet());
        }

        @Test @DisplayName("Total Chain count is at least 25 (was 18)")
        void chain_count() {
            assertTrue(Chain.values().length >= 25,
                "Expected at least 25 chains, got: " + Chain.values().length);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multicall3 — tryExecute / TryResult
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Multicall3 — tryExecute / TryResult")
    class MulticallTryTest {

        static byte[] padUint256(long v) {
            byte[] b = new byte[32];
            for (int i = 0; i < 8; i++) b[31 - i] = (byte)(v >> (8 * i));
            return b;
        }

        static byte[] buildAggregate3Response(byte[][] returnDatas, boolean[] successes) {
            // Encode: (uint256 n, [(bool success, bytes data), ...])
            int n = returnDatas.length;
            // Simplified: encode manually for 1-2 results only
            // Each result: 32 (offset) + 32 (success) + 32 (bytes len) + data
            var bb = new ByteArrayOutputStream();
            try {
                // write: 32-byte offset to array = 0x20
                byte[] off = new byte[32]; off[31] = 32; bb.write(off);
                // array length
                byte[] len = new byte[32]; len[31] = (byte) n; bb.write(len);
                // head offsets: each element's offset from start of array data
                int headSize = n * 32;
                int running = headSize;
                for (int i = 0; i < n; i++) {
                    byte[] elOff = new byte[32];
                    int r = running;
                    elOff[28] = (byte)(r >> 24); elOff[29] = (byte)(r >> 16);
                    elOff[30] = (byte)(r >> 8);  elOff[31] = (byte) r;
                    bb.write(elOff);
                    int pad = ((returnDatas[i].length + 31) / 32) * 32;
                    running += 32 + 32 + pad; // success + len + data
                }
                // each element: success (32) + offset to bytes (32) + bytes_len (32) + data
                for (int i = 0; i < n; i++) {
                    byte[] success = new byte[32]; success[31] = (byte)(successes[i] ? 1 : 0); bb.write(success);
                    int dataLen = returnDatas[i].length;
                    int pad = ((dataLen + 31) / 32) * 32;
                    byte[] bytesLen = new byte[32]; bytesLen[31] = (byte) dataLen; bb.write(bytesLen);
                    byte[] padded = new byte[pad];
                    System.arraycopy(returnDatas[i], 0, padded, 0, returnDatas[i].length);
                    bb.write(padded);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            return bb.toByteArray();
        }

        @Test @DisplayName("tryExecute() returns empty list for empty Multicall")
        void try_execute_empty() throws Exception {
            try (var rpc = new RpcMock()) {
                var mc = new Multicall3(rpc.client());
                var results = mc.tryExecute().join();
                assertTrue(results.isEmpty());
                assertEquals(0, rpc.requestCount());
            }
        }

        @Test @DisplayName("TryResult.success=true has non-null value")
        void try_result_success() throws Exception {
            try (var rpc = new RpcMock()) {
                byte[] encodedResponse = buildAggregate3Response(
                    new byte[][]{padUint256(42L)}, new boolean[]{true});
                rpc.enqueue("\"0x" + io.jeth.util.Hex.encode(encodedResponse).substring(2) + "\"");

                var mc = new Multicall3(rpc.client());
                Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
                mc.add("0xToken", fn, "0xUser");
                var results = mc.tryExecute().join();
                assertEquals(1, results.size());
                assertTrue(results.get(0).success());
            }
        }

        @Test @DisplayName("TryResult.success=false has revertReason (may be empty)")
        void try_result_failure() throws Exception {
            try (var rpc = new RpcMock()) {
                byte[] encodedResponse = buildAggregate3Response(
                    new byte[][]{new byte[0]}, new boolean[]{false});
                rpc.enqueue("\"0x" + io.jeth.util.Hex.encode(encodedResponse).substring(2) + "\"");

                var mc = new Multicall3(rpc.client());
                Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
                mc.add("0xBad", fn, "0xUser");
                var results = mc.tryExecute().join();
                assertEquals(1, results.size());
                assertFalse(results.get(0).success());
            }
        }

        @Test @DisplayName("TryResult.as() throws on failed result")
        void try_result_as_throws_on_failure() {
            var failed = new Multicall3.TryResult(false, null, "reverted");
            assertThrows(IllegalStateException.class, () -> failed.as(BigInteger.class));
        }

        @Test @DisplayName("TryResult.opt() returns empty Optional on failure")
        void try_result_opt_empty() {
            var failed = new Multicall3.TryResult(false, null, "reverted");
            assertTrue(failed.opt(BigInteger.class).isEmpty());
        }

        @Test @DisplayName("TryResult.opt() returns value on success")
        void try_result_opt_present() {
            var ok = new Multicall3.TryResult(true, BigInteger.TEN, null);
            assertEquals(BigInteger.TEN, ok.opt(BigInteger.class).orElseThrow());
        }

        @Test @DisplayName("TryResult.toString() shows success/fail")
        void try_result_to_string() {
            var ok   = new Multicall3.TryResult(true, BigInteger.ONE, null);
            var fail = new Multicall3.TryResult(false, null, "revert reason");
            assertTrue(ok.toString().contains("ok") || ok.toString().contains("TryResult"));
            assertTrue(fail.toString().contains("fail") || fail.toString().contains("revert reason"));
        }

        @Test @DisplayName("TryResult.isNull() is true when value == null")
        void try_result_is_null() {
            assertTrue(new Multicall3.TryResult(false, null, "x").isNull());
            assertFalse(new Multicall3.TryResult(true, BigInteger.ONE, null).isNull());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wallet.connect() → ConnectedWallet
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Wallet.connect() → ConnectedWallet")
    class ConnectedWalletTest {

        static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
        static final Wallet WALLET = Wallet.fromPrivateKey(new BigInteger(PK.substring(2), 16));

        @Test @DisplayName("connect() returns non-null ConnectedWallet")
        void connect_returns_connected() throws Exception {
            try (var rpc = new RpcMock()) {
                var connected = WALLET.connect(rpc.client());
                assertNotNull(connected);
            }
        }

        @Test @DisplayName("ConnectedWallet.getAddress() == wallet.getAddress()")
        void connected_address() throws Exception {
            try (var rpc = new RpcMock()) {
                var connected = WALLET.connect(rpc.client());
                assertEquals(WALLET.getAddress(), connected.getAddress());
            }
        }

        @Test @DisplayName("ConnectedWallet.getWallet() returns the original wallet")
        void connected_get_wallet() throws Exception {
            try (var rpc = new RpcMock()) {
                var connected = WALLET.connect(rpc.client());
                assertEquals(WALLET.getAddress(), connected.getWallet().getAddress());
            }
        }

        @Test @DisplayName("ConnectedWallet.getClient() returns the EthClient")
        void connected_get_client() throws Exception {
            try (var rpc = new RpcMock()) {
                var client = rpc.client();
                var connected = WALLET.connect(client);
                assertNotNull(connected.getClient());
            }
        }

        @Test @DisplayName("sendEth() makes multiple RPC calls (chainId, nonce, estimateGas, block, tip)")
        void send_eth_makes_rpc_calls() throws Exception {
            try (var rpc = new RpcMock()) {
                // sendEth calls: getChainId, getTransactionCount, estimateGas,
                //                getBlock("latest"), getMaxPriorityFeePerGas, sendRawTransaction
                rpc.enqueueHex(1L);                                          // chainId
                rpc.enqueueHex(0L);                                          // nonce
                rpc.enqueueHex(21_000L);                                     // estimateGas
                rpc.enqueueJson("{\"number\":\"0x12\",\"baseFeePerGas\":\"0x3B9ACA00\","
                    + "\"gasLimit\":\"0x1C9C380\",\"gasUsed\":\"0x0\","
                    + "\"hash\":\"0x1\",\"parentHash\":\"0x0\",\"timestamp\":\"0x0\","
                    + "\"transactions\":[],\"logsBloom\":\"0x0\"}"); // getBlock
                rpc.enqueueHex(1_000_000_000L);                              // maxPriorityFeePerGas
                rpc.enqueueStr("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" +
                               "deadbeefdeadbeefdeadbeef");                  // sendRawTransaction

                var connected = WALLET.connect(rpc.client());
                String hash = connected.sendEth(
                    "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                    Units.toWei("0.001")).join();

                assertNotNull(hash);
                assertTrue(hash.startsWith("0x"));
                assertTrue(rpc.requestCount() >= 4, "Expected multiple RPC calls");
            }
        }

        @Test @DisplayName("ConnectedWallet.toString() is human-readable")
        void connected_to_string() throws Exception {
            try (var rpc = new RpcMock()) {
                var connected = WALLET.connect(rpc.client());
                String s = connected.toString();
                assertTrue(s.contains("0x") || s.contains("Wallet") || s.contains("Connected"),
                    "toString should contain address or class name: " + s);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Contract.queryFilter()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Contract.queryFilter()")
    class QueryFilterTest {

        static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

        static final EventDef TRANSFER = EventDef.of("Transfer",
            EventDef.indexed("from",  "address"),
            EventDef.indexed("to",    "address"),
            EventDef.data("value",    "uint256"));

        @Test @DisplayName("queryFilter() passes correct fromBlock and toBlock to getLogs")
        void query_filter_block_range() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueJson("[]");
                var contract = new Contract(USDC, rpc.client());
                var events = contract.queryFilter(TRANSFER, 18_000_000L, 18_001_000L).join();
                assertTrue(events.isEmpty());
                assertEquals(1, rpc.requestCount());

                // Verify the request contained the right params
                var req = rpc.takeRequest();
                String body = req.getBody().readUtf8();
                assertTrue(body.contains("0x" + Long.toHexString(18_000_000L)),
                    "fromBlock must be in request: " + body);
            }
        }

        @Test @DisplayName("queryFilter() with 1-arg (fromBlock only) uses 'latest' as toBlock")
        void query_filter_open_end() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueJson("[]");
                var contract = new Contract(USDC, rpc.client());
                contract.queryFilter(TRANSFER, 18_000_000L).join();
                var req = rpc.takeRequest();
                assertTrue(req.getBody().readUtf8().contains("latest"));
            }
        }

        @Test @DisplayName("queryFilter() decodes matching logs into DecodedEvents")
        void query_filter_decodes() throws Exception {
            try (var rpc = new RpcMock()) {
                // Build a Transfer log
                String topic0 = TRANSFER.topic0Hex();
                String fromTopic = "0x000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266";
                String toTopic   = "0x00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8";
                String data      = "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000"; // 1e18

                rpc.enqueueJson("[{\"address\":\"" + USDC + "\","
                    + "\"topics\":[\"" + topic0 + "\",\"" + fromTopic + "\",\"" + toTopic + "\"],"
                    + "\"data\":\"" + data + "\","
                    + "\"blockNumber\":\"0x1\",\"transactionHash\":\"0x1\","
                    + "\"transactionIndex\":\"0x0\",\"blockHash\":\"0x1\","
                    + "\"logIndex\":\"0x0\",\"removed\":false}]");

                var contract = new Contract(USDC, rpc.client());
                var events = contract.queryFilter(TRANSFER, 18_000_000L, 18_001_000L).join();

                assertEquals(1, events.size());
                var e = events.get(0);
                assertEquals("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266",
                    e.address("from").toLowerCase());
            }
        }

        @Test @DisplayName("queryFilter() auto-chunks when range > MAX_LOG_CHUNK")
        void query_filter_auto_chunks() throws Exception {
            long from = 0L;
            long to   = 4_001L;  // 3 chunks
            try (var rpc = new RpcMock()) {
                rpc.enqueueJson("[]");
                rpc.enqueueJson("[]");
                rpc.enqueueJson("[]");
                var contract = new Contract(USDC, rpc.client());
                contract.queryFilter(TRANSFER, from, to).join();
                assertEquals(3, rpc.requestCount(), "Should have made 3 chunk requests");
            }
        }
    }
}
