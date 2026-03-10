/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.ens.CcipRead;
import io.jeth.util.Hex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for EIP-3668 CCIP-Read parsing and calldata construction. Does NOT require network
 * access.
 */
class CcipReadTest {

    // Known OffchainLookup revert data (manually constructed for testing)
    // OffchainLookup(
    //   sender:           0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266,
    //   urls:             ["https://ccip.example.com/{sender}/{data}.json"],
    //   callData:         0xdeadbeef,
    //   callbackFunction: 0x12345678,
    //   extraData:        0xcafebabe
    // )
    private static final String SENDER = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String URL = "https://ccip.example.com/{sender}/{data}.json";
    private static final String CALLBACK = "0x12345678";

    @Test
    void selectorIsCorrect() {
        // keccak256("OffchainLookup(address,string[],bytes,bytes4,bytes)")[0..4]
        assertEquals("0x556f1830", CcipRead.OFFCHAIN_LOOKUP_SELECTOR);
    }

    @Test
    void parseOffchainLookup_extractsSender() {
        String revertData =
                buildOffchainLookupRevert(
                        SENDER, new String[] {URL}, "0xdeadbeef", CALLBACK, "0xcafebabe");
        CcipRead.OffchainLookup lookup = CcipRead.parseOffchainLookup(revertData);
        assertEquals(SENDER.toLowerCase(), lookup.sender().toLowerCase());
    }

    @Test
    void parseOffchainLookup_extractsUrls() {
        String revertData =
                buildOffchainLookupRevert(
                        SENDER,
                        new String[] {URL, "https://fallback.example.com/"},
                        "0xdead",
                        CALLBACK,
                        "0xcafe");
        CcipRead.OffchainLookup lookup = CcipRead.parseOffchainLookup(revertData);
        assertEquals(2, lookup.urls().length);
        assertEquals(URL, lookup.urls()[0]);
        assertEquals("https://fallback.example.com/", lookup.urls()[1]);
    }

    @Test
    void parseOffchainLookup_extractsCallbackFunction() {
        String revertData =
                buildOffchainLookupRevert(SENDER, new String[] {URL}, "0xdead", CALLBACK, "0xcafe");
        CcipRead.OffchainLookup lookup = CcipRead.parseOffchainLookup(revertData);
        assertEquals(CALLBACK, lookup.callbackFunction());
    }

    @Test
    void buildCallbackCalldata_hasCorrectSelector() {
        String calldata = CcipRead.buildCallbackCalldata(CALLBACK, "0xaabbccdd", "0x11223344");
        // First 4 bytes should be the callback selector
        assertTrue(
                calldata.startsWith(CALLBACK.toLowerCase()),
                "Callback calldata must start with callback selector");
    }

    @Test
    void buildCallbackCalldata_lengthIsCorrect() {
        // (bytes, bytes) ABI encoding: 4 + 32 + 32 + 32 + padded(resp) + 32 + padded(extra)
        // Both 4-byte payloads → padded to 32 bytes each
        // Total: 4 + 32 + 32 + 32 + 32 + 32 + 32 = 196 bytes = 392 hex chars + "0x"
        String calldata = CcipRead.buildCallbackCalldata(CALLBACK, "0xdeadbeef", "0xcafebabe");
        // 4 + 4*32 bytes header + 2*32 padded data = 4 + 128 + 64 = 196 bytes
        int expectedHexLen = 2 + 196 * 2; // "0x" + hex bytes
        assertEquals(expectedHexLen, calldata.length());
    }

    @Test
    void roundtrip_parseAndRebuild() {
        String callData = "0xdeadbeefcafebabe";
        String extraData = "0x0102030405060708";
        String revertData =
                buildOffchainLookupRevert(
                        SENDER, new String[] {URL}, callData, CALLBACK, extraData);

        CcipRead.OffchainLookup lookup = CcipRead.parseOffchainLookup(revertData);

        assertEquals(callData, lookup.callData());
        assertEquals(extraData, lookup.extraData());
        assertEquals(1, lookup.urls().length);
        assertEquals(URL, lookup.urls()[0]);
    }

    // ─── Helper: manually build OffchainLookup revert ABI encoding ─────────────

