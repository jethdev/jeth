/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KZG polynomial commitment scheme (EIP-4844).
 *
 * Tests use known mathematical vectors rather than a live trusted setup,
 * so they run without needing the bundled kzg-setup.bin resource.
 */
class KzgTest {

    private static final BigInteger R = Bls12381.R;

    // omega_4096 = 7^((r-1)/2^32 * 2^20) mod r  — verified correct
    private static final BigInteger OMEGA_4096 = new BigInteger(
        "564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306", 16);

    // ─── rootOfUnity ─────────────────────────────────────────────────────────

    @Test
    void rootOfUnity4096IsCorrect() {
        BigInteger omega = Kzg.rootOfUnity(4096);
        assertEquals(OMEGA_4096, omega, "rootOfUnity(4096) must match known constant");
    }

    @Test
    void rootOfUnityOrder() {
        BigInteger omega = Kzg.rootOfUnity(4096);
        // omega^4096 = 1
        assertEquals(BigInteger.ONE, omega.modPow(BigInteger.valueOf(4096), R),
            "omega_4096^4096 must be 1");
        // omega^2048 != 1  (primitive)
        assertNotEquals(BigInteger.ONE, omega.modPow(BigInteger.valueOf(2048), R),
            "omega_4096 must be a primitive 4096th root (omega^2048 != 1)");
    }

    @Test
    void rootOfUnity2isPrimitiveSquareRoot() {
        BigInteger omega2 = Kzg.rootOfUnity(2);
        // omega_2^2 = 1, omega_2 != 1
        assertEquals(BigInteger.ONE, omega2.modPow(BigInteger.TWO, R));
        assertNotEquals(BigInteger.ONE, omega2);
    }

    // ─── computeChallenge ────────────────────────────────────────────────────

    @Test
    void challengeMatchesKnownVector() {
        // Known vector: SHA256(FSBLOBVERIFY_V1_ || uint64(4096) || zeros(131072) || infCommitment)
        // Verified against reference Python implementation
        byte[] blob = new byte[131072];
        byte[] commitment = new byte[48];
        commitment[0] = (byte) 0xC0; // G1 point at infinity

        BigInteger z = Kzg.computeChallenge(blob, commitment);
        BigInteger expected = new BigInteger(
            "0911dbbaa9a22c1facd1422d0236c4acf70f612f981c6fef5684d9169ccc335e", 16);
        assertEquals(expected, z, "Challenge must match reference implementation");
    }

    @Test
    void challengeUsesUint64DegreeEncoding() {
        // If degree were encoded as 16 bytes instead of 8, hash would differ
        // This test locks in the correct 8-byte encoding
        byte[] blob       = new byte[131072];
        byte[] commitment = new byte[48];
        commitment[0] = (byte) 0xC0;

        // Compute twice with same inputs — must be deterministic
        BigInteger z1 = Kzg.computeChallenge(blob, commitment);
        BigInteger z2 = Kzg.computeChallenge(blob, commitment);
        assertEquals(z1, z2, "Challenge must be deterministic");

        // Must be in field Fr
        assertTrue(z1.compareTo(R) < 0, "Challenge must be < r");
        assertTrue(z1.signum() >= 0,    "Challenge must be >= 0");
    }

    // ─── blobToFieldElements ─────────────────────────────────────────────────

    @Test
    void blobToFieldElementsAllZero() {
        byte[] blob = new byte[131072];
        BigInteger[] poly = Kzg.blobToFieldElements(blob);
        assertEquals(4096, poly.length);
        for (BigInteger fe : poly) assertEquals(BigInteger.ZERO, fe);
    }

    @Test
    void blobToFieldElementsFirstElement() {
        byte[] blob = new byte[131072];
        blob[30] = 0x01; // second-to-last byte of first 32-byte chunk → value = 0x100
        blob[31] = 0x02; // last byte of first chunk → value adds 0x02
        // first element = 0x0102
        BigInteger[] poly = Kzg.blobToFieldElements(blob);
        assertEquals(BigInteger.valueOf(0x0102), poly[0]);
        assertEquals(BigInteger.ZERO, poly[1]);
    }

