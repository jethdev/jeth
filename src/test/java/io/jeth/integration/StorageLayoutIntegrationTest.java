/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import io.jeth.contract.Contract;
import io.jeth.core.EthClient;
import io.jeth.crypto.Wallet;
import io.jeth.storage.StorageLayout;
import io.jeth.util.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StorageLayout} — reads real on-chain storage slots
 * from a deployed TestCounter contract on Besu dev network.
 *
 * <p>TestCounter storage layout (Solidity packs variables right-to-left, 32-byte slots):
 * <pre>
 *   slot 0: count   (uint256)
 *   slot 1: owner   (address, bytes 0-19) + initialized (bool, byte 20)
 *   slot 2: values  mapping(address => uint256)
 * </pre>
 *
 * Run: {@code ./gradlew integrationTest}  (requires Docker)
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageLayoutIntegrationTest {

    @Container
    static BesuContainer besu = new BesuContainer();

    static EthClient     client;
    static Wallet        dev;
    static String        counterAddr;
    static StorageLayout storage;
    static Contract      counter;

    @BeforeAll
    static void setup() {
        client      = EthClient.of(besu.httpUrl());
        dev         = Wallet.fromPrivateKey(BesuContainer.DEV_PRIVATE_KEY);
        counterAddr = IntegrationTestBase.deploy(client, dev, SolcContainer.compile("TestCounter"));
        storage     = StorageLayout.of(client, counterAddr);
        counter     = new Contract(counterAddr, client);
    }

    // ─── EOA has no storage (sanity) ─────────────────────────────────────────

    @Test @Order(0) @DisplayName("readSlot(0) on EOA = 0")
    void eoa_storage_is_zero() {
        assertEquals(BigInteger.ZERO,
            StorageLayout.of(client, BesuContainer.DEV_ADDRESS).readSlot(0L).join());
    }

    // ─── slot 0: count ────────────────────────────────────────────────────────

    @Test @Order(1) @DisplayName("slot 0 (count) = 0 after deploy")
    void count_starts_at_zero() {
        assertEquals(BigInteger.ZERO, storage.readSlot(0L).join());
    }

    @Test @Order(2) @DisplayName("slot 0 (count) = 1 after increment()")
    void count_increments() throws Exception {
        String txHash = counter.fn("increment()").send(dev).join();
        client.waitForTransaction(txHash, 30_000, 500).join();
        assertEquals(BigInteger.ONE, storage.readSlot(0L).join());
    }

    @Test @Order(3) @DisplayName("slot 0 (count) = 6 after incrementBy(5)")
    void count_increment_by() throws Exception {
        // count is 1 from previous test
        String txHash = counter.fn("incrementBy(uint256)").send(dev, BigInteger.valueOf(5)).join();
        client.waitForTransaction(txHash, 30_000, 500).join();
        assertEquals(BigInteger.valueOf(6), storage.readSlot(0L).join());
    }

    @Test @Order(4) @DisplayName("slot 0 matches view function count()")
    void slot_matches_view_call() {
        BigInteger fromStorage = storage.readSlot(0L).join();
        BigInteger fromCall    = counter.fn("count()").returns("uint256")
                                        .call().as(BigInteger.class).join();
        assertEquals(fromCall, fromStorage, "Storage slot must match view function");
    }

    // ─── slot 1: packed (owner + initialized) ─────────────────────────────────

    @Test @Order(10) @DisplayName("slot 1 raw is non-zero (contains owner + initialized)")
    void slot1_nonzero() {
        BigInteger raw = storage.readSlot(1L).join();
        assertTrue(raw.signum() > 0, "Slot 1 must contain owner address and initialized=true");
    }

    @Test @Order(11) @DisplayName("slot 1: unpack owner address (bytes 0-19)")
    void slot1_unpack_owner_address() {
        BigInteger raw   = storage.readSlot(1L).join();
        // address is stored right-aligned in the lower 20 bytes (bytes 0-19 from right)
        BigInteger owner = StorageLayout.unpackSlot(raw, 0, 20);
        // Compare with dev address as BigInteger
        BigInteger devAddr = new BigInteger(1, Hex.decode(BesuContainer.DEV_ADDRESS));
        assertEquals(devAddr, owner, "Unpacked owner address must match deployer");
    }

    @Test @Order(12) @DisplayName("slot 1: unpack initialized bool (byte 20) = 1 (true)")
    void slot1_unpack_initialized() {
        BigInteger raw         = storage.readSlot(1L).join();
        BigInteger initialized = StorageLayout.unpackSlot(raw, 20, 1);
        assertEquals(BigInteger.ONE, initialized, "initialized must be true (1) after constructor");
    }

    @Test @Order(13) @DisplayName("slot 1 owner bytes match owner() view call")
    void slot1_owner_matches_view() {
        BigInteger raw        = storage.readSlot(1L).join();
        BigInteger fromSlot   = StorageLayout.unpackSlot(raw, 0, 20);
        String     fromCall   = counter.fn("owner()").returns("address")
                                       .call().as(String.class).join();
        BigInteger fromCallBi = new BigInteger(1, Hex.decode(fromCall));
        assertEquals(fromCallBi, fromSlot);
    }

    // ─── slot 2: values mapping(address => uint256) ───────────────────────────

    @Test @Order(20) @DisplayName("values[dev] = 0 initially (unset mapping entry)")
    void mapping_zero_initially() {
        assertEquals(BigInteger.ZERO, storage.readMapping(2L, dev.getAddress()).join());
    }

    @Test @Order(21) @DisplayName("values[dev] = 42 after setValue(42)")
    void mapping_updates_after_set_value() throws Exception {
        String txHash = counter.fn("setValue(uint256)").send(dev, BigInteger.valueOf(42)).join();
        client.waitForTransaction(txHash, 30_000, 500).join();
        assertEquals(BigInteger.valueOf(42), storage.readMapping(2L, dev.getAddress()).join());
    }

    @Test @Order(22) @DisplayName("values[unknown] = 0 (different key, unset)")
    void mapping_zero_for_different_key() {
        assertEquals(BigInteger.ZERO,
            storage.readMapping(2L, Wallet.create().getAddress()).join());
    }

    @Test @Order(23) @DisplayName("mapping slot matches values() view call")
    void mapping_slot_matches_view() {
        BigInteger fromStorage = storage.readMapping(2L, dev.getAddress()).join();
        BigInteger fromCall    = counter.fn("values(address)").returns("uint256")
                                        .call(dev.getAddress()).as(BigInteger.class).join();
        assertEquals(fromCall, fromStorage, "Storage mapping slot must match view function");
    }

    // ─── readSlotBytes format ─────────────────────────────────────────────────

    @Test @Order(30) @DisplayName("readSlotBytes() returns 0x-prefixed hex string")
    void read_slot_bytes_format() {
        String raw = storage.readSlotBytes(0L).join();
        assertNotNull(raw);
        assertTrue(raw.startsWith("0x"), "readSlotBytes must return 0x-prefixed hex");
    }
}
