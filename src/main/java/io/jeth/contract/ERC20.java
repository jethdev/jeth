/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import io.jeth.core.EthClient;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.eip712.TypedData;
import io.jeth.util.Units;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * ERC-20 token read/write wrapper with built-in ERC-2612 Permit support.
 *
 * <p>All amounts use the token's native raw integer representation. To convert between
 * human-readable and raw amounts, use {@link io.jeth.util.Units}:
 *
 * <pre>
 * var usdc = new ERC20("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", client);
 *
 * // Read — raw BigInteger
 * BigInteger raw = usdc.balanceOf("0xAddress").join();     // e.g. 1_000_000 (= 1 USDC)
 *
 * // Read — human-readable BigDecimal (fetches decimals() in the same multicall)
 * BigDecimal fmt = usdc.balanceOfFormatted("0xAddress").join(); // "1.000000"
 *
 * // Write — raw amount
 * usdc.transfer(wallet, "0xTo", Units.parseToken("100", 6)).join();
 *
 * // ERC-2612 Permit: sign off-chain, approve on-chain in a single tx
 * // Works with USDC, DAI, UNI, AAVE, and any ERC-2612-compliant token
 * usdc.permit(wallet, "0xSpender", amount, deadline).join();
 * </pre>
 *
 * @see io.jeth.util.Units#parseToken
 * @see io.jeth.util.Units#fromWei
 */
public class ERC20 {

    private final Contract contract;
    private final ContractFunction fnBalanceOf;
    private final ContractFunction fnTotalSupply;
    private final ContractFunction fnAllowance;
    private final ContractFunction fnName;
    private final ContractFunction fnSymbol;
    private final ContractFunction fnDecimals;
    private final ContractFunction fnNonces;
    private final ContractFunction fnTransfer;
    private final ContractFunction fnApprove;
    private final ContractFunction fnTransferFrom;
    private final ContractFunction fnPermit;

