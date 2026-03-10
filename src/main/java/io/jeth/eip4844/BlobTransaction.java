/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import io.jeth.core.EthException;
import io.jeth.crypto.Rlp;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.crypto.digests.SHA256Digest;

/**
 * EIP-4844 Blob Transaction (Type 0x03) signer.
 *
 * <p>Blob transactions carry up to 6 blobs of ~128KB each (FIELD_ELEMENTS_PER_BLOB * 32 bytes).
 * Each blob has a KZG commitment and proof. On-chain only the commitment hash is stored; the blob
 * data is available on the beacon chain for ~18 days (4096 epochs).
 *
 * <pre>
 * // Create blob data (must be exactly 4096 * 32 = 131072 bytes, padded if needed)
 * byte[] blobData = BlobTransaction.padBlob("Hello from Layer 1!".getBytes());
 *
 * // Build and sign
 * String rawTx = BlobTransaction.builder()
 *     .to("0xRecipient")
 *     .blob(blobData)
 *     .maxFeePerGas(Units.gweiToWei(30))
 *     .maxPriorityFeePerGas(Units.gweiToWei(2))
 *     .maxFeePerBlobGas(Units.gweiToWei(1))
 *     .nonce(nonce)
 *     .chainId(1L)
 *     .sign(wallet);
 *
 * String txHash = client.sendRawTransaction(rawTx).join();
 * </pre>
 */
public class BlobTransaction {

    /** Each blob is exactly 4096 field elements × 32 bytes = 131072 bytes. */
    public static final int BLOB_SIZE = 131072;

    /** Max blobs per transaction (EIP-4844). */
    public static final int MAX_BLOBS_PER_TX = 6;

    // KZG field prime (BLS12-381 scalar field order)
    private static final BigInteger BLS_MODULUS =
            new BigInteger(
                    "52435875175126190479447740508185965837690552500527637822603658699938581184513");

    private final long chainId;
    private final long nonce;
    private final String to;
    private final BigInteger value;
    private final String data;
    private final BigInteger gas;
    private final BigInteger maxFeePerGas;
    private final BigInteger maxPriorityFeePerGas;
    private final BigInteger maxFeePerBlobGas;
    private final List<byte[]> blobs;
    private final List<byte[]> commitments; // KZG commitments (48 bytes each)
    private final List<byte[]> proofs; // KZG proofs (48 bytes each)
    private final List<byte[]> blobVersionedHashes; // 0x01 + keccak256(commitment)[1:]

    private BlobTransaction(Builder b) {
        this.chainId = b.chainId;
        this.nonce = b.nonce;
        this.to = b.to;
        this.value = b.value;
        this.data = b.data;
        this.gas = b.gas;
        this.maxFeePerGas = b.maxFeePerGas;
        this.maxPriorityFeePerGas = b.maxPriorityFeePerGas;
        this.maxFeePerBlobGas = b.maxFeePerBlobGas;
        this.blobs = b.blobs;
        this.commitments = b.commitments;
        this.proofs = b.proofs;
        this.blobVersionedHashes = computeVersionedHashes(b.commitments);
    }

    /**
     * Pad or truncate raw bytes to exactly BLOB_SIZE (131072 bytes). Bytes are placed at the start;
     * remainder is zero-padded.
     *
     * <p>Note: each 32-byte chunk must be < BLS_MODULUS. For arbitrary data use a real blob encoder
     * library; this method is suitable for small payloads.
     */
    public static byte[] padBlob(byte[] data) {
        byte[] blob = new byte[BLOB_SIZE];
        System.arraycopy(data, 0, blob, 0, Math.min(data.length, BLOB_SIZE));
        return blob;
    }

    /**
     * Sign the blob transaction and return the raw hex string for eth_sendRawTransaction.
     *
     * <p>Note: In production you need a real KZG library (e.g. c-kzg-4844 via JNI or
     * ethereum/c-kzg-4844 Java bindings) to compute valid commitments and proofs. This
     * implementation uses placeholder commitments for testing with devnets.
     */
    public String sign(Wallet wallet) {
        // Signing payload: 0x03 || RLP([chain_id, nonce, max_priority_fee, max_fee,
        //                               gas_limit, to, value, data, access_list,
        //                               max_fee_per_blob_gas, blob_versioned_hashes])
        byte[] toBytes = to != null ? Hex.decode(to) : new byte[0];
        byte[] dataBytes = data != null ? Hex.decode(data) : new byte[0];

        List<Object> txFields =
                new ArrayList<>(
                        List.of(
                                BigInteger.valueOf(chainId),
                                BigInteger.valueOf(nonce),
                                maxPriorityFeePerGas,
                                maxFeePerGas,
                                gas,
                                toBytes,
                                value != null ? value : BigInteger.ZERO,
                                dataBytes,
                                List.of(), // access list
                                maxFeePerBlobGas,
                                blobVersionedHashes));

        byte[] rlpBody = Rlp.encode(txFields);
        byte[] sigPayload = new byte[1 + rlpBody.length];
        sigPayload[0] = 0x03;
        System.arraycopy(rlpBody, 0, sigPayload, 1, rlpBody.length);

        byte[] hash = Keccak.hash(sigPayload);
        Signature sig = wallet.sign(hash);

        // Signed tx body
        List<Object> signedFields = new ArrayList<>(txFields);
        signedFields.add(BigInteger.valueOf(sig.v));
        signedFields.add(sig.r);
        signedFields.add(sig.s);

        // Full blob tx: 0x03 || RLP([tx_payload_body, blobs, commitments, proofs])
        // Per EIP-4844 network format: tx_payload_body is a nested LIST as the first element.
        // Correct structure: [[chainId, nonce, ..., v, r, s], [blob1,...], [comm1,...],
        // [proof1,...]]
        List<Object> networkWrapper = new ArrayList<>();
        networkWrapper.add(signedFields); // tx_payload_body as a nested list
        networkWrapper.add(blobs);
        networkWrapper.add(commitments);
        networkWrapper.add(proofs);

        byte[] networkRlp = Rlp.encode(networkWrapper);
        byte[] networkTx = new byte[1 + networkRlp.length];
        networkTx[0] = 0x03;
        System.arraycopy(networkRlp, 0, networkTx, 1, networkRlp.length);

        return Hex.encode(networkTx);
    }

