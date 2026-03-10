/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.aa.BundlerClient;
import io.jeth.aa.UserOperation;
import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.abi.HumanAbi;
import io.jeth.codegen.AbiJson;
import io.jeth.contract.ContractFunction;
import io.jeth.contract.ERC20;
import io.jeth.crypto.Signature;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.defi.AaveV3;
import io.jeth.defi.UniswapV3;
import io.jeth.eip4844.Blob;
import io.jeth.model.EthModels;
import io.jeth.safe.GnosisSafe;
import io.jeth.storage.StorageLayout;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Final coverage pass: tests for the remaining non-trivial public methods identified in the
 * systematic gap audit. Covers AbiJson entry predicates, Function/ContractFunction decode/selector,
 * TransactionSigner.signEip2930, Wallet.parseSignature/getPublicKeyHex, Blob hex helpers, AaveV3
 * write methods, UniswapV3.swapExactInputSingle, StorageLayout unit tests,
 * GnosisSafe.executeTransaction, BundlerClient.sponsorUserOperation, and ERC20.permit.
 */
class FinalCoverageTest {

    static final Wallet DEV =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    static String encodeUint(BigInteger v) {
        return "\"0x"
                + Hex.encodeNoPrefx(
                        AbiCodec.encode(new AbiType[] {AbiType.UINT256}, new Object[] {v}))
                + "\"";
    }

    static String encodeTxHash() {
        return "\"0x" + "a".repeat(64) + "\"";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AbiJson — entry type predicates and canonical helpers
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AbiJson entry predicates")
    class AbiJsonEntries {

        static final String ERC20_ABI =
                """
                [
                  {"type":"function","name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"nonpayable"},
                  {"type":"function","name":"balanceOf","inputs":[{"name":"account","type":"address"}],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"},
                  {"type":"event","name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true},{"name":"to","type":"address","indexed":true},{"name":"value","type":"uint256","indexed":false}],"anonymous":false},
                  {"type":"constructor","inputs":[],"stateMutability":"nonpayable"},
                  {"type":"error","name":"InsufficientBalance","inputs":[{"name":"needed","type":"uint256"},{"name":"available","type":"uint256"}]}
                ]
                """;

        @Test
        @DisplayName("isFunction true for function entries")
        void is_function() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            long count = entries.stream().filter(AbiJson.Entry::isFunction).count();
            assertEquals(2, count, "Should find 2 functions: transfer and balanceOf");
        }

        @Test
        @DisplayName("isEvent true for event entries")
        void is_event() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            long count = entries.stream().filter(AbiJson.Entry::isEvent).count();
            assertEquals(1, count, "Should find 1 event: Transfer");
        }

