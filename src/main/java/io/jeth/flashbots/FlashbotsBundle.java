/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.flashbots;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Flashbots bundle — an ordered list of signed transactions to be included atomically.
 *
 * <pre>
 * var bundle = FlashbotsBundle.of()
 *     .tx(signedApproveTx)            // must succeed
 *     .tx(signedSwapTx)               // must succeed
 *     .allowRevert(signedOptionalTx)  // failure here is OK — bundle still included
 *     .build();
 * </pre>
 */
public final class FlashbotsBundle {

    private final List<String> rawTxs;
    private final List<String> revertingTxHashes;

    private FlashbotsBundle(List<String> rawTxs, List<String> revertingTxHashes) {
        this.rawTxs            = Collections.unmodifiableList(rawTxs);
        this.revertingTxHashes = Collections.unmodifiableList(revertingTxHashes);
    }

    public List<String> rawTxs()            { return rawTxs; }
    public List<String> revertingTxHashes() { return revertingTxHashes; }
    public int          size()              { return rawTxs.size(); }

    /** Start building a bundle. */
    public static Builder of() { return new Builder(); }

    public static final class Builder {
        private final List<String> txs             = new ArrayList<>();
        private final List<String> revertingHashes = new ArrayList<>();

        /**
         * Add a transaction that MUST succeed.
         * If it reverts, the entire bundle is dropped.
         *
         * @param signedRawTx 0x-prefixed signed raw transaction hex
         */
        public Builder tx(String signedRawTx) {
            txs.add(signedRawTx);
            return this;
        }

        /**
         * Add a transaction that is allowed to revert.
         * The bundle will still be included even if this tx fails.
         * The tx hash will be added to {@code revertingTxHashes}.
         *
         * Useful for "backrun" transactions that only have value in specific conditions.
         *
         * @param signedRawTx 0x-prefixed signed raw transaction hex
         * @param txHash      the hash of the signed transaction (computed off-chain)
         */
        public Builder allowRevert(String signedRawTx, String txHash) {
            txs.add(signedRawTx);
            revertingHashes.add(txHash);
            return this;
        }

        /** Add multiple transactions at once. All must succeed. */
        public Builder txs(List<String> signedRawTxs) {
            txs.addAll(signedRawTxs);
            return this;
        }

        public FlashbotsBundle build() {
            if (txs.isEmpty())
                throw new IllegalStateException("Bundle must contain at least one transaction");
            return new FlashbotsBundle(new ArrayList<>(txs), new ArrayList<>(revertingHashes));
        }
    }

    @Override public String toString() {
        return "FlashbotsBundle{txs=" + rawTxs.size() +
                (revertingTxHashes.isEmpty() ? "" : ", revertingTxHashes=" + revertingTxHashes.size()) +
                "}";
    }
}
