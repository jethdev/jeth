/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.abi.AbiDecodeError;
import io.jeth.model.EthModels;
import io.jeth.model.RpcModels;
import io.jeth.middleware.MiddlewareProvider;
import io.jeth.provider.HttpProvider;
import io.jeth.provider.Provider;
import io.jeth.util.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main Ethereum JSON-RPC client. Start here.
 *
 * <p>Wraps the standard {@code eth_*} / {@code net_*} / {@code debug_*} JSON-RPC
 * methods as typed, non-blocking {@link CompletableFuture} calls. Network I/O
 * is delegated to a {@link io.jeth.provider.Provider}, so you can swap in
 * middleware (retry, cache, fallback) without changing call sites.
 *
 * <pre>
 * // Plain HTTP
 * var client = EthClient.of("https://mainnet.infura.io/v3/KEY");
 * var client = EthClient.of(Chain.BASE.publicRpc());
 *
 * // Production: retry + cache + fallback
 * var client = EthClient.of(
 *     MiddlewareProvider.wrap(HttpProvider.of(url))
 *         .withRetry(3, Duration.ofMillis(500))
 *         .withCache(Duration.ofSeconds(12))
 *         .build());
 *
 * BigInteger balance = client.getBalance("0xAddress").join();
 * var block          = client.getBlock("latest").join();
 * String txHash      = client.sendRawTransaction(signed).join();
 * var receipt        = client.waitForTransaction(txHash).join();
 * System.out.println("Gas used: " + receipt.gasUsed);
 * </pre>
 *
 * <p>All methods return {@link CompletableFuture}. Use {@code .join()} for
 * synchronous code, or chain with {@code .thenCompose} / {@code .thenApply}
 * for async pipelines.
 *
 * @see io.jeth.core.Chain
 * @see MiddlewareProvider
 * @see io.jeth.ws.WsProvider
 */
public class EthClient implements AutoCloseable {

    private final Provider      provider;
    private final ObjectMapper  mapper;

    public EthClient(Provider provider) {
        this.provider = provider;
        this.mapper   = provider.getObjectMapper();
    }

    /**
     * Create a client backed by a plain HTTP provider.
     *
     * @param rpcUrl full URL including API key, e.g. {@code https://mainnet.infura.io/v3/KEY}
     */
    public static EthClient of(String rpcUrl)     { return new EthClient(HttpProvider.of(rpcUrl)); }

    /**
     * Create a client from any {@link io.jeth.provider.Provider} — use this to
     * attach middleware ({@link MiddlewareProvider}) or a
     * WebSocket provider ({@link io.jeth.ws.WsProvider}).
     *
     * @param provider the backing provider
     */
    public static EthClient of(Provider provider) { return new EthClient(provider); }

    // ─── Network ──────────────────────────────────────────────────────────────

    /** Returns the chain ID (EIP-155). Cached by {@link MiddlewareProvider} when enabled. */
    public CompletableFuture<Long>    getChainId()      { return hexLong("eth_chainId"); }

    /** Returns the current highest block number known to the node. */
    public CompletableFuture<Long>    getBlockNumber()  { return hexLong("eth_blockNumber"); }

    /** Returns the network ID as a string (same value as chainId for most networks). */
    public CompletableFuture<String>  getNetworkId()    { return text("net_version"); }
    public CompletableFuture<Boolean> isSyncing()       {
        return send("eth_syncing", List.of())
                .thenApply(r -> { check(r); return r.result != null && !r.result.isNull() && (!r.result.isBoolean() || r.result.asBoolean()); });
    }

    // ─── Accounts ─────────────────────────────────────────────────────────────

    /**
     * Returns the ETH balance of {@code address} at the given block in wei.
     *
     * @param address EIP-55 checksum or lowercase 0x address
     * @param block   block tag ({@code "latest"}, {@code "earliest"}, {@code "pending"})
     *                or a hex block number ({@code "0x12c34f"})
     */
    public CompletableFuture<BigInteger> getBalance(String address, String block) { return hexBig("eth_getBalance", address, block); }

    /** Returns the ETH balance at {@code "latest"}. See {@link #getBalance(String, String)}. */
    public CompletableFuture<BigInteger> getBalance(String address)               { return getBalance(address, "latest"); }

