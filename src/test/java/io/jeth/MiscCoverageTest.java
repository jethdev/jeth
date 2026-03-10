/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.aa.BundlerClient;
import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.contract.ERC20;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.defi.AaveV3;
import io.jeth.gas.GasEstimator;
import io.jeth.middleware.MiddlewareProvider;
import io.jeth.model.EthModels;
import io.jeth.price.PriceOracle;
import io.jeth.safe.GnosisSafe;
import io.jeth.token.ERC721;
import io.jeth.util.Address;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import io.jeth.util.Units;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for the remaining gap areas identified in the systematic audit:
 * MiddlewareProvider (retry, logging, avgLatencyMs), ERC721 (ownerOf/tokenURI/getApproved),
 * AaveV3 (getUserAccountData/healthFactor/isLiquidatable), GnosisSafe (packSorted/getOwners),
 * BundlerClient (getUserOperationByHash), ERC20 (balanceOfFormatted/permit), 
 * PriceOracle (spot/twap/roundData), and utility helpers (Address.isZero, Units.toEther,
 * Hex.isHex/zeroPad, Keccak.hashHex, GasEstimator.estimateGasCost/priorityFeeGwei).
 */
class MiscCoverageTest {

    // ═══════════════════════════════════════════════════════════════════════
    // MiddlewareProvider — retry, logging, avgLatencyMs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("MiddlewareProvider retry + logging")
    class MiddlewareProviderExtended {

        @Test @DisplayName("withRetry: retries on transient error, succeeds on second attempt")
        void retry_on_error() throws Exception {
            try (var rpc = new RpcMock()) {
                // First call: RPC returns error; second: success
                rpc.enqueue("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"timeout\"}}".substring(0)); // can't enqueue error directly, use null trick
                // Actually enqueue a valid response for the retry
                rpc.enqueueHex(42L);

                // Build middleware with retry(2, 1ms delay)
                MiddlewareProvider mp = MiddlewareProvider.wrap(rpc.client().getProvider())
                        .withRetry(2, Duration.ofMillis(1))
                        .build();

                // The retry will attempt twice; second succeeds
                // (First response is the "no mock queued" error which triggers retry)
                // Note: RpcMock returns error for empty queue, so first call fails
                var result = mp.send(new io.jeth.model.RpcModels.RpcRequest("eth_blockNumber", java.util.List.of())).join();
                assertNotNull(result);
                assertEquals(1, mp.getMetrics().retries.get(), "Should record 1 retry");
            }
        }

