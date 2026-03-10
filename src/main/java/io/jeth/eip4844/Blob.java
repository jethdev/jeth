/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import io.jeth.util.Hex;
import org.bouncycastle.crypto.digests.SHA256Digest;

/**
 * An EIP-4844 blob — 128 KB of field elements with its KZG commitment and proof.
 *
 * <h2>Creating blobs</h2>
 *
 * <p>Use {@link #from(byte[])} to create a blob from arbitrary data. This computes
 * real KZG commitment and proof using the Ethereum trusted setup — valid on
 * Sepolia, Holesky, and Mainnet.
 *
 * <pre>
 * // Simple: auto-computes real commitment + proof (valid on mainnet)
 * Blob blob = Blob.from("Hello from L2!".getBytes());
 *
 * // Submit:
 * String rawTx = BlobTransaction.builder()
 *     .to("0xRecipient")
 *     .blob(blob)
 *     .maxFeePerGas(Units.gweiToWei(30))
 *     .maxPriorityFeePerGas(Units.gweiToWei(2))
 *     .maxFeePerBlobGas(Units.gweiToWei(1))
 *     .nonce(nonce).chainId(1L)
 *     .sign(wallet);
 *
 * client.sendRawTransaction(rawTx).join();
 * </pre>
 *
 * <h2>Supplying precomputed KZG values</h2>
 *
 * <p>If you have commitment and proof from an external KZG library (e.g. c-kzg-4844 JNI):
 * <pre>
 * Blob blob = Blob.of(blobBytes, commitment48, proof48);
 * </pre>
 *
 * <h2>Trusted setup</h2>
 *
 * <p>The Ethereum KZG trusted setup is bundled in jeth's jar (from the ceremony at
 * <a href="https://ceremony.ethereum.org">ceremony.ethereum.org</a>).
 * It is loaded lazily on the first KZG call and cached for the process lifetime.
 * You can supply a custom setup via the {@code JETH_KZG_SETUP} environment variable.
 *
 * @see Kzg
 * @see KzgTrustedSetup
 * @see BlobTransaction
 */
public final class Blob {

    public static final int BYTES_PER_BLOB    = 4096 * 32; // 131_072
    public static final int BYTES_COMMITMENT  = 48;
    public static final int BYTES_PROOF       = 48;

    public final byte[] data;        // 131_072 bytes
    public final byte[] commitment;  // 48 bytes compressed G1
    public final byte[] proof;       // 48 bytes KZG proof

    private Blob(byte[] data, byte[] commitment, byte[] proof) {
        this.data       = data;
        this.commitment = commitment;
        this.proof      = proof;
    }

    /**
     * Create a blob from arbitrary bytes, computing real KZG commitment and proof.
     *
     * <p>Data is zero-padded to {@link #BYTES_PER_BLOB} (131072) bytes.
     * Each 32-byte chunk must be a valid BLS12-381 scalar field element (< 2^255).
     * For raw binary data this is almost always satisfied; if not, use
     * {@link BlobEncoder#encode} to wrap data in a field-element-safe encoding.
     *
     * <p>This operation loads the Ethereum trusted setup on first call (~300ms JVM warmup)
     * and then computes ~4096 G1 scalar multiplications (~8–15s pure Java).
     * For performance-critical code, use the native c-kzg-4844 library instead.
     *
     * @param rawData the payload; zero-padded to 131072 bytes if shorter
     * @return blob with valid KZG commitment and proof, ready for mainnet submission
     * @throws IllegalArgumentException if any 32-byte chunk >= BLS12-381 scalar field order
     */
    public static Blob from(byte[] rawData) {
        byte[] padded = new byte[BYTES_PER_BLOB];
        System.arraycopy(rawData, 0, padded, 0, Math.min(rawData.length, BYTES_PER_BLOB));
        byte[] commitment = Kzg.blobToCommitment(padded);
        byte[] proof      = Kzg.computeBlobKzgProof(padded, commitment);
        return new Blob(padded, commitment, proof);
    }

    /**
     * Create a blob with externally computed KZG commitment and proof.
     *
     * <p>Use this when you have commitment/proof from a native KZG library
     * (e.g. ethereum/c-kzg-4844 via JNI) for maximum performance.
     *
     * @param data       exactly {@link #BYTES_PER_BLOB} bytes
     * @param commitment exactly 48 bytes (compressed G1 point)
     * @param proof      exactly 48 bytes (compressed G1 point)
     */
    public static Blob of(byte[] data, byte[] commitment, byte[] proof) {
        if (data.length       != BYTES_PER_BLOB)   throw new IllegalArgumentException("data must be " + BYTES_PER_BLOB + " bytes");
        if (commitment.length != BYTES_COMMITMENT)  throw new IllegalArgumentException("commitment must be 48 bytes");
        if (proof.length      != BYTES_PROOF)       throw new IllegalArgumentException("proof must be 48 bytes");
        return new Blob(data, commitment, proof);
    }

    /** Compute the versioned hash: 0x01 || sha256(commitment)[1:] (EIP-4844). */
    public byte[] versionedHash() {
        SHA256Digest sha = new SHA256Digest();
        sha.update(commitment, 0, commitment.length);
        byte[] h = new byte[32];
        sha.doFinal(h, 0);
        h[0] = 0x01;
        return h;
    }

    public String dataHex()           { return Hex.encode(data); }
    public String commitmentHex()     { return Hex.encode(commitment); }
    public String proofHex()          { return Hex.encode(proof); }
    public String versionedHashHex()  { return Hex.encode(versionedHash()); }

    @Override public String toString() {
        return "Blob{" + data.length + "B commitment=" + commitmentHex().substring(0, 10) + "...}";
    }
}
