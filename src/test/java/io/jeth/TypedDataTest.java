/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.eip712.TypedData;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TypedDataTest {

    static final Wallet W = Wallet.fromPrivateKey("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    static final TypedData.Domain DOMAIN = TypedData.Domain.builder()
        .name("Test").version("1").chainId(1L)
        .verifyingContract("0x0000000000000000000000000000000000000001").build();
    static final Map<String, List<TypedData.Field>> TYPES = Map.of(
        "Message", List.of(new TypedData.Field("content", "string")));

    // ── EIP-712 spec vectors ──────────────────────────────────────────────────

    @Test @DisplayName("EIP712Domain typehash matches spec")
    void domain_typehash() {
        assertHex("8b73c3c69bb8fe3d512ecc4cf759cc79239f7b179b0ffacaa9a75d522b39400f",
            Keccak.hash("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"));
    }

    @Test @DisplayName("encodeType produces canonical signature string")
    void encode_type() {
        var types = Map.of("Mail", List.of(
            new TypedData.Field("from",    "address"),
            new TypedData.Field("to",      "address"),
            new TypedData.Field("subject", "string")));
        assertEquals("Mail(address from,address to,string subject)",
            TypedData.encodeType("Mail", types));
    }

    @Test @DisplayName("typeHash is keccak256(encodeType)")
    void type_hash() {
        var types = Map.of("Foo", List.of(new TypedData.Field("bar", "uint256")));
        byte[] expected = Keccak.hash("Foo(uint256 bar)".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, TypedData.typeHash("Foo", types));
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    @Test @DisplayName("sign is deterministic (RFC 6979)")
    void sign_deterministic() {
        var msg = Map.<String,Object>of("content", "Hello");
        Signature s1 = TypedData.sign(DOMAIN, "Message", TYPES, msg, W);
        Signature s2 = TypedData.sign(DOMAIN, "Message", TYPES, msg, W);
        assertEquals(s1.r, s2.r);
        assertEquals(s1.s, s2.s);
    }

    @Test @DisplayName("sign produces 65-byte signature")
    void sign_length() {
        var msg = Map.<String,Object>of("content", "test");
        Signature sig = TypedData.sign(DOMAIN, "Message", TYPES, msg, W);
        assertEquals(65, sig.toBytes().length);
    }

    @Test @DisplayName("signHex returns 132-char 0x hex string")
    void sign_hex() {
        var msg = Map.<String,Object>of("content", "test");
        String hex = TypedData.signHex(DOMAIN, "Message", TYPES, msg, W);
        assertTrue(hex.startsWith("0x"));
        assertEquals(132, hex.length());
    }

    @Test @DisplayName("different messages → different signatures")
    void sign_message_uniqueness() {
        Signature s1 = TypedData.sign(DOMAIN, "Message", TYPES, Map.of("content", "A"), W);
        Signature s2 = TypedData.sign(DOMAIN, "Message", TYPES, Map.of("content", "B"), W);
        assertNotEquals(s1.r, s2.r);
    }

    @Test @DisplayName("different chainId → different signatures")
    void sign_chain_unique() {
        var d1 = TypedData.Domain.builder().name("T").version("1").chainId(1L)
            .verifyingContract("0x0000000000000000000000000000000000000001").build();
        var d2 = TypedData.Domain.builder().name("T").version("1").chainId(137L)
            .verifyingContract("0x0000000000000000000000000000000000000001").build();
        var msg = Map.<String,Object>of("content", "hello");
        Signature s1 = TypedData.sign(d1, "Message", TYPES, msg, W);
        Signature s2 = TypedData.sign(d2, "Message", TYPES, msg, W);
        assertNotEquals(s1.r, s2.r);
    }

    // ── signPermit (ERC-2612) ─────────────────────────────────────────────────

    @Test @DisplayName("signPermit returns valid 65-byte signature")
    void sign_permit() {
        Signature sig = TypedData.signPermit(
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            "USD Coin", "2", 1L,
            W.getAddress(),
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            BigInteger.valueOf(1_000_000L),
            BigInteger.ZERO,
            BigInteger.valueOf(9999999999L),
            W);
        assertNotNull(sig.r);
        assertNotNull(sig.s);
        assertEquals(65, sig.toBytes().length);
        assertTrue(sig.v == 27 || sig.v == 28, "Permit v must be 27 or 28");
    }

    @Test @DisplayName("signPermit is deterministic")
    void sign_permit_deterministic() {
        var args = new Object[]{"0xToken","Token","1",1L,W.getAddress(),"0xSpender",
            BigInteger.valueOf(100L),BigInteger.ZERO,BigInteger.valueOf(9999L),W};
        Signature s1 = TypedData.signPermit((String)args[0],(String)args[1],(String)args[2],(Long)args[3],
            (String)args[4],(String)args[5],(BigInteger)args[6],(BigInteger)args[7],(BigInteger)args[8],(Wallet)args[9]);
        Signature s2 = TypedData.signPermit((String)args[0],(String)args[1],(String)args[2],(Long)args[3],
            (String)args[4],(String)args[5],(BigInteger)args[6],(BigInteger)args[7],(BigInteger)args[8],(Wallet)args[9]);
        assertEquals(s1.r, s2.r);
    }

    // ── Struct with numeric fields ────────────────────────────────────────────

    @Test @DisplayName("encode uint256 value in struct")
    void encode_uint256_field() {
        var types = Map.of("Order", List.of(
            new TypedData.Field("amount",   "uint256"),
            new TypedData.Field("deadline", "uint256")));
        var msg = Map.<String,Object>of(
            "amount",   BigInteger.valueOf(1_000_000L),
            "deadline", BigInteger.valueOf(9999999999L));
        Signature sig = TypedData.sign(DOMAIN, "Order", types, msg, W);
        assertNotNull(sig);
        assertEquals(65, sig.toBytes().length);
    }

    static void assertHex(String expected, byte[] actual) {
        assertEquals(expected.toLowerCase(), Hex.encodeNoPrefx(actual).toLowerCase());
    }
}