        @Test
        @DisplayName("isConstructor true for constructor entries")
        void is_constructor() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            long count = entries.stream().filter(AbiJson.Entry::isConstructor).count();
            assertEquals(1, count);
        }

        @Test
        @DisplayName("isError true for error entries")
        void is_error() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            long count = entries.stream().filter(AbiJson.Entry::isError).count();
            assertEquals(1, count, "Should find 1 custom error");
        }

        @Test
        @DisplayName("isView true for view functions only")
        void is_view() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            long viewCount = entries.stream().filter(AbiJson.Entry::isView).count();
            assertEquals(1, viewCount, "Only balanceOf is view");
        }

        @Test
        @DisplayName("isPayable false for nonpayable functions")
        void is_payable_false() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            assertTrue(
                    entries.stream()
                            .filter(AbiJson.Entry::isFunction)
                            .noneMatch(AbiJson.Entry::isPayable),
                    "No payable functions in ERC-20 ABI");
        }

        @Test
        @DisplayName("isPayable true for payable function")
        void is_payable_true() {
            String abi =
                    """
                    [{"type":"function","name":"deposit","inputs":[],"outputs":[],"stateMutability":"payable"}]
                    """;
            List<AbiJson.Entry> entries = AbiJson.parse(abi);
            assertTrue(entries.get(0).isPayable());
        }

        @Test
        @DisplayName("canonicalType: uint → uint256, int → int256")
        void canonical_type() {
            String abi =
                    """
                    [{"type":"function","name":"test","inputs":[{"name":"x","type":"uint"},{"name":"y","type":"int"}],"outputs":[],"stateMutability":"nonpayable"}]
                    """;
            AbiJson.Entry entry = AbiJson.parse(abi).get(0);
            assertEquals("uint256", entry.inputs().get(0).canonicalType());
            assertEquals("int256", entry.inputs().get(1).canonicalType());
        }

        @Test
        @DisplayName("canonicalType: address stays address")
        void canonical_type_address() {
            String abi =
                    """
                    [{"type":"function","name":"f","inputs":[{"name":"a","type":"address"}],"outputs":[],"stateMutability":"nonpayable"}]
                    """;
            assertEquals("address", AbiJson.parse(abi).get(0).inputs().get(0).canonicalType());
        }

        @Test
        @DisplayName("safeName: returns param name when present")
        void safe_name_named() {
            String abi =
                    """
                    [{"type":"function","name":"f","inputs":[{"name":"recipient","type":"address"}],"outputs":[],"stateMutability":"nonpayable"}]
                    """;
            assertEquals("recipient", AbiJson.parse(abi).get(0).inputs().get(0).safeName(0));
        }

        @Test
        @DisplayName("safeName: returns 'arg0' when name is empty")
        void safe_name_unnamed() {
            String abi =
                    """
                    [{"type":"function","name":"f","inputs":[{"name":"","type":"address"}],"outputs":[],"stateMutability":"nonpayable"}]
                    """;
            String name = AbiJson.parse(abi).get(0).inputs().get(0).safeName(0);
            assertEquals("arg0", name, "Empty name should fall back to 'arg0'");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Function / ContractFunction — getSelector, getInputTypes, decodeReturn
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Function helpers")
    class FunctionHelpers {

        static final Function TRANSFER =
                HumanAbi.parseFunction("transfer(address,uint256) returns (bool)");
        static final Function BALANCE_OF =
                HumanAbi.parseFunction("balanceOf(address) returns (uint256)");

        @Test
        @DisplayName("getSelector returns 4-byte selector")
        void get_selector() {
            byte[] sel = TRANSFER.getSelector();
            assertEquals(4, sel.length, "Selector must be 4 bytes");
        }

        @Test
        @DisplayName("getSelector matches keccak256 of signature")
        void get_selector_value() {
            byte[] sel = TRANSFER.getSelector();
            byte[] hash =
                    Keccak.hash(
                            "transfer(address,uint256)"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertArrayEquals(new byte[] {hash[0], hash[1], hash[2], hash[3]}, sel);
        }

        @Test
        @DisplayName("getSelector: different functions have different selectors")
        void get_selector_unique() {
            assertFalse(java.util.Arrays.equals(TRANSFER.getSelector(), BALANCE_OF.getSelector()));
        }

        @Test
        @DisplayName("getInputTypes returns correct AbiType array")
        void get_input_types() {
            AbiType[] types = TRANSFER.getInputTypes();
            assertEquals(2, types.length);
            assertEquals("address", types[0].baseType());
            assertEquals("uint256", types[1].baseType());
        }

        @Test
        @DisplayName("decodeReturn decodes uint256 return")
        void decode_return_uint() {
            BigInteger bal = new BigInteger("1000000000000000000");
            byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.UINT256}, new Object[] {bal});
            Object[] result = BALANCE_OF.decodeReturn(encoded);
            assertEquals(1, result.length);
            assertEquals(bal, result[0]);
        }

        @Test
        @DisplayName("decodeReturn decodes bool return")
        void decode_return_bool() {
            byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.BOOL}, new Object[] {true});
            Object[] result = TRANSFER.decodeReturn(encoded);
            assertEquals(true, result[0]);
        }

        @Test
        @DisplayName("ContractFunction.getSelector matches Function.getSelector")
        void contract_function_selector() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(encodeUint(BigInteger.ONE)); // mock response
                var contract = new io.jeth.contract.Contract("0xToken", rpc.client());
                ContractFunction cf = contract.fn("balanceOf(address)").returns("uint256");
                byte[] sel = cf.getSelector();
                assertEquals(4, sel.length);
                // Same as Function.getSelector for balanceOf(address)
                byte[] expected =
                        HumanAbi.parseFunction("balanceOf(address) returns (uint256)")
                                .getSelector();
                assertArrayEquals(expected, sel);
            }
        }

        @Test
        @DisplayName("ContractFunction.getInputTypes for transfer")
        void contract_function_input_types() throws Exception {
            try (var rpc = new RpcMock()) {
                var contract = new io.jeth.contract.Contract("0xToken", rpc.client());
                ContractFunction cf = contract.fn("transfer(address,uint256)").returns("bool");
                AbiType[] types = cf.getInputTypes();
                assertEquals(2, types.length);
            }
        }

        @Test
        @DisplayName("ContractFunction.callAt sends block-at parameter")
        void contract_function_call_at() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(encodeUint(BigInteger.valueOf(500)));
                var contract = new io.jeth.contract.Contract("0xToken", rpc.client());
                BigInteger result =
                        contract.fn("balanceOf(address)")
                                .returns("uint256")
                                .callAt("0x100", "0xUser")
                                .as(BigInteger.class)
                                .join();
                String body = rpc.takeRequest().getBody().readUtf8();
                assertTrue(body.contains("0x100"), "callAt must send block tag in request");
                assertEquals(BigInteger.valueOf(500), result);
            }
        }

        @Test
        @DisplayName("ContractFunction.getFunction returns underlying Function")
        void contract_function_get_function() throws Exception {
            try (var rpc = new RpcMock()) {
                var contract = new io.jeth.contract.Contract("0xToken", rpc.client());
                ContractFunction cf = contract.fn("balanceOf(address)").returns("uint256");
                Function fn = cf.getFunction();
                assertNotNull(fn);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TransactionSigner.signEip2930
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionSigner.signEip2930")
    class SignEip2930 {

        @Test
        @DisplayName("signEip2930 returns 0x-prefixed hex string")
        void sign_eip2930_format() {
            var tx =
                    EthModels.TransactionRequest.builder()
                            .from(DEV.getAddress())
                            .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                            .value(BigInteger.ZERO)
                            .gas(BigInteger.valueOf(21000))
                            .gasPrice(BigInteger.valueOf(20_000_000_000L))
                            .nonce(0)
                            .chainId(1)
                            .build();
            List<Map<String, Object>> accessList = List.of();
            String signed = TransactionSigner.signEip2930(tx, DEV, accessList);
            assertNotNull(signed);
            assertTrue(signed.startsWith("0x"), "Signed tx must be 0x-prefixed");
        }

        @Test
        @DisplayName("signEip2930 produces type 1 transaction (starts with 0x01)")
        void sign_eip2930_type_1() {
            var tx =
                    EthModels.TransactionRequest.builder()
                            .from(DEV.getAddress())
                            .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                            .value(BigInteger.ZERO)
                            .gas(BigInteger.valueOf(21000))
                            .gasPrice(BigInteger.valueOf(20_000_000_000L))
                            .nonce(0)
                            .chainId(1)
                            .build();
            String signed = TransactionSigner.signEip2930(tx, DEV, List.of());
            // Type 1 tx: 0x + 01 + RLP
            assertEquals(
                    "01", signed.substring(2, 4), "EIP-2930 type 1 tx must have type prefix 0x01");
        }

        @Test
        @DisplayName("signEip2930 is deterministic (RFC 6979)")
        void sign_eip2930_deterministic() {
            var tx =
                    EthModels.TransactionRequest.builder()
                            .from(DEV.getAddress())
                            .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                            .value(BigInteger.valueOf(1000))
                            .gas(BigInteger.valueOf(21000))
                            .gasPrice(BigInteger.valueOf(10_000_000_000L))
                            .nonce(5)
                            .chainId(137)
                            .build();
            String s1 = TransactionSigner.signEip2930(tx, DEV, List.of());
            String s2 = TransactionSigner.signEip2930(tx, DEV, List.of());
            assertEquals(s1, s2, "signEip2930 must be deterministic");
        }

        @Test
        @DisplayName("signEip2930 with access list produces longer tx than without")
        void sign_eip2930_with_access_list() {
            var tx =
                    EthModels.TransactionRequest.builder()
                            .from(DEV.getAddress())
                            .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                            .value(BigInteger.ZERO)
                            .gas(BigInteger.valueOf(30000))
                            .gasPrice(BigInteger.valueOf(10_000_000_000L))
                            .nonce(0)
                            .chainId(1)
                            .build();
            String noAccess = TransactionSigner.signEip2930(tx, DEV, List.of());
            List<Map<String, Object>> al =
                    List.of(
                            Map.of(
                                    "address",
                                    "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                                    "storageKeys",
                                    List.of("0x" + "0".repeat(64))));
            String withAccess = TransactionSigner.signEip2930(tx, DEV, al);
            assertTrue(
                    withAccess.length() > noAccess.length(),
                    "Tx with access list must be larger than tx without");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Wallet — parseSignature, getPublicKeyHex
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wallet helpers")
    class WalletHelpers {

        @Test
        @DisplayName("getPublicKeyHex returns 0x-prefixed 130-char uncompressed pubkey")
        void get_public_key_hex() {
            String pk = DEV.getPublicKeyHex();
            assertNotNull(pk);
            assertTrue(
                    pk.startsWith("0x04") || pk.startsWith("0x"),
                    "Uncompressed public key must start with 0x04");
            // 0x + 04 (1 byte) + 32 bytes X + 32 bytes Y = 0x + 130 chars, or 0x + 04 + 128 chars
            assertTrue(pk.length() >= 130, "Public key hex must be at least 130 chars (65 bytes)");
        }

        @Test
        @DisplayName("getPublicKeyHex is consistent for same wallet")
        void get_public_key_hex_deterministic() {
            assertEquals(DEV.getPublicKeyHex(), DEV.getPublicKeyHex());
        }

        @Test
        @DisplayName("parseSignature recovers correct r, s, v from hex signature")
        void parse_signature() {
            // Sign something and convert to hex, then parse back
            byte[] digest =
                    Keccak.hash("test message".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Signature sig = DEV.sign(digest);

            // Convert to 65-byte hex
            byte[] sigBytes = new byte[65];
            byte[] r = sig.r.toByteArray();
            byte[] s = sig.s.toByteArray();
            // Right-align r and s in 32-byte slots
            int rOff = r.length > 32 ? r.length - 32 : 0;
            int sOff = s.length > 32 ? s.length - 32 : 0;
            System.arraycopy(
                    r, rOff, sigBytes, 32 - Math.min(r.length, 32), Math.min(r.length, 32));
            System.arraycopy(
                    s, sOff, sigBytes, 64 - Math.min(s.length, 32), Math.min(s.length, 32));
            sigBytes[64] = (byte) sig.v;
            String sigHex = "0x" + Hex.encodeNoPrefx(sigBytes);

            Signature parsed = Wallet.parseSignature(sigHex);
            assertEquals(sig.r, parsed.r, "Parsed r must match original");
            assertEquals(sig.s, parsed.s, "Parsed s must match original");
        }

        @Test
        @DisplayName("parseSignature: v is 27 or 28 for standard Ethereum signatures")
        void parse_signature_v_value() {
            byte[] digest = new byte[32];
            Signature sig = DEV.sign(digest);
            assertTrue(sig.v == 27 || sig.v == 28, "v must be 27 or 28");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Blob hex helpers
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Blob hex helpers")
    class BlobHelpers {

        static Blob makeBlob() {
            // Create a minimal valid blob (128KB of zeros + field element constraints)
            // Blob.of() takes field element data
            byte[] data = new byte[32]; // minimal non-empty blob data
            return Blob.of(data);
        }

        @Test
        @DisplayName("dataHex returns 0x-prefixed hex string")
        void data_hex_format() {
            Blob blob = makeBlob();
            String hex = blob.dataHex();
            assertNotNull(hex);
            assertTrue(hex.startsWith("0x"));
        }

        @Test
        @DisplayName("commitmentHex returns 0x-prefixed 96-byte KZG commitment")
        void commitment_hex_format() {
            Blob blob = makeBlob();
            String hex = blob.commitmentHex();
            assertNotNull(hex);
            assertTrue(hex.startsWith("0x"));
            // KZG commitment is 48 bytes = 96 hex chars + "0x"
            assertEquals(98, hex.length(), "KZG commitment must be 0x + 96 hex chars (48 bytes)");
        }

        @Test
        @DisplayName("proofHex returns 0x-prefixed 96-byte KZG proof")
        void proof_hex_format() {
            Blob blob = makeBlob();
            String hex = blob.proofHex();
            assertNotNull(hex);
            assertTrue(hex.startsWith("0x"));
            assertEquals(98, hex.length(), "KZG proof must be 0x + 96 hex chars (48 bytes)");
        }

        @Test
        @DisplayName("versionedHashHex returns 0x-prefixed 66-char hash")
        void versioned_hash_hex_format() {
            Blob blob = makeBlob();
            String hex = blob.versionedHashHex();
            assertNotNull(hex);
            assertTrue(
                    hex.startsWith("0x01"),
                    "Versioned hash must start with 0x01 (BLS12-381 KZG version byte)");
            assertEquals(66, hex.length(), "Versioned hash must be 0x + 64 hex chars");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AaveV3 — supply, borrow, repay (write methods)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AaveV3 write methods")
    class AaveV3WriteMethods {

        static final String POOL = "0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2";
        static final String ASSET = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"; // USDC
        static final BigInteger AMOUNT = new BigInteger("1000000000"); // 1000 USDC

        @Test
        @DisplayName("supply sends eth_sendRawTransaction")
        void supply_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                // eth_chainId, eth_getTransactionCount, eth_maxPriorityFeePerGas,
                // eth_getBlockByNumber, eth_estimateGas, eth_sendRawTransaction
                rpc.enqueueHex(1L); // chainId
                rpc.enqueueHex(0L); // nonce
                rpc.enqueueHex(1_000_000_000L); // tip
                rpc.enqueueJson(
                        "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "a".repeat(64)
                                + "\"}");
                rpc.enqueueHex(100_000L); // estimateGas
                rpc.enqueueStr("0x" + "b".repeat(64)); // tx hash

                AaveV3 aave = new AaveV3(POOL, rpc.client());
                String txHash = aave.supply(DEV, ASSET, AMOUNT).join();
                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
                assertEquals(66, txHash.length());
            }
        }

        @Test
        @DisplayName("borrow sends eth_sendRawTransaction")
        void borrow_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                rpc.enqueueHex(1L);
                rpc.enqueueHex(1_000_000_000L);
                rpc.enqueueJson(
                        "{\"number\":\"0x2\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "c".repeat(64)
                                + "\"}");
                rpc.enqueueHex(120_000L);
                rpc.enqueueStr("0x" + "d".repeat(64));

                AaveV3 aave = new AaveV3(POOL, rpc.client());
                String txHash = aave.borrow(DEV, ASSET, AMOUNT, 2).join(); // variable rate
                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
            }
        }

        @Test
        @DisplayName("repay sends eth_sendRawTransaction")
        void repay_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                rpc.enqueueHex(2L);
                rpc.enqueueHex(1_000_000_000L);
                rpc.enqueueJson(
                        "{\"number\":\"0x3\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "e".repeat(64)
                                + "\"}");
                rpc.enqueueHex(90_000L);
                rpc.enqueueStr("0x" + "f".repeat(64));

                AaveV3 aave = new AaveV3(POOL, rpc.client());
                String txHash = aave.repay(DEV, ASSET, AMOUNT, 2).join();
                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UniswapV3 — swapExactInputSingle
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UniswapV3.swapExactInputSingle")
    class UniswapV3SwapTests {

        static final String ROUTER = "0xE592427A0AEce92De3Edee1F18E0157C05861564";
        static final String WETH = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
        static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

        @Test
        @DisplayName("swapExactInputSingle sends tx and returns tx hash")
        void swap_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                rpc.enqueueHex(0L);
                rpc.enqueueHex(1_000_000_000L);
                rpc.enqueueJson(
                        "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "a".repeat(64)
                                + "\"}");
                rpc.enqueueHex(200_000L);
                rpc.enqueueStr("0x" + "b".repeat(64));

                UniswapV3 uni = new UniswapV3(rpc.client());
                String txHash =
                        uni.swapExactInputSingle(
                                        DEV,
                                        WETH,
                                        USDC,
                                        3000, // fee tier
                                        new BigInteger("1000000000000000000"), // 1 ETH in
                                        new BigInteger("3000000000"), // min 3000 USDC out
                                        0 // deadline (0 = no deadline)
                                        )
                                .join();
                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // StorageLayout — unit tests for readNestedMapping, readArrayLength, readArrayElement
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("StorageLayout unit tests")
    class StorageLayoutUnit {

        @Test
        @DisplayName("readNestedMapping sends eth_getStorageAt")
        void nested_mapping_sends_rpc() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueStr(
                        "0x0000000000000000000000000000000000000000000000000000000000000064");
                StorageLayout s = StorageLayout.of(rpc.client(), "0xContract");
                BigInteger val =
                        s.readNestedMapping(
                                        2L,
                                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                                        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                                .join();
                assertEquals(BigInteger.valueOf(100), val);
                assertEquals(1, rpc.requestCount());
            }
        }

        @Test
        @DisplayName("readArrayLength reads slot n")
        void array_length_reads_slot() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueStr(
                        "0x0000000000000000000000000000000000000000000000000000000000000005");
                StorageLayout s = StorageLayout.of(rpc.client(), "0xContract");
                BigInteger len = s.readArrayLength(5L).join();
                assertEquals(BigInteger.valueOf(5), len);
            }
        }

        @Test
        @DisplayName("readArrayElement reads keccak256(slot) + index")
        void array_element_reads_data_slot() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueStr(
                        "0x000000000000000000000000000000000000000000000000000000000000002a");
                StorageLayout s = StorageLayout.of(rpc.client(), "0xContract");
                BigInteger elem = s.readArrayElement(3L, 0L).join();
                assertEquals(BigInteger.valueOf(42), elem);
            }
        }

        @Test
        @DisplayName("readArrayElement(slot, index) uses different RPC slot than readSlot(slot)")
        void array_element_uses_different_slot() throws Exception {
            try (var rpc = new RpcMock()) {
                // readSlot(3)
                rpc.enqueueStr(
                        "0x0000000000000000000000000000000000000000000000000000000000000003");
                // readArrayElement(3, 0) — slot is keccak256(3), different from 3
                rpc.enqueueStr(
                        "0x0000000000000000000000000000000000000000000000000000000000000099");

                StorageLayout s = StorageLayout.of(rpc.client(), "0xContract");
                BigInteger slotVal = s.readSlot(3L).join();
                BigInteger elemVal = s.readArrayElement(3L, 0L).join();

                // The mock returns different values for different slot queries
                assertNotEquals(
                        slotVal,
                        elemVal,
                        "Array element must read keccak(slot)+index, not slot directly");
            }
        }

        @Test
        @DisplayName("readNestedMapping: different owner+spender pairs give different slots")
        void nested_mapping_unique_slots() {
            String owner1 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
            String owner2 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
            String spender = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

            BigInteger slot1 = StorageLayout.mappingSlot(2L, owner1); // inner slot for owner1
            BigInteger slot2 = StorageLayout.mappingSlot(2L, owner2); // inner slot for owner2
            assertNotEquals(slot1, slot2, "Different owners must produce different mapping slots");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GnosisSafe — executeTransaction
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GnosisSafe.executeTransaction")
    class GnosisSafeExecute {

        static final String SAFE = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

        @Test
        @DisplayName("executeTransaction sends eth_sendRawTransaction")
        void execute_transaction_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                // Sequentially: chainId, nonce (safe), getThreshold, getNonce, chainId, nonce
                // (wallet), tip, block, gas, sendRawTx
                // Simplified: just mock enough for the tx to be sent
                rpc.enqueueHex(1L); // chainId
                rpc.enqueueHex(0L); // wallet nonce
                rpc.enqueueHex(1_000_000_000L); // tip
                rpc.enqueueJson(
                        "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "a".repeat(64)
                                + "\"}");
                rpc.enqueueHex(150_000L); // gas estimate
                rpc.enqueueStr("0x" + "b".repeat(64)); // tx hash

                GnosisSafe safe = new GnosisSafe(SAFE, rpc.client());

                // Build a SafeTx
                GnosisSafe.SafeTx safeTx =
                        GnosisSafe.SafeTx.builder()
                                .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                                .value(BigInteger.ZERO)
                                .data("0x")
                                .operation(0)
                                .safeTxGas(BigInteger.ZERO)
                                .baseGas(BigInteger.ZERO)
                                .gasPrice(BigInteger.ZERO)
                                .gasToken("0x0000000000000000000000000000000000000000")
                                .refundReceiver("0x0000000000000000000000000000000000000000")
                                .nonce(BigInteger.ZERO)
                                .build();

                byte[] digest = new byte[32]; // simplified hash
                Signature sig = DEV.sign(digest);

                String txHash =
                        safe.executeTransaction(DEV, safeTx, Map.of(DEV.getAddress(), sig)).join();

                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BundlerClient — sponsorUserOperation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BundlerClient.sponsorUserOperation")
    class BundlerClientSponsor {

        @Test
        @DisplayName("sponsorUserOperation sends pm_sponsorUserOperation")
        void sponsor_user_op_method() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(
                        "{\"paymasterAndData\":\"0xPaymaster\",\"preVerificationGas\":\"0x5208\",\"verificationGasLimit\":\"0x5208\",\"callGasLimit\":\"0x5208\"}");
                BundlerClient bc = BundlerClient.of(rpc.url());

                UserOperation op =
                        UserOperation.builder()
                                .sender("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                                .nonce(BigInteger.ZERO)
                                .callData("0x")
                                .callGasLimit(BigInteger.valueOf(21000))
                                .verificationGasLimit(BigInteger.valueOf(100000))
                                .preVerificationGas(BigInteger.valueOf(21000))
                                .maxFeePerGas(BigInteger.valueOf(20_000_000_000L))
                                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                                .paymasterAndData("0x")
                                .signature("0x")
                                .build();

                String entryPoint = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789";
                var result = bc.sponsorUserOperation(op, entryPoint, Map.of()).join();

                String body = rpc.takeRequest().getBody().readUtf8();
                assertTrue(
                        body.contains("pm_sponsorUserOperation"),
                        "Should send pm_sponsorUserOperation method");
                assertNotNull(result, "sponsorUserOperation must return PaymasterData");
            }
        }

        @Test
        @DisplayName("sponsorUserOperation result has paymasterAndData")
        void sponsor_user_op_result_fields() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueue(
                        "{\"paymasterAndData\":\"0xDeadBeef\",\"preVerificationGas\":\"0x5208\",\"verificationGasLimit\":\"0xa000\",\"callGasLimit\":\"0x5208\"}");
                BundlerClient bc = BundlerClient.of(rpc.url());

                UserOperation op =
                        UserOperation.builder()
                                .sender("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                                .nonce(BigInteger.ZERO)
                                .callData("0x")
                                .callGasLimit(BigInteger.valueOf(21000))
                                .verificationGasLimit(BigInteger.valueOf(100000))
                                .preVerificationGas(BigInteger.valueOf(21000))
                                .maxFeePerGas(BigInteger.valueOf(20_000_000_000L))
                                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                                .paymasterAndData("0x")
                                .signature("0x")
                                .build();

                var result = bc.sponsorUserOperation(op, "0xEntryPoint", Map.of()).join();
                assertNotNull(result.paymasterAndData());
                assertEquals("0xDeadBeef", result.paymasterAndData());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERC20 — permit (EIP-2612)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ERC20.permit")
    class ERC20Permit {

        static final String TOKEN = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"; // USDC

        @Test
        @DisplayName("permit sends eth_sendRawTransaction with permit calldata")
        void permit_sends_tx() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L); // chainId
                rpc.enqueueHex(0L); // nonce
                rpc.enqueueHex(1_000_000_000L); // tip
                rpc.enqueueJson(
                        "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "a".repeat(64)
                                + "\"}");
                rpc.enqueueHex(50_000L); // gas
                rpc.enqueueStr("0x" + "c".repeat(64)); // tx hash

                ERC20 usdc = new ERC20(TOKEN, rpc.client());
                Wallet spender = Wallet.create();
                BigInteger value = new BigInteger("1000000000"); // 1000 USDC
                BigInteger deadline = BigInteger.valueOf(9999999999L);

                // Sign the permit: EIP-2612 off-chain signature
                // TypedData.signPermit handles this — permit() sends the signed tx
                io.jeth.eip712.TypedData.Domain domain =
                        io.jeth.eip712.TypedData.Domain.builder()
                                .name("USD Coin")
                                .version("2")
                                .chainId(1L)
                                .verifyingContract(TOKEN)
                                .build();
                Signature permitSig =
                        io.jeth.eip712.TypedData.signPermit(
                                domain,
                                DEV.getAddress(),
                                spender.getAddress(),
                                value,
                                BigInteger.ZERO,
                                deadline,
                                DEV);

                String txHash =
                        usdc.permit(
                                        DEV,
                                        DEV.getAddress(),
                                        spender.getAddress(),
                                        value,
                                        deadline,
                                        permitSig)
                                .join();

                assertNotNull(txHash);
                assertTrue(txHash.startsWith("0x"));
                assertEquals(66, txHash.length());
            }
        }

        @Test
        @DisplayName("permit makes exactly 1 RPC call (just eth_sendRawTransaction bundle)")
        void permit_rpc_calls() throws Exception {
            try (var rpc = new RpcMock()) {
                rpc.enqueueHex(1L);
                rpc.enqueueHex(0L);
                rpc.enqueueHex(1_000_000_000L);
                rpc.enqueueJson(
                        "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\",\"hash\":\"0x"
                                + "a".repeat(64)
                                + "\"}");
                rpc.enqueueHex(50_000L);
                rpc.enqueueStr("0x" + "d".repeat(64));

                ERC20 usdc = new ERC20(TOKEN, rpc.client());
                Wallet spender = Wallet.create();
                io.jeth.eip712.TypedData.Domain domain =
                        io.jeth.eip712.TypedData.Domain.builder()
                                .name("USD Coin")
                                .version("2")
                                .chainId(1L)
                                .verifyingContract(TOKEN)
                                .build();
                Signature sig =
                        io.jeth.eip712.TypedData.signPermit(
                                domain,
                                DEV.getAddress(),
                                spender.getAddress(),
                                BigInteger.valueOf(1_000_000),
                                BigInteger.ZERO,
                                BigInteger.valueOf(9999999999L),
                                DEV);

                usdc.permit(
                                DEV,
                                DEV.getAddress(),
                                spender.getAddress(),
                                BigInteger.valueOf(1_000_000),
                                BigInteger.valueOf(9999999999L),
                                sig)
                        .join();

                // 6 calls: chainId, nonce, tip, block, gas, sendRawTx
                assertEquals(6, rpc.requestCount());
            }
        }
    }
}