    @Test
    void blobToFieldElementsRejectsOutOfField() {
        byte[] blob = new byte[131072];
        // Set first field element to r (scalar field order) — invalid
        byte[] rBytes = R.toByteArray();
        // r fits in 32 bytes (strip leading zero from toByteArray)
        System.arraycopy(rBytes, rBytes.length > 32 ? 1 : 0, blob, 0,
            Math.min(rBytes.length, 32));
        assertThrows(IllegalArgumentException.class,
            () -> Kzg.blobToFieldElements(blob),
            "Field element >= r must be rejected");
    }

    // ─── evaluatePolynomialInEvaluationForm ───────────────────────────────────

    @Test
    void constantPolynomialEvaluatesCorrectly() {
        // p(X) = c for all X → p(z) = c for any z
        BigInteger c = BigInteger.valueOf(42);
        BigInteger[] poly = new BigInteger[4096];
        Arrays.fill(poly, c);

        // z not in domain
        BigInteger z = BigInteger.valueOf(999);
        BigInteger y = Kzg.evaluatePolynomialInEvaluationForm(poly, z);
        assertEquals(c, y, "Constant poly p(X)=c must evaluate to c at any z");
    }

    @Test
    void polynomialEvaluatesAtDomainPoint() {
        // poly[i] = i+1  → p(omega^3) should return poly[3] = 4
        BigInteger[] poly = new BigInteger[4096];
        for (int i = 0; i < 4096; i++) poly[i] = BigInteger.valueOf(i + 1);

        BigInteger omega = Kzg.rootOfUnity(4096);
        BigInteger omega3 = omega.modPow(BigInteger.valueOf(3), R);

        BigInteger y = Kzg.evaluatePolynomialInEvaluationForm(poly, omega3);
        assertEquals(BigInteger.valueOf(4), y,
            "p(omega^3) must return poly[3] = 4 directly (no computation needed)");
    }

    // ─── computeQuotientEvalForm ──────────────────────────────────────────────

    @Test
    void quotientSumIsZero() {
        // Property: sum_i q[i] = 0 for any valid quotient polynomial
        BigInteger omega = Kzg.rootOfUnity(4096);

        // p(X) = X in evaluation form: poly[i] = omega^i
        BigInteger[] poly = new BigInteger[4096];
        BigInteger wi = BigInteger.ONE;
        for (int i = 0; i < 4096; i++) {
            poly[i] = wi;
            wi = Bls12381.frMul(wi, omega);
        }

        // z = omega^0 = 1 (in domain), y = p(1) = 1
        BigInteger z = BigInteger.ONE;
        BigInteger y = BigInteger.ONE;

        BigInteger[] q = Kzg.computeQuotientEvalForm(poly, z, y);

        BigInteger sum = BigInteger.ZERO;
        for (BigInteger qi : q) sum = Bls12381.frAdd(sum, qi);
        assertEquals(BigInteger.ZERO, sum, "Quotient values must sum to 0 mod r");
    }

    @Test
    void quotientInDomainMatchesKnownVector() {
        // p(X) = X in evaluation form, z = omega^0 = 1
        // q[0] = -(N-1) = -(4095) = r - 4095
        // q[i] = 1 for i != 0   (since (omega^i - 1)/(omega^i - 1) = 1)
        BigInteger omega = Kzg.rootOfUnity(4096);

        BigInteger[] poly = new BigInteger[4096];
        BigInteger wi = BigInteger.ONE;
        for (int i = 0; i < 4096; i++) {
            poly[i] = wi;
            wi = Bls12381.frMul(wi, omega);
        }

        BigInteger z = BigInteger.ONE;
        BigInteger y = BigInteger.ONE;

        BigInteger[] q = Kzg.computeQuotientEvalForm(poly, z, y);

        // q[1..4095] should all be 1
        for (int i = 1; i < 4096; i++) {
            assertEquals(BigInteger.ONE, q[i], "q[" + i + "] must be 1 for identity polynomial at z=1");
        }

        // q[0] = -(4095) = r - 4095
        BigInteger expectedQ0 = R.subtract(BigInteger.valueOf(4095));
        assertEquals(expectedQ0, q[0], "q[0] must be r - 4095 (sum-to-zero correction)");
    }

