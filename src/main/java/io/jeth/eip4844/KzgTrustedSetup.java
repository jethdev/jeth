/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Loads and caches the Ethereum KZG trusted setup for EIP-4844 blob transactions.
 *
 * <p>The trusted setup (from the KZG ceremony at <a href="https://ceremony.ethereum.org">
 * ceremony.ethereum.org</a>) contains 4096 G1 points in both monomial and Lagrange forms, plus 65
 * G2 points for proof verification.
 *
 * <p>Three ways to supply the trusted setup:
 *
 * <ol>
 *   <li><b>Bundled</b> (default): jeth ships a compressed copy embedded in the jar. No
 *       configuration needed — it loads automatically.
 *   <li><b>Custom path</b>: {@code KzgTrustedSetup.loadFromFile(path)} for a local {@code
 *       trusted_setup.txt} in the Ethereum consensus-spec format.
 *   <li><b>Environment variable</b>: set {@code JETH_KZG_SETUP} to a file path and {@link
 *       #getInstance()} will load it automatically.
 * </ol>
 *
 * <p>Setup format: the standard {@code trusted_setup.txt} from <a
 * href="https://github.com/ethereum/consensus-spec-tests">ethereum/consensus-spec-tests</a>:
 *
 * <pre>
 * g1_monomial
 * 0x... (4096 lines)
 * g1_lagrange
 * 0x... (4096 lines)
 * g2_monomial
 * 0x... (65 lines)
 * </pre>
 */
public final class KzgTrustedSetup {

    /** Number of G1 points (= FIELD_ELEMENTS_PER_BLOB). */
    public static final int G1_COUNT = 4096;

    /** Number of G2 points for proof verification. */
    public static final int G2_COUNT = 65;

    private static final String BUNDLED_RESOURCE = "/io/jeth/eip4844/kzg-setup.bin";
    private static final String ENV_SETUP_PATH = "JETH_KZG_SETUP";

    private static volatile KzgTrustedSetup INSTANCE;

    /**
     * G1 setup points in <b>Lagrange (evaluation) form</b>: [L_0(τ)]G1, ..., [L_4095(τ)]G1. Used
     * for commitment and proof MSM (primary setup array per EIP-4844 spec).
     */
    final Bls12381.G1[] g1Lagrange;

    /** G1 setup points in <b>monomial (powers) form</b>: [τ^0]G1, ..., [τ^4095]G1. */
    final Bls12381.G1[] g1Monomial;

    /** G2 points (raw compressed bytes, 96 bytes each) for proof verification. */
    final byte[][] g2Raw;

    private KzgTrustedSetup(Bls12381.G1[] g1Lagrange, Bls12381.G1[] g1Monomial, byte[][] g2Raw) {
        this.g1Lagrange = g1Lagrange;
        this.g1Monomial = g1Monomial;
        this.g2Raw = g2Raw;
    }

    // Public API

    public static KzgTrustedSetup getInstance() {
        KzgTrustedSetup local = INSTANCE;
        if (local != null) return local;
        synchronized (KzgTrustedSetup.class) {
            if (INSTANCE == null) {
                String envPath = System.getenv(ENV_SETUP_PATH);
                INSTANCE = envPath != null ? loadFromFile(Path.of(envPath)) : loadBundled();
            }
            return INSTANCE;
        }
    }

    public static KzgTrustedSetup loadFromFile(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return parseTextFormat(reader);
        } catch (IOException e) {
            throw new KzgException(
                    "Failed to load KZG trusted setup from " + path + ": " + e.getMessage(), e);
        }
    }

    public static synchronized void setInstance(KzgTrustedSetup setup) {
        INSTANCE = setup;
    }

    // Loading

    private static KzgTrustedSetup loadBundled() {
        URL url = KzgTrustedSetup.class.getResource(BUNDLED_RESOURCE);
        if (url == null) {
            throw new KzgException(
                    "Bundled KZG trusted setup not found in jar ("
                            + BUNDLED_RESOURCE
                            + "). "
                            + "Run: ./gradlew bundleKzgSetup   then rebuild the jar. "
                            + "Or set JETH_KZG_SETUP=/path/to/trusted_setup.txt.");
        }
        try (InputStream raw = url.openStream();
                GZIPInputStream gz = new GZIPInputStream(raw);
                DataInputStream dis = new DataInputStream(new BufferedInputStream(gz))) {
            return readBinaryFormat(dis);
        } catch (IOException e) {
            throw new KzgException("Failed to read bundled KZG setup: " + e.getMessage(), e);
        }
    }

    static KzgTrustedSetup parseTextFormat(BufferedReader reader) throws IOException {
        Bls12381.G1[] g1Monomial = new Bls12381.G1[G1_COUNT];
        Bls12381.G1[] g1Lagrange = new Bls12381.G1[G1_COUNT];
        byte[][] g2Raw = new byte[G2_COUNT][];

        String line;

        skipTo(reader, "g1_monomial");
        for (int i = 0; i < G1_COUNT; i++) {
            line = reader.readLine();
            if (line == null) throw new IOException("Truncated at g1_monomial[" + i + "]");
            g1Monomial[i] = Bls12381.G1.decompress(line.trim());
        }

        skipTo(reader, "g1_lagrange");
        for (int i = 0; i < G1_COUNT; i++) {
            line = reader.readLine();
            if (line == null) throw new IOException("Truncated at g1_lagrange[" + i + "]");
            g1Lagrange[i] = Bls12381.G1.decompress(line.trim());
        }

        skipTo(reader, "g2_monomial");
        for (int i = 0; i < G2_COUNT; i++) {
            line = reader.readLine();
            if (line == null) throw new IOException("Truncated at g2_monomial[" + i + "]");
            String hex = line.trim().startsWith("0x") ? line.trim().substring(2) : line.trim();
            g2Raw[i] = hexToBytes(hex);
        }

        return new KzgTrustedSetup(g1Lagrange, g1Monomial, g2Raw);
    }

    private static void skipTo(BufferedReader reader, String section) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().equals(section)) return;
        }
        throw new IOException("Section '" + section + "' not found in trusted setup file");
    }

    private static KzgTrustedSetup readBinaryFormat(DataInputStream dis) throws IOException {
        int version = dis.readInt();
        int g1Count = dis.readInt();
        int g2Count = dis.readInt();
        if (version != 1) throw new IOException("Unknown kzg-setup.bin version: " + version);

        Bls12381.G1[] g1Monomial = readG1Array(dis, g1Count);
        Bls12381.G1[] g1Lagrange = readG1Array(dis, g1Count);

        byte[][] g2 = new byte[g2Count][];
        for (int i = 0; i < g2Count; i++) {
            g2[i] = new byte[96];
            dis.readFully(g2[i]);
        }
        return new KzgTrustedSetup(g1Lagrange, g1Monomial, g2);
    }

    private static Bls12381.G1[] readG1Array(DataInputStream dis, int count) throws IOException {
        Bls12381.G1[] points = new Bls12381.G1[count];
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[48];
            dis.readFully(bytes);
            points[i] = Bls12381.G1.decompress(bytes);
        }
        return points;
    }

    /**
     * Write the compact gzip-binary format for bundling in the jar. Layout: version(4) | g1Count(4)
     * | g2Count(4) | g1Monomial(g1Count×48) | g1Lagrange(g1Count×48) | g2Raw(g2Count×96)
     *
     * <p>Run {@code ./gradlew bundleKzgSetup} to generate from {@code trusted_setup.txt}.
     */
    public void writeBinaryFormat(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(out));
        dos.writeInt(1);
        dos.writeInt(g1Monomial.length);
        dos.writeInt(g2Raw.length);
        for (Bls12381.G1 p : g1Monomial) dos.write(p.compress());
        for (Bls12381.G1 p : g1Lagrange) dos.write(p.compress());
        for (byte[] g2 : g2Raw) dos.write(g2);
        dos.flush();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Thrown when the KZG setup cannot be loaded or a computation fails. */
    public static class KzgException extends RuntimeException {
        public KzgException(String msg) {
            super(msg);
        }

        public KzgException(String msg, Throwable c) {
            super(msg, c);
        }
    }
}
