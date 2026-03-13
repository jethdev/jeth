/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.crypto;

import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Signs Ethereum transactions. Supports EIP-1559 (type 2), legacy (type 0), and EIP-2930
 * access-list (type 1) transactions.
 */
public class TransactionSigner {

    /**
     * Sign an EIP-1559 (type 2) transaction. Returns a 0x-prefixed raw transaction hex for
     * eth_sendRawTransaction.
     */
    public static String signEip1559(EthModels.TransactionRequest tx, Wallet wallet) {
        byte[] to = tx.to != null ? Hex.decode(tx.to) : new byte[0];
        byte[] data = tx.data != null ? Hex.decode(tx.data) : new byte[0];
        BigInteger val = tx.value != null ? tx.value : BigInteger.ZERO;
        BigInteger gas = tx.gas != null ? tx.gas : BigInteger.valueOf(21000);
        BigInteger maxF = tx.maxFeePerGas != null ? tx.maxFeePerGas : BigInteger.ZERO;
        BigInteger tip =
                tx.maxPriorityFeePerGas != null ? tx.maxPriorityFeePerGas : BigInteger.ZERO;

        // EIP-1559: 0x02 || RLP([chainId, nonce, maxPriorityFee, maxFee, gas, to, value, data,
        // accessList])
        List<Object> unsigned =
                List.of(
                        BigInteger.valueOf(tx.chainId),
                        BigInteger.valueOf(tx.nonce),
                        tip,
                        maxF,
                        gas,
                        to,
                        val,
                        data,
                        List.of());

        byte[] payload = typed(0x02, Rlp.encode(unsigned));
        byte[] hash = Keccak.hash(payload);
        Signature sig = wallet.sign(hash);

        List<Object> signed =
                List.of(
                        BigInteger.valueOf(tx.chainId),
                        BigInteger.valueOf(tx.nonce),
                        tip,
                        maxF,
                        gas,
                        to,
                        val,
                        data,
                        List.of(),
                        BigInteger.valueOf(sig.v),
                        sig.r,
                        sig.s);

        return Hex.encode(typed(0x02, Rlp.encode(signed)));
    }

    /** Sign a legacy (type 0) transaction with EIP-155 replay protection. */
    public static String signLegacy(EthModels.TransactionRequest tx, Wallet wallet) {
        byte[] to = tx.to != null ? Hex.decode(tx.to) : new byte[0];
        byte[] data = tx.data != null ? Hex.decode(tx.data) : new byte[0];
        BigInteger val = tx.value != null ? tx.value : BigInteger.ZERO;
        BigInteger gas = tx.gas != null ? tx.gas : BigInteger.valueOf(21000);
        BigInteger gp = tx.maxFeePerGas != null ? tx.maxFeePerGas : BigInteger.ZERO;

        // EIP-155 signing payload
        List<Object> unsigned =
                List.of(
                        BigInteger.valueOf(tx.nonce),
                        gp,
                        gas,
                        to,
                        val,
                        data,
                        BigInteger.valueOf(tx.chainId),
                        BigInteger.ZERO,
                        BigInteger.ZERO);

        byte[] hash = Keccak.hash(Rlp.encode(unsigned));
        Signature sig = wallet.sign(hash);

        BigInteger v = BigInteger.valueOf(tx.chainId * 2L + 35 + sig.v);

        List<Object> signed =
                List.of(BigInteger.valueOf(tx.nonce), gp, gas, to, val, data, v, sig.r, sig.s);

        return Hex.encode(Rlp.encode(signed));
    }

    /**
     * Sign an EIP-2930 (type 1) access-list transaction. accessList format: list of (address,
     * storageKeys[]) pairs.
     */
    public static String signEip2930(
            EthModels.TransactionRequest tx,
            Wallet wallet,
            List<EthModels.AccessListEntry> accessList) {
        if (accessList == null) accessList = List.of();
        byte[] to = tx.to != null ? Hex.decode(tx.to) : new byte[0];
        byte[] data = tx.data != null ? Hex.decode(tx.data) : new byte[0];
        BigInteger val = tx.value != null ? tx.value : BigInteger.ZERO;
        BigInteger gas = tx.gas != null ? tx.gas : BigInteger.valueOf(21000);
        BigInteger gp = tx.maxFeePerGas != null ? tx.maxFeePerGas : BigInteger.ZERO;

        List<Object> encodedAccessList =
                accessList.stream()
                        .map(
                                e ->
                                        (Object)
                                                List.of(
                                                        Hex.decode(e.address()),
                                                        e.storageKeys().stream()
                                                                .map(Hex::decode)
                                                                .toList()))
                        .toList();

        return signEip2930Internal(tx, wallet, to, data, val, gas, gp, encodedAccessList);
    }

