/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.codegen.ContractGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;

class ContractGeneratorTest {

    static final String GREETER_ABI = """
        [
          {
            "type": "function",
            "name": "getGreeting",
            "inputs": [],
            "outputs": [{"name": "", "type": "string"}],
            "stateMutability": "view"
          },
          {
            "type": "function",
            "name": "setGreeting",
            "inputs": [{"name": "greeting", "type": "string"}],
            "outputs": [],
            "stateMutability": "nonpayable"
          },
          {
            "type": "function",
            "name": "owner",
            "inputs": [],
            "outputs": [{"name": "", "type": "address"}],
            "stateMutability": "view"
          }
        ]
        """;

    static final String ERC20_ABI = """
        [
          {"type":"function","name":"name","inputs":[],"outputs":[{"name":"","type":"string"}],"stateMutability":"view"},
          {"type":"function","name":"symbol","inputs":[],"outputs":[{"name":"","type":"string"}],"stateMutability":"view"},
          {"type":"function","name":"decimals","inputs":[],"outputs":[{"name":"","type":"uint8"}],"stateMutability":"view"},
          {"type":"function","name":"totalSupply","inputs":[],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"},
          {"type":"function","name":"balanceOf","inputs":[{"name":"account","type":"address"}],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"},
          {"type":"function","name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"nonpayable"},
          {"type":"function","name":"allowance","inputs":[{"name":"owner","type":"address"},{"name":"spender","type":"address"}],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"},
          {"type":"function","name":"approve","inputs":[{"name":"spender","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"nonpayable"},
          {"type":"function","name":"transferFrom","inputs":[{"name":"from","type":"address"},{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"nonpayable"}
        ]
        """;

    static final String COMPLEX_ABI = """
        [
          {"type":"function","name":"deposit","inputs":[],"outputs":[],"stateMutability":"payable"},
          {"type":"function","name":"withdraw","inputs":[{"name":"amount","type":"uint256"}],"outputs":[],"stateMutability":"nonpayable"},
          {"type":"function","name":"getBalance","inputs":[{"name":"user","type":"address"}],"outputs":[{"name":"balance","type":"uint256"}],"stateMutability":"view"},
          {"type":"function","name":"isApproved","inputs":[{"name":"user","type":"address"},{"name":"operator","type":"address"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"view"},
          {"type":"function","name":"getInfo","inputs":[],"outputs":[{"name":"name","type":"string"},{"name":"version","type":"uint256"},{"name":"active","type":"bool"}],"stateMutability":"view"}
        ]
        """;

    // Hardhat artifact format (has wrapping object with "abi" key)
    static final String HARDHAT_ARTIFACT = """
        {
          "contractName": "Greeter",
          "abi": [
            {"type":"function","name":"greet","inputs":[],"outputs":[{"name":"","type":"string"}],"stateMutability":"view"},
            {"type":"function","name":"setGreeting","inputs":[{"name":"_greeting","type":"string"}],"outputs":[],"stateMutability":"nonpayable"}
          ],
          "bytecode": "0x608060..."
        }
        """;

    @Test
    void testGenerateGreeter() throws Exception {
        String source = ContractGenerator.generateSource("Greeter", GREETER_ABI, "com.example");

        System.out.println("=== Greeter.java ===");
        System.out.println(source);

        assertTrue(source.contains("public class Greeter"));
        assertTrue(source.contains("package com.example"));

        // getGreeting() should return CompletableFuture<String>
        assertTrue(source.contains("CompletableFuture<String> getGreeting()"));

        // setGreeting takes a wallet + string
        assertTrue(source.contains("CompletableFuture<String> setGreeting(Wallet wallet, String greeting)"));

        // owner() returns address (String)
        assertTrue(source.contains("CompletableFuture<String> owner()"));
    }

    @Test
    void testGenerateERC20() throws Exception {
        String source = ContractGenerator.generateSource("MyToken", ERC20_ABI, "com.example.tokens");

        System.out.println("=== MyToken.java ===");
        System.out.println(source);

        assertTrue(source.contains("public class MyToken"));
        assertTrue(source.contains("CompletableFuture<String> name()"));
        assertTrue(source.contains("CompletableFuture<String> symbol()"));
        // uint8 decimals -> int
        assertTrue(source.contains("CompletableFuture<Integer> decimals()"));
        // uint256 -> BigInteger
        assertTrue(source.contains("CompletableFuture<BigInteger> totalSupply()"));
        assertTrue(source.contains("CompletableFuture<BigInteger> balanceOf(String account)"));
        // write methods take wallet
        assertTrue(source.contains("Wallet wallet"));
        assertTrue(source.contains("BigInteger amount"));
    }

    @Test
    void testGenerateComplex() throws Exception {
        String source = ContractGenerator.generateSource("Vault", COMPLEX_ABI, "com.example");

        System.out.println("=== Vault.java ===");
        System.out.println(source);

        // payable deposit — takes ethValue
        assertTrue(source.contains("BigInteger ethValue"));
        // view with address param
        assertTrue(source.contains("CompletableFuture<BigInteger> getBalance(String user)"));
        // two address inputs
        assertTrue(source.contains("CompletableFuture<Boolean> isApproved(String user, String operator)"));
        // multi-return -> Object[]
        assertTrue(source.contains("CompletableFuture<Object[]> getInfo()"));
    }

    @Test
    void testHardhatArtifactFormat() throws Exception {
        // Should handle { "abi": [...] } wrapping
        String source = ContractGenerator.generateSource("Greeter", HARDHAT_ARTIFACT, "com.example");

        System.out.println("=== Greeter (Hardhat) ===");
        System.out.println(source);

        assertTrue(source.contains("CompletableFuture<String> greet()"));
        assertTrue(source.contains("CompletableFuture<String> setGreeting(Wallet wallet, String _greeting)"));
    }

    @Test
    void testFunctionSelectorsCorrect() throws Exception {
        // The generated code must wire up the correct ABI types
        // We verify the Function.of() calls are present
        String source = ContractGenerator.generateSource("MyToken", ERC20_ABI, "test");

        // transfer(address,uint256) selector must reference ADDRESS and UINT256
        assertTrue(source.contains("AbiType.ADDRESS"));
        assertTrue(source.contains("AbiType.UINT256"));
        assertTrue(source.contains("AbiType.BOOL"));
        assertTrue(source.contains("AbiType.STRING"));
    }

    @Test
    void testGenerateToFile() throws Exception {
        var tmpDir = Files.createTempDirectory("jeth-test");
        var outFile = ContractGenerator.generate("Greeter", GREETER_ABI, tmpDir, "com.example");

        assertTrue(outFile.toFile().exists());
        String content = Files.readString(outFile);
        assertTrue(content.contains("public class Greeter"));

        System.out.println("Written to: " + outFile);
    }
}