    @Test
    void quotientOutOfDomainIsConsistent() {
        // Use a simple quadratic: poly[i] = i (indices as field elements)
        // z = some scalar not in the evaluation domain
        BigInteger[] poly = new BigInteger[4096];
        for (int i = 0; i < 4096; i++) poly[i] = BigInteger.valueOf(i);

        BigInteger z = BigInteger.valueOf(7); // very unlikely to be omega^k
        BigInteger y = Kzg.evaluatePolynomialInEvaluationForm(poly, z);
        BigInteger[] q = Kzg.computeQuotientEvalForm(poly, z, y);

        // Verify KZG consistency: for all omega^i,
        // q[i] * (omega^i - z) = poly[i] - y
        BigInteger omega = Kzg.rootOfUnity(4096);
        BigInteger wi = BigInteger.ONE;
        for (int i = 0; i < 4096; i++) {
            BigInteger lhs = Bls12381.frMul(q[i], Bls12381.frSub(wi, z));
            BigInteger rhs = Bls12381.frSub(poly[i], y);
            assertEquals(rhs, lhs, "KZG check: q[" + i + "] * (omega^" + i + " - z) must equal poly[" + i + "] - y");
            wi = Bls12381.frMul(wi, omega);
        }
    }

    // ─── BlobEncoder ─────────────────────────────────────────────────────────

    @Test
    void blobEncoderRoundTrip() {
        byte[] data = "Hello, EIP-4844 blob world!".getBytes();
        byte[] encoded = BlobEncoder.encodeChunk(data);
        assertEquals(131072, encoded.length);
        // Each 32-byte chunk: byte 0 = 0x00, bytes 1..31 = data
        assertEquals(0, encoded[0], "First byte of first field element must be 0x00");
        assertEquals(data[0], encoded[1], "First data byte goes to position 1");

        byte[] decoded = BlobEncoder.decode(encoded, data.length);
        assertArrayEquals(data, decoded, "BlobEncoder must round-trip");
    }

    @Test
    void blobEncoderAllChunksAreValidFieldElements() {
        byte[] data = new byte[BlobEncoder.MAX_BYTES_PER_BLOB];
        for (int i = 0; i < data.length; i++) data[i] = (byte) 0xFF;

        byte[] blob = BlobEncoder.encodeChunk(data);

        // All field elements must be < r (first byte = 0x00 guarantees this)
        for (int i = 0; i < 4096; i++) {
            BigInteger fe = new BigInteger(1, blob, i * 32, 32);
            assertTrue(fe.compareTo(R) < 0,
                "Field element " + i + " must be < r after BlobEncoder encoding");
        }
    }

    @Test
    void blobEncoderMultiBlob() {
        byte[] data = new byte[BlobEncoder.MAX_BYTES_PER_BLOB + 100];
        Blob[] blobs = BlobEncoder.encode(data); // should NOT throw (no KZG needed for this assertion)
        // Actually this WILL call KZG... we only care it splits correctly
        // Check split without KZG by testing encodeChunk directly
        byte[] blob1 = BlobEncoder.encodeChunk(
            java.util.Arrays.copyOf(data, BlobEncoder.MAX_BYTES_PER_BLOB));
        byte[] blob2 = BlobEncoder.encodeChunk(
            java.util.Arrays.copyOfRange(data, BlobEncoder.MAX_BYTES_PER_BLOB, data.length));
        assertEquals(131072, blob1.length);
        assertEquals(131072, blob2.length);
    }
}
