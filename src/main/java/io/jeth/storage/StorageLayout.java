/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.storage;

import io.jeth.core.EthClient;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * EVM storage layout reader — access contract storage slots directly.
 *
 * <p>Essential for debugging, MEV, and protocol analysis. Follows Solidity storage layout rules:
 * packed slots, mappings, arrays.
 *
 * <pre>
 * var storage = StorageLayout.of(client, "0xUSDC");
 *
 * // Read raw slot
 * BigInteger val = storage.readSlot(0).join();
 *
 * // Read a mapping: balances[address]
 * BigInteger bal = storage.readMapping(9, "0xAddress").join();
 *
 * // Read a nested mapping: allowances[owner][spender]
 * BigInteger allowance = storage.readNestedMapping(10, "0xOwner", "0xSpender").join();
 *
 * // Read a dynamic array element: array[index] at slot N
 * BigInteger elem = storage.readArrayElement(5, 3).join();
 * </pre>
 */
public class StorageLayout {

    private final EthClient client;
    private final String address;

    private StorageLayout(EthClient client, String address) {
        this.client = client;
        this.address = address;
    }

    public static StorageLayout of(EthClient client, String address) {
        return new StorageLayout(client, address);
    }

    // ─── Raw slot access ─────────────────────────────────────────────────────

    /** Read a raw storage slot by slot number. */
    public CompletableFuture<BigInteger> readSlot(long slot) {
        return readSlotBytes(slot).thenApply(Hex::toBigInteger);
    }

    public CompletableFuture<BigInteger> readSlot(BigInteger slot) {
        return readSlotBytes(slot).thenApply(Hex::toBigInteger);
    }

    public CompletableFuture<String> readSlotBytes(long slot) {
        return readSlotBytes(BigInteger.valueOf(slot));
    }

    public CompletableFuture<String> readSlotBytes(BigInteger slot) {
        return client.getStorageAt(address, slot);
    }

    // ─── Mappings ────────────────────────────────────────────────────────────

    /**
     * Read mapping(key => value) at a given base slot. Solidity: slot = keccak256(abi.encode(key,
     * baseSlot))
     */
    public CompletableFuture<BigInteger> readMapping(long baseSlot, String keyAddress) {
        BigInteger slot = mappingSlot(baseSlot, keyAddress);
        return readSlot(slot);
    }

    public CompletableFuture<BigInteger> readMapping(long baseSlot, BigInteger key) {
        BigInteger slot = mappingSlotUint(baseSlot, key);
        return readSlot(slot);
    }

    /**
     * Read nested mapping: mapping(key1 => mapping(key2 => value)) slot =
     * keccak256(abi.encode(key2, keccak256(abi.encode(key1, baseSlot))))
     */
    public CompletableFuture<BigInteger> readNestedMapping(
            long baseSlot, String key1Address, String key2Address) {
        BigInteger inner = mappingSlot(baseSlot, key1Address);
        BigInteger outer = mappingSlotFromBig(inner, key2Address);
        return readSlot(outer);
    }

    // ─── Dynamic arrays ───────────────────────────────────────────────────────

    /** Read the length of a dynamic array at baseSlot. */
    public CompletableFuture<BigInteger> readArrayLength(long baseSlot) {
        return readSlot(baseSlot);
    }

    /** Read dynamic array element at index. Data slot = keccak256(baseSlot) + index * elemSlots */
    public CompletableFuture<BigInteger> readArrayElement(long baseSlot, long index) {
        return readArrayElement(baseSlot, index, 1);
    }

    public CompletableFuture<BigInteger> readArrayElement(
            long baseSlot, long index, int slotsPerElem) {
        byte[] slotBytes = bigToBytes32(BigInteger.valueOf(baseSlot));
        BigInteger dataSlot =
                new BigInteger(1, Keccak.hash(slotBytes))
                        .add(BigInteger.valueOf(index * slotsPerElem));
        return readSlot(dataSlot);
    }

    // ─── Packed slot helpers ──────────────────────────────────────────────────

    /**
     * Extract a packed value from a slot. Solidity packs small values right-to-left within a
     * 32-byte slot.
     *
     * @param slot raw slot value
     * @param byteOffset offset from the right (0 = rightmost byte)
     * @param byteLen number of bytes to read
     */
    public static BigInteger unpackSlot(BigInteger slot, int byteOffset, int byteLen) {
        return slot.shiftRight(byteOffset * 8)
                .and(BigInteger.TWO.pow(byteLen * 8).subtract(BigInteger.ONE));
    }

    // ─── Slot computation helpers ─────────────────────────────────────────────

    /** Compute mapping slot for an address key. */
    public static BigInteger mappingSlot(long baseSlot, String address) {
        return mappingSlotFromBig(BigInteger.valueOf(baseSlot), address);
    }

    private static BigInteger mappingSlotFromBig(BigInteger baseSlot, String address) {
        // abi.encode(address, slot) = 12-byte zero pad + 20-byte addr + 32-byte slot
        byte[] key = new byte[64];
        byte[] addrBytes = Hex.decode(address);
        System.arraycopy(addrBytes, 0, key, 12, 20);
        byte[] slotBytes = bigToBytes32(baseSlot);
        System.arraycopy(slotBytes, 0, key, 32, 32);
        return new BigInteger(1, Keccak.hash(key));
    }

    private static BigInteger mappingSlotUint(long baseSlot, BigInteger key) {
        byte[] packed = new byte[64];
        byte[] keyBytes = bigToBytes32(key);
        byte[] slotBytes = bigToBytes32(BigInteger.valueOf(baseSlot));
        System.arraycopy(keyBytes, 0, packed, 0, 32);
        System.arraycopy(slotBytes, 0, packed, 32, 32);
        return new BigInteger(1, Keccak.hash(packed));
    }

    private static byte[] bigToBytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length <= 32) System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        else System.arraycopy(raw, raw.length - 32, out, 0, 32);
        return out;
    }
}
