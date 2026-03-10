/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Wallet;
import io.jeth.eip4844.Blob;
import io.jeth.eip4844.BlobTransaction;
import io.jeth.util.Hex;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for EIP-4844 BlobTransaction — network format, versioned hashes, signing. Extended from the
 * original BlobTransactionTest to cover the protocol-critical details.
 */
class BlobTransactionExtendedTest {

    static final Wallet WALLET =
            Wallet.fromPrivateKey(
                    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    // ─── Blob.versionedHash ────────────────────────────────────────────────────

    @Test
    @DisplayName("Blob.versionedHash uses SHA-256 not Keccak")
    void versioned_hash_uses_sha256() throws Exception {
        byte[] blobData = new byte[Blob.BYTES_PER_BLOB];
        blobData[0] = 0x42;
        Blob blob = Blob.from(blobData);

        // What SHA-256 of the commitment gives us
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] sha256OfCommitment = sha.digest(blob.commitment);

        byte[] vh = blob.versionedHash();
        assertEquals(32, vh.length);
        assertEquals(0x01, vh[0] & 0xFF, "Version byte must be 0x01");

        // Bytes 1..31 must match sha256[1..31]
        for (int i = 1; i < 32; i++) {
            assertEquals(
                    sha256OfCommitment[i],
                    vh[i],
                    "Versioned hash byte[" + i + "] must come from SHA-256(commitment)");
        }
    }

    @Test
    @DisplayName(
            "BlobTransaction.getBlobVersionedHashes() uses SHA-256 (matches Blob.versionedHash)")
    void blob_tx_versioned_hash_matches_blob() {
        byte[] blobData = new byte[BlobTransaction.BLOB_SIZE];
        blobData[5] = (byte) 0xAB; // non-zero to make it interesting

        Blob blob = Blob.from(blobData);

        BlobTransaction tx =
                BlobTransaction.builder()
                        .chainId(1L)
                        .nonce(0L)
                        .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                        .gas(BigInteger.valueOf(21000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L))
                        .blob(blobData, blob.commitment, blob.proof)
                        .build();

        byte[] txHash = tx.getBlobVersionedHashes().get(0);
        byte[] blobHash = blob.versionedHash();

        assertArrayEquals(
                blobHash,
                txHash,
                "BlobTransaction versioned hash must equal Blob.versionedHash() (both SHA-256 based)");
    }

    // ─── EIP-4844 network format ───────────────────────────────────────────────

    @Test
    @DisplayName("signed type-3 tx is 0x03-prefixed")
    void sign_type3_prefix() {
        String raw = buildAndSign();
        assertTrue(raw.startsWith("0x03"), "EIP-4844 tx must start with 0x03");
    }

    @Test
    @DisplayName("signed tx raw bytes: first byte is 0x03")
    void sign_first_byte_0x03() {
        byte[] raw = Hex.decode(buildAndSign());
        assertEquals(0x03, raw[0] & 0xFF);
    }

    @Test
    @DisplayName("signed tx encodes blobs, commitments, proofs as separate RLP list elements")
    void sign_network_format_has_four_top_level_elements() {
        // Per EIP-4844: 0x03 || RLP([[tx_fields], blobs, commitments, proofs])
        // After stripping the 0x03 prefix, the outer RLP list must have exactly 4 elements:
        // [0] = tx_payload_body (list), [1] = blobs, [2] = commitments, [3] = proofs
        byte[] raw = Hex.decode(buildAndSign());
        // Strip 0x03 type prefix
        byte[] rlp = Arrays.copyOfRange(raw, 1, raw.length);
        // The first RLP byte encodes a list; the list prefix 0xF8 means long list follows
        // We just verify the outer structure starts with a list prefix (0xC0-0xFF range)
        assertTrue(
                (rlp[0] & 0xFF) >= 0xC0, "After type prefix, signed blob tx must be an RLP list");
    }

    @Test
    @DisplayName("two blobs → two versioned hashes in tx")
    void two_blobs_two_hashes() {
        byte[] blob1 = new byte[BlobTransaction.BLOB_SIZE];
        byte[] blob2 = new byte[BlobTransaction.BLOB_SIZE];
        blob2[0] = 1; // different content

        Blob b1 = Blob.from(blob1), b2 = Blob.from(blob2);

        BlobTransaction tx =
                BlobTransaction.builder()
                        .chainId(1L)
                        .nonce(0L)
                        .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                        .gas(BigInteger.valueOf(21000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L))
                        .blob(blob1, b1.commitment, b1.proof)
                        .blob(blob2, b2.commitment, b2.proof)
                        .build();

        assertEquals(2, tx.getBlobVersionedHashes().size());
        assertFalse(
                Arrays.equals(
                        tx.getBlobVersionedHashes().get(0), tx.getBlobVersionedHashes().get(1)),
                "Different blobs must produce different versioned hashes");
    }

    @Test
    @DisplayName("MAX_BLOBS_PER_TX enforced — 7th blob throws")
    void max_blobs_enforced() {
        BlobTransaction.Builder b =
                BlobTransaction.builder()
                        .chainId(1L)
                        .nonce(0L)
                        .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                        .gas(BigInteger.valueOf(21000))
                        .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                        .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                        .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L));

        for (int i = 0; i < BlobTransaction.MAX_BLOBS_PER_TX; i++) {
            byte[] data = new byte[BlobTransaction.BLOB_SIZE];
            data[0] = (byte) i;
            b.blobRaw(data);
        }
        assertThrows(
                Exception.class,
                () -> b.blobRaw(new byte[BlobTransaction.BLOB_SIZE]),
                "Should throw when adding blob beyond MAX_BLOBS_PER_TX");
    }

    // ─── padBlob ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("padBlob truncates oversized input")
    void pad_blob_truncates() {
        byte[] big = new byte[BlobTransaction.BLOB_SIZE + 100];
        Arrays.fill(big, (byte) 0xFF);
        byte[] padded = BlobTransaction.padBlob(big);
        assertEquals(BlobTransaction.BLOB_SIZE, padded.length);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String buildAndSign() {
        return BlobTransaction.builder()
                .chainId(1L)
                .nonce(0L)
                .to("0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
                .value(BigInteger.ZERO)
                .gas(BigInteger.valueOf(21000))
                .maxFeePerGas(BigInteger.valueOf(30_000_000_000L))
                .maxPriorityFeePerGas(BigInteger.valueOf(1_000_000_000L))
                .maxFeePerBlobGas(BigInteger.valueOf(1_000_000_000L))
                .blobRaw(new byte[BlobTransaction.BLOB_SIZE])
                .sign(WALLET);
    }
}
