/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.model.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.jeth.util.Hex;

import java.io.IOException;
import java.math.BigInteger;

public class HexBigIntegerDeserializer extends JsonDeserializer<BigInteger> {
    @Override
    public BigInteger deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String value = p.getText();
        if (value == null || value.isBlank()) return BigInteger.ZERO;
        return Hex.toBigInteger(value);
    }
}
