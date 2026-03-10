/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BLS12-381 field and G1 group arithmetic.
 * Uses known constants from the BLS12-381 spec and IETF drafts.
 */
class Bls12381Test {

    @Test
    void generatorIsOnCurve() {
        Bls12381.G1 G = Bls12381.GENERATOR;
        BigInteger[] affine = G.toAffine();
        BigInteger x = affine[0], y = affine[1];

        // y^2 = x^3 + 4 (BLS12-381 G1 curve equation)
        BigInteger lhs = Bls12381.fpMul(y, y);
        BigInteger rhs = Bls12381.fpAdd(Bls12381.fpMul(Bls12381.fpMul(x, x), x), BigInteger.valueOf(4));
        assertEquals(lhs, rhs, "Generator must satisfy y^2 = x^3 + 4");
    }

    @Test
    void generatorTimesOrderIsInfinity() {
        Bls12381.G1 result = Bls12381.GENERATOR.mul(Bls12381.R);
        assertTrue(result.isInfinity(), "[r]G must be the point at infinity");
    }

    @Test
    void scalarMulCommutativity() {
        BigInteger a = new BigInteger("2893477812");
        BigInteger b = new BigInteger("7731894012");
        Bls12381.G1 aG = Bls12381.GENERATOR.mul(a);
        Bls12381.G1 bG = Bls12381.GENERATOR.mul(b);

        Bls12381.G1 aG_then_bG = aG.mul(b);
        Bls12381.G1 bG_then_aG = bG.mul(a);
        Bls12381.G1 abG        = Bls12381.GENERATOR.mul(a.multiply(b).mod(Bls12381.R));

        assertG1Equal(aG_then_bG, bG_then_aG, "(a·b)G must equal b·(a·G)");
        assertG1Equal(abG, aG_then_bG, "(ab)G must equal b·(a·G)");
    }

    @Test
    void addThenNegateIsInfinity() {
        Bls12381.G1 G = Bls12381.GENERATOR.mul(BigInteger.valueOf(12345));
        // Negate: same x, negated y
        BigInteger[] a = G.toAffine();
        Bls12381.G1 negG = new Bls12381.G1(a[0], Bls12381.fpNeg(a[1]));
        Bls12381.G1 sum  = G.add(negG);
        assertTrue(sum.isInfinity(), "G + (-G) must be infinity");
    }

    @Test
    void doublingMatchesSelfAdd() {
        Bls12381.G1 G  = Bls12381.GENERATOR.mul(BigInteger.valueOf(999));
        Bls12381.G1 d1 = G.dbl();
        Bls12381.G1 d2 = G.add(G);
        assertG1Equal(d1, d2, "2G via double must equal G + G");
    }

    @Test
    void compressDecompressRoundTrip() {
        BigInteger scalar = new BigInteger("1234567890abcdef", 16);
        Bls12381.G1 P     = Bls12381.GENERATOR.mul(scalar);
        byte[]      comp  = P.compress();
        Bls12381.G1 P2    = Bls12381.G1.decompress(comp);
        assertG1Equal(P, P2, "compress → decompress must round-trip");
    }

    @Test
    void compressInfinityFlag() {
        byte[] inf = Bls12381.INFINITY.compress();
        assertEquals(48, inf.length);
        assertEquals((byte) 0xC0, inf[0], "Infinity must have bits 7+6 set (0xC0)");
        for (int i = 1; i < 48; i++) assertEquals(0, inf[i]);
    }

    @Test
    void msmConsistentWithIndividualMuls() {
        int n = 5;
        Bls12381.G1[] points  = new Bls12381.G1[n];
        BigInteger[]  scalars = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            scalars[i] = BigInteger.valueOf(i + 1);
            points[i]  = Bls12381.GENERATOR.mul(BigInteger.valueOf(100 + i));
        }

        Bls12381.G1 msm = Bls12381.G1.msm(points, scalars);

        Bls12381.G1 manual = Bls12381.INFINITY;
        for (int i = 0; i < n; i++) manual = manual.add(points[i].mul(scalars[i]));

        assertG1Equal(manual, msm, "MSM must match sum of individual scalar muls");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void assertG1Equal(Bls12381.G1 expected, Bls12381.G1 actual, String msg) {
        if (expected.isInfinity() && actual.isInfinity()) return;
        assertFalse(expected.isInfinity() ^ actual.isInfinity(), msg + " (one is infinity, other isn't)");
        BigInteger[] ea = expected.toAffine();
        BigInteger[] aa = actual.toAffine();
        assertEquals(ea[0], aa[0], msg + " (x coordinates differ)");
        assertEquals(ea[1], aa[1], msg + " (y coordinates differ)");
    }
}