    static String buildOffchainLookupRevert(
            String sender,
            String[] urls,
            String callData,
            String callbackFunction,
            String extraData) {

        byte[] senderBytes = Hex.decode(sender);
        byte[][] urlBytes = new byte[urls.length][];
        for (int i = 0; i < urls.length; i++)
            urlBytes[i] = urls[i].getBytes(StandardCharsets.UTF_8);
        byte[] cdBytes = Hex.decode(callData);
        byte[] cbBytes = Hex.decode(callbackFunction);
        byte[] extraBytes = Hex.decode(extraData);

        // ABI encode: (address, string[], bytes, bytes4, bytes)
        // Offsets for dynamic types (relative to start of params):
        //   slot 0: sender (static, address)
        //   slot 1: offset to urls array
        //   slot 2: offset to callData bytes
        //   slot 3: callbackFunction (static, bytes4 left-padded)
        //   slot 4: offset to extraData bytes

        List<byte[]> parts = new ArrayList<>();

        // Sender (32 bytes, right-padded in first 12)
        byte[] senderSlot = new byte[32];
        System.arraycopy(senderBytes, 0, senderSlot, 12, 20);
        parts.add(senderSlot);

        // We'll compute offsets after we know sizes
        // First, encode the dynamic data:
        // urls array: length + per-string (offset + length + data)
        int urlsDataLen = 32; // length slot
        for (byte[] url : urlBytes) {
            urlsDataLen += 32; // offset slot
            urlsDataLen += 32; // length slot
            urlsDataLen += ((url.length + 31) / 32) * 32; // data
        }

        int cdPadded = ((cdBytes.length + 31) / 32) * 32;
        int extraPadded = ((extraBytes.length + 31) / 32) * 32;

        // Offsets (from start of the full 5-slot header = 160 bytes)
        int urlsOffset = 160;
        int cdOffset = urlsOffset + urlsDataLen;
        int extraOffset = cdOffset + 32 + cdPadded;

        // Slot 1: offset to urls
        parts.add(toSlot32(urlsOffset));
        // Slot 2: offset to callData
        parts.add(toSlot32(cdOffset));
        // Slot 3: callbackFunction (bytes4, padded left)
        byte[] cbSlot = new byte[32];
        System.arraycopy(cbBytes, 0, cbSlot, 0, Math.min(cbBytes.length, 4));
        parts.add(cbSlot);
        // Slot 4: offset to extraData
        parts.add(toSlot32(extraOffset));

        // URLs array data
        parts.add(toSlot32(urls.length)); // array length
        int perStringBase = 32 * urls.length; // base of string data within urls block
        int cursor = perStringBase;
        for (byte[] url : urlBytes) {
            parts.add(toSlot32(cursor)); // offset to this string (from start of string elements)
            cursor += 32 + ((url.length + 31) / 32) * 32;
        }
        for (byte[] url : urlBytes) {
            parts.add(toSlot32(url.length));
            byte[] padded = new byte[((url.length + 31) / 32) * 32];
            System.arraycopy(url, 0, padded, 0, url.length);
            parts.add(padded);
        }

        // callData bytes
        parts.add(toSlot32(cdBytes.length));
        if (cdBytes.length > 0) {
            byte[] padded = new byte[cdPadded];
            System.arraycopy(cdBytes, 0, padded, 0, cdBytes.length);
            parts.add(padded);
        }

        // extraData bytes
        parts.add(toSlot32(extraBytes.length));
        if (extraBytes.length > 0) {
            byte[] padded = new byte[extraPadded];
            System.arraycopy(extraBytes, 0, padded, 0, extraBytes.length);
            parts.add(padded);
        }

        // Assemble
        int total = parts.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[4 + total];
        byte[] selector = Hex.decode(CcipRead.OFFCHAIN_LOOKUP_SELECTOR);
        System.arraycopy(selector, 0, result, 0, 4);
        int pos = 4;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, pos, part.length);
            pos += part.length;
        }
        return Hex.encode(result);
    }

    private static byte[] toSlot32(long value) {
        byte[] slot = new byte[32];
        for (int i = 31; i >= 0; i--) {
            slot[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return slot;
    }
}