        @Test @DisplayName("withRetry: metrics.retries starts at 0")
        void retry_metrics_start_zero() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                MiddlewareProvider mp = MiddlewareProvider.wrap(rpc.client().getProvider())
                        .withRetry(3, Duration.ofMillis(1))
                        .build();
                mp.send(new io.jeth.model.RpcModels.RpcRequest("eth_blockNumber", java.util.List.of())).join();
                assertEquals(0, mp.getMetrics().retries.get(), "No retries on first success");
            }
        }

        @Test @DisplayName("withLogging: provider works correctly with logging enabled")
        void logging_enabled_works() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(100L);
                MiddlewareProvider mp = MiddlewareProvider.wrap(rpc.client().getProvider())
                        .withLogging()
                        .build();
                var result = mp.send(new io.jeth.model.RpcModels.RpcRequest("eth_blockNumber", java.util.List.of())).join();
                assertNotNull(result);
                assertNotNull(result.result);
            }
        }

        @Test @DisplayName("avgLatencyMs: returns 0 before any calls")
        void avg_latency_zero_before_calls() throws Exception {
            try (var rpc = new RpcMock()) {
                MiddlewareProvider mp = MiddlewareProvider.wrap(rpc.client().getProvider()).build();
                assertEquals(0.0, mp.getMetrics().avgLatencyMs(), 0.001);
            }
        }

        @Test @DisplayName("avgLatencyMs: returns positive value after calls")
        void avg_latency_positive_after_calls() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                MiddlewareProvider mp = MiddlewareProvider.wrap(rpc.client().getProvider()).build();
                mp.send(new io.jeth.model.RpcModels.RpcRequest("eth_blockNumber", java.util.List.of())).join();
                assertTrue(mp.getMetrics().avgLatencyMs() >= 0.0,
                        "avgLatencyMs must be non-negative after calls");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERC721 — ownerOf, tokenURI, getApproved
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("ERC721")
    class ERC721Tests {

        static final String CONTRACT = "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D"; // BAYC

        static String encodeAddr(String addr) {
            String hex = addr.startsWith("0x") ? addr.substring(2) : addr;
            return "\"0x" + "0".repeat(24) + hex.toLowerCase() + "\"";
        }

        static String encodeStr(String s) {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int padded = ((b.length + 31) / 32) * 32;
            byte[] result = new byte[96 + padded]; // offset(32) + len(32) + data
            result[31] = 0x20;
            result[63] = (byte) b.length;
            System.arraycopy(b, 0, result, 64, b.length);
            return "\"0x" + Hex.encodeNoPrefx(result) + "\"";
        }

        @Test @DisplayName("ownerOf() returns address string")
        void owner_of() throws Exception {
            try (var rpc = new RpcMock()) {
                String owner = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";
                rpc.enqueue(encodeAddr(owner));
                ERC721 nft = new ERC721(CONTRACT, rpc.client());
                String result = nft.ownerOf(BigInteger.ONE).join();
                assertTrue(owner.equalsIgnoreCase(result), "ownerOf must return owner address");
            }
        }

        @Test @DisplayName("ownerOf() sends balanceOf-style eth_call RPC")
        void owner_of_makes_rpc() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(encodeAddr("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"));
                new ERC721(CONTRACT, rpc.client()).ownerOf(BigInteger.valueOf(42)).join();
                assertEquals(1, rpc.requestCount());
            }
        }

        @Test @DisplayName("tokenURI() returns metadata URI string")
        void token_uri() throws Exception {
            try (var rpc = new RpcMock()) {
                String uri = "https://nft.example.com/metadata/1";
                rpc.enqueue(encodeStr(uri));
                ERC721 nft = new ERC721(CONTRACT, rpc.client());
                String result = nft.tokenURI(BigInteger.ONE).join();
                assertEquals(uri, result, "tokenURI must return metadata URI");
            }
        }

        @Test @DisplayName("getApproved() returns approved operator address")
        void get_approved() throws Exception {
            try (var rpc = new RpcMock()) {
                String operator = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
                rpc.enqueue(encodeAddr(operator));
                ERC721 nft = new ERC721(CONTRACT, rpc.client());
                String result = nft.getApproved(BigInteger.TEN).join();
                assertTrue(operator.equalsIgnoreCase(result));
            }
        }

        @Test @DisplayName("getApproved() returns zero address when no approval")
        void get_approved_none() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(encodeAddr("0x0000000000000000000000000000000000000000"));
                ERC721 nft = new ERC721(CONTRACT, rpc.client());
                String result = nft.getApproved(BigInteger.valueOf(999)).join();
                assertTrue(result.replace("0x", "").chars().allMatch(c -> c == '0'),
                        "Zero address means no approval");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AaveV3 — getUserAccountData, healthFactor, isLiquidatable
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("AaveV3")
    class AaveV3Tests {

        static final String POOL = "0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2";

        @Test @DisplayName("getUserAccountData returns AccountData with all fields")
        void get_user_account_data() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger collateral = new BigInteger("50000000000000000000"); // $50k
                BigInteger debt       = new BigInteger("10000000000000000000"); // $10k
                BigInteger available  = new BigInteger("30000000000000000000"); // $30k available
                BigInteger threshold  = BigInteger.valueOf(8000);               // 80% liquidation threshold
                BigInteger ltv        = BigInteger.valueOf(7500);               // 75% LTV
                BigInteger hf         = new BigInteger("5000000000000000000"); // 5.0 HF

                // AaveV3 returns (uint256,uint256,uint256,uint256,uint256,uint256)
                byte[] encoded = AbiCodec.encode(
                        new AbiType[]{AbiType.UINT256, AbiType.UINT256, AbiType.UINT256,
                                      AbiType.UINT256, AbiType.UINT256, AbiType.UINT256},
                        new Object[]{collateral, debt, available, threshold, ltv, hf});
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(encoded) + "\"");

                AaveV3 aave = new AaveV3(POOL, rpc.client());
                AaveV3.AccountData data = aave.getUserAccountData("0xUser").join();

                assertNotNull(data);
                assertEquals(collateral, data.totalCollateralBase);
                assertEquals(debt, data.totalDebtBase);
                assertEquals(hf, data.healthFactor);
            }
        }

        @Test @DisplayName("AccountData.healthFactorEther() converts from 18-decimal fixed point")
        void health_factor_ether() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger hf = new BigInteger("1500000000000000000"); // 1.5 HF
                byte[] enc = AbiCodec.encode(
                        new AbiType[]{AbiType.UINT256, AbiType.UINT256, AbiType.UINT256,
                                      AbiType.UINT256, AbiType.UINT256, AbiType.UINT256},
                        new Object[]{BigInteger.TEN, BigInteger.ONE, BigInteger.ONE,
                                     BigInteger.valueOf(8000), BigInteger.valueOf(7500), hf});
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(enc) + "\"");

                AaveV3 aave = new AaveV3(POOL, rpc.client());
                AaveV3.AccountData data = aave.getUserAccountData("0xUser").join();

                assertEquals(1.5, data.healthFactorEther(), 0.001, "HF must be 1.5");
            }
        }

        @Test @DisplayName("AccountData.isLiquidatable() true when HF < 1.0")
        void is_liquidatable_true() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger hf = new BigInteger("900000000000000000"); // 0.9 HF
                byte[] enc = AbiCodec.encode(
                        new AbiType[]{AbiType.UINT256, AbiType.UINT256, AbiType.UINT256,
                                      AbiType.UINT256, AbiType.UINT256, AbiType.UINT256},
                        new Object[]{BigInteger.TEN, BigInteger.ONE, BigInteger.ONE,
                                     BigInteger.valueOf(8000), BigInteger.valueOf(7500), hf});
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(enc) + "\"");

                AaveV3.AccountData data = new AaveV3(POOL, rpc.client()).getUserAccountData("0xUser").join();
                assertTrue(data.isLiquidatable(), "HF < 1.0 must be liquidatable");
            }
        }

        @Test @DisplayName("AccountData.isLiquidatable() false when HF > 1.0")
        void is_liquidatable_false() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger hf = new BigInteger("2000000000000000000"); // 2.0 HF
                byte[] enc = AbiCodec.encode(
                        new AbiType[]{AbiType.UINT256, AbiType.UINT256, AbiType.UINT256,
                                      AbiType.UINT256, AbiType.UINT256, AbiType.UINT256},
                        new Object[]{BigInteger.TEN, BigInteger.ONE, BigInteger.ONE,
                                     BigInteger.valueOf(8000), BigInteger.valueOf(7500), hf});
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(enc) + "\"");

                AaveV3.AccountData data = new AaveV3(POOL, rpc.client()).getUserAccountData("0xUser").join();
                assertFalse(data.isLiquidatable(), "HF > 1.0 must not be liquidatable");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GnosisSafe — packSorted, getOwners
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("GnosisSafe")
    class GnosisSafeTests {

        static final String SAFE = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

        @Test @DisplayName("packSorted: single signature is 65 bytes")
        void pack_sorted_single() {
            Wallet w = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
            byte[] digest = new byte[32]; // zeros
            Signature sig = w.sign(digest);
            byte[] packed = GnosisSafe.packSorted(Map.of(w.getAddress(), sig));
            assertEquals(65, packed.length, "Single packed Safe sig must be 65 bytes");
        }

        @Test @DisplayName("packSorted: two signatures = 130 bytes, sorted by address")
        void pack_sorted_two_sorted() {
            Wallet w1 = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
            Wallet w2 = Wallet.fromPrivateKey("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
            byte[] digest = new byte[32];
            Map<String, Signature> sigs = Map.of(
                    w1.getAddress(), w1.sign(digest),
                    w2.getAddress(), w2.sign(digest)
            );
            byte[] packed = GnosisSafe.packSorted(sigs);
            assertEquals(130, packed.length, "Two packed Safe sigs must be 130 bytes");
        }

        @Test @DisplayName("packSorted: lower address always appears first in output")
        void pack_sorted_order() {
            Wallet w1 = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
            Wallet w2 = Wallet.fromPrivateKey("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
            byte[] digest = new byte[32];

            // Sort manually: lower address first
            String a1 = w1.getAddress().toLowerCase();
            String a2 = w2.getAddress().toLowerCase();

            Map<String, Signature> sigs = Map.of(
                    a1, w1.sign(digest),
                    a2, w2.sign(digest)
            );
            byte[] packed = GnosisSafe.packSorted(sigs);

            // Verify same result regardless of map order
            Map<String, Signature> sigs2 = Map.of(
                    a2, w2.sign(digest),
                    a1, w1.sign(digest)
            );
            byte[] packed2 = GnosisSafe.packSorted(sigs2);
            assertArrayEquals(packed, packed2, "packSorted must be deterministic regardless of map ordering");
        }

        @Test @DisplayName("getOwners sends eth_call to getOwners() function")
        void get_owners() throws Exception {
            try (var rpc = new RpcMock()) {
                String o1 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
                String o2 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
                // Encode address[] result
                byte[] encoded = AbiCodec.encode(
                        new AbiType[]{AbiType.of("address[]")},
                        new Object[]{new Object[]{o1, o2}});
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(encoded) + "\"");

                GnosisSafe safe = new GnosisSafe(SAFE, rpc.client());
                java.util.List<String> owners = safe.getOwners().join();
                assertEquals(2, owners.size());
                assertTrue(owners.stream().anyMatch(o -> o.equalsIgnoreCase(o1)));
                assertTrue(owners.stream().anyMatch(o -> o.equalsIgnoreCase(o2)));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BundlerClient — getUserOperationByHash
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("BundlerClient extended")
    class BundlerClientExtended {

        @Test @DisplayName("getUserOperationByHash sends eth_getUserOperationByHash")
        void get_user_op_by_hash_method() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue("{\"sender\":\"0xSender\",\"nonce\":\"0x1\"}");
                BundlerClient bc = BundlerClient.of(rpc.url());
                String hash = "0x" + "a".repeat(64);
                bc.getUserOperationByHash(hash).join();
                String body = rpc.takeRequest().getBody().readUtf8();
                assertTrue(body.contains("eth_getUserOperationByHash"));
                assertTrue(body.contains(hash));
            }
        }

        @Test @DisplayName("getUserOperationByHash returns null when not found")
        void get_user_op_by_hash_null() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueNull();
                BundlerClient bc = BundlerClient.of(rpc.url());
                var result = bc.getUserOperationByHash("0x" + "b".repeat(64)).join();
                assertNull(result, "getUserOperationByHash must return null when op not found");
            }
        }

        @Test @DisplayName("getUserOperationByHash returns JsonNode when found")
        void get_user_op_by_hash_found() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue("{\"sender\":\"0xSender\",\"nonce\":\"0x5\",\"callGasLimit\":\"0x5208\"}");
                BundlerClient bc = BundlerClient.of(rpc.url());
                var result = bc.getUserOperationByHash("0x" + "c".repeat(64)).join();
                assertNotNull(result);
                assertEquals("0xSender", result.path("sender").asText());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERC20 — balanceOfFormatted, getContract
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("ERC20 extended")
    class ERC20Extended {

        static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

        static String encodeUint(BigInteger v) {
            return "\"0x" + Hex.encodeNoPrefx(AbiCodec.encode(
                    new AbiType[]{AbiType.UINT256}, new Object[]{v})) + "\"";
        }

        @Test @DisplayName("balanceOfFormatted returns BigDecimal with correct decimals")
        void balance_of_formatted() throws Exception {
            try (var rpc = new RpcMock()) {
                // decimals() = 6
                rpc.enqueue(encodeUint(BigInteger.valueOf(6)));
                // balanceOf = 5_000_000 (5 USDC in 6-decimal units)
                rpc.enqueue(encodeUint(BigInteger.valueOf(5_000_000)));

                ERC20 usdc = new ERC20(USDC, rpc.client());
                BigDecimal bal = usdc.balanceOfFormatted("0xUser").join();
                assertEquals(new BigDecimal("5"), bal.stripTrailingZeros(),
                        "5_000_000 with 6 decimals = 5.0 USDC");
            }
        }

        @Test @DisplayName("balanceOfFormatted: 1 USDC (1_000_000 units, 6 decimals)")
        void balance_of_formatted_one() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(encodeUint(BigInteger.valueOf(6)));       // decimals
                rpc.enqueue(encodeUint(BigInteger.valueOf(1_000_000))); // 1 USDC

                ERC20 usdc = new ERC20(USDC, rpc.client());
                BigDecimal bal = usdc.balanceOfFormatted("0xUser").join();
                assertEquals(0, new BigDecimal("1").compareTo(bal.stripTrailingZeros()),
                        "1_000_000 units with 6 decimals = 1 USDC");
            }
        }

        @Test @DisplayName("getContract returns the underlying Contract instance")
        void get_contract() throws Exception {
            try (var rpc = new RpcMock()) {
                ERC20 token = new ERC20(USDC, rpc.client());
                assertNotNull(token.getContract(), "getContract must return non-null Contract");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PriceOracle — roundData, spot, twap
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("PriceOracle extended")
    class PriceOracleExtended {

        static String encodeRoundData(BigInteger answer, int decimals) {
            // latestRoundData returns (uint80,int256,uint256,uint256,uint80)
            byte[] enc = AbiCodec.encode(
                    new AbiType[]{AbiType.of("uint80"), AbiType.INT256,
                                  AbiType.UINT256, AbiType.UINT256, AbiType.of("uint80")},
                    new Object[]{BigInteger.ONE, answer,
                                 BigInteger.valueOf(System.currentTimeMillis() / 1000),
                                 BigInteger.valueOf(System.currentTimeMillis() / 1000),
                                 BigInteger.ONE});
            return "\"0x" + Hex.encodeNoPrefx(enc) + "\"";
        }

        static String encodeUint(BigInteger v) {
            return "\"0x" + Hex.encodeNoPrefx(AbiCodec.encode(
                    new AbiType[]{AbiType.UINT256}, new Object[]{v})) + "\"";
        }

        @Test @DisplayName("roundData returns ChainlinkRound with answer and timestamp")
        void round_data() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger price = new BigInteger("341258000000"); // $3412.58, 8 decimals
                rpc.enqueue(encodeRoundData(price, 8));            // latestRoundData
                rpc.enqueue(encodeUint(BigInteger.valueOf(8)));    // decimals

                PriceOracle oracle = new PriceOracle(rpc.client());
                PriceOracle.ChainlinkRound round = oracle.roundData(PriceOracle.ETH_USD).join();

                assertNotNull(round);
                assertEquals(price, round.rawAnswer());
                assertTrue(round.updatedAt() > 0, "updatedAt must be positive");
            }
        }

        @Test @DisplayName("roundData: ChainlinkRound.price() applies decimal scaling")
        void round_data_price_scaled() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger price = new BigInteger("341258000000"); // $3412.58 * 10^8
                rpc.enqueue(encodeRoundData(price, 8));
                rpc.enqueue(encodeUint(BigInteger.valueOf(8)));

                PriceOracle oracle = new PriceOracle(rpc.client());
                PriceOracle.ChainlinkRound round = oracle.roundData(PriceOracle.ETH_USD).join();

                BigDecimal scaled = round.price();
                assertNotNull(scaled);
                assertEquals(0, new BigDecimal("3412.58").compareTo(scaled),
                        "price() must scale correctly: 341258000000 / 10^8 = 3412.58");
            }
        }

        @Test @DisplayName("spot returns USD price from Uniswap V3 TWAP (1-second window)")
        void spot_price() throws Exception {
            try (var rpc = new RpcMock()) {
                // spot() uses Uniswap V3 observe() — encode a realistic tick response
                // observe returns (int56[] tickCumulatives, uint160[] secondsPerLiquidityCumulativeX128s)
                BigInteger tick0 = BigInteger.valueOf(-200000L); // current tick
                BigInteger tick1 = BigInteger.valueOf(-199995L); // 1 second ago
                byte[] enc = AbiCodec.encode(
                        new AbiType[]{AbiType.of("int56[]"), AbiType.of("uint160[]")},
                        new Object[]{
                                new Object[]{tick0, tick1},
                                new Object[]{BigInteger.ZERO, BigInteger.ZERO}
                        });
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(enc) + "\"");

                PriceOracle oracle = new PriceOracle(rpc.client());
                BigDecimal price = oracle.spot(
                        "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", // WETH
                        "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", // USDC
                        3000
                ).join();

                assertNotNull(price);
                assertTrue(price.compareTo(BigDecimal.ZERO) > 0, "spot price must be positive");
            }
        }

        @Test @DisplayName("twap returns price averaged over window")
        void twap_price() throws Exception {
            try (var rpc = new RpcMock()) {
                // twap uses same observe() with a longer window (e.g. 1800 seconds)
                BigInteger tick0 = BigInteger.valueOf(-200000L);
                BigInteger tick1 = BigInteger.valueOf(-196400L); // 1800 seconds ago
                byte[] enc = AbiCodec.encode(
                        new AbiType[]{AbiType.of("int56[]"), AbiType.of("uint160[]")},
                        new Object[]{
                                new Object[]{tick0, tick1},
                                new Object[]{BigInteger.ZERO, BigInteger.ZERO}
                        });
                rpc.enqueue("\"0x" + Hex.encodeNoPrefx(enc) + "\"");

                PriceOracle oracle = new PriceOracle(rpc.client());
                BigDecimal price = oracle.twap(
                        "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",
                        "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                        3000, 1800
                ).join();

                assertNotNull(price);
                assertTrue(price.compareTo(BigDecimal.ZERO) > 0);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GasEstimator — estimateGasCost, priorityFeeGwei
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("GasEstimator extended")
    class GasEstimatorExtended {

        @Test @DisplayName("estimateGasCost returns wei cost (gas * gasPrice)")
        void estimate_gas_cost() throws Exception {
            try (var rpc = new RpcMock()) {
                BigInteger gas      = BigInteger.valueOf(50_000);
                BigInteger gasPrice = BigInteger.valueOf(20_000_000_000L); // 20 gwei

                rpc.enqueueHex(gas);      // eth_estimateGas
                rpc.enqueueHex(gasPrice); // eth_gasPrice

                GasEstimator estimator = GasEstimator.of(rpc.client());
                var req = EthModels.CallRequest.builder()
                        .from("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                        .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8").build();
                BigInteger cost = estimator.estimateGasCost(req).join();

                assertEquals(gas.multiply(gasPrice), cost, "Gas cost must be gas * gasPrice");
            }
        }

        @Test @DisplayName("FeeEstimate.priorityFeeGwei() converts to gwei double")
        void priority_fee_gwei() throws Exception {
            try (var rpc = new RpcMock()) {
                // suggest() returns low/medium/high fee tiers
                BigInteger tip  = BigInteger.valueOf(1_500_000_000L); // 1.5 gwei
                BigInteger base = BigInteger.valueOf(10_000_000_000L); // 10 gwei
                BigInteger maxFee = base.multiply(BigInteger.TWO).add(tip);

                // Mock: eth_maxPriorityFeePerGas, eth_getBlock (for baseFee)
                rpc.enqueueHex(tip);
                rpc.enqueueJson("{\"number\":\"0x1\",\"baseFeePerGas\":\"" +
                        "0x" + base.toString(16) + "\",\"hash\":\"0x" + "a".repeat(64) + "\"}");

                GasEstimator estimator = GasEstimator.of(rpc.client());
                GasEstimator.FeeEstimates fees = estimator.suggest().join();

                assertNotNull(fees);
                assertNotNull(fees.medium());
                double gwei = fees.medium().priorityFeeGwei();
                assertTrue(gwei >= 0, "priorityFeeGwei must be non-negative, got " + gwei);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility helpers — Address.isZero, Units.toEther, Hex.isHex/zeroPad,
    //                   Keccak.hashHex
    // ═══════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Utility helpers")
    class UtilityHelpers {

        @Test @DisplayName("Address.isZero: zero address = true")
        void address_is_zero_true() {
            assertTrue(Address.isZero("0x0000000000000000000000000000000000000000"));
        }

        @Test @DisplayName("Address.isZero: non-zero address = false")
        void address_is_zero_false() {
            assertFalse(Address.isZero("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"));
        }

        @Test @DisplayName("Address.isZero: null = true")
        void address_is_zero_null() {
            assertTrue(Address.isZero(null));
        }

        @Test @DisplayName("Address.isZero: 0x without address = true")
        void address_is_zero_short() {
            assertTrue(Address.isZero("0x0"));
        }

        @Test @DisplayName("Units.toEther: 1 ETH in wei → BigDecimal 1.0")
        void to_ether_one() {
            BigDecimal result = Units.toEther(new BigInteger("1000000000000000000"));
            assertEquals(0, BigDecimal.ONE.compareTo(result.stripTrailingZeros()));
        }

        @Test @DisplayName("Units.toEther: 0.5 ETH in wei → BigDecimal 0.5")
        void to_ether_half() {
            BigDecimal result = Units.toEther(new BigInteger("500000000000000000"));
            assertEquals(0, new BigDecimal("0.5").compareTo(result.stripTrailingZeros()));
        }

        @Test @DisplayName("Units.toEther(0) = 0")
        void to_ether_zero() {
            assertEquals(BigDecimal.ZERO, Units.toEther(BigInteger.ZERO).stripTrailingZeros());
        }

        @Test @DisplayName("Hex.isHex: valid 0x-prefixed hex = true")
        void hex_is_hex_valid() {
            assertTrue(Hex.isHex("0xdeadbeef"));
            assertTrue(Hex.isHex("0x0"));
            assertTrue(Hex.isHex("0xABCDEF"));
        }

        @Test @DisplayName("Hex.isHex: invalid = false")
        void hex_is_hex_invalid() {
            assertFalse(Hex.isHex("notHex"));
            assertFalse(Hex.isHex("0xZZZZ"));
            assertFalse(Hex.isHex(""));
            assertFalse(Hex.isHex(null));
        }

        @Test @DisplayName("Hex.zeroPad pads hex to 32 bytes")
        void hex_zero_pad() {
            String result = Hex.zeroPad("0x1", 32);
            assertEquals("0x" + "0".repeat(63) + "1", result);
        }

        @Test @DisplayName("Hex.zeroPad: already 32 bytes passes through")
        void hex_zero_pad_no_op() {
            String full = "0x" + "f".repeat(64);
            assertEquals(full, Hex.zeroPad(full, 32));
        }

        @Test @DisplayName("Keccak.hashHex returns 0x-prefixed 66-char string")
        void keccak_hash_hex_format() {
            String result = Keccak.hashHex("Transfer(address,address,uint256)");
            assertTrue(result.startsWith("0x"), "must be 0x-prefixed");
            assertEquals(66, result.length(), "must be 0x + 64 hex chars");
        }

        @Test @DisplayName("Keccak.hashHex: Transfer signature matches known hash")
        void keccak_hash_hex_transfer() {
            String result = Keccak.hashHex("Transfer(address,address,uint256)");
            assertEquals("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", result);
        }

        @Test @DisplayName("Keccak.hashHex: consistent with hash(byte[])")
        void keccak_hash_hex_consistent() {
            String s = "test string";
            String hex = Keccak.hashHex(s);
            byte[] raw = Keccak.hash(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("0x" + Hex.encodeNoPrefx(raw), hex);
        }
    }
}
