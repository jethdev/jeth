/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.multicall.Multicall3;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MulticallTest {

    @Test
    @DisplayName("ADDRESS constant is the canonical Multicall3 address")
    void canonical_address() {
        assertEquals("0xcA11bde05977b3631167028862bE2a173976CA11", Multicall3.ADDRESS);
    }

    @Test
    @DisplayName("empty execute() makes zero RPC calls")
    void empty_execute_no_rpc() throws Exception {
        try (var rpc = new RpcMock()) {
            var mc = new Multicall3(rpc.client());
            List<Object> results = mc.execute().join();
            assertTrue(results.isEmpty());
            assertEquals(0, rpc.requestCount());
        }
    }

    @Test
    @DisplayName("size() tracks added calls correctly")
    void size_tracking() throws Exception {
        try (var rpc = new RpcMock()) {
            var mc = new Multicall3(rpc.client());
            Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
            assertEquals(0, mc.size());
            mc.add("0xToken", fn, "0xUser1");
            assertEquals(1, mc.size());
            mc.add("0xToken", fn, "0xUser2");
            assertEquals(2, mc.size());
            mc.clear();
            assertEquals(0, mc.size());
        }
    }

    @Test
    @DisplayName("add() returns sequential indexes 0, 1, 2...")
    void add_returns_index() throws Exception {
        try (var rpc = new RpcMock()) {
            var mc = new Multicall3(rpc.client());
            Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
            assertEquals(0, mc.add("0xA", fn, "0xU1"));
            assertEquals(1, mc.add("0xA", fn, "0xU2"));
            assertEquals(2, mc.add("0xA", fn, "0xU3"));
        }
    }

    @Test
    @DisplayName("aggregate3 selector 0x82ad56cb is in calldata")
    void aggregate3_selector_in_calldata() throws Exception {
        try (var rpc = new RpcMock()) {
            // Build a fake aggregate3 response for 1 call returning uint256(1)
            rpc.enqueue(
                    buildAggregate3Response(new byte[][] {padUint256(1)}, new boolean[] {true}));
            var mc = new Multicall3(rpc.client());
            Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
            mc.add("0xToken", fn, "0xUser");
            mc.execute().join();
            String body = rpc.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("82ad56cb"), "aggregate3 selector must appear in calldata");
        }
    }

    @Test
    @DisplayName("decode: successful call returns decoded value")
    void decode_success() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(
                    buildAggregate3Response(new byte[][] {padUint256(42)}, new boolean[] {true}));
            var mc = new Multicall3(rpc.client());
            Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
            mc.add("0xToken", fn, "0xUser");
            List<Object> results = mc.execute().join();
            assertEquals(1, results.size());
            assertEquals(BigInteger.valueOf(42), results.get(0));
        }
    }

    @Test
    @DisplayName("addOptional: failed call returns null (no throw)")
    void decode_failed_optional() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueue(buildAggregate3Response(new byte[][] {new byte[0]}, new boolean[] {false}));
            var mc = new Multicall3(rpc.client());
            Function fn = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
            mc.addOptional("0xToken", fn, "0xUser");
            List<Object> results = mc.execute().join();
            assertEquals(1, results.size());
            assertNull(results.get(0));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Builds aggregate3((address,bool,bytes)[])-style response. */
    static String buildAggregate3Response(byte[][] results, boolean[] success) {
        var baos = new ByteArrayOutputStream();
        int n = results.length;
        // Each element: [success:32][offset_to_bytes:32][bytes_len:32][bytes_padded]
        var elements = new byte[n][];
        for (int i = 0; i < n; i++) {
            byte[] rd = results[i];
            int pad = ((rd.length + 31) / 32) * 32;
            byte[] el = new byte[96 + pad];
            el[31] = success[i] ? (byte) 1 : 0; // success
            el[32 + 31] = 64; // offset to bytes within element = 64
            writeInt(el, 64, rd.length); // bytes length
            if (rd.length > 0) System.arraycopy(rd, 0, el, 96, rd.length);
            elements[i] = el;
        }
        // outer: offset(32) + arrayLen(32) + head offsets(n*32) + elements
        byte[] outer = new byte[32 + 32 + n * 32 + totalLen(elements)];
        writeInt(outer, 28, 32); // offset to array = 32
        writeInt(outer, 60, n); // array length
        int off = 32 + n * 32; // start of element data relative to array body
        for (int i = 0; i < n; i++) {
            writeInt(outer, 64 + i * 32 + 28, off);
            off += elements[i].length;
        }
        int pos = 64 + n * 32;
        for (byte[] e : elements) {
            System.arraycopy(e, 0, outer, pos, e.length);
            pos += e.length;
        }
        return "\"0x" + io.jeth.util.Hex.encodeNoPrefx(outer) + "\"";
    }

    static byte[] padUint256(long v) {
        byte[] b = new byte[32];
        b[31] = (byte) v;
        b[30] = (byte) (v >> 8);
        return b;
    }

    static void writeInt(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v >> 24);
        buf[pos + 1] = (byte) (v >> 16);
        buf[pos + 2] = (byte) (v >> 8);
        buf[pos + 3] = (byte) v;
    }

    static int totalLen(byte[][] arrs) {
        int t = 0;
        for (byte[] a : arrs) t += a.length;
        return t;
    }
}
