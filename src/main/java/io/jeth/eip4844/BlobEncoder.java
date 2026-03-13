/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip4844;

import java.nio.charset.StandardCharsets;

/**
 * Encodes arbitrary bytes into the EIP-4844 blob field-element format.
 *
 * <p>Each 32-byte chunk of a blob must be a valid BLS12-381 scalar field element, i.e. &lt; {@code
 * 0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001}. This constraint is easily
 * violated by binary data (e.g. TLS certs, compressed data) where 32-byte chunks may have all bits
 * set.
 *
 * <p>This encoder uses the OP Stack / Optimism canonical format: each 32-byte field element stores
 * 31 bytes of data with the leading byte set to 0x00. This guarantees the value is always &lt;
 * 2^248 &lt; BLS12-381 Fr, regardless of input.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * byte[] data = Files.readAllBytes(Path.of("my-data.bin")); // any bytes
 *
 * // Encode into one or more blobs
 * List&lt;Blob&gt; blobs = BlobEncoder.encode(data);
 *
 * // Submit all blobs in a single transaction (up to 6 blobs = ~768KB)
 * BlobTransaction.Builder tx = BlobTransaction.builder()
 *     .to("0xRecipient")
 *     .maxFeePerGas(Units.gweiToWei(30))
 *     .maxPriorityFeePerGas(Units.gweiToWei(2))
 *     .maxFeePerBlobGas(Units.gweiToWei(1))
 *     .nonce(nonce).chainId(1L);
 *
 * for (Blob blob : blobs) tx.blob(blob);
 * String rawTx = tx.sign(wallet);
 * </pre>
 *
 * <h2>Decoding</h2>
 *
 * <pre>
 * // On the receiving side (e.g. from beacon chain blob sidecar):
 * byte[] decoded = BlobEncoder.decode(blobData, originalLength);
 * </pre>
 *
 * @see Blob
 * @see BlobTransaction
 */
public final class BlobEncoder {

    /** Usable bytes per field element (31 of 32, first byte is 0x00). */
    public static final int BYTES_PER_FIELD_ELEMENT = 31;

    /** Max data bytes per blob = 4096 field elements × 31 bytes. */
    public static final int MAX_BYTES_PER_BLOB =
            Kzg.FIELD_ELEMENTS_PER_BLOB * BYTES_PER_FIELD_ELEMENT;

    /** Max data bytes across all 6 blobs in a transaction. */
    public static final int MAX_BYTES_PER_TX =
            BlobTransaction.MAX_BLOBS_PER_TX * MAX_BYTES_PER_BLOB;

    private BlobEncoder() {}

    /**
     * Encode arbitrary bytes into one or more {@link Blob}s.
     *
     * <p>Splits the input into chunks of at most {@link #MAX_BYTES_PER_BLOB} bytes (126,976 bytes
     * per blob) and computes real KZG for each blob.
     *
     * <p>The number of blobs is {@code ceil(data.length / MAX_BYTES_PER_BLOB)}, capped at {@link
     * BlobTransaction#MAX_BLOBS_PER_TX} (6).
     *
     * @param data the payload to encode; max {@link #MAX_BYTES_PER_TX} bytes
     * @return array of blobs with real KZG commitments and proofs
     * @throws IllegalArgumentException if data exceeds the 6-blob limit
     */
    public static Blob[] encode(byte[] data) {
        if (data.length > MAX_BYTES_PER_TX)
            throw new IllegalArgumentException(
                    "Data too large for a single blob transaction: "
                            + data.length
                            + " bytes (max "
                            + MAX_BYTES_PER_TX
                            + " = 6 blobs × "
                            + MAX_BYTES_PER_BLOB
                            + " bytes)");

        int blobCount = (data.length + MAX_BYTES_PER_BLOB - 1) / MAX_BYTES_PER_BLOB;
        if (blobCount == 0) blobCount = 1;

        Blob[] blobs = new Blob[blobCount];
        for (int i = 0; i < blobCount; i++) {
            int start = i * MAX_BYTES_PER_BLOB;
            int end = Math.min(start + MAX_BYTES_PER_BLOB, data.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);
            blobs[i] = Blob.from(encodeChunk(chunk));
        }
        return blobs;
    }

    /**
     * Encode a string (UTF-8) into one or more blobs.
     *
     * @param text the string to encode
     * @return blobs with real KZG
     */
    public static Blob[] encode(String text) {
        return encode(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode blob data back to the original bytes.
     *
     * @param blobData 131072-byte raw blob field elements (field-element encoding)
     * @param originalLength the original data length before encoding (to strip padding)
     * @return original bytes
     */
    public static byte[] decode(byte[] blobData, int originalLength) {
        if (blobData.length != Blob.BYTES_PER_BLOB)
            throw new IllegalArgumentException(
                    "Expected " + Blob.BYTES_PER_BLOB + " bytes, got " + blobData.length);

        int maxOut = BYTES_PER_FIELD_ELEMENT * Kzg.FIELD_ELEMENTS_PER_BLOB;
        byte[] out = new byte[Math.min(originalLength, maxOut)];
        int outPos = 0;

        for (int i = 0; i < Kzg.FIELD_ELEMENTS_PER_BLOB && outPos < originalLength; i++) {
            int fieldStart = i * 32 + 1; // skip leading 0x00 byte
            int copy = Math.min(BYTES_PER_FIELD_ELEMENT, originalLength - outPos);
            System.arraycopy(blobData, fieldStart, out, outPos, copy);
            outPos += copy;
        }
        return out;
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    /**
     * Encode up to {@link #MAX_BYTES_PER_BLOB} bytes into a 131072-byte field-element blob. Each
     * 32-byte slot stores 31 bytes of data; byte 0 of each slot is 0x00.
     */
    static byte[] encodeChunk(byte[] data) {
        byte[] blob = new byte[Blob.BYTES_PER_BLOB]; // zero-filled
        int dataPos = 0;
        for (int i = 0; i < Kzg.FIELD_ELEMENTS_PER_BLOB && dataPos < data.length; i++) {
            int blobOffset = i * 32 + 1; // byte 0 stays 0x00 (field element prefix)
            int copy = Math.min(BYTES_PER_FIELD_ELEMENT, data.length - dataPos);
            System.arraycopy(data, dataPos, blob, blobOffset, copy);
            dataPos += copy;
        }
        return blob;
    }
}
