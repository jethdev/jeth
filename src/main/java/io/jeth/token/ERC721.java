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
 * ERC-721 NFT wrapper.
 *
 * <pre>
 * var nft = new ERC721("0xBayc", client);
 * String owner    = nft.ownerOf(BigInteger.valueOf(1234)).join();
 * String uri      = nft.tokenURI(BigInteger.valueOf(1234)).join();
 * BigInteger bal  = nft.balanceOf("0xAddress").join();
 * String txHash   = nft.transferFrom(wallet, "0xFrom", "0xTo", tokenId).join();
 * </pre>
 */
public class ERC721 {

    private final Contract contract;
    private final ContractFunction fnBalanceOf;
    private final ContractFunction fnOwnerOf;
    private final ContractFunction fnTokenURI;
    private final ContractFunction fnName;
    private final ContractFunction fnSymbol;
    private final ContractFunction fnTotalSupply;
    private final ContractFunction fnIsApprovedForAll;
    private final ContractFunction fnGetApproved;
    private final ContractFunction fnTransferFrom;
    private final ContractFunction fnSafeTransferFrom;
    private final ContractFunction fnApprove;
    private final ContractFunction fnSetApprovalForAll;

    public ERC721(String address, EthClient client) {
        this.contract = new Contract(address, client);
        this.fnBalanceOf = fn("balanceOf(address)").returns("uint256");
        this.fnOwnerOf = fn("ownerOf(uint256)").returns("address");
        this.fnTokenURI = fn("tokenURI(uint256)").returns("string");
        this.fnName = fn("name()").returns("string");
        this.fnSymbol = fn("symbol()").returns("string");
        this.fnTotalSupply = fn("totalSupply()").returns("uint256");
        this.fnIsApprovedForAll = fn("isApprovedForAll(address,address)").returns("bool");
        this.fnGetApproved = fn("getApproved(uint256)").returns("address");
        this.fnTransferFrom = fn("transferFrom(address,address,uint256)");
        this.fnSafeTransferFrom = fn("safeTransferFrom(address,address,uint256)");
        this.fnApprove = fn("approve(address,uint256)");
        this.fnSetApprovalForAll = fn("setApprovalForAll(address,bool)");
    }

    private Contract.FunctionBuilder fn(String sig) { return contract.fn(sig); }

    public CompletableFuture<BigInteger> balanceOf(String owner)          { return fnBalanceOf.call(owner).as(BigInteger.class); }
    public CompletableFuture<String>     ownerOf(BigInteger tokenId)      { return fnOwnerOf.call(tokenId).as(String.class); }
    public CompletableFuture<String>     tokenURI(BigInteger tokenId)     { return fnTokenURI.call(tokenId).as(String.class); }
    public CompletableFuture<String>     name()                           { return fnName.call().as(String.class); }
    public CompletableFuture<String>     symbol()                         { return fnSymbol.call().as(String.class); }
    public CompletableFuture<BigInteger> totalSupply()                    { return fnTotalSupply.call().as(BigInteger.class); }
    public CompletableFuture<String>     getApproved(BigInteger tokenId)  { return fnGetApproved.call(tokenId).as(String.class); }
    public CompletableFuture<Boolean>    isApprovedForAll(String owner, String op) { return fnIsApprovedForAll.call(owner, op).as(Boolean.class); }

    public CompletableFuture<String> transferFrom(Wallet w, String from, String to, BigInteger tokenId)   { return fnTransferFrom.send(w, from, to, tokenId); }
    public CompletableFuture<String> safeTransferFrom(Wallet w, String from, String to, BigInteger tokenId) { return fnSafeTransferFrom.send(w, from, to, tokenId); }
    public CompletableFuture<String> approve(Wallet w, String to, BigInteger tokenId)                     { return fnApprove.send(w, to, tokenId); }
    public CompletableFuture<String> setApprovalForAll(Wallet w, String op, boolean approved)             { return fnSetApprovalForAll.send(w, op, approved); }

    public String getAddress() { return contract.getAddress(); }
}
