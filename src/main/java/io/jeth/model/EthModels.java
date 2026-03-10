/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.jeth.model.deserializer.HexBigIntegerDeserializer;
import io.jeth.model.deserializer.HexLongDeserializer;
import java.math.BigInteger;
import java.util.List;

/** Core Ethereum domain models. All hex fields auto-decoded via Jackson deserializers. */
public class EthModels {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Block {
        @JsonProperty("hash")
        public String hash;

        @JsonProperty("parentHash")
        public String parentHash;

        @JsonProperty("number")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long number;

        @JsonProperty("timestamp")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long timestamp;

        @JsonProperty("nonce")
        public String nonce;

        @JsonProperty("difficulty")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger difficulty;

        @JsonProperty("gasLimit")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger gasLimit;

        @JsonProperty("gasUsed")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger gasUsed;

        @JsonProperty("baseFeePerGas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger baseFeePerGas;

        @JsonProperty("blobGasUsed")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger blobGasUsed;

        @JsonProperty("excessBlobGas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger excessBlobGas;

        @JsonProperty("miner")
        public String miner;

        @JsonProperty("extraData")
        public String extraData;

        @JsonProperty("size")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long size;

        @JsonProperty("transactions")
        public List<Object> transactions;

        @JsonProperty("transactionsRoot")
        public String transactionsRoot;

        @JsonProperty("stateRoot")
        public String stateRoot;

        @JsonProperty("receiptsRoot")
        public String receiptsRoot;

        @JsonProperty("logsBloom")
        public String logsBloom;

        @JsonProperty("sha3Uncles")
        public String sha3Uncles;

        @JsonProperty("uncles")
        public List<String> uncles;

        @JsonProperty("withdrawals")
        public List<Object> withdrawals; // EIP-4895

        @Override
        public String toString() {
            return "Block{number=" + number + ", hash='" + hash + "'}";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Transaction {
        @JsonProperty("hash")
        public String hash;

        @JsonProperty("blockHash")
        public String blockHash;

        @JsonProperty("blockNumber")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long blockNumber;

        @JsonProperty("transactionIndex")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long transactionIndex;

        @JsonProperty("from")
        public String from;

        @JsonProperty("to")
        public String to;

        @JsonProperty("value")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger value;

        @JsonProperty("gas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger gas;

        @JsonProperty("gasPrice")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger gasPrice;

        @JsonProperty("maxFeePerGas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger maxFeePerGas;

        @JsonProperty("maxPriorityFeePerGas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger maxPriorityFeePerGas;

        @JsonProperty("maxFeePerBlobGas")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger maxFeePerBlobGas;

        @JsonProperty("nonce")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long nonce;

        @JsonProperty("input")
        public String input;

        @JsonProperty("type")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long type;

        @JsonProperty("chainId")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long chainId;

        @JsonProperty("v")
        public String v;

        @JsonProperty("r")
        public String r;

        @JsonProperty("s")
        public String s;

        @JsonProperty("blobVersionedHashes")
        public List<String> blobVersionedHashes;

        @Override
        public String toString() {
            return "Transaction{hash='" + hash + "', from='" + from + "', to='" + to + "'}";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionReceipt {
        @JsonProperty("transactionHash")
        public String hash;

        @JsonProperty("blockHash")
        public String blockHash;

        @JsonProperty("blockNumber")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long blockNumber;

        @JsonProperty("transactionIndex")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long transactionIndex;

        @JsonProperty("from")
        public String from;

        @JsonProperty("to")
        public String to;

        @JsonProperty("contractAddress")
        public String contractAddress;

        @JsonProperty("status")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long status;

        @JsonProperty("gasUsed")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger gasUsed;

        @JsonProperty("cumulativeGasUsed")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger cumulativeGasUsed;

        @JsonProperty("effectiveGasPrice")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger effectiveGasPrice;

        @JsonProperty("blobGasUsed")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger blobGasUsed;

        @JsonProperty("blobGasPrice")
        @JsonDeserialize(using = HexBigIntegerDeserializer.class)
        public BigInteger blobGasPrice;

        @JsonProperty("logsBloom")
        public String logsBloom;

        @JsonProperty("logs")
        public List<Log> logs;

        @JsonProperty("type")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long type;

        public boolean isSuccess() {
            return status == 1L;
        }

        /** Compute total ETH cost: gasUsed × effectiveGasPrice */
        public BigInteger gasCostWei() {
            if (gasUsed == null || effectiveGasPrice == null) return BigInteger.ZERO;
            return gasUsed.multiply(effectiveGasPrice);
        }

        @Override
        public String toString() {
            return "TransactionReceipt{hash='"
                    + hash
                    + "', status="
                    + (isSuccess() ? "SUCCESS" : "FAILED")
                    + "}";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Log {
        @JsonProperty("address")
        public String address;

        @JsonProperty("blockHash")
        public String blockHash;

        @JsonProperty("blockNumber")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long blockNumber;

        @JsonProperty("transactionHash")
        public String transactionHash;

        @JsonProperty("transactionIndex")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long transactionIndex;

        @JsonProperty("logIndex")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long logIndex;

        @JsonProperty("topics")
        public List<String> topics;

        @JsonProperty("data")
        public String data;

        @JsonProperty("removed")
        public boolean removed;

        /** True if this log's topic0 matches the given event signature hash. */
        public boolean matchesTopic0(String topic0Hex) {
            return topics != null && !topics.isEmpty() && topic0Hex.equalsIgnoreCase(topics.get(0));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeeHistory {
        @JsonProperty("oldestBlock")
        @JsonDeserialize(using = HexLongDeserializer.class)
        public long oldestBlock;

        @JsonProperty("baseFeePerGas")
        public List<String> baseFeePerGas;

        @JsonProperty("gasUsedRatio")
        public List<Double> gasUsedRatio;

        @JsonProperty("reward")
        public List<List<String>> reward;
    }

    /** Used for eth_call and eth_estimateGas. */
    public static class CallRequest {
        @JsonProperty("from")
        public String from;

        @JsonProperty("to")
        public String to;

        @JsonProperty("gas")
        public String gas;

        @JsonProperty("value")
        public String value;

        @JsonProperty("data")
        public String data;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final CallRequest req = new CallRequest();

            public Builder from(String v) {
                req.from = v;
                return this;
            }

            public Builder to(String v) {
                req.to = v;
                return this;
            }

            public Builder gas(long v) {
                req.gas = "0x" + Long.toHexString(v);
                return this;
            }

            public Builder value(BigInteger v) {
                req.value = "0x" + v.toString(16);
                return this;
            }

            public Builder data(String v) {
                req.data = v;
                return this;
            }

            public CallRequest build() {
                return req;
            }
        }
    }

    /** EIP-1559 transaction request for signing and sending. */
    public static class TransactionRequest {
        public String from;
        public String to;
        public BigInteger value;
        public BigInteger gas;
        public BigInteger maxFeePerGas;
        public BigInteger maxPriorityFeePerGas;
        public long nonce;
        public long chainId;
        public String data;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final TransactionRequest tx = new TransactionRequest();

            public Builder from(String v) {
                tx.from = v;
                return this;
            }

            public Builder to(String v) {
                tx.to = v;
                return this;
            }

            public Builder value(BigInteger v) {
                tx.value = v;
                return this;
            }

            public Builder gas(BigInteger v) {
                tx.gas = v;
                return this;
            }

            public Builder maxFeePerGas(BigInteger v) {
                tx.maxFeePerGas = v;
                return this;
            }

            public Builder maxPriorityFeePerGas(BigInteger v) {
                tx.maxPriorityFeePerGas = v;
                return this;
            }

            public Builder nonce(long v) {
                tx.nonce = v;
                return this;
            }

            public Builder chainId(long v) {
                tx.chainId = v;
                return this;
            }

            public Builder data(String v) {
                tx.data = v;
                return this;
            }

            public TransactionRequest build() {
                return tx;
            }
        }
    }

    /** EIP-2930 access list entry. */
    public record AccessListEntry(String address, List<String> storageKeys) {}
}
