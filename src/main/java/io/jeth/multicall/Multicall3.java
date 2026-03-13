/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.multicall;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiDecodeError;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.core.EthClient;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Multicall3 — batch hundreds of contract reads into a SINGLE eth_call.
 *
 * <p>Reduces N RPC calls to 1. Canonical deployment on every major EVM chain.
 *
 * <pre>
 * var mc = new Multicall3(client);
 *
 * var balanceOf = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
 * var symbol    = Function.of("symbol").withReturns(AbiType.STRING);
 *
 * int i0 = mc.add("0xUSDC", balanceOf, "0xUser1");
 * int i1 = mc.add("0xUSDC", balanceOf, "0xUser2");
 * int i2 = mc.add("0xUSDC", symbol);
 *
 * List&lt;Object&gt; results = mc.execute().join();
 * BigInteger bal1 = (BigInteger) results.get(i0);
 * String sym      = (String)     results.get(i2);
 * </pre>
 *
 * Fluent builder API:
 *
 * <pre>
 * List&lt;BigInteger&gt; balances = Multicall3.builder(client)
 *     .call("0xUSDC", balanceOf, "0xAddr1")
 *     .call("0xUSDC", balanceOf, "0xAddr2")
 *     .executeAs(BigInteger.class).join();
 * </pre>
 */
public class Multicall3 {

    /** Canonical Multicall3 — same address on 50+ EVM chains. */
    public static final String ADDRESS = "0xcA11bde05977b3631167028862bE2a173976CA11";

    private final EthClient client;
    private final String address;
    private final List<Call> calls = new ArrayList<>();

    public Multicall3(EthClient client) {
        this(client, ADDRESS);
    }

    public Multicall3(EthClient client, String address) {
        this.client = client;
        this.address = address;
    }

    // ─── Queue ────────────────────────────────────────────────────────────────

    /**
     * Queues a required call. The entire batch reverts if this call fails.
     *
     * <p>Returns the index of this call in the result list, so you can retrieve the value after
     * {@link #execute()} without counting manually:
     *
     * <pre>
     * int i = mc.add("0xUSDC", balanceOf, "0xUser");
     * List&lt;Object&gt; results = mc.execute().join();
     * BigInteger bal = (BigInteger) results.get(i);
     * </pre>
     *
     * @param contractAddress the contract to call
     * @param function the ABI function definition
     * @param args call arguments (matched to function input types by position)
     * @return result index — use this to retrieve the value from the {@link #execute()} result list
     */
    public int add(String contractAddress, Function function, Object... args) {
        int idx = calls.size();
        calls.add(new Call(contractAddress, function, args, true));
        return idx;
    }

    /**
     * Queues an optional call. If this call reverts, its slot in the result list will be {@code
     * null} instead of propagating the revert to the whole batch.
     *
     * <p>Use for calls that might fail on some inputs (e.g. querying a contract that may not be
     * deployed on all chains, or a balance that may be zero and revert).
     *
     * @param contractAddress the contract to call
     * @param function the ABI function definition
     * @param args call arguments
     * @return result index
     */
    public int addOptional(String contractAddress, Function function, Object... args) {
        int idx = calls.size();
        calls.add(new Call(contractAddress, function, args, false));
        return idx;
    }

    public void clear() {
        calls.clear();
    }

    public int size() {
        return calls.size();
    }

    // ─── Execute ──────────────────────────────────────────────────────────────

    public CompletableFuture<List<Object>> execute() {
        return execute("latest");
    }

    public CompletableFuture<List<Object>> execute(String blockTag) {
        if (calls.isEmpty()) return CompletableFuture.completedFuture(List.of());
        List<Call> snap = List.copyOf(calls);
        return client.call(
                        EthModels.CallRequest.builder()
                                .to(address)
                                .data(encodeAggregate3(snap))
                                .build(),
                        blockTag)
                .thenApply(hex -> decodeResults(hex, snap));
    }

    /** Execute and get typed Result wrappers (success flag + value). */
    public CompletableFuture<List<Result>> executeWithResults() {
        return executeWithResults("latest");
    }

    public CompletableFuture<List<Result>> executeWithResults(String blockTag) {
        if (calls.isEmpty()) return CompletableFuture.completedFuture(List.of());
        List<Call> snap = List.copyOf(calls);
        return client.call(
                        EthModels.CallRequest.builder()
                                .to(address)
                                .data(encodeAggregate3(snap))
                                .build(),
                        blockTag)
                .thenApply(
                        hex -> {
                            List<Object> values = decodeResults(hex, snap);
                            List<Result> out = new ArrayList<>(values.size());
                            for (int i = 0; i < snap.size(); i++)
                                out.add(new Result(i, values.get(i), values.get(i) != null));
                            return out;
                        });
    }

