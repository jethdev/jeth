/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.codegen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Generates a type-safe Java contract wrapper from a Solidity ABI JSON.
 *
 * Input:  standard ABI JSON array (from Hardhat, Foundry, solc, Etherscan, etc.)
 * Output: a .java file where every ABI function becomes a real typed Java method.
 *
 * Usage:
 * <pre>
 *   // From file
 *   ContractGenerator.generate("Greeter", abiFile, outputDir, "com.myproject.contracts");
 *
 *   // From JSON string
 *   ContractGenerator.generate("Greeter", abiJsonString, outputDir, "com.myproject.contracts");
 *
 *   // Programmatic — get source as String
 *   String source = ContractGenerator.generateSource("Greeter", abiJsonString, "com.myproject");
 * </pre>
 *
 * The generated class looks like:
 * <pre>
 *   var greeter = new Greeter("0xAddress", client);
 *   String msg  = greeter.getGreeting().join();
 *   String tx   = greeter.setGreeting(wallet, "hiii").join();
 * </pre>
 */
public class ContractGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Generate from ABI JSON file, write .java to outputDir. */
    public static Path generate(String className, File abiFile, Path outputDir, String packageName)
            throws IOException {
        String json = Files.readString(abiFile.toPath());
        return generate(className, json, outputDir, packageName);
    }

    /** Generate from ABI JSON string, write .java to outputDir. */
    public static Path generate(String className, String abiJson, Path outputDir, String packageName)
            throws IOException {
        String source = generateSource(className, abiJson, packageName);
        Path outFile = outputDir.resolve(className + ".java");
        Files.createDirectories(outputDir);
        Files.writeString(outFile, source);
        return outFile;
    }

    /** Generate and return the Java source as a String (no file I/O). */
    public static String generateSource(String className, String abiJson, String packageName)
            throws IOException {
        List<AbiJson.Entry> abi = parseAbi(abiJson);
        return new Generator(className, packageName, abi).generate();
    }

    // ─── ABI parsing ─────────────────────────────────────────────────────────

    private static List<AbiJson.Entry> parseAbi(String json) throws IOException {
        String trimmed = json.trim();
        // Handle both raw array [ {...} ] and artifact format { "abi": [...] }
        if (trimmed.startsWith("{")) {
            var node = MAPPER.readTree(trimmed);
            if (node.has("abi")) {
                trimmed = node.get("abi").toString();
            }
        }
        return MAPPER.readValue(trimmed, new TypeReference<>() {});
    }

    // ─── Code generator ──────────────────────────────────────────────────────

    private static class Generator {

        private final String className;
        private final String packageName;
        private final List<AbiJson.Entry> abi;
        private final Set<String> imports = new TreeSet<>();

        Generator(String className, String packageName, List<AbiJson.Entry> abi) {
            this.className = className;
            this.packageName = packageName;
            this.abi = abi;
        }

        String generate() {
            // Pre-scan all functions to collect required imports
            List<AbiJson.Entry> functions = abi.stream()
                    .filter(AbiJson.Entry::isFunction)
                    .toList();

            // Always-needed imports
            imports.add("io.jeth.abi.AbiType");
            imports.add("io.jeth.abi.Function");
            imports.add("io.jeth.contract.ContractFunction");
            imports.add("io.jeth.core.EthClient");
            imports.add("io.jeth.model.EthModels");
            imports.add("java.util.concurrent.CompletableFuture");

            boolean needsWallet = functions.stream().anyMatch(f -> !f.isView());
            if (needsWallet) {
                imports.add("io.jeth.crypto.Wallet");
                imports.add("java.math.BigInteger");
            }

            // Collect type-specific imports
            for (AbiJson.Entry fn : functions) {
                collectImports(fn);
            }

            StringBuilder sb = new StringBuilder();

            // Package
            if (packageName != null && !packageName.isBlank()) {
                sb.append("package ").append(packageName).append(";\n\n");
            }

            // Imports
            for (String imp : imports) {
                sb.append("import ").append(imp).append(";\n");
            }
            sb.append("\n");

            // Javadoc
            sb.append("/**\n");
            sb.append(" * Auto-generated contract wrapper for <b>").append(className).append("</b>.\n");
            sb.append(" * Generated by jeth {@link io.jeth.codegen.ContractGenerator}.\n");
            sb.append(" *\n");
            sb.append(" * <pre>\n");
            sb.append(" * var contract = new ").append(className).append("(\"0xAddress\", client);\n");
            // Show one example read call if any
            functions.stream().filter(AbiJson.Entry::isView).findFirst().ifPresent(f -> {
                String ret = returnTypeForDoc(f);
                sb.append(" * ").append(ret).append(" result = contract.").append(f.name).append("(");
                if (f.inputs != null && !f.inputs.isEmpty()) {
                    sb.append("/* ").append(f.inputs.stream()
                            .map(p -> p.type + " " + p.name).collect(Collectors.joining(", ")))
                      .append(" */");
                }
                sb.append(").join();\n");
            });
            // Show one example write call if any
            functions.stream().filter(f -> !f.isView()).findFirst().ifPresent(f -> {
                sb.append(" * String txHash = contract.").append(f.name).append("(wallet");
                if (f.inputs != null) {
                    for (AbiJson.Param p : f.inputs) {
                        sb.append(", /* ").append(p.type).append(" */ ").append(p.safeName(0));
                    }
                }
                sb.append(").join();\n");
            });
            sb.append(" * </pre>\n");
            sb.append(" */\n");

            // Class declaration
            sb.append("public class ").append(className).append(" {\n\n");

            // Fields
            sb.append("    private final String address;\n");
            sb.append("    private final EthClient client;\n\n");

            // Pre-built ContractFunction fields
            for (AbiJson.Entry fn : functions) {
                sb.append("    private final ContractFunction _fn_").append(fieldName(fn)).append(";\n");
            }
            sb.append("\n");

            // Constructor
            sb.append("    public ").append(className).append("(String address, EthClient client) {\n");
            sb.append("        this.address = address;\n");
            sb.append("        this.client  = client;\n");
            for (AbiJson.Entry fn : functions) {
                sb.append("        this._fn_").append(fieldName(fn))
                  .append(" = ").append(buildFunctionInit(fn)).append(";\n");
            }
            sb.append("    }\n\n");

            // Methods
            for (AbiJson.Entry fn : functions) {
                sb.append(buildMethod(fn));
                sb.append("\n");
            }

            // Getters
            sb.append("    public String getAddress()   { return address; }\n");
            sb.append("    public EthClient getClient() { return client; }\n");
            sb.append("}\n");

            return sb.toString();
        }

        // ─── Method builder ──────────────────────────────────────────────────

        private String buildMethod(AbiJson.Entry fn) {
            StringBuilder sb = new StringBuilder();
            List<AbiJson.Param> inputs  = fn.inputs  != null ? fn.inputs  : List.of();
            List<AbiJson.Param> outputs = fn.outputs != null ? fn.outputs : List.of();

            // Javadoc
            sb.append("    /**\n");
            sb.append("     * <b>").append(fn.name).append("</b> — ")
              .append(fn.isView() ? "read-only (view/pure)" : "state-changing transaction").append(".\n");
            sb.append("     * Solidity: {@code ").append(soliditySignature(fn)).append("}\n");
            if (!inputs.isEmpty()) {
                sb.append("     *\n");
                for (int i = 0; i < inputs.size(); i++) {
                    AbiJson.Param p = inputs.get(i);
                    sb.append("     * @param ").append(p.safeName(i)).append(" ").append(p.type).append("\n");
                }
            }
            if (fn.isView() && !outputs.isEmpty()) {
                sb.append("     * @return ").append(outputs.stream()
                        .map(p -> p.type + (p.name != null && !p.name.isBlank() ? " " + p.name : ""))
                        .collect(Collectors.joining(", "))).append("\n");
            }
            sb.append("     */\n");

            // Method signature
            if (fn.isView()) {
                // Read — return CompletableFuture<ActualType>
                String returnType = javaReturnType(outputs);
                sb.append("    public CompletableFuture<").append(boxed(returnType)).append("> ")
                  .append(fn.name).append("(");
                sb.append(inputParams(inputs));
                sb.append(") {\n");

                // Build call
                String argsExpr = inputArgs(inputs);
                sb.append("        return _fn_").append(fieldName(fn))
                  .append(".call(").append(argsExpr).append(").as(")
                  .append(classLiteral(returnType)).append(");\n");
                sb.append("    }\n");

            } else {
                // Write — returns CompletableFuture<String> (txHash)
                sb.append("    public CompletableFuture<String> ").append(fn.name).append("(");
                List<String> params = new ArrayList<>();
                params.add("Wallet wallet");
                if (fn.isPayable()) params.add("BigInteger ethValue");
                for (int i = 0; i < inputs.size(); i++) {
                    params.add(TypeMapper.toJavaType(inputs.get(i).canonicalType())
                            + " " + inputs.get(i).safeName(i));
                }
                sb.append(String.join(", ", params));
                sb.append(") {\n");

                // Build send
                String valueArg = fn.isPayable() ? "ethValue" : "BigInteger.ZERO";
                String argsExpr = inputArgs(inputs);
                sb.append("        return _fn_").append(fieldName(fn))
                  .append(".send(wallet, ").append(valueArg);
                if (!argsExpr.isEmpty()) sb.append(", ").append(argsExpr);
                sb.append(");\n");
                sb.append("    }\n");
            }

            return sb.toString();
        }

        // ─── ContractFunction initializer expression ─────────────────────────

        private String buildFunctionInit(AbiJson.Entry fn) {
            List<AbiJson.Param> inputs  = fn.inputs  != null ? fn.inputs  : List.of();
            List<AbiJson.Param> outputs = fn.outputs != null ? fn.outputs : List.of();

            // Build the ContractFunction via the Contract's fn() builder inline
            // We inline the Function creation directly for clarity
            String inputAbiTypes = inputs.stream()
                    .map(p -> TypeMapper.toAbiTypeExpr(p.canonicalType()))
                    .collect(Collectors.joining(", "));

            String outputAbiTypes = outputs.stream()
                    .map(p -> TypeMapper.toAbiTypeExpr(p.canonicalType()))
                    .collect(Collectors.joining(", "));

            StringBuilder sb = new StringBuilder();
            sb.append("new ContractFunction(address, client,\n            ");
            sb.append("Function.of(\"").append(fn.name).append("\"");
            if (!inputAbiTypes.isEmpty()) sb.append(", ").append(inputAbiTypes);
            sb.append(")");
            if (!outputAbiTypes.isEmpty()) {
                sb.append("\n                .withReturns(").append(outputAbiTypes).append(")");
            }
            sb.append(")");
            return sb.toString();
        }

        // ─── Helpers ─────────────────────────────────────────────────────────

        private void collectImports(AbiJson.Entry fn) {
            List<AbiJson.Param> all = new ArrayList<>();
            if (fn.inputs  != null) all.addAll(fn.inputs);
            if (fn.outputs != null) all.addAll(fn.outputs);
            for (AbiJson.Param p : all) {
                TypeMapper.importsFor(p.canonicalType()).forEach(imports::add);
            }
        }

        private String soliditySignature(AbiJson.Entry fn) {
            List<AbiJson.Param> inputs = fn.inputs != null ? fn.inputs : List.of();
            String params = inputs.stream().map(AbiJson.Param::canonicalType).collect(Collectors.joining(","));
            return fn.name + "(" + params + ")";
        }

        /** Unique field name, handles overloaded functions by appending input types */
        private String fieldName(AbiJson.Entry fn) {
            List<AbiJson.Param> inputs = fn.inputs != null ? fn.inputs : List.of();
            if (inputs.isEmpty()) return fn.name;
            String typeSuffix = inputs.stream()
                    .map(p -> p.canonicalType().replaceAll("[^a-zA-Z0-9]", "_"))
                    .collect(Collectors.joining("_"));
            return fn.name + "_" + typeSuffix;
        }

        private String javaReturnType(List<AbiJson.Param> outputs) {
            if (outputs == null || outputs.isEmpty()) return "Void";
            if (outputs.size() == 1) return TypeMapper.toJavaType(outputs.get(0).canonicalType());
            return "Object[]"; // multi-return
        }

        private String returnTypeForDoc(AbiJson.Entry fn) {
            List<AbiJson.Param> outputs = fn.outputs != null ? fn.outputs : List.of();
            return javaReturnType(outputs);
        }

        private String boxed(String type) {
            return switch (type) {
                case "boolean" -> "Boolean";
                case "int"     -> "Integer";
                case "long"    -> "Long";
                case "void"    -> "Void";
                default        -> type;
            };
        }

        private String classLiteral(String javaType) {
            // as(SomeClass.class) — only works for non-generic types
            return switch (javaType) {
                case "boolean", "Boolean"         -> "Boolean.class";
                case "int", "Integer"             -> "Integer.class";
                case "long", "Long"               -> "Long.class";
                case "String"                     -> "String.class";
                case "byte[]"                     -> "byte[].class";
                case "Object[]"                   -> "Object[].class";
                case "Void"                       -> "Void.class";
                default -> {
                    // Strip package prefix for class literal
                    int dot = javaType.lastIndexOf('.');
                    yield (dot >= 0 ? javaType.substring(dot + 1) : javaType) + ".class";
                }
            };
        }

        private String inputParams(List<AbiJson.Param> inputs) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                AbiJson.Param p = inputs.get(i);
                parts.add(TypeMapper.toJavaType(p.canonicalType()) + " " + p.safeName(i));
            }
            return String.join(", ", parts);
        }

        private String inputArgs(List<AbiJson.Param> inputs) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                parts.add(inputs.get(i).safeName(i));
            }
            return String.join(", ", parts);
        }
    }
}
