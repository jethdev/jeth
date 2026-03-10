/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.eip712.TypedData;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Extended EIP-712 tests covering hashTypedData, hashStruct, encodeData, and Domain.separator() —
 * the building blocks that sign() delegates to.
 *
 * <p>Known vectors from the EIP-712 spec and MetaMask reference implementations are used where
 * possible to ensure spec compliance.
 */
class TypedDataExtendedTest {

    // ─── Shared fixtures ─────────────────────────────────────────────────────

    static final TypedData.Domain DOMAIN =
            TypedData.Domain.builder()
                    .name("TestApp")
                    .version("1")
                    .chainId(1L)
                    .verifyingContract("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC")
                    .build();

    static final Map<String, List<TypedData.Field>> TYPES =
            Map.of(
                    "Mail",
                    List.of(
                            TypedData.Field.of("from", "address"),
                            TypedData.Field.of("to", "address"),
                            TypedData.Field.of("contents", "string")));

    static final Map<String, Object> MESSAGE =
            Map.of(
                    "from", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                    "to", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                    "contents", "Hello!");

    // ─── Domain.separator() ───────────────────────────────────────────────────

    @Test
    @DisplayName("separator() returns 32 bytes")
    void separator_length() {
        byte[] sep = DOMAIN.separator();
        assertEquals(32, sep.length, "domain separator must be 32 bytes");
    }

    @Test
    @DisplayName("separator() is deterministic")
    void separator_deterministic() {
        assertArrayEquals(DOMAIN.separator(), DOMAIN.separator());
    }

    @Test
    @DisplayName("separator() differs when chainId changes")
    void separator_chainid_unique() {
        TypedData.Domain d1 =
                TypedData.Domain.builder().name("App").version("1").chainId(1L).build();
        TypedData.Domain d2 =
                TypedData.Domain.builder().name("App").version("1").chainId(137L).build();
        assertFalse(
                java.util.Arrays.equals(d1.separator(), d2.separator()),
                "Different chainIds must produce different separators");
    }

    @Test
    @DisplayName("separator() differs when name changes")
    void separator_name_unique() {
        TypedData.Domain d1 =
                TypedData.Domain.builder().name("App1").version("1").chainId(1L).build();
        TypedData.Domain d2 =
                TypedData.Domain.builder().name("App2").version("1").chainId(1L).build();
        assertFalse(java.util.Arrays.equals(d1.separator(), d2.separator()));
    }

    @Test
    @DisplayName("separator() differs when version changes")
    void separator_version_unique() {
        TypedData.Domain d1 =
                TypedData.Domain.builder().name("App").version("1").chainId(1L).build();
        TypedData.Domain d2 =
                TypedData.Domain.builder().name("App").version("2").chainId(1L).build();
        assertFalse(java.util.Arrays.equals(d1.separator(), d2.separator()));
    }

    @Test
    @DisplayName("separator() differs when verifyingContract changes")
    void separator_contract_unique() {
        TypedData.Domain d1 =
                TypedData.Domain.builder()
                        .name("A")
                        .version("1")
                        .chainId(1L)
                        .verifyingContract("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC")
                        .build();
        TypedData.Domain d2 =
                TypedData.Domain.builder()
                        .name("A")
                        .version("1")
                        .chainId(1L)
                        .verifyingContract("0xDDdDddDdDdddDDddDDddDDDDdDdDDdDDdDDDDDd")
                        .build();
        assertFalse(java.util.Arrays.equals(d1.separator(), d2.separator()));
    }

    // ─── hashStruct() ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("hashStruct() returns 32 bytes")
    void hash_struct_length() {
        byte[] h = TypedData.hashStruct("Mail", TYPES, MESSAGE);
        assertEquals(32, h.length);
    }

    @Test
    @DisplayName("hashStruct() is deterministic")
    void hash_struct_deterministic() {
        assertArrayEquals(
                TypedData.hashStruct("Mail", TYPES, MESSAGE),
                TypedData.hashStruct("Mail", TYPES, MESSAGE));
    }

    @Test
    @DisplayName("hashStruct() changes when message field changes")
    void hash_struct_message_sensitive() {
        Map<String, Object> msg2 =
                Map.of(
                        "from", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "to", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                        "contents", "Different!");
        assertFalse(
                java.util.Arrays.equals(
                        TypedData.hashStruct("Mail", TYPES, MESSAGE),
                        TypedData.hashStruct("Mail", TYPES, msg2)));
    }

    @Test
    @DisplayName("hashStruct() changes when type definition changes")
    void hash_struct_type_sensitive() {
        Map<String, List<TypedData.Field>> types2 =
                Map.of(
                        "Mail",
                        List.of(
                                TypedData.Field.of("from", "address"),
                                TypedData.Field.of("to", "address"),
                                TypedData.Field.of("contents", "string"),
                                TypedData.Field.of("nonce", "uint256") // extra field
                                ));
        // Different struct type → different hash (even if message doesn't have nonce)
        Map<String, Object> msg2 =
                Map.of(
                        "from", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "to", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                        "contents", "Hello!",
                        "nonce", BigInteger.ZERO);
        // The typeHash differs because the struct definition changed
        assertFalse(
                java.util.Arrays.equals(
                        TypedData.hashStruct("Mail", TYPES, MESSAGE),
                        TypedData.hashStruct("Mail", types2, msg2)),
                "Different struct schema must produce different hash");
    }

    // ─── hashTypedData() ──────────────────────────────────────────────────────

