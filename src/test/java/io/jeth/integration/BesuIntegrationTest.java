/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.jeth.contract.Contract;
import io.jeth.core.EthClient;
import io.jeth.crypto.TransactionSigner;
import io.jeth.crypto.Wallet;
import io.jeth.event.EventDef;
import io.jeth.gas.GasEstimator;
import io.jeth.model.EthModels;
import io.jeth.multicall.Multicall3;
import io.jeth.util.Hex;
import io.jeth.util.Units;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration tests against a local Hyperledger Besu dev node.
 *
 * <p>Run: ./gradlew integrationTest (requires Docker)
 *
 * <p>Each test group is independent where possible; @Order ensures deployment happens before the
 * contract tests.
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BesuIntegrationTest {

    @Container static BesuContainer besu = new BesuContainer();

    static EthClient client;
    static Wallet dev;
    static String tokenAddr; // set after deploy

    @BeforeAll
    static void setup() {
        client = EthClient.of(besu.httpUrl());
        dev = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);
    }

    // ══════════════════════ 1. NETWORK ═══════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("[net] chainId = 1337 (Besu dev mode)")
    void chainId() {
        assertEquals(1337L, client.getChainId().join());
    }

    @Test
    @Order(2)
    @DisplayName("[net] blockNumber >= 0")
    void blockNumber() {
        assertTrue(client.getBlockNumber().join() >= 0);
    }

    @Test
    @Order(3)
    @DisplayName("[net] isListening = true")
    void listening() {
        assertTrue(!client.isSyncing().join());
    }

    // ══════════════════════ 2. ACCOUNTS ══════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("[account] dev wallet has pre-funded balance")
    void devBalance() {
        BigInteger bal = client.getBalance(BesuContainer.DEV_ADDRESS).join();
        assertTrue(bal.compareTo(BigInteger.ZERO) > 0, "Balance was: " + Units.formatEther(bal));
    }

    @Test
    @Order(11)
    @DisplayName("[account] fresh wallet has zero balance")
    void freshBalance() {
        assertEquals(BigInteger.ZERO, client.getBalance(Wallet.create().getAddress()).join());
    }

    @Test
    @Order(12)
    @DisplayName("[account] isContract false for EOA")
    void eoaNotContract() {
        assertFalse(client.isContract(BesuContainer.DEV_ADDRESS).join());
    }

    // ══════════════════════ 3. BLOCKS ════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("[block] getBlock('latest') returns valid block")
    void latestBlock() {
        var b = client.getBlock("latest").join();
        assertNotNull(b);
        assertNotNull(b.hash);
        assertTrue(b.gasLimit.compareTo(BigInteger.ZERO) > 0);
    }

    @Test
    @Order(21)
    @DisplayName("[block] getBlockByNumber matches latest")
    void blockByNumber() {
        long n = client.getBlockNumber().join();
        assertEquals(client.getBlock("latest").join().hash, client.getBlockByNumber(n).join().hash);
    }

    @Test
    @Order(22)
    @DisplayName("[block] getBlockByHash round-trips")
    void blockByHash() {
        var latest = client.getBlock("latest").join();
        assertEquals(latest.number, client.getBlockByHash(latest.hash).join().number);
    }

    // ══════════════════════ 4. GAS ═══════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("[gas] getGasPrice returns non-negative")
    void gasPrice() {
        assertTrue(client.getGasPrice().join().compareTo(BigInteger.ZERO) >= 0);
    }

    @Test
    @Order(31)
    @DisplayName("[gas] estimateGas for ETH transfer = 21000")
    void estimateGasTransfer() {
        var req =
                EthModels.CallRequest.builder()
                        .from(BesuContainer.DEV_ADDRESS)
                        .to(Wallet.create().getAddress())
                        .value(Units.toWei("0.01"))
                        .build();
        assertEquals(BigInteger.valueOf(21000), client.estimateGas(req).join());
    }

    @Test
    @Order(32)
    @DisplayName("[gas] GasEstimator.suggest() returns ordered tiers")
    void gasSuggest() {
        var fees = GasEstimator.of(client).suggest().join();
        assertTrue(fees.low().maxFeePerGas().compareTo(fees.high().maxFeePerGas()) <= 0);
    }

    // ══════════════════════ 5. ETH TRANSFER ══════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("[tx] sign + send ETH, wait for receipt")
    void sendEth() throws Exception {
        var recipient = Wallet.create();
        var amount = Units.toWei("0.1");
        var txHash = sendTx(dev, recipient.getAddress(), amount, "0x", null);
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        assertNotNull(receipt);
        assertTrue(receipt.isSuccess());
        assertEquals(amount, client.getBalance(recipient.getAddress()).join());
        assertTrue(receipt.gasCostWei().compareTo(BigInteger.ZERO) > 0);
    }

    @Test
    @Order(41)
    @DisplayName("[tx] getTransaction() returns correct fields")
    void getTransaction() throws Exception {
        var recipient = Wallet.create();
        var txHash = sendTx(dev, recipient.getAddress(), Units.toWei("0.01"), "0x", null);
        client.waitForTransaction(txHash, 30_000, 500).join();
        var tx = client.getTransaction(txHash).join();
        assertNotNull(tx);
        assertEquals(txHash, tx.hash);
        assertEquals(2L, tx.type);
    }

    @Test
    @Order(42)
    @DisplayName("[tx] receipt null for unknown hash")
    void unknownReceipt() {
        assertNull(client.getTransactionReceipt("0x" + "0".repeat(64)).join());
    }

    // ══════════════════════ 6. CONTRACT DEPLOY ════════════════════════════════

    @Test
    @Order(50)
    @DisplayName("[deploy] Deploy TestToken ERC-20 (compiled via SolcContainer)")
    void deployToken() throws Exception {
        // Compile TestToken.sol at test time using the ethereum/solc Docker image.
        // No JETH_ERC20_BYTECODE env var needed — fully self-contained.
        String bytecode = SolcContainer.compile("TestToken");

        var txHash = sendTx(dev, null, BigInteger.ZERO, bytecode, null);
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        assertTrue(receipt.isSuccess(), "Deploy failed");
        assertNotNull(receipt.contractAddress);
        tokenAddr = receipt.contractAddress;
        assertTrue(client.isContract(tokenAddr).join());
        System.out.println("✅ TestToken deployed at: " + tokenAddr);
    }

    // ══════════════════════ 7. CONTRACT READS ════════════════════════════════

    @Test
    @Order(51)
    @DisplayName("[contract] name() = 'TestToken'")
    void tokenName() {
        skipIfNoToken();
        assertEquals(
                "TestToken",
                contract().fn("name()").returns("string").call().as(String.class).join());
    }

    @Test
    @Order(52)
    @DisplayName("[contract] symbol() = 'TT'")
    void tokenSymbol() {
        skipIfNoToken();
        assertEquals(
                "TT", contract().fn("symbol()").returns("string").call().as(String.class).join());
    }

    @Test
    @Order(53)
    @DisplayName("[contract] decimals() = 18")
    void tokenDecimals() {
        skipIfNoToken();
        assertEquals(
                BigInteger.valueOf(18),
                contract().fn("decimals()").returns("uint8").call().as(BigInteger.class).join());
    }

    @Test
    @Order(54)
    @DisplayName("[contract] totalSupply() = 1_000_000 TT")
    void tokenTotalSupply() {
        skipIfNoToken();
        assertEquals(
                Units.toWei("1000000"),
                contract()
                        .fn("totalSupply()")
                        .returns("uint256")
                        .call()
                        .as(BigInteger.class)
                        .join());
    }

    @Test
    @Order(55)
    @DisplayName("[contract] balanceOf(deployer) = totalSupply")
    void tokenBalance() {
        skipIfNoToken();
        assertEquals(
                Units.toWei("1000000"),
                contract()
                        .fn("balanceOf(address)")
                        .returns("uint256")
                        .call(dev.getAddress())
                        .as(BigInteger.class)
                        .join());
    }

    // ══════════════════════ 8. CONTRACT WRITES ═══════════════════════════════

    @Test
    @Order(60)
    @DisplayName("[contract] transfer() succeeds, balances updated")
    void tokenTransfer() throws Exception {
        skipIfNoToken();
        var recipient = Wallet.create();
        var amount = Units.toWei("100");
        var txHash =
                contract()
                        .fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), amount)
                        .join();
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        assertTrue(receipt.isSuccess());
        assertEquals(
                amount,
                contract()
                        .fn("balanceOf(address)")
                        .returns("uint256")
                        .call(recipient.getAddress())
                        .as(BigInteger.class)
                        .join());
    }

    // ══════════════════════ 9. EVENTS ════════════════════════════════════════

    @Test
    @Order(70)
    @DisplayName("[events] Transfer emits correct log with topic0")
    void transferEmitsLog() throws Exception {
        skipIfNoToken();
        var recipient = Wallet.create();
        var txHash =
                contract()
                        .fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), Units.toWei("1"))
                        .join();
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        assertFalse(receipt.logs.isEmpty());
        String t0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        assertTrue(receipt.logs.stream().anyMatch(l -> l.matchesTopic0(t0)));
    }

    @Test
    @Order(71)
    @DisplayName("[events] EventDef decodes Transfer log")
    void eventDefDecode() throws Exception {
        skipIfNoToken();
        var Transfer =
                EventDef.of(
                        "Transfer",
                        EventDef.indexed("from", "address"),
                        EventDef.indexed("to", "address"),
                        EventDef.data("value", "uint256"));
        var recipient = Wallet.create();
        BigInteger amount = Units.toWei("2");
        var txHash =
                contract()
                        .fn("transfer(address,uint256)")
                        .send(dev, recipient.getAddress(), amount)
                        .join();
        var receipt = client.waitForTransaction(txHash, 30_000, 500).join();
        var decoded = Transfer.decode(receipt.logs.get(0));
        assertNotNull(decoded);
        assertTrue(dev.getAddress().equalsIgnoreCase(decoded.address("from")));
        assertTrue(recipient.getAddress().equalsIgnoreCase(decoded.address("to")));
        assertEquals(amount, decoded.uint("value"));
    }

    @Test
    @Order(72)
    @DisplayName("[events] getLogs returns Transfer events")
    void getLogs() throws Exception {
        skipIfNoToken();
        long latest = client.getBlockNumber().join();
        String t0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        var logs = client.getLogs("0x0", Hex.fromLong(latest), tokenAddr, List.of(t0)).join();
        assertFalse(logs.isEmpty());
    }

    // ══════════════════════ 10. MULTICALL3 (empty) ════════════════════════════

    @Test
    @Order(80)
    @DisplayName("[multicall] Multicall3.ADDRESS is canonical")
    void multicallAddress() {
        assertEquals("0xcA11bde05977b3631167028862bE2a173976CA11", Multicall3.ADDRESS);
    }

    @Test
    @Order(81)
    @DisplayName("[multicall] empty execute = [] (no RPC)")
    void multicallEmpty() {
        assertTrue(new Multicall3(client).execute().join().isEmpty());
    }

    // ══════════════════════ 11. ADVANCED ══════════════════════════════════════

    @Test
    @Order(90)
    @DisplayName("[advanced] eth_getProof returns proof structure")
    void getProof() {
        var proof = client.getProof(BesuContainer.DEV_ADDRESS, List.of(), "latest").join();
        assertNotNull(proof);
        assertNotNull(proof.get("address"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    Contract contract() {
        return new Contract(tokenAddr, client);
    }

    void skipIfNoToken() {
        assumeTrue(
                tokenAddr != null,
                "Token not deployed — run tests in order (deployToken runs first)");
    }

    String sendTx(Wallet from, String to, BigInteger value, String data, BigInteger gasOverride) {
        long nonce = client.getTransactionCount(from.getAddress()).join();
        long chainId = client.getChainId().join();
        BigInteger tip = client.getMaxPriorityFeePerGas().join();
        var blk = client.getBlock("latest").join();
        BigInteger base = blk.baseFeePerGas != null ? blk.baseFeePerGas : BigInteger.ZERO;
        BigInteger maxFee = base.multiply(BigInteger.TWO).add(tip).add(BigInteger.ONE);
        BigInteger gas =
                gasOverride != null
                        ? gasOverride
                        : client.estimateGas(
                                        EthModels.CallRequest.builder()
                                                .from(from.getAddress())
                                                .to(to)
                                                .value(value)
                                                .data(data)
                                                .build())
                                .join()
                                .multiply(BigInteger.valueOf(130))
                                .divide(BigInteger.valueOf(100));
        var tx =
                EthModels.TransactionRequest.builder()
                        .from(from.getAddress())
                        .to(to)
                        .value(value)
                        .gas(gas)
                        .maxFeePerGas(maxFee)
                        .maxPriorityFeePerGas(tip)
                        .nonce(nonce)
                        .chainId(chainId)
                        .data(data)
                        .build();
        return client.sendRawTransaction(TransactionSigner.signEip1559(tx, from)).join();
    }
}