    /**
     * Returns the transaction count (nonce) of {@code address} at the given block.
     * Use this value as the {@code nonce} field when building a new transaction.
     *
     * @param block {@code "latest"} for confirmed nonce, {@code "pending"} to include
     *              transactions already in the mempool
     */
    public CompletableFuture<Long>       getTransactionCount(String address, String block) { return hexLong("eth_getTransactionCount", address, block); }

    /** Returns the nonce at {@code "latest"}. See {@link #getTransactionCount(String, String)}. */
    public CompletableFuture<Long>       getTransactionCount(String address)       { return getTransactionCount(address, "latest"); }

    /**
     * Returns the deployed bytecode at {@code address}. Returns {@code "0x"} for EOAs.
     *
     * @param block block tag or hex number — use {@code "latest"} unless you need historical state
     */
    public CompletableFuture<String>     getCode(String address, String block)     { return text("eth_getCode", address, block); }

    /** Returns the bytecode at {@code "latest"}. See {@link #getCode(String, String)}. */
    public CompletableFuture<String>     getCode(String address)                   { return getCode(address, "latest"); }

    /**
     * Reads a raw 32-byte storage slot from a contract (eth_getStorageAt).
     * For structured reads that follow Solidity layout rules (mappings, arrays),
     * use {@link io.jeth.storage.StorageLayout} instead.
     *
     * @param slot 256-bit storage slot index
     */
    public CompletableFuture<String>     getStorageAt(String address, BigInteger slot, String block) { return text("eth_getStorageAt", address, Hex.fromBigInteger(slot), block); }

    /** Reads a storage slot at {@code "latest"}. See {@link #getStorageAt(String, BigInteger, String)}. */
    public CompletableFuture<String>     getStorageAt(String address, BigInteger slot)               { return getStorageAt(address, slot, "latest"); }

    /**
     * Returns {@code true} if {@code address} has deployed code (is a contract).
     * Internally calls {@link #getCode(String)} and checks length > 2 (i.e. not {@code "0x"}).
     */
    public CompletableFuture<Boolean>    isContract(String address)               { return getCode(address).thenApply(c -> c != null && c.length() > 2); }

    // ─── Blocks ───────────────────────────────────────────────────────────────

    public CompletableFuture<EthModels.Block> getBlock(String tag, boolean full)     { return parse("eth_getBlockByNumber", EthModels.Block.class, tag, full); }
    public CompletableFuture<EthModels.Block> getBlock(String tag)                   { return getBlock(tag, false); }
    public CompletableFuture<EthModels.Block> getBlockByNumber(long n, boolean full) { return getBlock(Hex.fromLong(n), full); }
    public CompletableFuture<EthModels.Block> getBlockByNumber(long n)               { return getBlockByNumber(n, false); }
    public CompletableFuture<EthModels.Block> getBlockByHash(String hash, boolean full) { return parse("eth_getBlockByHash", EthModels.Block.class, hash, full); }
    public CompletableFuture<EthModels.Block> getBlockByHash(String hash)            { return getBlockByHash(hash, false); }

    public CompletableFuture<List<EthModels.TransactionReceipt>> getBlockReceipts(String tag) {
        return send("eth_getBlockReceipts", List.of(tag)).thenApply(r -> {
            check(r);
            var list = new ArrayList<EthModels.TransactionReceipt>();
            if (r.result != null && r.result.isArray())
                r.result.forEach(n -> list.add(mapper.convertValue(n, EthModels.TransactionReceipt.class)));
            return list;
        });
    }

    // ─── Transactions ─────────────────────────────────────────────────────────

    /**
     * Returns the transaction by hash, or {@code null} if not yet indexed.
     * Does not include execution results — for that, use {@link #getTransactionReceipt(String)}.
     */
    public CompletableFuture<EthModels.Transaction>        getTransaction(String hash)        { return parse("eth_getTransactionByHash", EthModels.Transaction.class, hash); }

    /**
     * Returns the receipt of a mined transaction, or {@code null} if not yet mined.
     * The receipt includes {@code status} (1 = success, 0 = reverted), {@code gasUsed},
     * and emitted {@code logs}.
     *
     * <p>To wait for mining with polling, use {@link #waitForTransaction(String)}.
     */
    public CompletableFuture<EthModels.TransactionReceipt> getTransactionReceipt(String hash) { return parse("eth_getTransactionReceipt", EthModels.TransactionReceipt.class, hash); }

