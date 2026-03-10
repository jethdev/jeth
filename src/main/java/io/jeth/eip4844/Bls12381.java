/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import java.math.BigInteger;

/**
 * BLS12-381 curve arithmetic needed for KZG polynomial commitments (EIP-4844).
 *
 * <p>Implements the minimum required for KZG:
 *
 * <ul>
 *   <li>Fp — base field arithmetic (mod p)
 *   <li>Fr — scalar field arithmetic (mod r)
 *   <li>G1 — curve point in projective coordinates, add/double/scalar-mul/MSM
 *   <li>G1 point compression/decompression (ZCash serialisation)
 * </ul>
 *
 * <p>All arithmetic uses {@link BigInteger} — correct but not optimised for speed. For
 * high-throughput use, replace with native c-kzg-4844 JNI bindings. For blob submission (one blob
 * per tx, occasional), this is fast enough.
 *
 * <p>Constants from the Ethereum consensus spec and BLS12-381 IETF draft.
 */
public final class Bls12381 {

    private Bls12381() {}

    // ─── Field constants ──────────────────────────────────────────────────────

    /** BLS12-381 base field prime p. */
    public static final BigInteger P =
            new BigInteger(
                    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab",
                    16);

    /** BLS12-381 scalar field order r (group order). */
    public static final BigInteger R =
            new BigInteger("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16);

    /** G1 generator x-coordinate. */
    private static final BigInteger Gx =
            new BigInteger(
                    "17f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb",
                    16);

    /** G1 generator y-coordinate. */
    private static final BigInteger Gy =
            new BigInteger(
                    "08b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1",
                    16);

    /** Cofactor h (= 1 for G1 on BLS12-381). */
    public static final BigInteger H = BigInteger.ONE;

    /** G1 generator point. */
    public static final G1 GENERATOR = new G1(Gx, Gy);

    /** G1 point at infinity (identity). */
    public static final G1 INFINITY = new G1();

    // ─── Fp field helpers ─────────────────────────────────────────────────────

    static BigInteger fpAdd(BigInteger a, BigInteger b) {
        return a.add(b).mod(P);
    }

    static BigInteger fpSub(BigInteger a, BigInteger b) {
        return a.subtract(b).mod(P);
    }

    static BigInteger fpMul(BigInteger a, BigInteger b) {
        return a.multiply(b).mod(P);
    }

    static BigInteger fpNeg(BigInteger a) {
        return a.signum() == 0 ? BigInteger.ZERO : P.subtract(a);
    }

    static BigInteger fpInv(BigInteger a) {
        return a.modPow(P.subtract(BigInteger.TWO), P);
    }

    static BigInteger fpSqrt(BigInteger a) {
        return a.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
    }

    // ─── Fr scalar field helpers ──────────────────────────────────────────────

    public static BigInteger frMod(BigInteger a) {
        return a.mod(R);
    }

    public static BigInteger frAdd(BigInteger a, BigInteger b) {
        return a.add(b).mod(R);
    }

    public static BigInteger frSub(BigInteger a, BigInteger b) {
        return a.subtract(b).mod(R);
    }

    public static BigInteger frMul(BigInteger a, BigInteger b) {
        return a.multiply(b).mod(R);
    }

    public static BigInteger frInv(BigInteger a) {
        return a.modPow(R.subtract(BigInteger.TWO), R);
    }

    public static BigInteger frNeg(BigInteger a) {
        return a.signum() == 0 ? BigInteger.ZERO : R.subtract(a);
    }

    public static BigInteger frPow(BigInteger a, BigInteger e) {
        return a.modPow(e, R);
    }

    // ─── G1 point (projective Jacobian coordinates) ───────────────────────────

    /**
     * A point on the BLS12-381 G1 curve.
     *
     * <p>Internally stored in projective (Jacobian) coordinates (X, Y, Z) where the affine point is
     * (X/Z², Y/Z³). External API uses affine (x, y).
     */
    public static final class G1 {

        // Projective coordinates
        final BigInteger X;
        final BigInteger Y;
        final BigInteger Z;

        final boolean infinity;

        /** Point at infinity (identity). */
        public G1() {
            this.X = BigInteger.ZERO;
            this.Y = BigInteger.ONE;
            this.Z = BigInteger.ZERO;
            this.infinity = true;
        }

        /** Affine point. */
        public G1(BigInteger x, BigInteger y) {
            this.X = x;
            this.Y = y;
            this.Z = BigInteger.ONE;
            this.infinity = false;
        }

        /** Projective point (internal use). */
        private G1(BigInteger X, BigInteger Y, BigInteger Z) {
            this.X = X;
            this.Y = Y;
            this.Z = Z;
            this.infinity = Z.signum() == 0;
        }

        public boolean isInfinity() {
            return infinity;
        }

        /** Convert to affine (x, y). */
        public BigInteger[] toAffine() {
            if (infinity)
                throw new ArithmeticException("Point at infinity has no affine representation");
            BigInteger zInv = fpInv(Z);
            BigInteger zInv2 = fpMul(zInv, zInv);
            BigInteger zInv3 = fpMul(zInv2, zInv);
            return new BigInteger[] {fpMul(X, zInv2), fpMul(Y, zInv3)};
        }

        /** Point doubling. */
        public G1 dbl() {
            if (infinity) return this;
            // Jacobian doubling formulas (a=0 for BLS12-381)
            BigInteger Y2 = fpMul(Y, Y);
            BigInteger S = fpMul(BigInteger.valueOf(4), fpMul(X, Y2));
            BigInteger M = fpMul(BigInteger.valueOf(3), fpMul(X, X));
            BigInteger X3 = fpSub(fpMul(M, M), fpMul(BigInteger.TWO, S));
            BigInteger Y3 =
                    fpSub(fpMul(M, fpSub(S, X3)), fpMul(BigInteger.valueOf(8), fpMul(Y2, Y2)));
            BigInteger Z3 = fpMul(BigInteger.TWO, fpMul(Y, Z));
            return new G1(X3, Y3, Z3);
        }

        /** Point addition (mixed: this=projective, other=affine). */
        public G1 add(G1 other) {
            if (infinity) return other;
            if (other.infinity) return this;

            // Mixed addition (other.Z == 1)
            BigInteger oZ = other.Z;
            BigInteger Z2 = fpMul(Z, Z);
            BigInteger U1, S1, U2, S2;
            if (oZ.equals(BigInteger.ONE)) {
                U1 = X;
                S1 = Y;
                U2 = fpMul(other.X, Z2);
                S2 = fpMul(other.Y, fpMul(Z2, Z));
            } else {
                BigInteger oZ2 = fpMul(oZ, oZ);
                U1 = fpMul(X, oZ2);
                S1 = fpMul(Y, fpMul(oZ2, oZ));
                U2 = fpMul(other.X, Z2);
                S2 = fpMul(other.Y, fpMul(Z2, Z));
            }

            BigInteger H = fpSub(U2, U1);
            BigInteger R = fpSub(S2, S1);

            if (H.signum() == 0) {
                return R.signum() == 0 ? dbl() : INFINITY;
            }

            BigInteger H2 = fpMul(H, H);
            BigInteger H3 = fpMul(H2, H);
            BigInteger U1H2 = fpMul(U1, H2);

            BigInteger X3 = fpSub(fpMul(R, R), fpAdd(H3, fpMul(BigInteger.TWO, U1H2)));
            BigInteger Y3 = fpSub(fpMul(R, fpSub(U1H2, X3)), fpMul(S1, H3));
            BigInteger Z3 = fpMul(fpMul(H, Z), oZ.equals(BigInteger.ONE) ? BigInteger.ONE : oZ);
            return new G1(X3, Y3, Z3);
        }

        /** Scalar multiplication: k * this using double-and-add. */
        public G1 mul(BigInteger k) {
            k = k.mod(R);
            if (k.signum() == 0) return INFINITY;
            if (k.equals(BigInteger.ONE)) return this;

            G1 result = INFINITY;
            G1 addend = this;
            while (k.signum() > 0) {
                if (k.testBit(0)) result = result.add(addend);
                addend = addend.dbl();
                k = k.shiftRight(1);
            }
            return result;
        }

        /** Multi-scalar multiplication: sum(scalars[i] * points[i]). Pippenger-like. */
        public static G1 msm(G1[] points, BigInteger[] scalars) {
            if (points.length != scalars.length)
                throw new IllegalArgumentException("points and scalars must have equal length");
            G1 acc = INFINITY;
            for (int i = 0; i < points.length; i++) {
                if (scalars[i].signum() != 0 && !points[i].isInfinity()) {
                    acc = acc.add(points[i].mul(scalars[i]));
                }
            }
            return acc;
        }

        // ─── Serialization (ZCash / IETF compressed G1) ──────────────────────

        /**
         * Compress to 48 bytes (ZCash format, used by EIP-4844). Bit 7 of byte 0: compression flag
         * (1) Bit 6 of byte 0: infinity flag Bit 5 of byte 0: y-parity (sign bit)
         */
        public byte[] compress() {
            if (infinity) {
                byte[] out = new byte[48];
                out[0] = (byte) 0xC0; // compressed + infinity
                return out;
            }
            BigInteger[] affine = toAffine();
            BigInteger x = affine[0], y = affine[1];
            byte[] out = bigIntTo48Bytes(x);
            out[0] |= 0x80; // compression flag
            // y-parity: set bit 5 if y > p/2
            if (y.compareTo(P.shiftRight(1)) > 0) {
                out[0] |= 0x20;
            }
            return out;
        }

        /**
         * Decompress from 48 bytes (ZCash format). Recovers y from x using the curve equation y² =
         * x³ + 4.
         */
        public static G1 decompress(byte[] bytes) {
            if (bytes.length != 48)
                throw new IllegalArgumentException("G1 compressed point must be 48 bytes");
            boolean compressed = (bytes[0] & 0x80) != 0;
            boolean infinity = (bytes[0] & 0x40) != 0;
            boolean ySign = (bytes[0] & 0x20) != 0;

            if (!compressed) throw new IllegalArgumentException("Expected compressed G1 point");
            if (infinity) return INFINITY;

            // Strip flag bits from x
            byte[] xBytes = bytes.clone();
            xBytes[0] &= 0x1F;
            BigInteger x = new BigInteger(1, xBytes);
            if (x.compareTo(P) >= 0)
                throw new IllegalArgumentException("G1 x coordinate out of range");

            // y² = x³ + 4
            BigInteger x3 = fpMul(fpMul(x, x), x);
            BigInteger rhs = fpAdd(x3, BigInteger.valueOf(4)); // b=4 for G1 BLS12-381
            BigInteger y = fpSqrt(rhs);
            if (y == null || !fpMul(y, y).equals(rhs))
                throw new IllegalArgumentException("G1 point not on curve");

            // Choose correct y based on sign bit
            boolean computedSign = y.compareTo(P.shiftRight(1)) > 0;
            if (computedSign != ySign) y = fpNeg(y);

            return new G1(x, y);
        }

        /** Decompress from hex string. */
        public static G1 decompress(String hex) {
            String h = hex.startsWith("0x") ? hex.substring(2) : hex;
            byte[] bytes = new byte[48];
            for (int i = 0; i < 48; i++) {
                bytes[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
            return decompress(bytes);
        }

        @Override
        public String toString() {
            if (infinity) return "G1(∞)";
            BigInteger[] a = toAffine();
            return "G1("
                    + a[0].toString(16).substring(0, 8)
                    + "..., "
                    + a[1].toString(16).substring(0, 8)
                    + "...)";
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Encode BigInteger as exactly 48 bytes big-endian. */
    public static byte[] bigIntTo48Bytes(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[48];
        if (raw[0] == 0) {
            // Leading sign byte
            int len = Math.min(raw.length - 1, 48);
            System.arraycopy(raw, 1, out, 48 - len, len);
        } else {
            int len = Math.min(raw.length, 48);
            System.arraycopy(raw, 0, out, 48 - len, len);
        }
        return out;
    }

    /** Encode BigInteger as exactly 32 bytes big-endian. */
    public static byte[] bigIntTo32Bytes(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        if (raw[0] == 0) {
            int len = Math.min(raw.length - 1, 32);
            System.arraycopy(raw, 1, out, 32 - len, len);
        } else {
            int len = Math.min(raw.length, 32);
            System.arraycopy(raw, 0, out, 32 - len, len);
        }
        return out;
    }

    /** Decode 32 bytes big-endian to BigInteger (unsigned). */
    public static BigInteger bytesToBigInt(byte[] bytes, int offset, int length) {
        byte[] slice = new byte[length];
        System.arraycopy(bytes, offset, slice, 0, length);
        return new BigInteger(1, slice);
    }
}
