/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.crypto;

import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * EIP-7702 (Type 4) transaction signer — "Set Code for EOA".
 *
 * <p>Introduced in the Pectra hard fork (April 2025). Lets an EOA temporarily delegate its
 * execution to a smart contract without deploying anything. This enables Account Abstraction
 * without a smart contract wallet.
 *
 * <pre>
 * // Alice delegates her EOA to a Multisig contract until the tx is mined
 * var auth = Eip7702Signer.signAuthorization(
 *     1L,                                  // chainId (0 = any chain)
 *     \"0xMultisigImplementation\",          // contract to delegate to
 *     aliceNonce,                          // Alice's current nonce
 *     aliceWallet                          // Alice signs the delegation
 * );
 *
 * // Bob pays gas; Alice's EOA temporarily has Multisig code
 * String rawTx = Eip7702Signer.sign(
 *     EthModels.TransactionRequest.builder()
 *         .from(bobWallet.getAddress())
 *         .to(aliceWallet.getAddress())   // target is the delegating EOA
 *         .gas(BigInteger.valueOf(100_000))
 *         .maxFeePerGas(Units.gweiToWei(30))
 *         .maxPriorityFeePerGas(Units.gweiToWei(2))
 *         .nonce(bobNonce)
 *         .chainId(1L)
 *         .data(calldata)
 *         .build(),
 *     List.of(auth),
 *     bobWallet
 * );
 * String txHash = client.sendRawTransaction(rawTx).join();
 * </pre>
 *
 * EIP-7702 spec: https://eips.ethereum.org/EIPS/eip-7702
 */
public class Eip7702Signer {

    private Eip7702Signer() {}

    /**
     * Sign an EIP-7702 authorization tuple.
     *
     * <p>The authorization authorizes a specific EOA ({@code wallet}) to delegate its code to
     * {@code contractAddress} for a single transaction.
     *
     * <p>Signing payload (per EIP-7702): {@code keccak256(0x05 || RLP([chainId, address, nonce]))}
     *
     * @param chainId chain ID (0 = valid on all chains)
     * @param contractAddress the smart contract whose code the EOA will borrow
     * @param nonce the authorizing EOA's current account nonce
     * @param wallet the EOA that is delegating
     * @return a signed Authorization tuple ready for inclusion in a type-4 tx
     */
    public static Authorization signAuthorization(
            long chainId, String contractAddress, long nonce, Wallet wallet) {

        byte[] addr = Hex.decode(contractAddress);

        // Signing hash: keccak256(0x05 || RLP([chainId, address, nonce]))
        List<Object> authTuple =
                List.of(BigInteger.valueOf(chainId), addr, BigInteger.valueOf(nonce));

        byte[] rlp = prependMagic(Rlp.encode(authTuple));
        byte[] hash = Keccak.hash(rlp);
        Signature sig = wallet.sign(hash);

        // Zero-pad r and s to 64 hex chars (32 bytes) to avoid leading-zero truncation
        String rHex = String.format("%064x", sig.r);
        String sHex = String.format("%064x", sig.s);
        return new Authorization(chainId, contractAddress, nonce, sig.v, rHex, sHex);
    }

    /**
     * Sign an EIP-7702 (type 4) transaction containing authorization tuples.
     *
     * <p>Wire format: {@code 0x04 || RLP([chainId, nonce, maxPriorityFee, maxFee, gasLimit, to,
     * value, data, accessList, authorizationList])}
     *
     * @param tx the transaction envelope (gas, to, value, data, etc.)
     * @param auths list of signed authorization tuples
     * @param senderWallet wallet that pays gas and sends the transaction
     * @return 0x-prefixed raw transaction hex for eth_sendRawTransaction
     */
    public static String sign(
            EthModels.TransactionRequest tx, List<Authorization> auths, Wallet senderWallet) {

        byte[] to = tx.to != null ? Hex.decode(tx.to) : new byte[0];
        byte[] data = tx.data != null ? Hex.decode(tx.data) : new byte[0];
        BigInteger val = tx.value != null ? tx.value : BigInteger.ZERO;
        BigInteger gas = tx.gas != null ? tx.gas : BigInteger.valueOf(21_000);
        BigInteger maxF = tx.maxFeePerGas != null ? tx.maxFeePerGas : BigInteger.ZERO;
        BigInteger tip =
                tx.maxPriorityFeePerGas != null ? tx.maxPriorityFeePerGas : BigInteger.ZERO;

        List<Object> encodedAuths = encodeAuthList(auths);

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
                        List.of(), // accessList (empty)
                        encodedAuths);

        byte[] payload = typed(0x04, Rlp.encode(unsigned));
        byte[] hash = Keccak.hash(payload);
        Signature sig = senderWallet.sign(hash);

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
                        encodedAuths,
                        BigInteger.valueOf(sig.v),
                        sig.r,
                        sig.s);

        return Hex.encode(typed(0x04, Rlp.encode(signed)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static List<Object> encodeAuthList(List<Authorization> auths) {
        List<Object> list = new ArrayList<>(auths.size());
        for (Authorization a : auths) {
            list.add(
                    List.of(
                            BigInteger.valueOf(a.chainId()),
                            Hex.decode(a.contractAddress()),
                            BigInteger.valueOf(a.nonce()),
                            BigInteger.valueOf(a.yParity()),
                            new BigInteger(a.r(), 16),
                            new BigInteger(a.s(), 16)));
        }
        return list;
    }

    private static byte[] typed(int type, byte[] rlp) {
        byte[] out = new byte[1 + rlp.length];
        out[0] = (byte) type;
        System.arraycopy(rlp, 0, out, 1, rlp.length);
        return out;
    }

    /** EIP-7702 signing magic: 0x05 prefix. */
    private static byte[] prependMagic(byte[] rlp) {
        byte[] out = new byte[1 + rlp.length];
        out[0] = 0x05;
        System.arraycopy(rlp, 0, out, 1, rlp.length);
        return out;
    }

    // ─── Data types ───────────────────────────────────────────────────────────

    /**
     * A signed EIP-7702 authorization tuple.
     *
     * @param chainId chain constraint (0 = any chain)
     * @param contractAddress the implementation contract address
     * @param nonce the authorizing EOA's nonce at signing time
     * @param yParity signature recovery bit (0 or 1)
     * @param r signature r component (hex, no 0x prefix)
     * @param s signature s component (hex, no 0x prefix)
     */
    public record Authorization(
            long chainId, String contractAddress, long nonce, int yParity, String r, String s) {
        /** Create a self-sponsoring authorization (sender = delegator). */
        public static Authorization selfSponsored(
                long chainId, String contractAddress, long nonce, Wallet wallet) {
            return Eip7702Signer.signAuthorization(chainId, contractAddress, nonce, wallet);
        }

        /** Create a chain-agnostic authorization (valid on any EVM chain). */
        public static Authorization anyChain(String contractAddress, long nonce, Wallet wallet) {
            return Eip7702Signer.signAuthorization(0L, contractAddress, nonce, wallet);
        }

        @Override
        public String toString() {
            return "Authorization{chain="
                    + chainId
                    + ", contract="
                    + contractAddress
                    + ", nonce="
                    + nonce
                    + "}";
        }
    }
}
