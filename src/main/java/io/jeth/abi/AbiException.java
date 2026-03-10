/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

public class AbiException extends RuntimeException {
    public AbiException(String message) { super(message); }
    public AbiException(String message, Throwable cause) { super(message, cause); }
}