    public CompletableFuture<String> sendRawTransaction(String hex) {
        return send("eth_sendRawTransaction", List.of(hex)).thenApply(r -> {
            if (r.hasError()) {
                String revertData = r.revertData();
                String msg = r.errorMessage() != null ? r.errorMessage() : "sendRawTransaction failed";
                if (revertData != null) {
                    msg += " | " + AbiDecodeError.decode(revertData);
                    throw new EthException(msg, revertData);
                }
                throw new EthException(msg);
            }
            return r.resultAsText();
        });
    }

    // ─── Unlocked account methods (Hardhat / dev nodes) ─────────────────────────

    /**
     * Send an unsigned transaction via eth_sendTransaction.
     * Only works on nodes with unlocked accounts (Hardhat, Ganache, dev Geth).
     * For production, use {@link #sendRawTransaction(String)} with a signed tx.
     */
    public CompletableFuture<String> sendTransaction(EthModels.CallRequest req) {
        return send("eth_sendTransaction", List.of(req)).thenApply(r -> {
            check(r); return r.resultAsText();
        });
    }

    /**
     * Returns the list of accounts available on the node ({@code eth_accounts}).
     *
     * <p>Returns unlocked accounts on Hardhat / Ganache dev nodes.
     * Production RPC nodes (Infura, Alchemy) always return an empty list —
     * they do not expose private keys.
     *
     * @return list of 0x addresses, or empty list on production nodes
     */
    public CompletableFuture<List<String>> getAccounts() {
        return send("eth_accounts", List.of()).thenApply(r -> {
            check(r);
            var list = new ArrayList<String>();
            if (r.result != null && r.result.isArray())
                r.result.forEach(n -> list.add(n.asText()));
            return list;
        });
    }

    /**
     * Get current network info: chainId + name (from Chain enum if known).
     * Equivalent to ethers.js provider.getNetwork().
     */
    public CompletableFuture<NetworkInfo> getNetwork() {
        return getChainId().thenApply(chainId ->
                new NetworkInfo(chainId, Chain.fromId(chainId)
                        .map(Chain::getName)
                        .orElse("unknown")));
    }

    /** Network information returned by {@link #getNetwork()}. */
    public record NetworkInfo(long chainId, String name) {
        @Override public String toString() { return name + " (chainId=" + chainId + ")"; }
    }

    // ─── Wait ─────────────────────────────────────────────────────────────────

    /**
     * Polls until a transaction is confirmed or {@code timeoutMs} elapses.
     *
     * <p>Uses a non-blocking delayed-executor loop — does not block a thread while waiting.
     *
     * @param txHash    transaction hash to watch
     * @param timeoutMs how long to wait before failing, in milliseconds
     * @param pollMs    how often to poll {@code eth_getTransactionReceipt}
     * @throws io.jeth.core.EthException if the timeout is exceeded
     */
    public CompletableFuture<EthModels.TransactionReceipt> waitForTransaction(String txHash, long timeoutMs, long pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        return pollReceipt(txHash, deadline, pollMs);
    }

    /**
     * Waits up to 120 seconds, polling every 2 seconds.
     * See {@link #waitForTransaction(String, long, long)} for custom timeouts.
     */
    public CompletableFuture<EthModels.TransactionReceipt> waitForTransaction(String txHash) {
        return waitForTransaction(txHash, 120_000, 2_000);
    }

    private CompletableFuture<EthModels.TransactionReceipt> pollReceipt(String hash, long deadline, long pollMs) {
        return getTransactionReceipt(hash).thenCompose(r -> {
            if (r != null) return CompletableFuture.completedFuture(r);
            if (System.currentTimeMillis() > deadline)
                return CompletableFuture.failedFuture(
                        new EthException("Timeout waiting for tx: " + hash));
            // Correct non-blocking delay: schedule empty task then recurse
            return CompletableFuture
                    .supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(pollMs, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> pollReceipt(hash, deadline, pollMs));
        });
    }

    // ─── Contract calls ───────────────────────────────────────────────────────

    public CompletableFuture<String> call(EthModels.CallRequest req, String block) {
        return send("eth_call", Arrays.asList(req, block)).thenApply(r -> {
            if (r.hasError()) {
                String revert = r.revertData();
                String msg    = r.errorMessage() + (revert != null ? " | " + AbiDecodeError.decode(revert) : "");
                throw new EthException(msg, revert);
            }
            return r.resultAsText();
        });
    }

