/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.codegen;

import java.io.File;
import java.nio.file.Path;

/**
 * CLI entry point for generating contract wrappers.
 *
 * Usage:
 * <pre>
 *   java -cp jeth.jar io.jeth.codegen.ContractGeneratorCli \
 *       --abi path/to/Greeter.json \
 *       --name Greeter \
 *       --package com.myproject.contracts \
 *       --out src/main/java/com/myproject/contracts
 * </pre>
 *
 * Or with Gradle:
 * <pre>
 *   ./gradlew generateContract -Pabi=path/to/Greeter.json -Pname=Greeter -Ppackage=com.example
 * </pre>
 */
public class ContractGeneratorCli {

    public static void main(String[] args) throws Exception {
        String abiPath = null, name = null, pkg = "generated", out = ".";

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--abi"     -> abiPath = args[++i];
                case "--name"    -> name    = args[++i];
                case "--package" -> pkg     = args[++i];
                case "--out"     -> out     = args[++i];
            }
        }

        if (abiPath == null || name == null) {
            System.err.println("Usage: --abi <file> --name <ClassName> [--package <pkg>] [--out <dir>]");
            System.exit(1);
        }

        Path outPath = ContractGenerator.generate(name, new File(abiPath), Path.of(out), pkg);
        System.out.println("Generated: " + outPath.toAbsolutePath());
    }
}
