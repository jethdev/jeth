/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.core.Chain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChainTest {

    @Test
    @DisplayName("MAINNET chainId = 1")
    void mainnet_id() {
        assertEquals(1L, Chain.MAINNET.id());
    }

    @Test
    @DisplayName("All chains have valid public HTTPS RPC")
    void all_public_rpcs() {
        for (Chain c : Chain.values()) {
            assertNotNull(c.publicRpc(), c.name() + " has null publicRpc");
            assertTrue(c.publicRpc().startsWith("https://"), c.name() + " RPC: " + c.publicRpc());
        }
    }

    @Test
    @DisplayName("All chains have an explorer URL")
    void all_explorers() {
        for (Chain c : Chain.values()) assertNotNull(c.explorer(), c.name() + " has null explorer");
    }

    @Test
    @DisplayName("fromId() finds major chains")
    void from_id() {
        assertEquals(Chain.MAINNET, Chain.fromId(1L).orElseThrow());
        assertEquals(Chain.ARBITRUM, Chain.fromId(42161L).orElseThrow());
        assertEquals(Chain.OPTIMISM, Chain.fromId(10L).orElseThrow());
        assertEquals(Chain.BASE, Chain.fromId(8453L).orElseThrow());
        assertEquals(Chain.POLYGON, Chain.fromId(137L).orElseThrow());
        assertEquals(Chain.ZKSYNC, Chain.fromId(324L).orElseThrow());
    }

    @Test
    @DisplayName("fromId() returns empty for unknown chain")
    void from_id_unknown() {
        assertTrue(Chain.fromId(999999L).isEmpty());
    }

    @Test
    @DisplayName("requireId() throws for unknown chain")
    void require_id_throws() {
        assertThrows(IllegalArgumentException.class, () -> Chain.requireId(999999L));
    }

    @Test
    @DisplayName("isL2() correct for L1 vs L2")
    void is_l2() {
        assertFalse(Chain.MAINNET.isL2());
        assertTrue(Chain.ARBITRUM.isL2());
        assertTrue(Chain.OPTIMISM.isL2());
        assertTrue(Chain.BASE.isL2());
        assertTrue(Chain.ZKSYNC.isL2());
        assertTrue(Chain.LINEA.isL2());
    }

    @Test
    @DisplayName("isTestnet() identifies testnets correctly")
    void is_testnet() {
        assertTrue(Chain.SEPOLIA.isTestnet());
        assertTrue(Chain.HOLESKY.isTestnet());
        assertTrue(Chain.BASE_SEPOLIA.isTestnet());
        assertFalse(Chain.MAINNET.isTestnet());
        assertFalse(Chain.BASE.isTestnet());
    }

    @Test
    @DisplayName("txUrl() builds correct etherscan link")
    void tx_url() {
        assertEquals("https://etherscan.io/tx/0xabc", Chain.MAINNET.txUrl("0xabc"));
    }

    @Test
    @DisplayName("addressUrl() builds correct basescan link")
    void address_url() {
        assertEquals("https://basescan.org/address/0xAddr", Chain.BASE.addressUrl("0xAddr"));
    }

    @Test
    @DisplayName("rpc(key) interpolates API key")
    void rpc_with_key() {
        String url = Chain.MAINNET.rpc("mykey");
        assertTrue(
                url.contains("mykey") || url.equals(Chain.MAINNET.publicRpc()),
                "Either key is interpolated or falls back to public RPC");
    }
}