    public CompletableFuture<String>     call(EthModels.CallRequest req)        { return call(req, "latest"); }
    public CompletableFuture<BigInteger> estimateGas(EthModels.CallRequest req) { return hexBig("eth_estimateGas", req); }

    // ─── Gas & Fees ───────────────────────────────────────────────────────────

    /** Returns the legacy gas price in wei (pre-EIP-1559). Prefer EIP-1559 fees for new transactions. */
    public CompletableFuture<BigInteger>          getGasPrice()             { return hexBig("eth_gasPrice"); }

    /**
     * Returns the current suggested priority fee (tip) per gas in wei.
     * Used as {@code maxPriorityFeePerGas} in EIP-1559 transactions.
     */
    public CompletableFuture<BigInteger>          getMaxPriorityFeePerGas() { return hexBig("eth_maxPriorityFeePerGas"); }

    /**
     * Returns the current blob base fee in wei (EIP-4844 / Dencun).
     * Required for sizing {@code maxFeePerBlobGas} in type-3 blob transactions.
     *
     * @see io.jeth.eip4844.BlobTransaction
     */
    public CompletableFuture<BigInteger>          getBlobBaseFee()          { return hexBig("eth_blobBaseFee"); }
    public CompletableFuture<EthModels.FeeHistory> getFeeHistory(int blocks, String newest, List<Integer> pcts) {
        return parse("eth_feeHistory", EthModels.FeeHistory.class, Hex.fromLong(blocks), newest, pcts);
    }

    /** Suggest EIP-1559 fees: returns [maxFeePerGas, maxPriorityFeePerGas] in wei. */
    public CompletableFuture<long[]> suggestEip1559Fees() {
        return getBlock("latest").thenCompose(block ->
                getMaxPriorityFeePerGas().thenApply(tip -> {
                    BigInteger base   = block.baseFeePerGas != null ? block.baseFeePerGas : BigInteger.ZERO;
                    BigInteger maxFee = base.multiply(BigInteger.TWO).add(tip);
                    return new long[]{maxFee.longValue(), tip.longValue()};
                }));
    }

    // ─── Logs ─────────────────────────────────────────────────────────────────

    /**
     * Fetch logs matching a filter. Automatically chunks large block ranges to avoid
     * "response size exceeded" errors from RPC nodes.
     *
     * Chunking: if the range spans more than {@code MAX_LOG_CHUNK} blocks, the range
     * is split into sequential sub-requests and results are merged. Default chunk = 2000.
     */
    public static final int MAX_LOG_CHUNK = 2_000;

    public CompletableFuture<List<EthModels.Log>> getLogs(Map<String, Object> filter) {
        // Extract block range for auto-chunking
        String from = filter.containsKey("fromBlock") ? filter.get("fromBlock").toString() : null;
        String to   = filter.containsKey("toBlock")   ? filter.get("toBlock").toString()   : null;

        // Only chunk when both bounds are numeric hex block numbers (not "latest", "pending")
        if (from != null && to != null && from.startsWith("0x") && to.startsWith("0x")) {
            long fromN = Hex.toBigInteger(from).longValue();
            long toN   = Hex.toBigInteger(to).longValue();
            if (toN - fromN > MAX_LOG_CHUNK)
                return getLogsChunked(filter, fromN, toN);
        }
        return getLogsRaw(filter);
    }

    /** Fetch logs without auto-chunking. Use when you control the block range. */
    public CompletableFuture<List<EthModels.Log>> getLogsRaw(Map<String, Object> filter) {
        return send("eth_getLogs", List.of(filter)).thenApply(r -> {
            check(r);
            var list = new ArrayList<EthModels.Log>();
            if (r.result != null && r.result.isArray())
                r.result.forEach(n -> list.add(mapper.convertValue(n, EthModels.Log.class)));
            return list;
        });
    }

    public CompletableFuture<List<EthModels.Log>> getLogs(
            String fromBlock, String toBlock, String address, List<String> topics) {
        var f = new LinkedHashMap<String, Object>();
        if (fromBlock != null) f.put("fromBlock", fromBlock);
        if (toBlock   != null) f.put("toBlock",   toBlock);
        if (address   != null) f.put("address",   address);
        if (topics    != null) f.put("topics",    topics);
        return getLogs(f);
    }