    /**
     * Execute with per-call failure tolerance — equivalent to Multicall3's {@code tryAggregate}.
     *
     * <p>Unlike {@link #execute()} which throws if any required call fails, this method returns a
     * {@link TryResult} per call: each result has a {@code success} flag and either a decoded value
     * or a revert reason.
     *
     * <p>All calls are treated as "allow failure" regardless of how they were added.
     *
     * <pre>
     * mc.add("0xUSDC",   balanceOf, user1);  // might revert (e.g. non-existent account)
     * mc.add("0xInvalid", symbol);           // will revert
     *
     * mc.tryExecute().join().forEach(r -> {
     *     if (r.success()) System.out.println("Value: " + r.value());
     *     else             System.out.println("Failed: " + r.revertReason());
     * });
     * </pre>
     */
    public CompletableFuture<List<TryResult>> tryExecute() {
        return tryExecute("latest");
    }

    public CompletableFuture<List<TryResult>> tryExecute(String blockTag) {
        if (calls.isEmpty()) return CompletableFuture.completedFuture(List.of());
        // Override all calls to allowFailure=true
        List<Call> permissive =
                calls.stream()
                        .map(c -> new Call(c.address(), c.function(), c.args(), false))
                        .toList();

        return client.call(
                        EthModels.CallRequest.builder()
                                .to(address)
                                .data(encodeAggregate3(permissive))
                                .build(),
                        blockTag)
                .thenApply(hex -> decodeTryResults(hex, permissive));
    }

    private List<TryResult> decodeTryResults(String hexResult, List<Call> snap) {
        byte[] data = Hex.decode(hexResult);
        int n = (int) new BigInteger(1, Arrays.copyOfRange(data, 32, 64)).longValue();
        List<TryResult> out = new ArrayList<>(n);

        for (int i = 0; i < n && i < snap.size(); i++) {
            int elemOff =
                    64
                            + (int)
                                    new BigInteger(
                                                    1,
                                                    Arrays.copyOfRange(
                                                            data, 64 + i * 32, 96 + i * 32))
                                            .longValue();
            boolean success = data[elemOff + 31] != 0;
            int bytesLen =
                    (int)
                            new BigInteger(1, Arrays.copyOfRange(data, elemOff + 32, elemOff + 64))
                                    .longValue();

            if (success && bytesLen > 0) {
                byte[] ret = Arrays.copyOfRange(data, elemOff + 64, elemOff + 64 + bytesLen);
                try {
                    AbiType[] types = snap.get(i).function().getOutputTypes();
                    Object value;
                    if (types != null && types.length > 0) {
                        Object[] dec = AbiCodec.decode(types, ret);
                        value = dec.length == 1 ? dec[0] : dec;
                    } else {
                        value = ret;
                    }
                    out.add(new TryResult(true, value, null));
                } catch (Exception e) {
                    out.add(new TryResult(false, null, "ABI decode failed: " + e.getMessage()));
                }
            } else if (!success && bytesLen > 0) {
                byte[] revertData = Arrays.copyOfRange(data, elemOff + 64, elemOff + 64 + bytesLen);
                String reason = AbiDecodeError.decode(Hex.encode(revertData));
                out.add(new TryResult(false, null, reason));
            } else {
                out.add(new TryResult(success, null, success ? null : "empty revert"));
            }
        }
        return out;
    }

    /**
     * Optional call result.
     *
     * @param success whether the call succeeded
     * @param value decoded result (if success)
     * @param revertReason revert reason (if failed)
     */
    public record TryResult(boolean success, Object value, String revertReason) {
        public <T> T as(Class<T> t) {
            if (!success) throw new IllegalStateException("Call failed: " + revertReason);
            return t.cast(value);
        }

        public <T> Optional<T> opt(Class<T> t) {
            return success && value != null ? Optional.of(t.cast(value)) : Optional.empty();
        }

        public boolean isNull() {
            return value == null;
        }

        @Override
        public String toString() {
            return "TryResult{" + (success ? "ok=" + value : "failed=" + revertReason) + "}";
        }
    }

    // ─── Static helpers ───────────────────────────────────────────────────────

    /**
     * Batches {@code eth_getBalance} for every address in a single Multicall3 call.
     *
     * @param client the RPC client
     * @param addrs list of addresses to query
     * @return wei balances in the same order as {@code addrs}
     */
    public static CompletableFuture<List<BigInteger>> getEthBalances(
            EthClient client, List<String> addrs) {
        var mc = new Multicall3(client);
        Function fn = Function.of("getEthBalance", AbiType.ADDRESS).withReturns(AbiType.UINT256);
        addrs.forEach(a -> mc.add(ADDRESS, fn, a));
        return mc.execute().thenApply(r -> r.stream().map(v -> (BigInteger) v).toList());
    }

    /**
     * Batches {@code balanceOf} for many addresses against one ERC-20 token.
     *
     * <p>Uses {@link #addOptional} so addresses with no balance (or non-ERC-20 contracts) return
     * {@code BigInteger.ZERO} instead of failing the batch.
     *
     * @param client the RPC client
     * @param token ERC-20 contract address
     * @param addrs addresses to query
     * @return raw token balances in the same order as {@code addrs}
     */
    public static CompletableFuture<List<BigInteger>> getTokenBalances(
            EthClient client, String token, List<String> addrs) {
        var mc = new Multicall3(client);
        Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
        addrs.forEach(a -> mc.addOptional(token, fn, a));
        return mc.execute()
                .thenApply(
                        r ->
                                r.stream()
                                        .map(v -> v instanceof BigInteger bi ? bi : BigInteger.ZERO)
                                        .toList());
    }

