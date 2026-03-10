/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip712;

public class EIP712Exception extends RuntimeException {
    public EIP712Exception(String message) {
        super(message);
    }

    public EIP712Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
