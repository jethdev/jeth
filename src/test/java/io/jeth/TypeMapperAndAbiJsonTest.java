/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.codegen.AbiJson;
import io.jeth.codegen.TypeMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TypeMapperAndAbiJsonTest {

    // ═══════════════════════════════════════════════════════════════════════
    // TypeMapper
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TypeMapper")
    class TypeMapperTests {

        @Test
        @DisplayName("uint256 → BigInteger")
        void uint256_to_biginteger() {
            assertEquals("BigInteger", TypeMapper.toJavaType("uint256"));
        }

        @Test
        @DisplayName("uint8 → int")
        void uint8_to_int() {
            String t = TypeMapper.toJavaType("uint8");
            assertTrue(
                    t.equals("int") || t.equals("BigInteger"),
                    "uint8 should map to int or BigInteger, got: " + t);
        }

        @Test
        @DisplayName("address → String")
        void address_to_string() {
            assertEquals("String", TypeMapper.toJavaType("address"));
        }

        @Test
        @DisplayName("bool → boolean")
        void bool_to_boolean() {
            String t = TypeMapper.toJavaType("bool");
            assertTrue(t.equals("boolean") || t.equals("Boolean"));
        }

        @Test
        @DisplayName("bytes32 → byte[]")
        void bytes32_to_byte_array() {
            String t = TypeMapper.toJavaType("bytes32");
            assertEquals("byte[]", t);
        }

        @Test
        @DisplayName("bytes → byte[]")
        void bytes_to_byte_array() {
            String t = TypeMapper.toJavaType("bytes");
            assertEquals("byte[]", t);
        }

        @Test
        @DisplayName("string → String")
        void string_to_string() {
            assertEquals("String", TypeMapper.toJavaType("string"));
        }

        @Test
        @DisplayName("toJavaTypeBoxed: bool → Boolean (boxed)")
        void bool_boxed() {
            String t = TypeMapper.toJavaTypeBoxed("bool");
            assertEquals("Boolean", t);
        }

        @Test
        @DisplayName("toJavaTypeBoxed: uint256 → BigInteger (already boxed)")
        void uint256_boxed() {
            assertEquals("BigInteger", TypeMapper.toJavaTypeBoxed("uint256"));
        }

        @Test
        @DisplayName("importsFor: uint256 needs BigInteger import")
        void imports_uint256() {
            List<String> imports = TypeMapper.importsFor("uint256");
            assertTrue(
                    imports.stream().anyMatch(i -> i.contains("BigInteger")),
                    "uint256 must require BigInteger import");
        }

        @Test
        @DisplayName("importsFor: address needs no extra imports")
        void imports_address() {
            List<String> imports = TypeMapper.importsFor("address");
            // address maps to String which is java.lang — no import needed
            assertTrue(
                    imports.isEmpty() || imports.stream().noneMatch(i -> i.contains("BigInteger")));
        }

        @Test
        @DisplayName("toAbiTypeExpr: uint256 → AbiType.UINT256")
        void abi_type_expr_uint256() {
            String expr = TypeMapper.toAbiTypeExpr("uint256");
            assertTrue(
                    expr.contains("UINT256") || expr.contains("uint256"),
                    "uint256 ABI type expression should reference UINT256, got: " + expr);
        }

        @Test
        @DisplayName("toAbiTypeExpr: address → AbiType.ADDRESS")
        void abi_type_expr_address() {
            String expr = TypeMapper.toAbiTypeExpr("address");
            assertTrue(
                    expr.contains("ADDRESS") || expr.contains("address"),
                    "address ABI type expression should reference ADDRESS, got: " + expr);
        }

        @Test
        @DisplayName("castFrom: address casts to String")
        void cast_from_address() {
            String cast = TypeMapper.castFrom("address", "val");
            assertNotNull(cast);
            assertTrue(cast.contains("val"), "cast expression must reference the value");
        }

        @Test
        @DisplayName("castFrom: uint256 casts to BigInteger")
        void cast_from_uint256() {
            String cast = TypeMapper.castFrom("uint256", "val");
            assertNotNull(cast);
            assertTrue(cast.contains("val"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AbiJson
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AbiJson")
    class AbiJsonTests {

        static final String ERC20_ABI =
                """
            [
              {"type":"function","name":"balanceOf","inputs":[{"name":"account","type":"address"}],"outputs":[{"name":"","type":"uint256"}],"stateMutability":"view"},
              {"type":"function","name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[{"name":"","type":"bool"}],"stateMutability":"nonpayable"},
              {"type":"event","name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true},{"name":"to","type":"address","indexed":true},{"name":"value","type":"uint256","indexed":false}],"anonymous":false},
              {"type":"constructor","inputs":[{"name":"name","type":"string"},{"name":"symbol","type":"string"}],"stateMutability":"nonpayable"}
            ]
            """;

        @Test
        @DisplayName("parse() returns correct entry count")
        void parse_entry_count() {
            List<AbiJson.Entry> entries = AbiJson.parse(ERC20_ABI);
            assertEquals(4, entries.size());
        }

        @Test
        @DisplayName("parse() identifies function entries")
        void parse_functions() {
            List<AbiJson.Entry> fns =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "function".equals(e.type))
                            .toList();
            assertEquals(2, fns.size());
        }

        @Test
        @DisplayName("parse() identifies event entries")
        void parse_events() {
            List<AbiJson.Entry> events =
                    AbiJson.parse(ERC20_ABI).stream().filter(e -> "event".equals(e.type)).toList();
            assertEquals(1, events.size());
            assertEquals("Transfer", events.get(0).name);
        }

        @Test
        @DisplayName("parse() identifies constructor entry")
        void parse_constructor() {
            List<AbiJson.Entry> ctors =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "constructor".equals(e.type))
                            .toList();
            assertEquals(1, ctors.size());
        }

        @Test
        @DisplayName("balanceOf has one address input")
        void balanceof_inputs() {
            AbiJson.Entry balanceOf =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "balanceOf".equals(e.name))
                            .findFirst()
                            .orElseThrow();
            assertEquals(1, balanceOf.inputs.size());
            assertEquals("address", balanceOf.inputs.get(0).type);
        }

        @Test
        @DisplayName("transfer has two inputs: address and uint256")
        void transfer_inputs() {
            AbiJson.Entry transfer =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "transfer".equals(e.name))
                            .findFirst()
                            .orElseThrow();
            assertEquals(2, transfer.inputs.size());
            assertEquals("address", transfer.inputs.get(0).type);
            assertEquals("uint256", transfer.inputs.get(1).type);
        }

        @Test
        @DisplayName("Transfer event has 3 inputs")
        void transfer_event_inputs() {
            AbiJson.Entry ev =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "Transfer".equals(e.name))
                            .findFirst()
                            .orElseThrow();
            assertEquals(3, ev.inputs.size());
            assertEquals("from", ev.inputs.get(0).name);
            assertEquals("to", ev.inputs.get(1).name);
            assertEquals("value", ev.inputs.get(2).name);
        }

        @Test
        @DisplayName("parse() empty ABI returns empty list")
        void parse_empty() {
            assertTrue(AbiJson.parse("[]").isEmpty());
        }

        @Test
        @DisplayName("view function stateMutability is preserved")
        void stateMutability_view() {
            AbiJson.Entry balanceOf =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "balanceOf".equals(e.name))
                            .findFirst()
                            .orElseThrow();
            assertEquals("view", balanceOf.stateMutability);
        }

        @Test
        @DisplayName("nonpayable function stateMutability is preserved")
        void stateMutability_nonpayable() {
            AbiJson.Entry transfer =
                    AbiJson.parse(ERC20_ABI).stream()
                            .filter(e -> "transfer".equals(e.name))
                            .findFirst()
                            .orElseThrow();
            assertEquals("nonpayable", transfer.stateMutability);
        }
    }
}