    /** Legacy support for signEip2930 with List of Maps. */
    @SuppressWarnings("unchecked")
    public static String signEip2930(
            EthModels.TransactionRequest tx,
            Wallet wallet,
            java.util.Collection<java.util.Map<String, Object>> accessList) {
        if (accessList == null || accessList.isEmpty()) {
            return signEip2930(tx, wallet, (List<EthModels.AccessListEntry>) null);
        }
        byte[] to = tx.to != null ? Hex.decode(tx.to) : new byte[0];
        byte[] data = tx.data != null ? Hex.decode(tx.data) : new byte[0];
        BigInteger val = tx.value != null ? tx.value : BigInteger.ZERO;
        BigInteger gas = tx.gas != null ? tx.gas : BigInteger.valueOf(21000);
        BigInteger gp = tx.maxFeePerGas != null ? tx.maxFeePerGas : BigInteger.ZERO;

        List<Object> encodedAccessList =
                accessList.stream()
                        .map(
                                m -> {
                                    String addr = (String) m.get("address");
                                    List<String> keys = (List<String>) m.get("storageKeys");
                                    return (Object)
                                            List.of(
                                                    Hex.decode(addr),
                                                    keys.stream().map(Hex::decode).toList());
                                })
                        .toList();

        return signEip2930Internal(tx, wallet, to, data, val, gas, gp, encodedAccessList);
    }

    private static String signEip2930Internal(
            EthModels.TransactionRequest tx,
            Wallet wallet,
            byte[] to,
            byte[] data,
            BigInteger val,
            BigInteger gas,
            BigInteger gp,
            List<Object> encodedAccessList) {
        List<Object> unsigned =
                List.of(
                        BigInteger.valueOf(tx.chainId),
                        BigInteger.valueOf(tx.nonce),
                        gp,
                        gas,
                        to,
                        val,
                        data,
                        encodedAccessList);

        byte[] payload = typed(0x01, Rlp.encode(unsigned));
        byte[] hash = Keccak.hash(payload);
        Signature sig = wallet.sign(hash);

        List<Object> signed =
                List.of(
                        BigInteger.valueOf(tx.chainId),
                        BigInteger.valueOf(tx.nonce),
                        gp,
                        gas,
                        to,
                        val,
                        data,
                        encodedAccessList,
                        BigInteger.valueOf(sig.v),
                        sig.r,
                        sig.s);

        return Hex.encode(typed(0x01, Rlp.encode(signed)));
    }

    /** Prepend a transaction type byte to RLP-encoded bytes. */
    private static byte[] typed(int type, byte[] rlp) {
        byte[] out = new byte[1 + rlp.length];
        out[0] = (byte) type;
        System.arraycopy(rlp, 0, out, 1, rlp.length);
        return out;
    }

    /** Convert RLP-decoded byte[] to BigInteger (unsigned). */
    private static BigInteger rlpBytes(Object item) {
        if (item instanceof byte[] b) return b.length == 0 ? BigInteger.ZERO : new BigInteger(1, b);
        if (item instanceof BigInteger bi) return bi;
        throw new IllegalArgumentException(
                "Expected byte[] from RLP decoder, got: " + item.getClass());
    }

    /** Compute the tx hash of an already-signed raw transaction. */
    public static String transactionHash(String rawTxHex) {
        return Hex.encode(Keccak.hash(Hex.decode(rawTxHex)));
    }

    /**
     * Recover the signer address from a signed raw transaction hex. Supports EIP-1559 (type 0x02)
     * and legacy transactions.
     */
    @SuppressWarnings({"unchecked", "RedundantSuppression"})
    public static String recoverSigner(String rawTxHex) {
        byte[] raw = Hex.decode(rawTxHex);
        if (raw.length == 0) throw new IllegalArgumentException("Empty transaction");

        byte[] signingHash;
        BigInteger v, r, s;

        if (raw[0] == 0x02) {
            // EIP-1559: strip the 0x02 type byte, then decode
            byte[] rlpBody = Arrays.copyOfRange(raw, 1, raw.length);
            List<?> decoded = (List<?>) Rlp.decode(rlpBody);
            v = rlpBytes(decoded.get(9));
            r = rlpBytes(decoded.get(10));
            s = rlpBytes(decoded.get(11));
            List<?> unsigned = decoded.subList(0, 9);
            signingHash = Keccak.hash(typed(0x02, Rlp.encode(unsigned)));
        } else {
            // Legacy (EIP-155)
            List<?> decoded = (List<?>) Rlp.decode(raw);
            v = rlpBytes(decoded.get(6));
            r = rlpBytes(decoded.get(7));
            s = rlpBytes(decoded.get(8));
            long chainId = (v.longValue() - 35) / 2;
            List<?> unsigned =
                    List.of(
                            decoded.get(0),
                            decoded.get(1),
                            decoded.get(2),
                            decoded.get(3),
                            decoded.get(4),
                            decoded.get(5),
                            BigInteger.valueOf(chainId),
                            BigInteger.ZERO,
                            BigInteger.ZERO);
            signingHash = Keccak.hash(Rlp.encode(unsigned));
        }

        int recoveryId = v.intValue() & 1;
        return Wallet.recoverAddress(signingHash, new Signature(r, s, recoveryId));
    }
}