    public ERC20(String address, EthClient client) {
        this.contract = new Contract(address, client);
        fnBalanceOf = contract.fn("balanceOf(address)").returns("uint256");
        fnTotalSupply = contract.fn("totalSupply()").returns("uint256");
        fnAllowance = contract.fn("allowance(address,address)").returns("uint256");
        fnName = contract.fn("name()").returns("string");
        fnSymbol = contract.fn("symbol()").returns("string");
        fnDecimals = contract.fn("decimals()").returns("uint8");
        fnNonces = contract.fn("nonces(address)").returns("uint256");
        fnTransfer = contract.fn("transfer(address,uint256)").returns("bool");
        fnApprove = contract.fn("approve(address,uint256)").returns("bool");
        fnTransferFrom = contract.fn("transferFrom(address,address,uint256)").returns("bool");
        fnPermit = contract.fn("permit(address,address,uint256,uint256,uint8,bytes32,bytes32)");
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    public CompletableFuture<BigInteger> balanceOf(String account) {
        return fnBalanceOf.call(account).as(BigInteger.class);
    }

    public CompletableFuture<BigInteger> totalSupply() {
        return fnTotalSupply.call().as(BigInteger.class);
    }

    public CompletableFuture<BigInteger> allowance(String owner, String spender) {
        return fnAllowance.call(owner, spender).as(BigInteger.class);
    }

    public CompletableFuture<String> name() {
        return fnName.call().as(String.class);
    }

    public CompletableFuture<String> symbol() {
        return fnSymbol.call().as(String.class);
    }

    public CompletableFuture<BigInteger> nonces(String owner) {
        return fnNonces.call(owner).as(BigInteger.class);
    }

    public CompletableFuture<Integer> decimals() {
        return fnDecimals.call().as(BigInteger.class).thenApply(BigInteger::intValue);
    }

    /**
     * Returns the token balance as a human-readable {@link java.math.BigDecimal}.
     *
     * <p>Makes two RPC calls: {@code decimals()} and {@code balanceOf()}, then divides. For
     * batching multiple balances with a single RPC call, use {@link
     * io.jeth.multicall.Multicall3#getTokenBalances}.
     *
     * @param account the address to query
     */
    public CompletableFuture<BigDecimal> balanceOfFormatted(String account) {
        return decimals()
                .thenCompose(dec -> balanceOf(account).thenApply(bal -> Units.fromWei(bal, dec)));
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    public CompletableFuture<String> transfer(Wallet wallet, String to, BigInteger amount) {
        return fnTransfer.send(wallet, to, amount);
    }

    public CompletableFuture<String> approve(Wallet wallet, String spender, BigInteger amount) {
        return fnApprove.send(wallet, spender, amount);
    }

    public CompletableFuture<String> transferFrom(
            Wallet wallet, String from, String to, BigInteger amount) {
        return fnTransferFrom.send(wallet, from, to, amount);
    }

    // ─── EIP-2612 Permit ──────────────────────────────────────────────────────

    /**
     * Executes a gasless ERC-2612 Permit approval in a single transaction.
     *
     * <p>Internally: fetches {@code name()}, {@code nonces(owner)}, and {@code chainId}, constructs
     * and signs the EIP-712 Permit digest, then calls {@code permit(owner, spender, value,
     * deadline, v, r, s)} on-chain.
     *
     * <p>This replaces the classic two-transaction {@code approve + transferFrom} pattern with a
     * single transaction, saving ~50k gas and eliminating a user confirmation step. Compatible with
     * USDC, DAI, UNI, AAVE, and any EIP-2612 token.
     *
     * @param owner wallet that owns the tokens (signs the permit)
     * @param spender contract being approved to spend
     * @param value amount approved (raw, use {@link io.jeth.util.Units#parseToken})
     * @param deadline unix timestamp after which the permit is invalid (use a short window, e.g.
     *     now + 20 min)
     * @return transaction hash of the on-chain permit call
     */
    public CompletableFuture<String> permit(
            Wallet owner, String spender, BigInteger value, BigInteger deadline) {
        return name().thenCompose(
                        tokenName ->
                                nonces(owner.getAddress())
                                        .thenCompose(
                                                nonce ->
                                                        contract.getClient()
                                                                .getChainId()
                                                                .thenCompose(
                                                                        chainId -> {
                                                                            Signature sig =
                                                                                    TypedData
                                                                                            .signPermit(
                                                                                                    contract
                                                                                                            .getAddress(),
                                                                                                    tokenName,
                                                                                                    "1",
                                                                                                    chainId,
                                                                                                    owner
                                                                                                            .getAddress(),
                                                                                                    spender,
                                                                                                    value,
                                                                                                    nonce,
                                                                                                    deadline,
                                                                                                    owner);
                                                                            BigInteger v =
                                                                                    BigInteger
                                                                                            .valueOf(
                                                                                                    sig.v); // TypedData.sign already normalizes to 27 or 28
                                                                            byte[] r =
                                                                                    toBe32(sig.r);
                                                                            byte[] s =
                                                                                    toBe32(sig.s);
                                                                            return fnPermit.send(
                                                                                    owner,
                                                                                    owner
                                                                                            .getAddress(),
                                                                                    spender,
                                                                                    value,
                                                                                    deadline,
                                                                                    v,
                                                                                    r,
                                                                                    s);
                                                                        })));
    }

    /** Max uint256 — use for unlimited approval. */
    public static final BigInteger MAX_APPROVAL = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    public String getAddress() {
        return contract.getAddress();
    }

    public Contract getContract() {
        return contract;
    }

    private static byte[] toBe32(BigInteger n) {
        byte[] raw = n.toByteArray(), out = new byte[32];
        System.arraycopy(
                raw,
                Math.max(0, raw.length - 32),
                out,
                Math.max(0, 32 - raw.length),
                Math.min(32, raw.length));
        return out;
    }
}
