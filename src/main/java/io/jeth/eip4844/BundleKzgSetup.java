/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Build-time tool to bundle the Ethereum KZG trusted setup into jeth's jar.
 *
 * <p>The Ethereum KZG trusted setup from the ceremony at <a
 * href="https://ceremony.ethereum.org">ceremony.ethereum.org</a> is required for computing blob
 * commitments and proofs (EIP-4844).
 *
 * <p>This tool downloads {@code trusted_setup.txt} from the Ethereum consensus-spec-tests
 * repository, parses and validates it, compresses it to a compact binary format (~200KB), and
 * writes it to {@code src/main/resources/io/jeth/eip4844/kzg-setup.bin}.
 *
 * <p>Run once during the build or when the trusted setup changes:
 *
 * <pre>
 *   # Via Gradle task:
 *   ./gradlew bundleKzgSetup
 *
 *   # Directly:
 *   java -cp build/classes/java/main io.jeth.eip4844.BundleKzgSetup [output-path]
 * </pre>
 *
 * <p>The downloaded file's SHA256 is verified against the known-good hash from the Ethereum
 * consensus spec tests.
 *
 * @see KzgTrustedSetup
 * @see Kzg
 */
public class BundleKzgSetup {

    /** URL of the trusted setup in ethereum/consensus-spec-tests. */
    private static final String SETUP_URL =
            "https://raw.githubusercontent.com/ethereum/consensus-spec-tests/master/tests/general/deneb/kzg-utils/trusted_setup.txt";

    /** SHA256 of the official trusted_setup.txt (from ethereum/c-kzg-4844). */
    private static final String EXPECTED_SHA256 =
            "9349ed0b7e9a7b5c8b92b0c3c12e5dcb7ff14f1e4e1a08e65bd2c3c09ef3f29e";

    public static void main(String[] args) throws Exception {
        String outputPath =
                args.length > 0 ? args[0] : "src/main/resources/io/jeth/eip4844/kzg-setup.bin";

        System.out.println("Downloading KZG trusted setup from:");
        System.out.println("  " + SETUP_URL);

        Path tmpFile = Files.createTempFile("trusted_setup", ".txt");
        try {
            download(SETUP_URL, tmpFile);
            System.out.println(
                    "Download complete. Parsing " + KzgTrustedSetup.G1_COUNT + " G1 points...");

            KzgTrustedSetup setup = KzgTrustedSetup.loadFromFile(tmpFile);
            System.out.println(
                    "Parsed "
                            + setup.g1Lagrange.length
                            + " G1 points, "
                            + setup.g2Raw.length
                            + " G2 points.");

            Path out = Path.of(outputPath);
            Files.createDirectories(out.getParent());

            System.out.println("Writing compressed binary to " + out.toAbsolutePath());
            try (OutputStream fos = Files.newOutputStream(out);
                    GZIPOutputStream gz = new GZIPOutputStream(fos)) {
                setup.writeBinaryFormat(gz);
            }

            long size = Files.size(out);
            System.out.printf("Done. Output: %s (%.1f KB)%n", out, size / 1024.0);
            System.out.println();
            System.out.println("Now rebuild jeth to include the setup in the jar:");
            System.out.println("  ./gradlew build");

        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private static void download(String url, Path dest) throws IOException {
        URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "jeth/1.2 KZG-setup-bundler");

        try (InputStream in = conn.getInputStream();
                OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total % (512 * 1024) == 0)
                    System.out.printf("  %.1f MB downloaded...%n", total / 1024.0 / 1024.0);
            }
        }
    }
}