    // ─── Fluent builder ───────────────────────────────────────────────────────

    public static FluentBuilder builder(EthClient client) {
        return new FluentBuilder(client);
    }

    public static class FluentBuilder {
        private final Multicall3 mc;

        FluentBuilder(EthClient client) {
            this.mc = new Multicall3(client);
        }

        public FluentBuilder call(String addr, Function fn, Object... args) {
            mc.add(addr, fn, args);
            return this;
        }

        public FluentBuilder optional(String addr, Function fn, Object... args) {
            mc.addOptional(addr, fn, args);
            return this;
        }

        @SuppressWarnings("unused")
        public CompletableFuture<List<Object>> execute() {
            return mc.execute();
        }

        @SuppressWarnings({"unchecked", "unused"})
        public <T> CompletableFuture<List<T>> executeAs(@SuppressWarnings("unused") Class<T> type) {
            return mc.execute().thenApply(r -> r.stream().map(v -> (T) v).toList());
        }

        public CompletableFuture<List<Result>> executeWithResults() {
            return mc.executeWithResults();
        }
    }

    // ─── Encoding ─────────────────────────────────────────────────────────────

    private static final byte[] AGGREGATE3_SELECTOR = Hex.decode("82ad56cb");

    private String encodeAggregate3(List<Call> calls) {
        List<byte[]> calldatas = new ArrayList<>();
        for (Call c : calls) calldatas.add(Hex.decode(c.function.encode(c.args)));

        int n = calls.size();
        List<byte[]> elements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Call c = calls.get(i);
            byte[] cd = calldatas.get(i);
            byte[] target = AbiCodec.encodeAddress(c.address);
            byte[] allowFail = new byte[32];
            allowFail[31] = (byte) (c.requireSuccess ? 0 : 1);
            byte[] offsetPtr = be32(96);
            byte[] lenBytes = be32(cd.length);
            int pad = ((cd.length + 31) / 32) * 32;
            byte[] cdPad = Arrays.copyOf(cd, pad);
            elements.add(concat(target, allowFail, offsetPtr, lenBytes, cdPad));
        }

        int headSize = n * 32;
        int[] tailOffsets = new int[n];
        int running = headSize;
        for (int i = 0; i < n; i++) {
            tailOffsets[i] = running;
            running += elements.get(i).length;
        }

        ByteBuilder bb = new ByteBuilder();
        bb.add(AGGREGATE3_SELECTOR);
        bb.add(be32(32)); // outer offset
        bb.add(be32(n)); // array length
        for (int off : tailOffsets) bb.add(be32(off));
        for (byte[] e : elements) bb.add(e);
        return Hex.encode(bb.build());
    }

    private List<Object> decodeResults(String hexResult, List<Call> calls) {
        byte[] data = Hex.decode(hexResult);
        int n = (int) new BigInteger(1, Arrays.copyOfRange(data, 32, 64)).longValue();
        List<Object> out = new ArrayList<>(Collections.nCopies(n, null));

        for (int i = 0; i < n && i < calls.size(); i++) {
            int elemOff =
                    64
                            + (int)
                                    new BigInteger(
                                                    1,
                                                    Arrays.copyOfRange(
                                                            data, 64 + i * 32, 96 + i * 32))
                                            .longValue();
            boolean success = data[elemOff + 31] != 0;
            int bytesLen =
                    (int)
                            new BigInteger(1, Arrays.copyOfRange(data, elemOff + 32, elemOff + 64))
                                    .longValue();
            if (success && bytesLen > 0) {
                byte[] ret = Arrays.copyOfRange(data, elemOff + 64, elemOff + 64 + bytesLen);
                try {
                    AbiType[] types = calls.get(i).function.getOutputTypes();
                    if (types != null && types.length > 0) {
                        Object[] dec = AbiCodec.decode(types, ret);
                        out.set(i, dec.length == 1 ? dec[0] : dec);
                    } else out.set(i, ret);
                } catch (Exception e) {
                    out.set(i, null);
                }
            }
        }
        return out;
    }

    // ─── Utils ────────────────────────────────────────────────────────────────

    private static byte[] be32(int v) {
        byte[] b = new byte[32];
        b[28] = (byte) (v >> 24);
        b[29] = (byte) (v >> 16);
        b[30] = (byte) (v >> 8);
        b[31] = (byte) v;
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static class ByteBuilder {
        final List<byte[]> parts = new ArrayList<>();

        void add(byte[] b) {
            parts.add(b);
        }

        byte[] build() {
            int tot = parts.stream().mapToInt(a -> a.length).sum();
            byte[] out = new byte[tot];
            int pos = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, pos, p.length);
                pos += p.length;
            }
            return out;
        }
    }

    // ─── Types ────────────────────────────────────────────────────────────────

    private record Call(String address, Function function, Object[] args, boolean requireSuccess) {}

    public record Result(int index, Object value, boolean success) {
        public <T> T as(Class<T> t) {
            return t.cast(value);
        }

        public boolean isNull() {
            return value == null;
        }
    }
}
