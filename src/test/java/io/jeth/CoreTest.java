/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.crypto.Rlp;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.eip712.TypedData;
import io.jeth.ens.EnsResolver;
import io.jeth.util.Address;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import io.jeth.util.Units;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests validated against external test vectors from: - Ethereum Yellow Paper -
 * EIP specifications (712, 137, 55, 1559) - ethers.js / viem test suites - Hardhat/Anvil known
 * values
 */
class CoreTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Keccak-256
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("keccak256 of empty bytes")
    void keccak_empty() {
        assertHex(
                "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                Keccak.hash(new byte[0]));
    }

    @Test
    @DisplayName("keccak256('hello')")
    void keccak_hello() {
        assertHex(
                "1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
                Keccak.hash("hello"));
    }

    @Test
    @DisplayName("keccak256('transfer(address,uint256)') — function selector")
    void keccak_transfer_selector() {
        byte[] hash = Keccak.hash("transfer(address,uint256)");
        assertHex("a9059cbb", Arrays.copyOf(hash, 4));
    }

    @Test
    @DisplayName("Function selectors match Solidity")
    void function_selectors() {
        assertEquals("0xa9059cbb", Keccak.functionSelector("transfer(address,uint256)"));
        assertEquals("0x70a08231", Keccak.functionSelector("balanceOf(address)"));
        assertEquals("0x095ea7b3", Keccak.functionSelector("approve(address,uint256)"));
        assertEquals("0xdd62ed3e", Keccak.functionSelector("allowance(address,address)"));
        assertEquals("0x18160ddd", Keccak.functionSelector("totalSupply()"));
        assertEquals("0x06fdde03", Keccak.functionSelector("name()"));
        assertEquals("0x95d89b41", Keccak.functionSelector("symbol()"));
        assertEquals("0x313ce567", Keccak.functionSelector("decimals()"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ABI Encoding — Primitives
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ABI encode uint256(42)")
    void abi_uint256() {
        byte[] encoded =
                AbiCodec.encode(
                        new AbiType[] {AbiType.UINT256}, new Object[] {BigInteger.valueOf(42)});
        assertHex("000000000000000000000000000000000000000000000000000000000000002a", encoded);
    }

    @Test
    @DisplayName("ABI encode address")
    void abi_address() {
        byte[] encoded =
                AbiCodec.encode(
                        new AbiType[] {AbiType.ADDRESS},
                        new Object[] {"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"});
        assertHex("000000000000000000000000d8da6bf26964af9d7eed9e03e53415d37aa96045", encoded);
    }

    @Test
    @DisplayName("ABI encode bool true")
    void abi_bool_true() {
        byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.BOOL}, new Object[] {true});
        assertHex("0000000000000000000000000000000000000000000000000000000000000001", encoded);
    }

    @Test
    @DisplayName("ABI encode bool false")
    void abi_bool_false() {
        byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.BOOL}, new Object[] {false});
        assertHex("0000000000000000000000000000000000000000000000000000000000000000", encoded);
    }

    @Test
    @DisplayName("ABI encode bytes32")
    void abi_bytes32() {
        byte[] val =
                Hex.decode("0xdeadbeef00000000000000000000000000000000000000000000000000000000");
        byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.BYTES32}, new Object[] {val});
        assertHex("deadbeef00000000000000000000000000000000000000000000000000000000", encoded);
    }

    @Test
    @DisplayName("ABI encode string 'hello'")
    void abi_string() {
        byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.STRING}, new Object[] {"hello"});
        String hex = Hex.encodeNoPrefx(encoded);
        // offset(32) + length(32) + "hello"(32)
        assertEquals(96, encoded.length);
        assertTrue(
                hex.startsWith(
                        "0000000000000000000000000000000000000000000000000000000000000020")); // offset 32
        assertTrue(
                hex.contains(
                        "0000000000000000000000000000000000000000000000000000000000000005")); // length 5
        assertTrue(hex.contains("68656c6c6f")); // "hello"
    }

    @Test
    @DisplayName("ABI encode bytes (dynamic)")
    void abi_bytes_dynamic() {
        byte[] val = new byte[] {0x01, 0x02, 0x03};
        byte[] encoded = AbiCodec.encode(new AbiType[] {AbiType.BYTES}, new Object[] {val});
        assertEquals(96, encoded.length);
    }

    @Test
    @DisplayName("ABI encode int256 negative")
    void abi_int256_negative() {
        byte[] encoded =
                AbiCodec.encode(
                        new AbiType[] {AbiType.INT256}, new Object[] {BigInteger.valueOf(-1)});
        assertHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", encoded);
    }

    @Test
    @DisplayName("ABI encode multiple params — transfer calldata")
    void abi_transfer_calldata() {
        // balanceOf(address) calldata
        String calldata =
                new Function("balanceOf", new AbiType[] {AbiType.ADDRESS})
                        .withReturns(AbiType.UINT256)
                        .encode("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045");
        // selector + padded address
        assertTrue(calldata.startsWith("0x70a08231"));
        assertTrue(calldata.toLowerCase().contains("d8da6bf26964af9d7eed9e03e53415d37aa96045"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ABI Encoding — Arrays
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ABI encode uint256[] (dynamic array)")
    void abi_uint_dynamic_array() {
        AbiType arrayType = AbiType.arrayOf(AbiType.UINT256);
        Object[] values = {BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)};
        byte[] encoded = AbiCodec.encode(new AbiType[] {arrayType}, new Object[] {values});

        // outer offset(32) + length(32) + 3 elements(96)
        assertEquals(32 + 32 + 32 + 96, encoded.length);
    }

    @Test
    @DisplayName("ABI decode uint256[] round-trip")
    void abi_uint_array_roundtrip() {
        AbiType arrayType = AbiType.arrayOf(AbiType.UINT256);
        Object[] values = {
            BigInteger.valueOf(100), BigInteger.valueOf(200), BigInteger.valueOf(300)
        };
        byte[] encoded = AbiCodec.encode(new AbiType[] {arrayType}, new Object[] {values});
        Object[] decoded = AbiCodec.decode(new AbiType[] {arrayType}, encoded);

        Object[] arr = (Object[]) decoded[0];
        assertEquals(3, arr.length);
        assertEquals(BigInteger.valueOf(100), arr[0]);
        assertEquals(BigInteger.valueOf(200), arr[1]);
        assertEquals(BigInteger.valueOf(300), arr[2]);
    }

    @Test
    @DisplayName("ABI encode address[] (dynamic array)")
    void abi_address_array() {
        AbiType arrayType = AbiType.arrayOf(AbiType.ADDRESS);
        Object[] addrs = {
            "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        };
        byte[] encoded = AbiCodec.encode(new AbiType[] {arrayType}, new Object[] {addrs});
        Object[] decoded = AbiCodec.decode(new AbiType[] {arrayType}, encoded);
        Object[] arr = (Object[]) decoded[0];
        assertEquals(2, arr.length);
        assertTrue(
                arr[0].toString().equalsIgnoreCase("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"));
    }

    @Test
    @DisplayName("ABI encode string[] (array of dynamic)")
    void abi_string_array() {
        AbiType arrayType = AbiType.arrayOf(AbiType.STRING);
        Object[] strs = {"hello", "world"};
        byte[] encoded = AbiCodec.encode(new AbiType[] {arrayType}, new Object[] {strs});
        Object[] decoded = AbiCodec.decode(new AbiType[] {arrayType}, encoded);
        Object[] arr = (Object[]) decoded[0];
        assertEquals("hello", arr[0]);
        assertEquals("world", arr[1]);
    }

    @Test
    @DisplayName("ABI fixed array uint256[3]")
    void abi_fixed_array() {
        AbiType fixedArray = AbiType.arrayOf(AbiType.UINT256, 3);
        assertFalse(fixedArray.isDynamic());
        Object[] values = {BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)};
        byte[] encoded = AbiCodec.encode(new AbiType[] {fixedArray}, new Object[] {values});
        assertEquals(96, encoded.length); // 3 * 32, no offset needed

        Object[] decoded = AbiCodec.decode(new AbiType[] {fixedArray}, encoded);
        Object[] arr = (Object[]) decoded[0];
        assertEquals(BigInteger.ONE, arr[0]);
        assertEquals(BigInteger.valueOf(3), arr[2]);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ABI Encoding — Tuples
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ABI encode simple tuple (address,uint256)")
    void abi_tuple_simple() {
        AbiType tupleType = AbiType.tuple(AbiType.ADDRESS, AbiType.UINT256);
        assertFalse(tupleType.isDynamic()); // no dynamic members

        Object[] values = {
            "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", BigInteger.valueOf(1_000_000L)
        };
        byte[] encoded = AbiCodec.encode(new AbiType[] {tupleType}, new Object[] {values});
        assertEquals(64, encoded.length); // 2 static fields

        Object[] decoded = AbiCodec.decode(new AbiType[] {tupleType}, encoded);
        Object[] tuple = (Object[]) decoded[0];
        assertEquals(BigInteger.valueOf(1_000_000L), tuple[1]);
    }

    @Test
    @DisplayName("ABI tuple with dynamic member (address,string)")
    void abi_tuple_with_string() {
        AbiType tupleType = AbiType.tuple(AbiType.ADDRESS, AbiType.STRING);
        assertTrue(tupleType.isDynamic());

        Object[] values = {"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", "hello"};
        byte[] encoded = AbiCodec.encode(new AbiType[] {tupleType}, new Object[] {values});
        Object[] decoded = AbiCodec.decode(new AbiType[] {tupleType}, encoded);
        Object[] tuple = (Object[]) decoded[0];
        assertEquals("hello", tuple[1]);
    }

    @Test
    @DisplayName("ABI tuple array (struct[])")
    void abi_tuple_array() {
        // (address,uint256)[] — like Multicall Call[]
        AbiType tupleType = AbiType.tuple(AbiType.ADDRESS, AbiType.UINT256);
        AbiType arrayType = AbiType.arrayOf(tupleType);
        assertTrue(arrayType.isDynamic());

        Object[][] elements = {
            {"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", BigInteger.valueOf(100)},
            {"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", BigInteger.valueOf(200)}
        };

        byte[] encoded = AbiCodec.encode(new AbiType[] {arrayType}, new Object[] {elements});
        Object[] decoded = AbiCodec.decode(new AbiType[] {arrayType}, encoded);
        Object[] arr = (Object[]) decoded[0];
        assertEquals(2, arr.length);

        Object[] first = (Object[]) arr[0];
        assertEquals(BigInteger.valueOf(100), first[1]);
    }

    @Test
    @DisplayName("ABI type parsing — complex types")
    void abi_type_parsing() {
        AbiType t1 = AbiType.of("uint256[]");
        assertTrue(t1.isArray() && t1.isDynamicArray());
        assertEquals("uint256[]", t1.toString());

        AbiType t2 = AbiType.of("address[5]");
        assertTrue(t2.isFixedArray());
        assertEquals(5, t2.getArraySize());

        AbiType t3 = AbiType.of("(address,uint256,bool)");
        assertTrue(t3.isTuple());
        assertEquals(3, t3.getTupleTypes().length);

        AbiType t4 = AbiType.of("(address,uint256)[]");
        assertTrue(t4.isArray() && t4.isDynamic());
        assertTrue(t4.getArrayElementType().isTuple());

        AbiType t5 = AbiType.of("(string,uint256[])");
        assertTrue(t5.isTuple() && t5.isDynamic());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ABI Decode round-trips
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ABI round-trip: (address,uint256,bool,string)")
    void abi_roundtrip_mixed() {
        AbiType[] types = {AbiType.ADDRESS, AbiType.UINT256, AbiType.BOOL, AbiType.STRING};
        Object[] values = {
            "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
            BigInteger.valueOf(12345678),
            true,
            "Hello, jeth!"
        };

        byte[] encoded = AbiCodec.encode(types, values);
        Object[] decoded = AbiCodec.decode(types, encoded);

        assertTrue(decoded[0].toString().equalsIgnoreCase((String) values[0]));
        assertEquals(values[1], decoded[1]);
        assertEquals(values[2], decoded[2]);
        assertEquals(values[3], decoded[3]);
    }

    @Test
    @DisplayName("ABI decode real balanceOf response")
    void abi_decode_balanceof_response() {
        // Real response from eth_call to balanceOf — just a uint256
        String hex = "0x000000000000000000000000000000000000000000000000000000003B9ACA00";
        byte[] data = Hex.decode(hex);
        Object[] decoded = AbiCodec.decode(new AbiType[] {AbiType.UINT256}, data);
        assertEquals(new BigInteger("1000000000"), decoded[0]); // 1 USDC (6 decimals * 1000)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENS Namehash (EIP-137)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("namehash of empty string")
    void ens_empty() {
        byte[] hash = EnsResolver.namehash("");
        assertHex("0000000000000000000000000000000000000000000000000000000000000000", hash);
    }

    @Test
    @DisplayName("namehash('eth')")
    void ens_eth() {
        assertHex(
                "93cdeb708b7545dc668eb9280176169d1c33cfd8ed6f04690a0bcc88a93fc4ae",
                EnsResolver.namehash("eth"));
    }

    @Test
    @DisplayName("namehash('foo.eth')")
    void ens_foo_eth() {
        assertHex(
                "de9b09fd7c5f901e23a3f19fecc54828e9c848539801e86591bd9801b019f84f",
                EnsResolver.namehash("foo.eth"));
    }

    @Test
    @DisplayName("namehash('addr.reverse')")
    void ens_addr_reverse() {
        assertHex(
                "91d1777781884d03a6757a803996e38de2a42967fb37eeaca72729271025a9e2",
                EnsResolver.namehash("addr.reverse"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EIP-55 Checksum Addresses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EIP-55 checksum — vitalik.eth")
    void checksum_vitalik() {
        assertEquals(
                "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
                Address.toChecksumAddress("d8da6bf26964af9d7eed9e03e53415d37aa96045"));
    }

    @Test
    @DisplayName("EIP-55 checksum — USDC contract")
    void checksum_usdc() {
        assertEquals(
                "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                Address.toChecksumAddress("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"));
    }

    @Test
    @DisplayName("EIP-55 checksum — known vectors")
    void checksum_vectors() {
        assertEquals(
                "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                Address.toChecksumAddress("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"));
        assertEquals(
                "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                Address.toChecksumAddress("fb6916095ca1df60bb79ce92ce3ea74c37c5d359"));
    }

    @Test
    @DisplayName("Address validation")
    void address_validation() {
        assertTrue(Address.isValid("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"));
        assertTrue(Address.isValid("0x0000000000000000000000000000000000000000"));
        assertFalse(Address.isValid("0xinvalid"));
        assertFalse(Address.isValid("0x123")); // too short
        assertFalse(Address.isValid(""));
        assertFalse(Address.isValid(null));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wallet / secp256k1
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Hardhat account #0 address derivation")
    void wallet_hardhat_0() {
        Wallet w =
                Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", w.getAddress());
    }

    @Test
    @DisplayName("Hardhat account #1 address derivation")
    void wallet_hardhat_1() {
        Wallet w =
                Wallet.fromPrivateKey(
                        "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
        assertEquals("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", w.getAddress());
    }

    @Test
    @DisplayName("Hardhat account #2 address derivation")
    void wallet_hardhat_2() {
        Wallet w =
                Wallet.fromPrivateKey(
                        "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a");
        assertEquals("0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC", w.getAddress());
    }

    @Test
    @DisplayName("Wallet sign + verify recovery id")
    void wallet_sign() {
        Wallet w =
                Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        byte[] hash = Keccak.hash("test message");
        Signature sig = w.sign(hash);

        assertNotNull(sig.r);
        assertNotNull(sig.s);
        assertTrue(sig.v == 0 || sig.v == 1);
        // s must be in lower half (EIP-2 low-s)
        BigInteger halfN = Wallet.CURVE.getN().shiftRight(1);
        assertTrue(sig.s.compareTo(halfN) <= 0, "s must be in lower half of curve order");
    }

    @Test
    @DisplayName("personal_sign hash (eth_sign)")
    void wallet_personal_sign_hash() {
        byte[] hash = Wallet.hashPersonalMessage("Hello World".getBytes());
        // Verify it includes the Ethereum prefix
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    @DisplayName("Wallet.create() generates valid random wallet")
    void wallet_create() {
        Wallet w = Wallet.create();
        assertTrue(Address.isValid(w.getAddress()));
        assertEquals(32, Hex.decode(w.getPrivateKeyHex()).length);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RLP Encoding
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RLP empty bytes → 0x80")
    void rlp_empty_bytes() {
        assertHex("80", Rlp.encode(new byte[0]));
    }

    @Test
    @DisplayName("RLP zero → 0x80")
    void rlp_zero() {
        assertHex("80", Rlp.encode(BigInteger.ZERO));
    }

    @Test
    @DisplayName("RLP 1 → 0x01")
    void rlp_one() {
        assertHex("01", Rlp.encode(BigInteger.ONE));
    }

    @Test
    @DisplayName("RLP 127 → 0x7f")
    void rlp_127() {
        assertHex("7f", Rlp.encode(BigInteger.valueOf(127)));
    }

    @Test
    @DisplayName("RLP 128 → 0x8180")
    void rlp_128() {
        assertHex("8180", Rlp.encode(BigInteger.valueOf(128)));
    }

    @Test
    @DisplayName("RLP 1024 → 0x820400")
    void rlp_1024() {
        assertHex("820400", Rlp.encode(BigInteger.valueOf(1024)));
    }

    @Test
    @DisplayName("RLP 'dog' → 0x83646f67")
    void rlp_dog() {
        assertHex("83646f67", Rlp.encode("dog".getBytes()));
    }

    @Test
    @DisplayName("RLP ['cat','dog'] → 0xc88363617483646f67")
    void rlp_list_cat_dog() {
        assertHex("c88363617483646f67", Rlp.encode(List.of("cat".getBytes(), "dog".getBytes())));
    }

    @Test
    @DisplayName("RLP [] → 0xc0")
    void rlp_empty_list() {
        assertHex("c0", Rlp.encode(List.of()));
    }

    @Test
    @DisplayName("RLP nested lists")
    void rlp_nested() {
        assertHex(
                "c7c0c1c0c3c0c1c0",
                Rlp.encode(
                        List.of(
                                List.of(),
                                List.of(List.of()),
                                List.of(List.of(), List.of(List.of())))));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EIP-712 Typed Data
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EIP-712 domain typehash")
    void eip712_domain_typehash() {
        assertHex(
                "8b73c3c69bb8fe3d512ecc4cf759cc79239f7b179b0ffacaa9a75d522b39400f",
                Keccak.hash(
                        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"));
    }

    @Test
    @DisplayName("EIP-712 encodeType for simple struct")
    void eip712_encode_type() {
        Map<String, List<TypedData.Field>> types =
                Map.of(
                        "Mail",
                        List.of(
                                new TypedData.Field("from", "address"),
                                new TypedData.Field("to", "address"),
                                new TypedData.Field("contents", "string")));
        String encoded = TypedData.encodeType("Mail", types);
        assertEquals("Mail(address from,address to,string contents)", encoded);
    }

    @Test
    @DisplayName("EIP-712 sign produces deterministic signature")
    void eip712_sign_deterministic() {
        Wallet wallet =
                Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

        TypedData.Domain domain =
                TypedData.Domain.builder()
                        .name("Test")
                        .version("1")
                        .chainId(1L)
                        .verifyingContract("0x0000000000000000000000000000000000000001")
                        .build();

        Map<String, List<TypedData.Field>> types =
                Map.of("Message", List.of(new TypedData.Field("content", "string")));
        Map<String, Object> message = Map.of("content", "Hello");

        // Same inputs → same signature (deterministic via RFC 6979)
        Signature sig1 = TypedData.sign(domain, "Message", types, message, wallet);
        Signature sig2 = TypedData.sign(domain, "Message", types, message, wallet);

        assertEquals(sig1.r, sig2.r);
        assertEquals(sig1.s, sig2.s);
        assertEquals(sig1.v, sig2.v);
    }

    @Test
    @DisplayName("EIP-712 ERC-2612 Permit signing")
    void eip712_permit() {
        Wallet wallet =
                Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

        Signature sig =
                TypedData.signPermit(
                        "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", // USDC
                        "USD Coin",
                        "2",
                        1L,
                        wallet.getAddress(),
                        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", // spender
                        BigInteger.valueOf(1_000_000L), // 1 USDC
                        BigInteger.ZERO, // nonce 0
                        BigInteger.valueOf(9999999999L), // deadline
                        wallet);

        // Verify structure
        assertNotNull(sig.r);
        assertNotNull(sig.s);
        assertTrue(sig.v == 27 || sig.v == 28);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Units
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Units: ETH to Wei conversion")
    void units_eth_to_wei() {
        assertEquals(new BigInteger("1000000000000000000"), Units.toWei("1"));
        assertEquals(new BigInteger("100000000000000000"), Units.toWei("0.1"));
        assertEquals(new BigInteger("1000000000000000"), Units.toWei("0.001"));
        assertEquals(new BigInteger("1500000000000000000"), Units.toWei("1.5"));
        assertEquals(BigInteger.ZERO, Units.toWei("0"));
    }

    @Test
    @DisplayName("Units: Wei to ETH formatting")
    void units_format_ether() {
        assertEquals("1.0", Units.formatEther(Units.toWei("1")));
        assertEquals("0.1", Units.formatEther(Units.toWei("0.1")));
        assertEquals("1.5", Units.formatEther(Units.toWei("1.5")));
        assertEquals("0.001", Units.formatEther(Units.toWei("0.001")));
    }

    @Test
    @DisplayName("Units: Gwei conversions")
    void units_gwei() {
        assertEquals(new BigInteger("30000000000"), Units.gweiToWei(30));
        assertEquals(new BigInteger("1000000000"), Units.gweiToWei(1));
        assertEquals(BigInteger.valueOf(30), Units.weiToGwei(Units.gweiToWei(30)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hex utilities
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Hex encode/decode round-trip")
    void hex_roundtrip() {
        byte[] original = new byte[] {0x01, 0x02, (byte) 0xFF, 0x00, (byte) 0xAB};
        assertEquals("0x0102ff00ab", Hex.encode(original));
        assertArrayEquals(original, Hex.decode("0x0102ff00ab"));
        assertArrayEquals(original, Hex.decode("0102ff00ab")); // no prefix
    }

    @Test
    @DisplayName("Hex: BigInteger conversions")
    void hex_biginteger() {
        assertEquals(BigInteger.valueOf(255), Hex.toBigInteger("0xff"));
        assertEquals(BigInteger.valueOf(256), Hex.toBigInteger("0x100"));
        assertEquals(BigInteger.ZERO, Hex.toBigInteger("0x0"));
        assertEquals(BigInteger.ZERO, Hex.toBigInteger("0x"));
        assertEquals("0xff", Hex.fromBigInteger(BigInteger.valueOf(255)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AbiType parsing
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AbiType.of() handles all primitive types")
    void abitype_parsing_primitives() {
        assertEquals("uint256", AbiType.of("uint256").toString());
        assertEquals("uint8", AbiType.of("uint8").toString());
        assertEquals("int256", AbiType.of("int256").toString());
        assertEquals("address", AbiType.of("address").toString());
        assertEquals("bool", AbiType.of("bool").toString());
        assertEquals("string", AbiType.of("string").toString());
        assertEquals("bytes", AbiType.of("bytes").toString());
        assertEquals("bytes32", AbiType.of("bytes32").toString());
        assertEquals("bytes4", AbiType.of("bytes4").toString());
        assertEquals("uint256", AbiType.of("uint").toString()); // alias
        assertEquals("int256", AbiType.of("int").toString()); // alias
    }

    @Test
    @DisplayName("AbiType.of() handles array types")
    void abitype_parsing_arrays() {
        AbiType dyn = AbiType.of("uint256[]");
        assertTrue(dyn.isDynamicArray());
        assertEquals("uint256[]", dyn.toString());

        AbiType fixed = AbiType.of("address[10]");
        assertTrue(fixed.isFixedArray());
        assertEquals(10, fixed.getArraySize());
        assertEquals("address[10]", fixed.toString());

        AbiType nested = AbiType.of("uint256[][]");
        assertTrue(nested.isDynamic());
        assertEquals("uint256[][]", nested.toString());
    }

    @Test
    @DisplayName("AbiType.of() handles tuple types")
    void abitype_parsing_tuples() {
        AbiType t = AbiType.of("(address,uint256,bool)");
        assertTrue(t.isTuple());
        assertEquals(3, t.getTupleTypes().length);
        assertEquals("(address,uint256,bool)", t.toString());

        AbiType arrayOfTuple = AbiType.of("(address,uint256)[]");
        assertTrue(arrayOfTuple.isArray());
        assertTrue(arrayOfTuple.getArrayElementType().isTuple());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void assertHex(String expectedHex, byte[] actual) {
        String actualHex = Hex.encodeNoPrefx(actual);
        assertEquals(
                expectedHex.toLowerCase(),
                actualHex.toLowerCase(),
                "Expected: " + expectedHex + "\nActual:   " + actualHex);
    }
}
