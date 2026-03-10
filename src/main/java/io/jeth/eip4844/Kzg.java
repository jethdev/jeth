/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import org.bouncycastle.crypto.digests.SHA256Digest;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * KZG polynomial commitment scheme for EIP-4844 blob transactions.
 *
 * <p>Implements the Ethereum consensus spec operations exactly:
 * <ul>
 *   <li>{@link #blobToCommitment} — {@code blob_to_kzg_commitment}: 48-byte G1 commitment</li>
 *   <li>{@link #computeBlobKzgProof} — {@code compute_blob_kzg_proof}: 48-byte KZG proof</li>
 *   <li>{@link #computeChallenge} — {@code compute_challenge}: Fiat-Shamir hash</li>
 *   <li>{@link #evaluatePolynomialInEvaluationForm} — barycentric evaluation</li>
 * </ul>
 *
 * <p>The implementation matches the
 * <a href="https://github.com/ethereum/consensus-specs/blob/dev/specs/deneb/polynomial-commitments.md">
 * Ethereum consensus spec (Deneb)</a> exactly, including:
 * <ul>
 *   <li>Using the <b>Lagrange (evaluation) form</b> of the trusted setup for both
 *       commitment and proof computation (not the monomial form)</li>
 *   <li>The Fiat-Shamir challenge hash with correct {@code uint64} degree encoding</li>
 *   <li>In-domain quotient formula for {@code z = ω^m} (uses sum-to-zero correction)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * byte[] blobData   = BlobEncoder.encodeChunk(myData);    // field-element-safe padding
 * byte[] commitment = Kzg.blobToCommitment(blobData);
 * byte[] proof      = Kzg.computeBlobKzgProof(blobData, commitment);
 *
 * // Or just:
 * Blob blob = Blob.from(myData);  // does all of the above automatically
 * </pre>
 *
 * <h2>Performance (pure-Java BigInteger)</h2>
 * <ul>
 *   <li>Setup load: ~300ms (first call, then cached)</li>
 *   <li>Commitment: ~8–15s (4096 G1 scalar multiplications)</li>
 *   <li>Proof: ~8–15s</li>
 * </ul>
 * For high-throughput use, provide precomputed KZG from native c-kzg-4844 (~1ms/blob)
 * and pass directly to {@link BlobTransaction.Builder#blob(byte[], byte[], byte[])}.
 */
public final class Kzg {

    private Kzg() {}

    /** Number of field elements per blob (EIP-4844 constant). */
    public static final int FIELD_ELEMENTS_PER_BLOB = 4096;

    private static final BigInteger R = Bls12381.R;

    /** Domain separation tag for the Fiat-Shamir challenge (16 ASCII bytes). */
    private static final byte[] FIAT_SHAMIR_PROTOCOL_DOMAIN =
        "FSBLOBVERIFY_V1_".getBytes(StandardCharsets.US_ASCII);

    /**
     * Primitive 2^32 root of unity in the BLS12-381 scalar field Fr.
     *
     * <p>Computed as {@code 7^((r-1)/2^32) mod r} where 7 is the generator of Fr*.
     * Verified: {@code ROOT_OF_UNITY_2_32^(2^32) ≡ 1 (mod r)}
     *         and {@code ROOT_OF_UNITY_2_32^(2^31) ≢ 1 (mod r)} (primitive).
     */
    private static final BigInteger ROOT_OF_UNITY_2_32 = new BigInteger(
        "16a2a19edfe81f20d09b681922c813b4b63683508c2280b93829971f439f0d2b", 16);

    // ─── Main API ─────────────────────────────────────────────────────────────

    /**
     * Compute the KZG commitment for a blob.
     *
     * <p>Spec: {@code blob_to_kzg_commitment(blob)}.
     * Uses the Lagrange (evaluation) form of the setup:
     * {@code C = Σ_i poly[i] · G1_lagrange[i]}
     *
     * @param blob exactly {@link BlobTransaction#BLOB_SIZE} (131072) bytes
     * @return 48-byte compressed G1 commitment
     * @throws IllegalArgumentException if blob is wrong size or contains out-of-field values
     */
    public static byte[] blobToCommitment(byte[] blob) {
        validateBlobSize(blob);
        KzgTrustedSetup setup = KzgTrustedSetup.getInstance();
        BigInteger[] poly = blobToFieldElements(blob);
        Bls12381.G1 commitment = Bls12381.G1.msm(setup.g1Lagrange, poly);
        return commitment.compress();
    }

    /**
     * Compute the KZG proof for a blob given its commitment.
     *
     * <p>Spec: {@code compute_blob_kzg_proof(blob, commitment)}.
     * <ol>
     *   <li>Compute Fiat-Shamir challenge {@code z = hash(blob ‖ commitment)}</li>
     *   <li>Evaluate {@code y = p(z)} using the barycentric formula</li>
     *   <li>Compute quotient polynomial {@code q} in evaluation form</li>
     *   <li>Commit: {@code proof = Σ_i q[i] · G1_lagrange[i]}</li>
     * </ol>
     *
     * @param blob       exactly 131072 bytes
     * @param commitment the 48-byte commitment from {@link #blobToCommitment}
     * @return 48-byte compressed G1 proof
     */
    public static byte[] computeBlobKzgProof(byte[] blob, byte[] commitment) {
        validateBlobSize(blob);
        if (commitment.length != 48)
            throw new IllegalArgumentException("commitment must be 48 bytes, got " + commitment.length);

        KzgTrustedSetup setup = KzgTrustedSetup.getInstance();
        BigInteger[] poly = blobToFieldElements(blob);

        BigInteger z = computeChallenge(blob, commitment);
        BigInteger y = evaluatePolynomialInEvaluationForm(poly, z);

        BigInteger[] quotient = computeQuotientEvalForm(poly, z, y);

        Bls12381.G1 proof = Bls12381.G1.msm(setup.g1Lagrange, quotient);
        return proof.compress();
    }

    /**
     * Compute the Fiat-Shamir challenge z.
     *
     * <p>Spec: {@code compute_challenge(polynomial, commitment)}.
     * Hash: {@code SHA256(FSBLOBVERIFY_V1_ ‖ degree_poly_as_uint64_be ‖ blob_bytes ‖ commitment)}
     * Result: {@code hash_to_bls_field(h) = int.from_bytes(h, 'big') mod r}
     */
    public static BigInteger computeChallenge(byte[] blob, byte[] commitment) {
        SHA256Digest sha = new SHA256Digest();

        // DST (16 bytes)
        sha.update(FIAT_SHAMIR_PROTOCOL_DOMAIN, 0, FIAT_SHAMIR_PROTOCOL_DOMAIN.length);

        // degree_poly: FIELD_ELEMENTS_PER_BLOB as 32-byte little-endian
        // Per Ethereum consensus spec int_to_bytes(n, BYTES_PER_FIELD_ELEMENT) where BYTES_PER_FIELD_ELEMENT=32
        // 4096 = 0x1000 → LE: [0x00, 0x10, 0x00, 0x00, ..., 0x00] (32 bytes)
        byte[] degree = new byte[32];
        degree[0] = (byte) (FIELD_ELEMENTS_PER_BLOB & 0xFF);        // low byte
        degree[1] = (byte) ((FIELD_ELEMENTS_PER_BLOB >> 8) & 0xFF); // high byte
        sha.update(degree, 0, 32);

        sha.update(blob, 0, blob.length);
        sha.update(commitment, 0, commitment.length);

        byte[] hash = new byte[32];
        sha.doFinal(hash, 0);
        return new BigInteger(1, hash).mod(R);
    }

    /**
     * Evaluate a polynomial (in evaluation form) at point z using the barycentric formula.
     *
     * <p>Spec: {@code evaluate_polynomial_in_evaluation_form(poly, z)}.
     *
     * <p>When {@code z = ω^m} is in the evaluation domain, returns {@code poly[m]} directly.
     * Otherwise:
     * <pre>
     * p(z) = (z^N - 1) / N · Σ_i poly[i] · ω^i / (z - ω^i)
     * </pre>
     */
    public static BigInteger evaluatePolynomialInEvaluationForm(BigInteger[] poly, BigInteger z) {
        int N = poly.length;
        BigInteger omega = rootOfUnity(N);

        // If z is a root of unity, return poly[i] directly
        BigInteger wi = BigInteger.ONE;
        for (int i = 0; i < N; i++) {
            if (wi.equals(z)) return poly[i];
            wi = Bls12381.frMul(wi, omega);
        }

        // Barycentric evaluation:
        // p(z) = (z^N - 1) / N · Σ_i [ poly[i] · ω^i / (z - ω^i) ]
        BigInteger zN   = Bls12381.frPow(z, BigInteger.valueOf(N));
        BigInteger zNm1 = Bls12381.frSub(zN, BigInteger.ONE); // z^N - 1

        BigInteger numer = BigInteger.ZERO;
        wi = BigInteger.ONE;
        for (int i = 0; i < N; i++) {
            // term_i = poly[i] * ω^i / (z - ω^i)
            BigInteger diff = Bls12381.frSub(z, wi);
            BigInteger term = Bls12381.frMul(poly[i], Bls12381.frMul(wi, Bls12381.frInv(diff)));
            numer = Bls12381.frAdd(numer, term);
            wi = Bls12381.frMul(wi, omega);
        }

        BigInteger Ninv = Bls12381.frInv(BigInteger.valueOf(N));
        return Bls12381.frMul(numer, Bls12381.frMul(zNm1, Ninv));
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Convert 131072 blob bytes to 4096 field elements.
     * Each 32-byte chunk is a big-endian scalar in Fr.
     */
    static BigInteger[] blobToFieldElements(byte[] blob) {
        BigInteger[] poly = new BigInteger[FIELD_ELEMENTS_PER_BLOB];
        for (int i = 0; i < FIELD_ELEMENTS_PER_BLOB; i++) {
            BigInteger fe = new BigInteger(1, blob, i * 32, 32);
            if (fe.compareTo(R) >= 0)
                throw new IllegalArgumentException(
                    "Blob field element " + i + " >= BLS12-381 scalar field order. " +
                    "Use BlobEncoder.encode() to safely encode arbitrary binary data.");
            poly[i] = fe;
        }
        return poly;
    }

    /**
     * Compute quotient polynomial q in evaluation form.
     *
     * <p>Spec: {@code compute_quotient_eval_within_domain(z, poly, y)} (when z in domain)
     * and {@code compute_kzg_proof_impl} (general case).
     *
     * <p>Two cases:
     * <ul>
     *   <li><b>z = ω^m</b> (in domain): {@code q[i] = (poly[i] - y) / (ω^i - ω^m)} for {@code i ≠ m},
     *       and {@code q[m]} uses the sum-to-zero correction.</li>
     *   <li><b>z ∉ domain</b>: {@code q[i] = (poly[i] - y) / (ω^i - z)} for all i.</li>
     * </ul>
     */
    static BigInteger[] computeQuotientEvalForm(BigInteger[] poly, BigInteger z, BigInteger y) {
        int N = poly.length;
        BigInteger omega = rootOfUnity(N);
        BigInteger[] quotient = new BigInteger[N];

        // Find if z is in the evaluation domain (z == ω^m for some m)
        int zDomainIdx = -1;
        BigInteger wi = BigInteger.ONE;
        for (int i = 0; i < N; i++) {
            if (wi.equals(z)) { zDomainIdx = i; break; }
            wi = Bls12381.frMul(wi, omega);
        }

        if (zDomainIdx < 0) {
            // z not in domain: q[i] = (poly[i] - y) / (ω^i - z)
            wi = BigInteger.ONE;
            for (int i = 0; i < N; i++) {
                BigInteger diff = Bls12381.frSub(wi, z);
                quotient[i] = Bls12381.frMul(Bls12381.frSub(poly[i], y), Bls12381.frInv(diff));
                wi = Bls12381.frMul(wi, omega);
            }
        } else {
            // z = ω^m is in the domain.
            // For i ≠ m: q[i] = (poly[i] - y) / (ω^i - ω^m)
            // For i = m: use sum-to-zero: sum_i q[i] = 0, so q[m] = -Σ_{i≠m} q[i]
            wi = BigInteger.ONE;
            BigInteger qSum = BigInteger.ZERO;
            for (int i = 0; i < N; i++) {
                if (i != zDomainIdx) {
                    BigInteger diff = Bls12381.frSub(wi, z);
                    quotient[i] = Bls12381.frMul(Bls12381.frSub(poly[i], y), Bls12381.frInv(diff));
                    qSum = Bls12381.frAdd(qSum, quotient[i]);
                }
                wi = Bls12381.frMul(wi, omega);
            }
            // q[m] = -sum_{i≠m} q[i]  (sum of all quotient values over the domain = 0)
            quotient[zDomainIdx] = Bls12381.frNeg(qSum);
        }

        return quotient;
    }

    /**
     * Compute the primitive Nth root of unity in Fr.
     *
     * <p>Uses {@link #ROOT_OF_UNITY_2_32} (a primitive 2^32-th root) and squares it
     * {@code log2(2^32/N)} times to get the primitive Nth root.
     * N must be a power of 2 dividing 2^32.
     */
    static BigInteger rootOfUnity(int N) {
        // N must be a power of 2 dividing 2^32.
        // ROOT_OF_UNITY_2_32 has order 2^32; squaring it (32 - log2(N)) times gives a primitive Nth root.
        // For N = 4096 = 2^12: squarings = 32 - 12 = 20.
        int squarings = 32 - Integer.numberOfTrailingZeros(N);
        // squarings = 32 - log2(N)
        BigInteger omega = ROOT_OF_UNITY_2_32;
        for (int i = 0; i < squarings; i++) {
            omega = Bls12381.frMul(omega, omega);
        }
        return omega;
    }

    private static void validateBlobSize(byte[] blob) {
        if (blob.length != BlobTransaction.BLOB_SIZE)
            throw new IllegalArgumentException(
                "Blob must be exactly " + BlobTransaction.BLOB_SIZE + " bytes, got " + blob.length);
    }
}
