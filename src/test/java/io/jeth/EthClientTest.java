/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EthClient unit tests — all calls backed by RpcMock, no network required. */
class EthClientTest {

    // ─── Network ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getChainId parses hex response")
    void chainId() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(1L);
            assertEquals(1L, rpc.client().getChainId().join());
        }
    }

    @Test
    @DisplayName("getBlockNumber parses hex response")
    void blockNumber() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(18_000_000L);
            assertEquals(18_000_000L, rpc.client().getBlockNumber().join());
        }
    }

    @Test
    @DisplayName("getNetworkId returns string")
    void networkId() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("1");
            assertEquals("1", rpc.client().getNetworkId().join());
        }
    }

    // ─── Accounts ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBalance returns wei amount")
    void balance() throws Exception {
        try (var rpc = new RpcMock()) {
            BigInteger oneEth = new BigInteger("1000000000000000000");
            rpc.enqueueHex(oneEth);
            assertEquals(
                    oneEth,
                    rpc.client().getBalance("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").join());
        }
    }

    @Test
    @DisplayName("getBalance with block tag sends both params")
    void balanceWithBlock() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(42L);
            var client = rpc.client();
            client.getBalance("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", "0x1000000").join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("0x1000000"), "block tag must be in params");
        }
    }

    @Test
    @DisplayName("getTransactionCount (nonce)")
    void nonce() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(7L);
            assertEquals(
                    7L,
                    rpc.client()
                            .getTransactionCount("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
                            .join());
        }
    }

    @Test
    @DisplayName("getCode returns hex bytecode")
    void getCode() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("6080604052");
            assertEquals(
                    "6080604052",
                    rpc.client().getCode("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48").join());
        }
    }

    @Test
    @DisplayName("isContract true when code non-empty")
    void isContractTrue() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("6080604052");
            assertTrue(rpc.client().isContract("0xContract").join());
        }
    }

    @Test
    @DisplayName("isContract false for EOA (empty bytecode)")
    void isContractFalse() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0x");
            assertFalse(rpc.client().isContract("0xEOA").join());
        }
    }

    @Test
    @DisplayName("getGasPrice parses hex")
    void gasPrice() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(BigInteger.valueOf(30_000_000_000L));
            assertEquals(30_000_000_000L, rpc.client().getGasPrice().join().longValue());
        }
    }

    // ─── Blocks ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBlock parses all standard fields")
    void getBlock() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    """
                {
                  "hash":"0xdeadbeef","number":"0x112a880","timestamp":"0x65000000",
                  "gasLimit":"0x1c9c380","gasUsed":"0xd28840",
                  "baseFeePerGas":"0x4a817c800",
                  "miner":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                  "transactions":[]
                }""");
            var block = rpc.client().getBlock("latest").join();
            assertNotNull(block);
            assertEquals("0xdeadbeef", block.hash);
            assertEquals(18_000_000L, block.number);
            assertEquals(new BigInteger("20000000000"), block.baseFeePerGas);
            assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", block.miner);
        }
    }

    @Test
    @DisplayName("getBlock returns null for unknown block")
    void getBlockNull() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueNull();
            assertNull(rpc.client().getBlock("0x999999").join());
        }
    }

    @Test
    @DisplayName("getBlockByNumber sends correct number param")
    void getBlockByNumber() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "{\"hash\":\"0xabc\",\"number\":\"0x5\",\"timestamp\":\"0x0\",\"gasLimit\":\"0x0\",\"gasUsed\":\"0x0\",\"transactions\":[]}");
            rpc.client().getBlockByNumber(5L).join();
            assertTrue(rpc.takeRequest().getBody().readUtf8().contains("0x5"));
        }
    }

    // ─── Transactions ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTransaction parses all EIP-1559 fields")
    void getTransaction() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    """
                {
                  "hash":"0xtxhash","from":"0xfrom","to":"0xto",
                  "value":"0x0","nonce":"0x5","gas":"0x5208",
                  "maxFeePerGas":"0x6fc23ac00","maxPriorityFeePerGas":"0x3b9aca00",
                  "blockNumber":"0x112a880","type":"0x2"
                }""");
            var tx = rpc.client().getTransaction("0xtxhash").join();
            assertNotNull(tx);
            assertEquals("0xtxhash", tx.hash);
            assertEquals(5L, tx.nonce);
            assertEquals(2L, tx.type);
            assertEquals(new BigInteger("30000000000"), tx.maxFeePerGas);
        }
    }

    @Test
    @DisplayName("getTransactionReceipt parses status and gasCostWei")
    void getReceipt() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    """
                {
                  "transactionHash":"0xtxhash","status":"0x1",
                  "blockNumber":"0x1","gasUsed":"0x5208",
                  "effectiveGasPrice":"0x77359400",
                  "from":"0xfrom","to":"0xto","logs":[]
                }""");
            var r = rpc.client().getTransactionReceipt("0xtxhash").join();
            assertNotNull(r);
            assertTrue(r.isSuccess());
            assertEquals(21_000L, r.gasUsed.longValue());
            // gasCostWei = 21000 * 2_000_000_000 = 42_000_000_000_000
            assertEquals(new BigInteger("42000000000000"), r.gasCostWei());
        }
    }

    @Test
    @DisplayName("getTransactionReceipt status 0 = failed")
    void getReceiptFailed() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "{\"transactionHash\":\"0xtx\",\"status\":\"0x0\",\"blockNumber\":\"0x1\",\"gasUsed\":\"0x5208\",\"logs\":[]}");
            assertFalse(rpc.client().getTransactionReceipt("0xtx").join().isSuccess());
        }
    }

    @Test
    @DisplayName("getTransactionReceipt null = pending/unknown")
    void getReceiptNull() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueNull();
            assertNull(rpc.client().getTransactionReceipt("0xpending").join());
        }
    }

    @Test
    @DisplayName("sendRawTransaction returns hash")
    void sendRawTx() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueStr("0xabcdef1234567890");
            assertEquals("0xabcdef1234567890", rpc.client().sendRawTransaction("0xf802...").join());
        }
    }

    // ─── eth_call ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("call returns raw hex result")
    void ethCall() throws Exception {
        try (var rpc = new RpcMock()) {
            // balanceOf returning 1_000_000
            rpc.enqueueStr("0x00000000000000000000000000000000000000000000000000000000000f4240");
            var req =
                    EthModels.CallRequest.builder()
                            .to("0xToken")
                            .data("0x70a08231" + "0".repeat(64))
                            .build();
            String result = rpc.client().call(req).join();
            assertEquals(new BigInteger("1000000"), Hex.toBigInteger(result));
        }
    }

    @Test
    @DisplayName("estimateGas returns gas as BigInteger")
    void estimateGas() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueHex(65000L);
            var req = EthModels.CallRequest.builder().to("0xToken").data("0xa9059cbb").build();
            assertEquals(BigInteger.valueOf(65000), rpc.client().estimateGas(req).join());
        }
    }

    // ─── Logs ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getLogs parses log array and matchesTopic0")
    void getLogs() throws Exception {
        String topic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "[{\"address\":\"0xToken\",\"topics\":[\""
                            + topic0
                            + "\",\"0x0\",\"0x0\"],"
                            + "\"data\":\"0x00000000000000000000000000000000000000000000000000000000000f4240\","
                            + "\"blockNumber\":\"0x1\",\"transactionHash\":\"0xtx\",\"logIndex\":\"0x0\",\"transactionIndex\":\"0x0\"}]");
            var logs = rpc.client().getLogs("0x0", "latest", "0xToken", null).join();
            assertEquals(1, logs.size());
            assertTrue(logs.get(0).matchesTopic0(topic0));
        }
    }

    @Test
    @DisplayName("getLogs empty array")
    void getLogsEmpty() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue("[]");
            assertTrue(rpc.client().getLogs("0x0", "latest", "0xToken", null).join().isEmpty());
        }
    }

    // ─── suggestEip1559Fees ───────────────────────────────────────────────────

    @Test
    @DisplayName("suggestEip1559Fees returns [maxFee, tip] both positive")
    void suggestFees() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "{\"hash\":\"0xabc\",\"number\":\"0x1\",\"timestamp\":\"0x0\","
                            + "\"baseFeePerGas\":\"0x12a05f200\",\"gasLimit\":\"0x1c9c380\",\"gasUsed\":\"0x0\",\"transactions\":[]}");
            rpc.enqueueHex(BigInteger.valueOf(1_000_000_000L)); // tip 1 gwei
            long[] fees = rpc.client().suggestEip1559Fees().join();
            assertEquals(2, fees.length);
            assertTrue(fees[0] > 0, "maxFeePerGas must be positive");
            assertTrue(fees[1] > 0, "maxPriorityFeePerGas must be positive");
            assertTrue(fees[0] >= fees[1], "maxFee must be >= tip");
        }
    }

    // ─── getFeeHistory ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFeeHistory parses reward array")
    void feeHistory() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(
                    "{\"oldestBlock\":\"0x1\",\"baseFeePerGas\":[\"0x4a817c800\"],"
                            + "\"gasUsedRatio\":[0.85],\"reward\":[[\"0x3b9aca00\",\"0x77359400\"]]}");
            var history = rpc.client().getFeeHistory(1, "latest", List.of(25, 75)).join();
            assertNotNull(history);
            assertFalse(history.reward.isEmpty());
            assertEquals(2, history.reward.get(0).size());
        }
    }
}