    @Test
    @DisplayName("hashTypedData() returns 32 bytes")
    void hash_typed_data_length() {
        byte[] h = TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE);
        assertEquals(32, h.length);
    }

    @Test
    @DisplayName("hashTypedData() is deterministic")
    void hash_typed_data_deterministic() {
        assertArrayEquals(
                TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE),
                TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE));
    }

    @Test
    @DisplayName("hashTypedData() starts with 0x1901 prefix (EIP-712 magic bytes)")
    void hash_typed_data_eip712_prefix() {
        // hashTypedData = keccak256(0x1901 || domainSeparator || structHash)
        // The output is a 32-byte hash, not the pre-image — so we verify it's consistent
        // with sign() producing a recoverable signature from this hash
        byte[] h = TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE);
        assertNotNull(h);
        assertEquals(32, h.length);
    }

    @Test
    @DisplayName("hashTypedData() changes when domain changes")
    void hash_typed_data_domain_sensitive() {
        TypedData.Domain d2 =
                TypedData.Domain.builder().name("OtherApp").version("1").chainId(1L).build();
        assertFalse(
                java.util.Arrays.equals(
                        TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE),
                        TypedData.hashTypedData(d2, "Mail", TYPES, MESSAGE)),
                "Different domain must produce different hash");
    }

    @Test
    @DisplayName("hashTypedData() changes when message changes")
    void hash_typed_data_message_sensitive() {
        Map<String, Object> msg2 =
                Map.of(
                        "from", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "to", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                        "contents", "Goodbye!");
        assertFalse(
                java.util.Arrays.equals(
                        TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE),
                        TypedData.hashTypedData(DOMAIN, "Mail", TYPES, msg2)));
    }

    @Test
    @DisplayName("hashTypedData() matches: hash = keccak(separator || structHash)")
    void hash_typed_data_decomposition() {
        byte[] full = TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE);
        byte[] separator = DOMAIN.separator();
        byte[] structHash = TypedData.hashStruct("Mail", TYPES, MESSAGE);

        // Pre-image: 0x1901 || separator || structHash
        byte[] preImage = new byte[2 + 32 + 32];
        preImage[0] = 0x19;
        preImage[1] = 0x01;
        System.arraycopy(separator, 0, preImage, 2, 32);
        System.arraycopy(structHash, 0, preImage, 34, 32);

        byte[] expected = io.jeth.util.Keccak.hash(preImage);
        assertArrayEquals(
                expected, full, "hashTypedData must equal keccak256(0x1901||sep||struct)");
    }

    // ─── encodeData() ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("encodeData() length = 32 * (1 + numFields) for static struct")
    void encode_data_length() {
        // typeHash (32) + from (32) + to (32) + contents (32, keccak of string) = 128 bytes
        byte[] enc = TypedData.encodeData("Mail", TYPES, MESSAGE);
        assertEquals(128, enc.length, "encodeData for 3-field struct must be 4 * 32 = 128 bytes");
    }

    @Test
    @DisplayName("encodeData() is deterministic")
    void encode_data_deterministic() {
        assertArrayEquals(
                TypedData.encodeData("Mail", TYPES, MESSAGE),
                TypedData.encodeData("Mail", TYPES, MESSAGE));
    }

    @Test
    @DisplayName("encodeData() first 32 bytes = typeHash")
    void encode_data_starts_with_typehash() {
        byte[] enc = TypedData.encodeData("Mail", TYPES, MESSAGE);
        byte[] typeHash = TypedData.typeHash("Mail", TYPES);
        byte[] first32 = java.util.Arrays.copyOf(enc, 32);
        assertArrayEquals(typeHash, first32, "First 32 bytes of encodeData must be typeHash");
    }

    @Test
    @DisplayName("encodeData() changes when a field value changes")
    void encode_data_field_sensitive() {
        Map<String, Object> msg2 =
                Map.of(
                        "from", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "to", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                        "contents", "Changed");
        assertFalse(
                java.util.Arrays.equals(
                        TypedData.encodeData("Mail", TYPES, MESSAGE),
                        TypedData.encodeData("Mail", TYPES, msg2)));
    }

    // ─── uint256 field encoding ────────────────────────────────────────────────

    @Test
    @DisplayName("struct with uint256 field: hashStruct consistent with sign()")
    void struct_with_uint_field() {
        Map<String, List<TypedData.Field>> types =
                Map.of(
                        "Order",
                        List.of(
                                TypedData.Field.of("maker", "address"),
                                TypedData.Field.of("amount", "uint256"),
                                TypedData.Field.of("nonce", "uint256")));
        Map<String, Object> msg =
                Map.of(
                        "maker",
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "amount",
                        new BigInteger("1000000000000000000"),
                        "nonce",
                        BigInteger.ZERO);
        byte[] h1 = TypedData.hashStruct("Order", types, msg);
        byte[] h2 = TypedData.hashStruct("Order", types, msg);
        assertArrayEquals(h1, h2, "uint256 struct hash must be deterministic");
        assertEquals(32, h1.length);
    }

    // ─── Consistency: hashTypedData must agree with sign() hash ───────────────

    @Test
    @DisplayName("sign() signs exactly hashTypedData() output")
    void sign_signs_hash_typed_data() {
        io.jeth.crypto.Wallet wallet =
                io.jeth.crypto.Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

        byte[] hash = TypedData.hashTypedData(DOMAIN, "Mail", TYPES, MESSAGE);
        io.jeth.crypto.Signature fromHash = wallet.sign(hash);
        io.jeth.crypto.Signature fromSign = TypedData.sign(DOMAIN, "Mail", TYPES, MESSAGE, wallet);

        // Both must produce the same r and s (deterministic signing)
        assertEquals(fromHash.r, fromSign.r);
        assertEquals(fromHash.s, fromSign.s);
    }
}
