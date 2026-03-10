/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.aa;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.core.EthException;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ERC-4337 UserOperation (v0.6) — the core primitive for Account Abstraction.
 *
 * <p>A UserOperation is not sent directly to the blockchain. Instead it is sent to a bundler which
 * batches it with other UserOps into a single transaction calling the EntryPoint contract's {@code
 * handleOps()} function.
 *
 * <pre>
 * // Create a UserOperation
 * var userOp = UserOperation.builder()
 *     .sender("0xSmartWalletAddress")
 *     .nonce(BigInteger.ZERO)
 *     .callData(Hex.encode(calldata))
 *     .callGasLimit(BigInteger.valueOf(100_000))
 *     .verificationGasLimit(BigInteger.valueOf(150_000))
 *     .preVerificationGas(BigInteger.valueOf(21_000))
 *     .maxFeePerGas(Units.gweiToWei(30))
 *     .maxPriorityFeePerGas(Units.gweiToWei(2))
 *     .build();
 *
 * // Sign with owner key
 * String signedOp = userOp.sign(ownerWallet, chainId, ENTRY_POINT_ADDRESS);
 *
 * // Send to bundler (eth_sendUserOperation)
 * bundler.sendUserOperation(userOp, ENTRY_POINT_ADDRESS);
 * </pre>
 */
public class UserOperation {

    /** EntryPoint v0.6 (canonical deployment) */
    public static final String ENTRY_POINT_V06 = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789";

    /** EntryPoint v0.7 (ERC-4337 v0.7) */
    public static final String ENTRY_POINT_V07 = "0x0000000071727De22E5E9d8BAf0edAc6f37da032";

    public final String sender;
    public final BigInteger nonce;
    public final String initCode; // factory + initCalldata (empty if already deployed)
    public final String callData;
    public final BigInteger callGasLimit;
    public final BigInteger verificationGasLimit;
    public final BigInteger preVerificationGas;
    public final BigInteger maxFeePerGas;
    public final BigInteger maxPriorityFeePerGas;
    public final String paymasterAndData; // paymaster address + data (empty = no paymaster)
    public final String signature; // set after signing

    private UserOperation(Builder b, String signature) {
        this.sender = b.sender;
        this.nonce = b.nonce;
        this.initCode = b.initCode;
        this.callData = b.callData;
        this.callGasLimit = b.callGasLimit;
        this.verificationGasLimit = b.verificationGasLimit;
        this.preVerificationGas = b.preVerificationGas;
        this.maxFeePerGas = b.maxFeePerGas;
        this.maxPriorityFeePerGas = b.maxPriorityFeePerGas;
        this.paymasterAndData = b.paymasterAndData;
        this.signature = signature;
    }

    /**
     * Compute the userOpHash as defined by ERC-4337: keccak256(abi.encode(keccak256(pack(userOp)),
     * entryPoint, chainId))
     */
    public String getUserOpHash(String entryPointAddress, long chainId) {
        byte[] packed = pack();
        byte[] innerHash = Keccak.hash(packed);

        // abi.encode(innerHash, entryPoint, chainId)
        byte[] encoded =
                AbiCodec.encode(
                        new AbiType[] {AbiType.BYTES32, AbiType.ADDRESS, AbiType.UINT256},
                        new Object[] {innerHash, entryPointAddress, BigInteger.valueOf(chainId)});
        return Hex.encode(Keccak.hash(encoded));
    }

    /**
     * Sign this UserOperation with the given wallet. Returns a new UserOperation with the signature
     * field populated.
     */
    public UserOperation sign(Wallet wallet, long chainId, String entryPoint) {
        String userOpHash = getUserOpHash(entryPoint, chainId);
        byte[] hashBytes = Hex.decode(userOpHash);

        // Ethereum signed message prefix (same as personal_sign)
        String prefix = "\u0019Ethereum Signed Message:\n32";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] toSign = new byte[prefixBytes.length + 32];
        System.arraycopy(prefixBytes, 0, toSign, 0, prefixBytes.length);
        System.arraycopy(hashBytes, 0, toSign, prefixBytes.length, 32);

        Signature sig = wallet.sign(Keccak.hash(toSign));
        int v = sig.v + 27; // Ethereum convention
        byte[] sigBytes = new byte[65];
        byte[] rBytes = toFixedBytes(sig.r, 32);
        byte[] sBytes = toFixedBytes(sig.s, 32);
        System.arraycopy(rBytes, 0, sigBytes, 0, 32);
        System.arraycopy(sBytes, 0, sigBytes, 32, 32);
        sigBytes[64] = (byte) v;

