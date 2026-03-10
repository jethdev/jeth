/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Wallet;
import io.jeth.eip4844.Blob;
import io.jeth.eip4844.BlobTransaction;
import java.math.BigInteger;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobTransactionTest {

    @Test
    @DisplayName("BLOB_SIZE constant = 131072 bytes")
    void blob_size_constant() {
        assertEquals(131072, BlobTransaction.BLOB_SIZE);
    }

    @Test
    @DisplayName("MAX_BLOBS_PER_TX = 6")
    void max_blobs_constant() {
        assertEquals(6, BlobTransaction.MAX_BLOBS_PER_TX);
    }

    @Test
    @DisplayName("padBlob pads to 131072 bytes")
    void pad_blob_to_size() {
        byte[] input = "Hello, blob!".getBytes();
        byte[] padded = BlobTransaction.padBlob(input);
        assertEquals(BlobTransaction.BLOB_SIZE, padded.length);
        // First bytes must match input
        assertArrayEquals(input, Arrays.copyOf(padded, input.length));
        // Rest must be zero
        for (int i = input.length; i < 10; i++) assertEquals(0, padded[i]);
    }

    @Test
    @DisplayName("padBlob of exact blob size returns same size")
    void pad_blob_exact() {
        byte[] full = new byte[BlobTransaction.BLOB_SIZE];
        Arrays.fill(full, (byte) 0xAB);
        assertEquals(BlobTransaction.BLOB_SIZE, BlobTransaction.padBlob(full).length);
    }

    @Test
    @DisplayName("Blob.versioned hash is 32 bytes starting with 0x01")
    void blob_versioned_hash() {
        byte[] data = new byte[BlobTransaction.BLOB_SIZE];
        Blob blob = Blob.from(data);
        byte[] hash = blob.versionedHash();
        assertEquals(32, hash.length);
        assertEquals(0x01, hash[0] & 0xFF, "Versioned hash must start with 0x01 (BLS version)");
    }

    @Test
    @DisplayName("sign() returns type-3 tx starting with 0x03")
    void sign_type3() {
        Wallet wallet =
                Wallet.fromPrivateKey(
                        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        byte[] blobData = new byte[BlobTransaction.BLOB_SIZE];
        BlobTransaction tx =
                BlobTransaction.builder()
                        .chainId(1L)
                        .nonce(0L)
                        .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                        .value(BigInteger.ZERO)
                        .gas(BigInteger.valueOf(21000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L))
                        .blob(blobData)
                        .build();
        String raw = tx.sign(wallet);
        assertNotNull(raw);
        assertTrue(
                raw.startsWith("0x03"),
                "Type-3 tx must start with 0x03, got: "
                        + raw.substring(0, Math.min(6, raw.length())));
    }

    @Test
    @DisplayName("getBlobVersionedHashes returns one hash per blob")
    void blob_versioned_hashes_count() {
        BlobTransaction tx =
                BlobTransaction.builder()
                        .chainId(1L)
                        .nonce(0L)
                        .to("0xTarget")
                        .value(BigInteger.ZERO)
                        .gas(BigInteger.valueOf(21000))
                        .maxFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L))
                        .blob(new byte[BlobTransaction.BLOB_SIZE])
                        .build();
        assertEquals(1, tx.getBlobVersionedHashes().size());
    }
}
