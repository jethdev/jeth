/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.safe;

import io.jeth.contract.Contract;
import io.jeth.contract.ContractFunction;
import io.jeth.core.EthClient;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.eip712.TypedData;
import io.jeth.util.Hex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gnosis Safe (v1.3.0+) multisig wallet integration.
 *
 * <p>The most widely used multisig on Ethereum. Used by Uniswap DAO, Aave, Compound, and virtually
 * every major DeFi protocol treasury.
 *
 * <pre>
 * var safe = new GnosisSafe("0xSafeAddress", client);
 *
 * // Get Safe state
 * int threshold = safe.getThreshold().join();
 * List&lt;String&gt; owners = safe.getOwners().join();
 * BigInteger nonce = safe.getNonce().join();
 *
 * // Build and sign a transaction
 * GnosisSafe.SafeTx tx = GnosisSafe.SafeTx.builder()
 *     .to("0xRecipient")
 *     .value(Units.toWei("0.1"))
 *     .data("0x")
 *     .nonce(nonce)
 *     .build();
 *
 * // Owner 1 signs
 * Signature sig1 = safe.signTransaction(tx, chainId, owner1Wallet);
 *
 * // Owner 2 signs
 * Signature sig2 = safe.signTransaction(tx, chainId, owner2Wallet);
 *
 * // Execute once threshold is met
 * String txHash = safe.executeTransaction(tx, List.of(sig1, sig2), executorWallet).join();
 * </pre>
 */
public class GnosisSafe {

    @SuppressWarnings({"unused", "RedundantSuppression"})
    private static final String SAFE_TX_TYPEHASH_STR =
            "SafeTx(address to,uint256 value,bytes data,uint8 operation,uint256 safeTxGas,uint256 baseGas,uint256 gasPrice,address gasToken,address refundReceiver,uint256 nonce)";

    private final Contract safe;
    private final ContractFunction fnNonce;
    private final ContractFunction fnThreshold;
    private final ContractFunction fnGetOwners;
    private final ContractFunction fnExecTransaction;
    private final ContractFunction fnIsOwner;

