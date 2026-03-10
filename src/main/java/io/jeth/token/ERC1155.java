/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.token;

import io.jeth.contract.Contract;
import io.jeth.contract.ContractFunction;
import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * ERC-1155 Multi-Token wrapper.
 *
 * <pre>
 * var token = new ERC1155("0xAddress", client);
 * BigInteger bal = token.balanceOf("0xUser", tokenId).join();
 * String txHash  = token.safeTransferFrom(wallet, "0xFrom", "0xTo", tokenId, amount, new byte[0]).join();
 * </pre>
 */
public class ERC1155 {

    private final Contract contract;
    private final ContractFunction fnBalanceOf;
    private final ContractFunction fnUri;
    private final ContractFunction fnIsApproved;
    private final ContractFunction fnSetApproval;
    private final ContractFunction fnSafeTransfer;

    public ERC1155(String address, EthClient client) {
        this.contract = new Contract(address, client);
        this.fnBalanceOf = fn("balanceOf(address,uint256)").returns("uint256");
        this.fnUri = fn("uri(uint256)").returns("string");
        this.fnIsApproved = fn("isApprovedForAll(address,address)").returns("bool");
        this.fnSetApproval = fn("setApprovalForAll(address,bool)");
        this.fnSafeTransfer = fn("safeTransferFrom(address,address,uint256,uint256,bytes)");
    }

    private Contract.FunctionBuilder fn(String sig) { return contract.fn(sig); }

    public CompletableFuture<BigInteger> balanceOf(String account, BigInteger tokenId) { return fnBalanceOf.call(account, tokenId).as(BigInteger.class); }
    public CompletableFuture<String>     uri(BigInteger tokenId)                       { return fnUri.call(tokenId).as(String.class); }
    public CompletableFuture<Boolean>    isApprovedForAll(String owner, String op)     { return fnIsApproved.call(owner, op).as(Boolean.class); }
    public CompletableFuture<String>     setApprovalForAll(Wallet w, String op, boolean approved) { return fnSetApproval.send(w, op, approved); }
    public CompletableFuture<String>     safeTransferFrom(Wallet w, String from, String to, BigInteger id, BigInteger amount, byte[] data) { return fnSafeTransfer.send(w, from, to, id, amount, data); }

    public String getAddress() { return contract.getAddress(); }
}
