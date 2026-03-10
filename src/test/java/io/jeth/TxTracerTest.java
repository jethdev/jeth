/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.trace.TxTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TxTracerTest {

    static final String TRACE_JSON =
            """
        {
          "type":"CALL","from":"0xfrom","to":"0xto","value":"0x0",
          "gas":"0x5208","gasUsed":"0x5208","input":"0x","output":"0x",
          "calls":[]
        }""";

    static final String REVERT_TRACE_JSON =
            """
        {
          "type":"CALL","from":"0xfrom","to":"0xcontract","value":"0x0",
          "gas":"0xbeef","gasUsed":"0xbeef","input":"0xa9059cbb",
          "error":"execution reverted","calls":[]
        }""";

    static final String NESTED_TRACE_JSON =
            """
        {
          "type":"CALL","from":"0xfrom","to":"0xrouter","value":"0x0",
          "gas":"0x30000","gasUsed":"0x20000","input":"0x","output":"0x",
          "calls":[
            {"type":"CALL","from":"0xrouter","to":"0xpool","value":"0x0",
             "gas":"0x10000","gasUsed":"0x8000","input":"0x","output":"0x","calls":[]},
            {"type":"CALL","from":"0xrouter","to":"0xtoken","value":"0x0",
             "gas":"0x10000","gasUsed":"0x4000","input":"0x","output":"0x","calls":[]}
          ]
        }""";

    @Test
    @DisplayName("trace() parses simple CALL trace")
    void trace_simple() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(TRACE_JSON);
            var trace = TxTracer.of(rpc.client()).trace("0xtxhash").join();
            assertNotNull(trace);
            assertEquals("CALL", trace.type());
            assertEquals("0xfrom", trace.from());
            assertEquals("0xto", trace.to());
            assertTrue(trace.success());
            assertFalse(trace.isReverted());
        }
    }

    @Test
    @DisplayName("trace() captures revert error")
    void trace_revert() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(REVERT_TRACE_JSON);
            var trace = TxTracer.of(rpc.client()).trace("0xtx").join();
            assertFalse(trace.success());
            assertTrue(trace.isReverted());
            assertNotNull(trace.error());
            assertTrue(trace.error().contains("reverted"));
        }
    }

    @Test
    @DisplayName("nested calls: depth() and callCount()")
    void nested_depth() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(NESTED_TRACE_JSON);
            var trace = TxTracer.of(rpc.client()).trace("0xtx").join();
            assertEquals(2, trace.calls().size());
            assertEquals(1, trace.depth());
            assertEquals(2, trace.callCount());
        }
    }

    @Test
    @DisplayName("render() produces non-empty string")
    void render_non_empty() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(TRACE_JSON);
            String rendered = TxTracer.of(rpc.client()).trace("0xtx").join().render();
            assertNotNull(rendered);
            assertFalse(rendered.isEmpty());
        }
    }

    @Test
    @DisplayName("render() includes from → to")
    void render_contains_addresses() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(TRACE_JSON);
            String rendered = TxTracer.of(rpc.client()).trace("0xtx").join().render();
            assertTrue(
                    rendered.contains("0xfrom") || rendered.contains("0xto"),
                    "render should contain addresses. Got: " + rendered);
        }
    }

    @Test
    @DisplayName("CallTrace.empty() is successful with 0 depth")
    void call_trace_empty() {
        var empty = TxTracer.CallTrace.empty();
        assertTrue(empty.success());
        assertEquals(0, empty.depth());
        assertEquals(0, empty.callCount());
    }
}