    public GnosisSafe(String safeAddress, EthClient client) {
        this.safe = new Contract(safeAddress, client);
        this.fnNonce = safe.fn("nonce()").returns("uint256");
        this.fnThreshold = safe.fn("getThreshold()").returns("uint256");
        this.fnGetOwners = safe.fn("getOwners()").returns("address[]");
        this.fnIsOwner = safe.fn("isOwner(address)").returns("bool");
        this.fnExecTransaction =
                safe.fn(
                                "execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")
                        .build();
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    public CompletableFuture<BigInteger> getNonce() {
        return fnNonce.call().as(BigInteger.class);
    }

    public CompletableFuture<Integer> getThreshold() {
        return fnThreshold.call().as(BigInteger.class).thenApply(BigInteger::intValue);
    }

    public CompletableFuture<Boolean> isOwner(String addr) {
        return fnIsOwner.call(addr).as(Boolean.class);
    }

    public CompletableFuture<List<String>> getOwners() {
        return fnGetOwners
                .call()
                .raw()
                .thenApply(
                        arr -> {
                            Object raw = arr[0];
                            if (raw instanceof Object[] oa) {
                                return Arrays.asList(Arrays.copyOf(oa, oa.length, String[].class));
                            }
                            return List.of();
                        });
    }

    // ─── Signing ─────────────────────────────────────────────────────────────

    /**
     * Sign a Safe transaction (EIP-712). Collect signatures from threshold owners, then call
     * executeTransaction().
     */
    public Signature signTransaction(SafeTx tx, long chainId, Wallet wallet) {
        byte[] txHash = getTransactionHash(tx, chainId);
        // Safe uses eth_sign-style prefix (not EIP-712 — Safe handles the domain itself)
        Signature sig = wallet.sign(txHash);
        // Safe requires v = 31 or 32 (eth_sign variant) for off-chain sigs
        return new Signature(sig.r, sig.s, sig.v + 31);
    }

    /** Compute the EIP-712 transaction hash for a SafeTx. */
    public byte[] getTransactionHash(SafeTx tx, long chainId) {
        // Build domain separator
        var domain =
                TypedData.Domain.builder()
                        .chainId(chainId)
                        .verifyingContract(safe.getAddress())
                        .build();

        var types =
                Map.of(
                        "SafeTx",
                        List.of(
                                new TypedData.Field("to", "address"),
                                new TypedData.Field("value", "uint256"),
                                new TypedData.Field("data", "bytes"),
                                new TypedData.Field("operation", "uint8"),
                                new TypedData.Field("safeTxGas", "uint256"),
                                new TypedData.Field("baseGas", "uint256"),
                                new TypedData.Field("gasPrice", "uint256"),
                                new TypedData.Field("gasToken", "address"),
                                new TypedData.Field("refundReceiver", "address"),
                                new TypedData.Field("nonce", "uint256")));

        var message = new LinkedHashMap<String, Object>();
        message.put("to", tx.to);
        message.put("value", tx.value);
        message.put("data", Hex.decode(tx.data));
        message.put("operation", BigInteger.valueOf(tx.operation));
        message.put("safeTxGas", tx.safeTxGas);
        message.put("baseGas", tx.baseGas);
        message.put("gasPrice", tx.gasPrice);
        message.put("gasToken", tx.gasToken);
        message.put("refundReceiver", tx.refundReceiver);
        message.put("nonce", tx.nonce);

        return TypedData.hashTypedData(domain, "SafeTx", types, message);
    }

    // ─── Execution ────────────────────────────────────────────────────────────

    /**
     * Execute a Safe transaction with collected owner signatures.
     *
     * <p><strong>IMPORTANT:</strong> {@code signatures} MUST be sorted by signer address in
     * ascending order. The Safe contract will revert if signatures are not sorted. Use {@link
     * #packSorted(Map)} to sort automatically when you have (address, signature) pairs, or sort
     * manually before calling this method.
     */
    @SuppressWarnings("unused")
    public CompletableFuture<String> executeTransaction(
            SafeTx tx, List<Signature> signatures, Wallet executor) {
        return executeTransaction(executor, tx, signatures);
    }

    /** Legacy support for executeTransaction with executor wallet at first position. */
    public CompletableFuture<String> executeTransaction(
            Wallet executor, SafeTx tx, List<Signature> signatures) {
        byte[] packedSigs = packSignatures(signatures);
        return fnExecTransaction.send(
                executor,
                tx.to,
                tx.value,
                Hex.decode(tx.data),
                BigInteger.valueOf(tx.operation),
                tx.safeTxGas,
                tx.baseGas,
                tx.gasPrice,
                tx.gasToken,
                tx.refundReceiver,
                packedSigs);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<String> executeTransaction(
            SafeTx tx, Map<String, Signature> signatures, Wallet executor) {
        return executeTransaction(tx, signatures.values().stream().toList(), executor);
    }

    /** Legacy support for executeTransaction with executor wallet at first position and Map. */
    @SuppressWarnings("unused")
    public CompletableFuture<String> executeTransaction(
            Wallet executor, SafeTx tx, Map<String, Signature> signatures) {
        return executeTransaction(executor, tx, signatures.values().stream().toList());
    }

    /**
     * Sort and pack (address, signature) pairs into Safe's packed signature format. The Safe
     * contract requires signatures sorted by signer address ascending. Pass the result to {@link
     * #executeTransaction} as pre-packed bytes via the low-level exec.
     */
    public static byte[] packSorted(Map<String, Signature> sigsByAddress) {
        List<Map.Entry<String, Signature>> sorted = new ArrayList<>(sigsByAddress.entrySet());
        sorted.sort(Comparator.comparing(e -> e.getKey().toLowerCase()));
        List<Signature> sigs = sorted.stream().map(Map.Entry::getValue).toList();
        return packSignatures(sigs);
    }

    /** Pack signatures in Safe's expected format (caller is responsible for sort order). */
    private static byte[] packSignatures(List<Signature> signatures) {
        byte[] out = new byte[signatures.size() * 65];
        for (int i = 0; i < signatures.size(); i++) {
            Signature sig = signatures.get(i);
            byte[] r = toFixed32(sig.r), s = toFixed32(sig.s);
            System.arraycopy(r, 0, out, i * 65, 32);
            System.arraycopy(s, 0, out, i * 65 + 32, 32);
            out[i * 65 + 64] = (byte) sig.v;
        }
        return out;
    }

    private static byte[] toFixed32(BigInteger n) {
        byte[] raw = n.toByteArray(), out = new byte[32];
        System.arraycopy(
                raw,
                Math.max(0, raw.length - 32),
                out,
                Math.max(0, 32 - raw.length),
                Math.min(32, raw.length));
        return out;
    }

    public String getAddress() {
        return safe.getAddress();
    }

    // ─── SafeTx ───────────────────────────────────────────────────────────────

    public static final class SafeTx {
        public final String to;
        public final BigInteger value;
        public final String data;
        public final int operation; // 0=CALL, 1=DELEGATECALL
        public final BigInteger safeTxGas;
        public final BigInteger baseGas;
        public final BigInteger gasPrice;
        public final String gasToken;
        public final String refundReceiver;
        public final BigInteger nonce;

        private static final String ZERO_ADDR = "0x0000000000000000000000000000000000000000";

        private SafeTx(Builder b) {
            this.to = b.to;
            this.value = b.value;
            this.data = b.data != null ? b.data : "0x";
            this.operation = b.operation;
            this.safeTxGas = b.safeTxGas;
            this.baseGas = b.baseGas;
            this.gasPrice = b.gasPrice;
            this.gasToken = b.gasToken != null ? b.gasToken : ZERO_ADDR;
            this.refundReceiver = b.refundReceiver != null ? b.refundReceiver : ZERO_ADDR;
            this.nonce = b.nonce;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            String to;
            BigInteger value = BigInteger.ZERO;
            String data;
            int operation = 0;
            BigInteger safeTxGas = BigInteger.ZERO,
                    baseGas = BigInteger.ZERO,
                    gasPrice = BigInteger.ZERO;
            String gasToken, refundReceiver;
            BigInteger nonce = BigInteger.ZERO;

            public Builder to(String v) {
                this.to = v;
                return this;
            }

            public Builder value(BigInteger v) {
                this.value = v;
                return this;
            }

            public Builder data(String v) {
                this.data = v;
                return this;
            }

            public Builder operation(int v) {
                this.operation = v;
                return this;
            }

            public Builder nonce(BigInteger v) {
                this.nonce = v;
                return this;
            }

            public Builder safeTxGas(BigInteger v) {
                this.safeTxGas = v;
                return this;
            }

            public Builder baseGas(BigInteger v) {
                this.baseGas = v;
                return this;
            }

            public Builder gasPrice(BigInteger v) {
                this.gasPrice = v;
                return this;
            }

            public Builder gasToken(String v) {
                this.gasToken = v;
                return this;
            }

            public Builder refundReceiver(String v) {
                this.refundReceiver = v;
                return this;
            }

            public SafeTx build() {
                return new SafeTx(this);
            }
        }
    }
}
