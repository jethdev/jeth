/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.core.EthClient;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.model.EthModels;
import io.jeth.token.ERC1155;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link ERC1155} against a live Besu dev node.
 *
 * <p>TestERC1155 is compiled at test time via {@link SolcContainer} — no env vars needed. The test
 * contract has a {@code mint()} function (callable by anyone) for easy test setup.
 *
 * <p>Run: {@code ./gradlew integrationTest}
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ERC1155IntegrationTest {

    @Container static BesuContainer besu = new BesuContainer();

    static EthClient client;
    static Wallet dev;
    static String contractAddr;

    static final BigInteger TOKEN_ID_1 = BigInteger.ONE;
    static final BigInteger TOKEN_ID_2 = BigInteger.TWO;

    @BeforeAll
    static void setup() {
        client = EthClient.of(besu.httpUrl());
        dev = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);

        // Deploy TestERC1155 with URI "https://api.test/{id}"
        // The constructor takes a string arg — encode it as ABI
        String bytecode = SolcContainer.compile("TestERC1155");
        // Append ABI-encoded constructor arg: string "https://api.test/{id}"
        String uri = "https://api.test/{id}";
        String abiUri = encodeStringArg(uri);
        contractAddr = IntegrationTestBase.deploy(client, dev, bytecode + abiUri);
        System.out.println("[ERC1155] TestERC1155 @ " + contractAddr);
    }

    // ─── Read-only methods ────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("getAddress() returns the contract address")
    void get_address() {
        ERC1155 token = new ERC1155(contractAddr, client);
        assertEquals(contractAddr, token.getAddress());
    }

    @Test
    @Order(2)
    @DisplayName("balanceOf() returns 0 before any mint")
    void balance_of_zero_before_mint() {
        ERC1155 token = new ERC1155(contractAddr, client);
        BigInteger bal = token.balanceOf(dev.getAddress(), TOKEN_ID_1).join();
        assertEquals(BigInteger.ZERO, bal, "Balance must be 0 before minting");
    }

    @Test
    @Order(3)
    @DisplayName("uri() returns the token URI set in constructor")
    void uri_matches_constructor() {
        ERC1155 token = new ERC1155(contractAddr, client);
        String uri = token.uri(TOKEN_ID_1).join();
        assertNotNull(uri);
        assertFalse(uri.isBlank(), "uri must not be blank");
        assertTrue(
                uri.contains("api.test") || uri.contains("{id}"),
                "uri must contain expected value, got: " + uri);
    }

    @Test
    @Order(4)
    @DisplayName("isApprovedForAll() returns false before setApprovalForAll")
    void not_approved_initially() {
        ERC1155 token = new ERC1155(contractAddr, client);
        Wallet operator = Wallet.create();
        assertFalse(token.isApprovedForAll(dev.getAddress(), operator.getAddress()).join());
    }

    // ─── Mint (via raw contract call) ─────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("balanceOf() returns correct amount after mint")
    void balance_after_mint() throws Exception {
        BigInteger amount = BigInteger.valueOf(100);
        mint(dev, dev.getAddress(), TOKEN_ID_1, amount);

        ERC1155 token = new ERC1155(contractAddr, client);
        BigInteger bal = token.balanceOf(dev.getAddress(), TOKEN_ID_1).join();
        assertTrue(
                bal.compareTo(amount) >= 0,
                "Balance must be >= " + amount + " after minting, got " + bal);
    }

    // ─── setApprovalForAll ────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("setApprovalForAll() grants approval — isApprovedForAll() returns true")
    void set_approval_for_all() throws Exception {
        Wallet operator = Wallet.create();
        // Mint some tokens first so dev has a balance
        mint(dev, dev.getAddress(), TOKEN_ID_2, BigInteger.valueOf(50));

        ERC1155 token = new ERC1155(contractAddr, client);
        String txHash = token.setApprovalForAll(dev, operator.getAddress(), true).join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        assertTrue(
                token.isApprovedForAll(dev.getAddress(), operator.getAddress()).join(),
                "isApprovedForAll must return true after setApprovalForAll(true)");
    }

    @Test
    @Order(21)
    @DisplayName("setApprovalForAll(false) revokes approval")
    void revoke_approval() throws Exception {
        Wallet operator = Wallet.create();
        ERC1155 token = new ERC1155(contractAddr, client);

        // Grant then revoke
        client.waitForTransaction(
                        token.setApprovalForAll(dev, operator.getAddress(), true).join(),
                        30_000,
                        500)
                .join();
        assertTrue(token.isApprovedForAll(dev.getAddress(), operator.getAddress()).join());

        client.waitForTransaction(
                        token.setApprovalForAll(dev, operator.getAddress(), false).join(),
                        30_000,
                        500)
                .join();
        assertFalse(
                token.isApprovedForAll(dev.getAddress(), operator.getAddress()).join(),
                "Approval must be revoked after setApprovalForAll(false)");
    }

    // ─── safeTransferFrom ─────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("safeTransferFrom() transfers tokens between accounts")
    void safe_transfer_from() throws Exception {
        BigInteger amount = BigInteger.valueOf(10);
        Wallet recipient = Wallet.create();
        ERC1155 token = new ERC1155(contractAddr, client);

        // Mint some tokens to dev
        mint(dev, dev.getAddress(), TOKEN_ID_1, BigInteger.valueOf(50));
        BigInteger devBalBefore = token.balanceOf(dev.getAddress(), TOKEN_ID_1).join();

        // Transfer
        String txHash =
                token.safeTransferFrom(
                                dev,
                                dev.getAddress(),
                                recipient.getAddress(),
                                TOKEN_ID_1,
                                amount,
                                new byte[0])
                        .join();
        client.waitForTransaction(txHash, 30_000, 500).join();

        BigInteger recipientBal = token.balanceOf(recipient.getAddress(), TOKEN_ID_1).join();
        BigInteger devBalAfter = token.balanceOf(dev.getAddress(), TOKEN_ID_1).join();

        assertEquals(amount, recipientBal, "Recipient must have received exact amount");
        assertEquals(
                devBalBefore.subtract(amount),
                devBalAfter,
                "Sender balance must decrease by amount");
    }

    @Test
    @Order(31)
    @DisplayName("safeTransferFrom() returns a 0x-prefixed tx hash")
    void safe_transfer_from_returns_hash() throws Exception {
        mint(dev, dev.getAddress(), TOKEN_ID_1, BigInteger.valueOf(5));

        ERC1155 token = new ERC1155(contractAddr, client);
        String txHash =
                token.safeTransferFrom(
                                dev,
                                dev.getAddress(),
                                Wallet.create().getAddress(),
                                TOKEN_ID_1,
                                BigInteger.ONE,
                                new byte[0])
                        .join();

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("0x"), "tx hash must be 0x-prefixed");
        assertEquals(66, txHash.length(), "tx hash must be 32 bytes");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Mint tokens using the test contract's mint() function via raw eth_call. */
    void mint(Wallet minter, String to, BigInteger tokenId, BigInteger amount) throws Exception {
        // Function selector for mint(address,uint256,uint256)
        // keccak256("mint(address,uint256,uint256)") = first 4 bytes
        String mintData = buildMintCalldata(to, tokenId, amount);
        long nonce = client.getTransactionCount(minter.getAddress()).join();
        long chainId = client.getChainId().join();
        BigInteger tip = client.getMaxPriorityFeePerGas().join();
        var blk = client.getBlock("latest").join();
        BigInteger base = blk.baseFeePerGas != null ? blk.baseFeePerGas : BigInteger.ZERO;
        BigInteger maxFee = base.multiply(BigInteger.TWO).add(tip).add(BigInteger.ONE);
        BigInteger gas =
                client.estimateGas(
                                EthModels.CallRequest.builder()
                                        .from(minter.getAddress())
                                        .to(contractAddr)
                                        .value(BigInteger.ZERO)
                                        .data(mintData)
                                        .build())
                        .join()
                        .multiply(BigInteger.valueOf(130))
                        .divide(BigInteger.valueOf(100));
        var tx =
                EthModels.TransactionRequest.builder()
                        .from(minter.getAddress())
                        .to(contractAddr)
                        .value(BigInteger.ZERO)
                        .gas(gas)
                        .maxFeePerGas(maxFee)
                        .maxPriorityFeePerGas(tip)
                        .nonce(nonce)
                        .chainId(chainId)
                        .data(mintData)
                        .build();
        String txHash = client.sendRawTransaction(TransactionSigner.signEip1559(tx, minter)).join();
        client.waitForTransaction(txHash, 30_000, 500).join();
    }

    /**
     * Build calldata for mint(address,uint256,uint256). Selector:
     * keccak256("mint(address,uint256,uint256)")[0:4]
     */
    static String buildMintCalldata(String to, BigInteger tokenId, BigInteger amount) {
        // Use jeth's ABI codec
        io.jeth.abi.AbiType[] types = {
            io.jeth.abi.AbiType.ADDRESS, io.jeth.abi.AbiType.UINT256, io.jeth.abi.AbiType.UINT256
        };
        byte[] encoded = io.jeth.abi.AbiCodec.encode(types, new Object[] {to, tokenId, amount});
        // Compute selector: keccak256("mint(address,uint256,uint256)")[0:4]
        byte[] sig =
                io.jeth.util.Keccak.hash(
                        "mint(address,uint256,uint256)"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] selector = java.util.Arrays.copyOf(sig, 4);
        byte[] calldata = new byte[4 + encoded.length];
        System.arraycopy(selector, 0, calldata, 0, 4);
        System.arraycopy(encoded, 0, calldata, 4, encoded.length);
        return io.jeth.util.Hex.encode(calldata);
    }

    /** ABI-encode a string constructor argument (offset + length + data). */
    static String encodeStringArg(String s) {
        byte[] strBytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // offset (32 bytes) = 0x20 (32)
        // length (32 bytes) = strBytes.length
        // data (padded to 32-byte boundary)
        int padded = ((strBytes.length + 31) / 32) * 32;
        byte[] result = new byte[32 + 32 + padded];
        // offset = 32
        result[31] = 0x20;
        // length
        result[32 + 31] = (byte) strBytes.length; // works for short strings
        // data
        System.arraycopy(strBytes, 0, result, 64, strBytes.length);
        return io.jeth.util.Hex.encodeNoPrefx(result);
    }
}
