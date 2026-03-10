/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.ens.EnsResolver;
import io.jeth.util.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended EnsResolver tests covering methods not in the existing CoreTest:
 * reverseLookup, getText, getAvatar, getContenthash, namehashHex, getResolver.
 *
 * <p>All tests mock the RPC layer so no live node is needed. ENS resolution
 * involves two RPC calls: one to get the resolver address from the Registry,
 * then one to call the resolver contract.
 */
class EnsResolverExtendedTest {

    // Known ENS registry address (mainnet)
    static final String REGISTRY = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e";
    // A non-zero resolver address to use in mocks
    static final String RESOLVER = "0x4976fb03C32e5B8cfe2b6cCB31c09Ba78EBaBa41";
    // Zero address — means no resolver set
    static final String ZERO = "0x0000000000000000000000000000000000000000";

    // ─── namehashHex ──────────────────────────────────────────────────────────

    @Test @DisplayName("namehashHex('') returns 0x + 64 zeros")
    void namehash_hex_empty() {
        String h = EnsResolver.namehashHex("");
        assertEquals("0x" + "0".repeat(64), h);
    }

    @Test @DisplayName("namehashHex('eth') matches spec value")
    void namehash_hex_eth() {
        String h = EnsResolver.namehashHex("eth");
        assertEquals("0x93cdeb708b7545dc668eb9280176169d1c33cfd8ed6f04690a0bcc88a93fc4ae", h);
    }

    @Test @DisplayName("namehashHex('vitalik.eth') matches spec value")
    void namehash_hex_vitalik() {
        String h = EnsResolver.namehashHex("vitalik.eth");
        assertEquals("0xee6c4522aab0003e8d14cd40a6af439055fd2577951148c14b6cea9a53475835", h);
    }

    @Test @DisplayName("namehashHex returns 0x-prefixed 66-char string")
    void namehash_hex_format() {
        String h = EnsResolver.namehashHex("foo.eth");
        assertTrue(h.startsWith("0x"), "must be 0x-prefixed");
        assertEquals(66, h.length(), "must be 0x + 64 hex chars");
    }

    @Test @DisplayName("namehashHex is deterministic")
    void namehash_hex_deterministic() {
        assertEquals(EnsResolver.namehashHex("foo.eth"), EnsResolver.namehashHex("foo.eth"));
    }

    // ─── resolve (forward lookup) ──────────────────────────────────────────────

    @Test @DisplayName("resolve returns null when resolver is zero address")
    void resolve_no_resolver() throws Exception {
        try (var rpc = new RpcMock()) {
            // Registry returns zero address (no resolver set)
            rpc.enqueue(abiEncodeAddress(ZERO));

            EnsResolver ens = new EnsResolver(rpc.client());
            String addr = ens.resolve("noname.eth").join();
            assertNull(addr, "resolve must return null when no resolver is configured");
        }
    }

    @Test @DisplayName("resolve makes 2 RPC calls: getResolver + addr(node)")
    void resolve_two_rpc_calls() throws Exception {
        try (var rpc = new RpcMock()) {
            String target = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";
            rpc.enqueue(abiEncodeAddress(RESOLVER)); // resolver address
            rpc.enqueue(abiEncodeAddress(target));    // resolved address

            EnsResolver ens = new EnsResolver(rpc.client());
            String addr = ens.resolve("vitalik.eth").join();
            assertEquals(2, rpc.requestCount(), "resolve must make exactly 2 RPC calls");
            assertTrue(target.equalsIgnoreCase(addr));
        }
    }

