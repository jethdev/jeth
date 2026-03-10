/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.core;

/**
 * Base exception for all jeth errors. Carries optional revert data for decoding with
 * AbiDecodeError.
 */
public class EthException extends RuntimeException {

    private final String revertData;

    public EthException(String message) {
        super(message);
        this.revertData = null;
    }

    public EthException(String message, Throwable cause) {
        super(message, cause);
        this.revertData = null;
    }

    public EthException(String message, String revertData) {
        super(message);
        this.revertData = revertData;
    }

    /** Raw hex revert data (if available). Decode with AbiDecodeError.decode(). */
    public String getRevertData() {
        return revertData;
    }

    public boolean hasRevertData() {
        return revertData != null && !revertData.isEmpty();
    }
}