    /** Compute blob versioned hashes: 0x01 || sha256(commitment)[1:] (EIP-4844) */
    private static List<byte[]> computeVersionedHashes(List<byte[]> commitments) {
        List<byte[]> hashes = new ArrayList<>();
        for (byte[] commitment : commitments) {
            SHA256Digest sha = new SHA256Digest();
            sha.update(commitment, 0, commitment.length);
            byte[] hash = new byte[32];
            sha.doFinal(hash, 0);
            hash[0] = 0x01; // EIP-4844 version byte
            hashes.add(hash);
        }
        return hashes;
    }

    public List<byte[]> getBlobVersionedHashes() {
        return blobVersionedHashes;
    }

    public List<byte[]> getBlobs() {
        return blobs;
    }

    public List<byte[]> getCommitments() {
        return commitments;
    }

    public List<byte[]> getProofs() {
        return proofs;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        long chainId = 1;
        long nonce = 0;
        String to;
        BigInteger value = BigInteger.ZERO;
        String data;
        BigInteger gas = BigInteger.valueOf(21_000);
        BigInteger maxFeePerGas = BigInteger.ZERO;
        BigInteger maxPriorityFeePerGas = BigInteger.ZERO;
        BigInteger maxFeePerBlobGas = BigInteger.ZERO;
        List<byte[]> blobs = new ArrayList<>();
        List<byte[]> commitments = new ArrayList<>();
        List<byte[]> proofs = new ArrayList<>();

        public Builder chainId(long v) {
            this.chainId = v;
            return this;
        }

        public Builder nonce(long v) {
            this.nonce = v;
            return this;
        }

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

        public Builder gas(BigInteger v) {
            this.gas = v;
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

        public Builder maxFeePerBlobGas(BigInteger v) {
            this.maxFeePerBlobGas = v;
            return this;
        }

        /**
         * Add a blob with its KZG commitment and proof. blob must be exactly BLOB_SIZE (131072)
         * bytes. commitment and proof must be exactly 48 bytes each.
         */
        public Builder blob(byte[] blob, byte[] commitment, byte[] proof) {
            if (blob.length != BLOB_SIZE)
                throw new EthException(
                        "Blob must be exactly " + BLOB_SIZE + " bytes, got " + blob.length);
            if (commitment.length != 48) throw new EthException("KZG commitment must be 48 bytes");
            if (proof.length != 48) throw new EthException("KZG proof must be 48 bytes");
            if (blobs.size() >= MAX_BLOBS_PER_TX)
                throw new EthException("Max " + MAX_BLOBS_PER_TX + " blobs per transaction");
            this.blobs.add(blob);
            this.commitments.add(commitment);
            this.proofs.add(proof);
            return this;
        }

        /**
         * Add a {@link Blob} with real KZG commitment and proof.
         *
         * <p>This is the recommended API — commitment and proof are already computed inside the
         * {@link Blob} object, valid on Sepolia and Mainnet.
         *
         * <pre>
         * Blob blob = Blob.from("Hello from L2!".getBytes()); // computes real KZG
         * builder.blob(blob);
         * </pre>
         */
        public Builder blob(Blob blob) {
            return blob(blob.data, blob.commitment, blob.proof);
        }

        /**
         * Add raw blob bytes with placeholder KZG — for local devnet / unit testing only.
         *
         * <p><b>NOT valid on Sepolia, Holesky, or Mainnet.</b> Use {@link #blob(Blob)} which calls
         * {@link Blob#from(byte[])} to compute real KZG automatically.
         *
         * @deprecated Prefer {@code builder.blob(Blob.from(data))} for real networks.
         */
        @Deprecated
        public Builder blobRaw(byte[] blob) {
            byte[] fakeCommitment = new byte[48];
            byte[] fakeProof = new byte[48];
            fakeCommitment[0] = (byte) 0xc0;
            fakeProof[0] = (byte) 0xc0;
            return blob(blob, fakeCommitment, fakeProof);
        }

        /** Build a BlobTransaction instance without signing (for inspection/testing). */
        public BlobTransaction build() {
            if (to == null) throw new EthException("Blob transaction must have a 'to' address");
            return new BlobTransaction(this);
        }

        public String sign(Wallet wallet) {
            if (to == null) throw new EthException("Blob transaction must have a 'to' address");
            if (blobs.isEmpty())
                throw new EthException("Blob transaction must have at least one blob");
            return new BlobTransaction(this).sign(wallet);
        }
    }
}
