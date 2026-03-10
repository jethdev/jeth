/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Compiles Solidity contracts using the {@code ethereum/solc:0.8.20} Docker image, eliminating the
 * need for a local {@code solc} installation or environment variables.
 *
 * <p>A single shared container instance is started once per JVM run, stays alive via a {@code tail
 * -f /dev/null} entrypoint, and each compile is an {@code execInContainer} call. Results are cached
 * so contracts are only compiled once.
 *
 * <p>Usage:
 *
 * <pre>
 * String tokenBytecode   = SolcContainer.compile("TestToken");
 * String counterBytecode = SolcContainer.compile("TestCounter");
 * Map&lt;String,String&gt; all = SolcContainer.compileAll();
 * </pre>
 */
public class SolcContainer {

    private static final String SOLC_IMAGE = "ethereum/solc:0.8.20";
    private static final String WORK_DIR = "/contracts";

    static final String[] CONTRACT_NAMES = {"TestToken", "TestCounter", "TestERC1155"};

    /** Shared, lazily-started container — kept alive for exec calls. */
    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(DockerImageName.parse(SOLC_IMAGE))
                    .withCreateContainerCmdModifier(
                            cmd ->
                                    // Override entrypoint so the container stays alive for exec
                                    // calls
                                    cmd.withEntrypoint("tail", "-f", "/dev/null"))
                    .waitingFor(Wait.forSuccessfulCommand("true"));

    /** Bytecode cache: contract name -> "0x..." */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** Start the shared container (idempotent). */
    private static synchronized void ensureRunning() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
    }

    /**
     * Compile all known test contracts and return name -> bytecode map. Safe to call multiple times
     * — cached after first run.
     */
    public static Map<String, String> compileAll() {
        for (String name : CONTRACT_NAMES) {
            compile(name);
        }
        return Map.copyOf(CACHE);
    }

    /**
     * Compile the named contract (filename = &lt;name&gt;.sol). Returns 0x-prefixed deployment
     * bytecode. Cached after first compile.
     */
    public static String compile(String contractName) {
        return CACHE.computeIfAbsent(contractName, SolcContainer::doCompile);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String doCompile(String contractName) {
        ensureRunning();

        String fileName = contractName + ".sol";
        String dest = WORK_DIR + "/" + fileName;

        URL resource = SolcContainer.class.getResource("/contracts/" + fileName);
        if (resource == null) {
            throw new IllegalStateException("Cannot find /contracts/" + fileName + " in classpath");
        }

        try {
            // Copy the .sol file into the running container
            CONTAINER.copyFileToContainer(
                    MountableFile.forClasspathResource("/contracts/" + fileName), dest);

            // Compile inside the container
            ExecResult result =
                    CONTAINER.execInContainer(
                            "solc", "--bin", "--optimize", "--evm-version", "berlin", dest);

            if (result.getExitCode() != 0) {
                throw new RuntimeException(
                        "solc failed (exit "
                                + result.getExitCode()
                                + "):\nstderr: "
                                + result.getStderr());
            }

            String bytecode = extractBytecode(result.getStdout(), contractName);
            System.out.println(
                    "[SolcContainer] "
                            + contractName
                            + " compiled: "
                            + (bytecode.length() / 2 - 1)
                            + " bytes");
            return bytecode;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Compilation of " + fileName + " failed", e);
        }
    }

    /**
     * Parse {@code solc --bin} output to extract the hex bytecode for the named contract.
     *
     * <p>solc output format:
     *
     * <pre>
     * ======= /contracts/TestToken.sol:TestToken =======
     * Binary:
     * 608060405234801561001057...
     * </pre>
     */
    static String extractBytecode(String solcOutput, String contractName) {
        // Match the section for this specific contract
        Pattern specific =
                Pattern.compile(
                        Pattern.quote(contractName) + " =+\nBinary:\n([0-9a-fA-F]+)",
                        Pattern.MULTILINE);
        Matcher m = specific.matcher(solcOutput);
        if (m.find()) return "0x" + m.group(1).trim();

        // Broader fallback: any "Binary:\n<hex>" block
        Pattern fallback = Pattern.compile("Binary:\n([0-9a-fA-F]+)", Pattern.MULTILINE);
        Matcher fm = fallback.matcher(solcOutput);
        if (fm.find()) return "0x" + fm.group(1).trim();

        throw new RuntimeException(
                "Could not find bytecode for '" + contractName + "' in:\n" + solcOutput);
    }
}