    /**
     * Recursively chunk a large log query into MAX_LOG_CHUNK-sized slices.
     * Executes chunks sequentially to respect node rate limits.
     */
    private CompletableFuture<List<EthModels.Log>> getLogsChunked(
            Map<String, Object> baseFilter, long fromN, long toN) {

        List<EthModels.Log> accumulated = new ArrayList<>();
        return getLogsChunkLoop(baseFilter, fromN, toN, accumulated);
    }

    private CompletableFuture<List<EthModels.Log>> getLogsChunkLoop(
            Map<String, Object> baseFilter, long cursor, long toN,
            List<EthModels.Log> accumulated) {

        long chunkEnd = Math.min(cursor + MAX_LOG_CHUNK - 1, toN);
        var chunkFilter = new LinkedHashMap<>(baseFilter);
        chunkFilter.put("fromBlock", Hex.fromLong(cursor));
        chunkFilter.put("toBlock",   Hex.fromLong(chunkEnd));

        return getLogsRaw(chunkFilter).thenCompose(chunk -> {
            accumulated.addAll(chunk);
            if (chunkEnd >= toN) return CompletableFuture.completedFuture(accumulated);
            return getLogsChunkLoop(baseFilter, chunkEnd + 1, toN, accumulated);
        });
    }

    // ─── Debug / Advanced ─────────────────────────────────────────────────────

    public CompletableFuture<JsonNode> getProof(String address, List<String> keys, String block) {
        return rawResult("eth_getProof", List.of(address, keys, block));
    }

    public CompletableFuture<JsonNode> traceTransaction(String txHash, Map<String, Object> opts) {
        return rawResult("debug_traceTransaction", List.of(txHash, opts));
    }

    public CompletableFuture<JsonNode> traceTransaction(String txHash) {
        return traceTransaction(txHash, Map.of());
    }

    public CompletableFuture<JsonNode> traceCall(EthModels.CallRequest req, String block, Map<String, Object> opts) {
        return rawResult("debug_traceCall", List.of(req, block, opts));
    }

    public CompletableFuture<JsonNode> createAccessList(EthModels.CallRequest req, String block) {
        return rawResult("eth_createAccessList", List.of(req, block));
    }

    /** eth_call with state override (for simulation). */
    public CompletableFuture<String> callWithOverride(
            EthModels.CallRequest req, String block, Map<String, Object> stateOverride) {
        return send("eth_call", Arrays.asList(req, block, stateOverride)).thenApply(r -> {
            check(r); return r.resultAsText();
        });
    }

    // ─── Raw RPC ──────────────────────────────────────────────────────────────

    public CompletableFuture<RpcModels.RpcResponse> send(String method, List<?> params) {
        return provider.send(new RpcModels.RpcRequest(method, params));
    }

    public Provider     getProvider()      { return provider; }
    public ObjectMapper getObjectMapper()  { return mapper; }

    @Override public void close() { provider.close(); }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private CompletableFuture<String>     text(String m, Object... p)            { return send(m, asList(p)).thenApply(r -> { check(r); return r.resultAsText(); }); }
    private CompletableFuture<Long>       hexLong(String m, Object... p)         { return text(m, p).thenApply(h -> h == null ? 0L : Hex.toBigInteger(h).longValue()); }
    private CompletableFuture<BigInteger> hexBig(String m, Object... p)          { return text(m, p).thenApply(h -> h == null ? BigInteger.ZERO : Hex.toBigInteger(h)); }
    private <T> CompletableFuture<T>      parse(String m, Class<T> t, Object... p){ return send(m, asList(p)).thenApply(r -> { check(r); return r.result == null || r.result.isNull() ? null : mapper.convertValue(r.result, t); }); }
    private CompletableFuture<JsonNode>   rawResult(String m, List<?> p)         { return send(m, p).thenApply(r -> { check(r); return r.result; }); }

    private void check(RpcModels.RpcResponse r) {
        if (r.hasError()) {
            String msg = r.errorMessage();
            String revert = r.revertData();
            if (revert != null) msg += " | " + AbiDecodeError.decode(revert);
            throw new EthException(msg != null ? msg : "RPC error", revert);
        }
    }

    private List<Object> asList(Object[] p) { return p.length == 0 ? List.of() : Arrays.asList(p); }
}