    @Test @DisplayName("resolve returns null when resolver returns zero address")
    void resolve_resolver_returns_zero() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER)); // resolver exists
            rpc.enqueue(abiEncodeAddress(ZERO));      // but addr() returns 0x0

            EnsResolver ens = new EnsResolver(rpc.client());
            assertNull(ens.resolve("unregistered.eth").join());
        }
    }

    // ─── reverseLookup ────────────────────────────────────────────────────────

    @Test @DisplayName("reverseLookup returns null when no reverse resolver")
    void reverse_lookup_no_resolver() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(ZERO)); // no resolver for addr.reverse node

            EnsResolver ens = new EnsResolver(rpc.client());
            String name = ens.reverseLookup("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045").join();
            assertNull(name, "reverseLookup must return null when no reverse record");
        }
    }

    @Test @DisplayName("reverseLookup returns ENS name when reverse record set")
    void reverse_lookup_success() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));    // reverse resolver
            rpc.enqueue(abiEncodeString("vitalik.eth")); // name() result

            EnsResolver ens = new EnsResolver(rpc.client());
            String name = ens.reverseLookup("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045").join();
            assertEquals("vitalik.eth", name);
        }
    }

    @Test @DisplayName("reverseLookup lowercases address for reverse node computation")
    void reverse_lookup_case_insensitive() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeString("test.eth"));

            EnsResolver ens = new EnsResolver(rpc.client());
            // Upper and lower case should produce the same reverse node
            String result = ens.reverseLookup("0xF39FD6E51AAD88F6F4CE6AB8827279CFFFB92266").join();
            assertEquals("test.eth", result);
        }
    }

    @Test @DisplayName("reverseLookup makes 2 RPC calls: getResolver + name(node)")
    void reverse_lookup_rpc_count() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeString("vitalik.eth"));

            new EnsResolver(rpc.client()).reverseLookup("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045").join();
            assertEquals(2, rpc.requestCount());
        }
    }

    // ─── getText ──────────────────────────────────────────────────────────────

    @Test @DisplayName("getText returns null when no resolver set")
    void get_text_no_resolver() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(ZERO));

            EnsResolver ens = new EnsResolver(rpc.client());
            assertNull(ens.getText("vitalik.eth", "email").join());
        }
    }

    @Test @DisplayName("getText returns text record value")
    void get_text_success() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeString("vitalik@ethereum.org"));

            EnsResolver ens = new EnsResolver(rpc.client());
            String email = ens.getText("vitalik.eth", "email").join();
            assertEquals("vitalik@ethereum.org", email);
        }
    }

    @Test @DisplayName("getText returns null when record is empty string")
    void get_text_empty_record() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeString("")); // empty text record

            EnsResolver ens = new EnsResolver(rpc.client());
            assertNull(ens.getText("nobody.eth", "twitter").join(),
                    "Empty text record should return null");
        }
    }

    // ─── getAvatar ────────────────────────────────────────────────────────────

    @Test @DisplayName("getAvatar is shorthand for getText(name, 'avatar')")
    void get_avatar_uses_avatar_key() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeString("ipfs://QmAvatar"));

            EnsResolver ens = new EnsResolver(rpc.client());
            String avatar = ens.getAvatar("vitalik.eth").join();
            assertEquals("ipfs://QmAvatar", avatar);
        }
    }

    @Test @DisplayName("getAvatar returns null when not set")
    void get_avatar_null() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(ZERO)); // no resolver

            assertNull(new EnsResolver(rpc.client()).getAvatar("nobody.eth").join());
        }
    }

    // ─── getContenthash ───────────────────────────────────────────────────────

    @Test @DisplayName("getContenthash returns null when no resolver")
    void get_contenthash_no_resolver() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(abiEncodeAddress(ZERO));
            assertNull(new EnsResolver(rpc.client()).getContenthash("nobody.eth").join());
        }
    }

    @Test @DisplayName("getContenthash returns raw bytes when set")
    void get_contenthash_success() throws Exception {
        try (var rpc = new RpcMock()) {
            // IPFS CID encoded as bytes
            byte[] cid = new byte[]{(byte)0xe3, 0x01, 0x01, 0x70};
            rpc.enqueue(abiEncodeAddress(RESOLVER));
            rpc.enqueue(abiEncodeBytes(cid));

            EnsResolver ens = new EnsResolver(rpc.client());
            byte[] result = ens.getContenthash("vitalik.eth").join();
            assertNotNull(result);
            assertTrue(result.length > 0, "contenthash must be non-empty when set");
        }
    }

    // ─── ABI encode helpers ───────────────────────────────────────────────────

    /** Encode an address as ABI padded 32-byte hex result */
    static String abiEncodeAddress(String addr) {
        String hex = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "\"0x" + "0".repeat(24) + hex.toLowerCase() + "\"";
    }

    /** Encode a string as ABI bytes: offset(32) + length(32) + data(padded) */
    static String abiEncodeString(String s) {
        byte[] strBytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int padded = ((strBytes.length + 31) / 32) * 32;
        byte[] result = new byte[32 + 32 + padded];
        result[31] = 0x20; // offset
        result[63] = (byte) strBytes.length; // length (works for short strings)
        System.arraycopy(strBytes, 0, result, 64, strBytes.length);
        return "\"0x" + Hex.encodeNoPrefx(result) + "\"";
    }

    /** Encode bytes as ABI dynamic bytes result */
    static String abiEncodeBytes(byte[] data) {
        int padded = ((data.length + 31) / 32) * 32;
        byte[] result = new byte[32 + 32 + padded];
        result[31] = 0x20;
        result[63] = (byte) data.length;
        System.arraycopy(data, 0, result, 64, data.length);
        return "\"0x" + Hex.encodeNoPrefx(result) + "\"";
    }
}