        // Return new UserOp with signature
        Builder b = new Builder();
        b.sender = this.sender;
        b.nonce = this.nonce;
        b.initCode = this.initCode;
        b.callData = this.callData;
        b.callGasLimit = this.callGasLimit;
        b.verificationGasLimit = this.verificationGasLimit;
        b.preVerificationGas = this.preVerificationGas;
        b.maxFeePerGas = this.maxFeePerGas;
        b.maxPriorityFeePerGas = this.maxPriorityFeePerGas;
        b.paymasterAndData = this.paymasterAndData;
        return new UserOperation(b, Hex.encode(sigBytes));
    }

    /** Encode for eth_sendUserOperation JSON-RPC param. */
    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("sender", sender);
        m.put("nonce", "0x" + nonce.toString(16));
        m.put("initCode", initCode != null ? initCode : "0x");
        m.put("callData", callData != null ? callData : "0x");
        m.put("callGasLimit", "0x" + callGasLimit.toString(16));
        m.put("verificationGasLimit", "0x" + verificationGasLimit.toString(16));
        m.put("preVerificationGas", "0x" + preVerificationGas.toString(16));
        m.put("maxFeePerGas", "0x" + maxFeePerGas.toString(16));
        m.put("maxPriorityFeePerGas", "0x" + maxPriorityFeePerGas.toString(16));
        m.put("paymasterAndData", paymasterAndData != null ? paymasterAndData : "0x");
        m.put("signature", signature != null ? signature : "0x");
        return m;
    }

    /** Pack fields for hashing (ERC-4337 spec). */
    private byte[] pack() {
        return AbiCodec.encode(
                new AbiType[] {
                    AbiType.ADDRESS, // sender
                    AbiType.UINT256, // nonce
                    AbiType.BYTES32, // keccak256(initCode)
                    AbiType.BYTES32, // keccak256(callData)
                    AbiType.UINT256, // callGasLimit
                    AbiType.UINT256, // verificationGasLimit
                    AbiType.UINT256, // preVerificationGas
                    AbiType.UINT256, // maxFeePerGas
                    AbiType.UINT256, // maxPriorityFeePerGas
                    AbiType.BYTES32 // keccak256(paymasterAndData)
                },
                new Object[] {
                    sender,
                    nonce,
                    Keccak.hash(
                            initCode != null && initCode.length() > 2
                                    ? Hex.decode(initCode)
                                    : new byte[0]),
                    Keccak.hash(
                            callData != null && callData.length() > 2
                                    ? Hex.decode(callData)
                                    : new byte[0]),
                    callGasLimit,
                    verificationGasLimit,
                    preVerificationGas,
                    maxFeePerGas,
                    maxPriorityFeePerGas,
                    Keccak.hash(
                            paymasterAndData != null && paymasterAndData.length() > 2
                                    ? Hex.decode(paymasterAndData)
                                    : new byte[0])
                });
    }

    private static byte[] toFixedBytes(BigInteger n, int len) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[len];
        if (raw.length >= len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        String sender;
        BigInteger nonce = BigInteger.ZERO;
        String initCode;
        String callData;
        BigInteger callGasLimit = BigInteger.valueOf(100_000);
        BigInteger verificationGasLimit = BigInteger.valueOf(150_000);
        BigInteger preVerificationGas = BigInteger.valueOf(21_000);
        BigInteger maxFeePerGas = BigInteger.ZERO;
        BigInteger maxPriorityFeePerGas = BigInteger.ZERO;
        String paymasterAndData;

        public Builder sender(String v) {
            this.sender = v;
            return this;
        }

        public Builder nonce(BigInteger v) {
            this.nonce = v;
            return this;
        }

        public Builder nonce(long v) {
            this.nonce = BigInteger.valueOf(v);
            return this;
        }

        public Builder initCode(String v) {
            this.initCode = v;
            return this;
        }

        public Builder callData(String v) {
            this.callData = v;
            return this;
        }

        public Builder callGasLimit(BigInteger v) {
            this.callGasLimit = v;
            return this;
        }

        public Builder verificationGasLimit(BigInteger v) {
            this.verificationGasLimit = v;
            return this;
        }

        public Builder preVerificationGas(BigInteger v) {
            this.preVerificationGas = v;
            return this;
        }

        public Builder maxFeePerGas(BigInteger v) {
            this.maxFeePerGas = v;
            return this;
        }

        public Builder maxPriorityFeePerGas(BigInteger v) {
            this.maxPriorityFeePerGas = v;
            return this;
        }

        public Builder paymasterAndData(String v) {
            this.paymasterAndData = v;
            return this;
        }

        public UserOperation build() {
            if (sender == null) throw new EthException("UserOperation.sender is required");
            return new UserOperation(this, null);
        }
    }
}
