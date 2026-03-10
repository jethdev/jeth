/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import io.jeth.util.Hex;
import io.jeth.util.Keccak;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Represents a Solidity contract function — computes selector, encodes calldata, decodes return values.
 */
public class Function {

    private final String    name;
    private final AbiType[] inputTypes;
    private AbiType[]       outputTypes = new AbiType[0];
    private final String    signature;
    private final byte[]    selector;

    public Function(String name, AbiType... inputTypes) {
        this.name       = name;
        this.inputTypes = inputTypes;
        this.signature  = name + "(" + Arrays.stream(inputTypes)
            .map(AbiType::toString).collect(Collectors.joining(",")) + ")";
        byte[] hash = Keccak.hash(signature);
        this.selector = Arrays.copyOf(hash, 4);
    }

    public static Function of(String name, AbiType... inputTypes) {
        return new Function(name, inputTypes);
    }

    public Function withReturns(AbiType... outputTypes) {
        this.outputTypes = outputTypes;
        return this;
    }

    public Function withReturns(String... typeNames) {
        this.outputTypes = Arrays.stream(typeNames).map(AbiType::of).toArray(AbiType[]::new);
        return this;
    }

    /** Encode function call into hex calldata (selector + ABI params). */
    public String encode(Object... values) {
        byte[] params   = AbiCodec.encode(inputTypes, values);
        byte[] calldata = new byte[4 + params.length];
        System.arraycopy(selector, 0, calldata, 0, 4);
        System.arraycopy(params,   0, calldata, 4, params.length);
        return Hex.encode(calldata);
    }

    /** Decode the return value from an eth_call hex result. */
    public Object[] decodeReturn(String hexResult) {
        if (outputTypes.length == 0) throw new AbiException("No output types declared — use .withReturns(...)");
        byte[] data = Hex.decode(hexResult);
        return AbiCodec.decode(outputTypes, data);
    }

    public Object[] decodeReturn(byte[] data) {
        if (outputTypes.length == 0) throw new AbiException("No output types declared — use .withReturns(...)");
        return AbiCodec.decode(outputTypes, data);
    }

    public String    getSignature()    { return signature; }
    public String    getSelectorHex()  { return Hex.encode(selector); }
    /** Returns 4-byte selector as "0x..." hex — shorthand for {@link #getSelectorHex()}. */
    public String    selector()        { return getSelectorHex(); }
    public byte[]    getSelector()     { return Arrays.copyOf(selector, 4); }
    public AbiType[] getInputTypes()   { return Arrays.copyOf(inputTypes, inputTypes.length); }
    public AbiType[] getOutputTypes()  { return Arrays.copyOf(outputTypes, outputTypes.length); }
    public String    getName()         { return name; }

    @Override public String toString() {
        return "Function{" + signature + ", selector=" + Hex.encode(selector) + "}";
    }
}
