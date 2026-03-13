/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.eip712;

import io.jeth.abi.AbiCodec;
import io.jeth.abi.AbiType;
import io.jeth.crypto.Signature;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EIP-712: Typed structured data hashing and signing.
 *
 * <p>Used by every major DApp: Uniswap, OpenSea, ERC-2612 Permit, Compound, MetaMask, Gnosis Safe,
 * 1inch, Aave, and more.
 *
 * <pre>
 * // Define domain + types
 * var domain = TypedData.Domain.builder()
 *     .name("MyApp").version("1").chainId(1L)
 *     .verifyingContract("0xContractAddress")
 *     .build();
 *
 * var types = Map.of(
 *     "Order", List.of(
 *         new TypedData.Field("from",     "address"),
 *         new TypedData.Field("to",       "address"),
 *         new TypedData.Field("amount",   "uint256"),
 *         new TypedData.Field("deadline", "uint256")
 *     )
 * );
 *
 * var message = Map.of(
 *     "from", wallet.getAddress(), "to", "0xRecipient",
 *     "amount", BigInteger.valueOf(1_000_000L),
 *     "deadline", BigInteger.valueOf(9999999999L)
 * );
 *
 * Signature sig = TypedData.sign(domain, "Order", types, message, wallet);
 * String sigHex = TypedData.signHex(domain, "Order", types, message, wallet);
 * </pre>
 */
public class TypedData {

    // ─── Core signing ─────────────────────────────────────────────────────────

    /** Sign EIP-712 typed data. Returns Signature where v is 27 or 28. */
    public static Signature sign(
            Domain domain,
            String primaryType,
            Map<String, List<Field>> types,
            Map<String, Object> message,
            Wallet wallet) {
        byte[] hash = hashTypedData(domain, primaryType, types, message);
        Signature raw = wallet.sign(hash);
        return new Signature(raw.r, raw.s, raw.v + 27);
    }

    /** Sign and return as 65-byte hex (r+s+v, 0x-prefixed). */
    public static String signHex(
            Domain domain,
            String primaryType,
            Map<String, List<Field>> types,
            Map<String, Object> message,
            Wallet wallet) {
        Signature sig = sign(domain, primaryType, types, message, wallet);
        byte[] out = new byte[65];
        byte[] r = toBe32(sig.r), s = toBe32(sig.s);
        System.arraycopy(r, 0, out, 0, 32);
        System.arraycopy(s, 0, out, 32, 32);
        out[64] = (byte) sig.v;
        return Hex.encode(out);
    }

    /** Compute the EIP-712 signing hash: keccak256("\x19\x01" || domainSep || structHash) */
    public static byte[] hashTypedData(
            Domain domain,
            String primaryType,
            Map<String, List<Field>> types,
            Map<String, Object> message) {
        byte[] domainSep = domain.separator();
        byte[] structHash = hashStruct(primaryType, types, message);
        byte[] payload = new byte[2 + 32 + 32];
        payload[0] = 0x19;
        payload[1] = 0x01;
        System.arraycopy(domainSep, 0, payload, 2, 32);
        System.arraycopy(structHash, 0, payload, 34, 32);
        return Keccak.hash(payload);
    }

    /** keccak256(typeHash || encodeData(typeName, message)) */
    public static byte[] hashStruct(
            String typeName, Map<String, List<Field>> types, Map<String, Object> message) {
        byte[] th = typeHash(typeName, types);
        byte[] data = encodeData(typeName, types, message);
        return Keccak.hash(concat(th, data));
    }

    /** keccak256(encodeType(typeName)) */
    public static byte[] typeHash(String typeName, Map<String, List<Field>> types) {
        return Keccak.hash(encodeType(typeName, types).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * EIP-712 encodeType: primary type string + referenced types (sorted alphabetically). e.g.
     * "Mail(address from,address to,string contents)"
     */
    public static String encodeType(String typeName, Map<String, List<Field>> types) {
        List<Field> fields = types.get(typeName);
        if (fields == null) throw new EIP712Exception("Type not found: " + typeName);

        // Collect all referenced struct types (DFS)
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        collectTypes(typeName, types, visited);
        visited.remove(typeName);

        StringBuilder sb = new StringBuilder();
        sb.append(typeName).append('(');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            Field f = fields.get(i);
            sb.append(f.type()).append(' ').append(f.name());
        }
        sb.append(')');

        // Append referenced types alphabetically
        List<String> sorted = new ArrayList<>(visited);
        Collections.sort(sorted);
        for (String ref : sorted) {
            List<Field> refFields = types.get(ref);
            if (refFields == null) continue;
            sb.append(ref).append('(');
            for (int i = 0; i < refFields.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(refFields.get(i).type()).append(' ').append(refFields.get(i).name());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static void collectTypes(
            String typeName, Map<String, List<Field>> types, Set<String> visited) {
        if (visited.contains(typeName)) return;
        visited.add(typeName);
        List<Field> fields = types.get(typeName);
        if (fields == null) return;
        for (Field f : fields) {
            String base = f.type().replaceAll("\\[.*]$", "");
            if (types.containsKey(base)) collectTypes(base, types, visited);
        }
    }

    /** Encode struct fields as 32-byte slots (EIP-712 encodeData). */
    public static byte[] encodeData(
            String typeName, Map<String, List<Field>> types, Map<String, Object> message) {
        List<Field> fields = types.get(typeName);
        if (fields == null) throw new EIP712Exception("Type not found: " + typeName);
        byte[] result = new byte[0];
        for (Field field : fields) {
            if (!message.containsKey(field.name()))
                throw new EIP712Exception("Missing field in message: " + field.name());
            result = concat(result, encodeField(field.type(), message.get(field.name()), types));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static byte[] encodeField(String type, Object value, Map<String, List<Field>> types) {
        // Struct reference
        String baseType = type.replaceAll("\\[.*]$", "");
        if (types.containsKey(baseType)) {
            if (type.contains("[")) {
                // Array of structs
                Object[] elements = toArray(value);
                byte[] encoded = new byte[0];
                for (Object elem : elements)
                    encoded =
                            concat(
                                    encoded,
                                    Keccak.hash(
                                            concat(
                                                    typeHash(baseType, types),
                                                    encodeData(
                                                            baseType,
                                                            types,
                                                            (Map<String, Object>) elem))));
                return Keccak.hash(encoded);
            }
            Map<String, Object> struct = (Map<String, Object>) value;
            return Keccak.hash(concat(typeHash(type, types), encodeData(type, types, struct)));
        }

        // Dynamic types — hash the content
        if (type.equals("bytes") || type.equals("string")) {
            byte[] data =
                    type.equals("string")
                            ? value.toString().getBytes(StandardCharsets.UTF_8)
                            : toBytes(value);
            return Keccak.hash(data);
        }

        // Dynamic arrays of primitives — encode elements then hash
        if (type.endsWith("[]")) {
            String elemType = type.substring(0, type.length() - 2);
            Object[] elements = toArray(value);
            byte[] encoded = new byte[0];
            for (Object elem : elements)
                encoded = concat(encoded, encodeField(elemType, elem, types));
            return Keccak.hash(encoded);
        }

        // Primitive types — 32-byte encoding
        if (type.equals("bool")) return AbiCodec.encodeBool(toBoolean(value));
        if (type.equals("address")) return AbiCodec.encodeAddress(value.toString());
        if (type.startsWith("uint") || type.startsWith("int"))
            return AbiCodec.encodeUint256(AbiCodec.toBigInteger(value));
        if (type.startsWith("bytes")) {
            int size = Integer.parseInt(type.substring(5));
            return AbiCodec.encodeFixedBytes(toBytes(value), size);
        }

        throw new EIP712Exception("Unknown field type: " + type);
    }

    // ─── ERC-2612 Permit helper ───────────────────────────────────────────────

    /**
     * @deprecated Use {@link #signPermit(String, String, String, long, String, String, BigInteger,
     *     BigInteger, BigInteger, Wallet)}
     */
    @Deprecated
    public static Signature signPermit(
            Domain domain,
            String owner,
            String spender,
            BigInteger value,
            BigInteger nonce,
            BigInteger deadline,
            Wallet wallet) {
        Map<String, List<Field>> types =
                Map.of(
                        "Permit",
                        List.of(
                                new Field("owner", "address"),
                                new Field("spender", "address"),
                                new Field("value", "uint256"),
                                new Field("nonce", "uint256"),
                                new Field("deadline", "uint256")));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("owner", owner);
        message.put("spender", spender);
        message.put("value", value);
        message.put("nonce", nonce);
        message.put("deadline", deadline);

        return sign(domain, "Permit", types, message, wallet);
    }

    /**
     * Sign an ERC-2612 Permit (gasless ERC-20 approval). Compatible with USDC, DAI, Uniswap LP
     * tokens, and any ERC-2612 token.
     *
     * <pre>
     * Signature sig = TypedData.signPermit(
     *     "0xUSDC", "USD Coin", "2", 1L,
     *     wallet.getAddress(), spenderAddress,
     *     BigInteger.valueOf(1_000_000L), nonce, deadline, wallet
     * );
     * // token.permit(owner, spender, value, deadline, v, r, s)
     * </pre>
     */
    public static Signature signPermit(
            String tokenAddress,
            String tokenName,
            String version,
            long chainId,
            String owner,
            String spender,
            BigInteger value,
            BigInteger nonce,
            BigInteger deadline,
            Wallet wallet) {

        Domain domain =
                Domain.builder()
                        .name(tokenName)
                        .version(version)
                        .chainId(chainId)
                        .verifyingContract(tokenAddress)
                        .build();

        return signPermit(domain, owner, spender, value, nonce, deadline, wallet);
    }

    // ─── Domain ───────────────────────────────────────────────────────────────

    /**
     * EIP-712 field definition.
     *
     * @param name field name
     * @param type field type (e.g. "uint256", "address")
     */
    public record Field(String name, String type) {
        public static Field of(String name, String type) {
            return new Field(name, type);
        }
    }

    /** EIP-712 domain separator definition. */
    public static final class Domain {
        public final String name;
        public final String version;
        public final Long chainId;
        public final String verifyingContract;
        public final byte[] salt;

        private Domain(Builder b) {
            this.name = b.name;
            this.version = b.version;
            this.chainId = b.chainId;
            this.verifyingContract = b.verifyingContract;
            this.salt = b.salt;
        }

        /**
         * Compute the EIP-712 domain separator for this domain. Result is deterministic and can be
         * cached.
         */
        public byte[] separator() {
            // Build type string from non-null fields
            StringBuilder typeStr = new StringBuilder("EIP712Domain(");
            List<AbiType> abiTypes = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            boolean first = true;

            if (name != null) {
                first = false;
                typeStr.append("string name");
                abiTypes.add(AbiType.BYTES32);
                vals.add(Keccak.hash(name.getBytes(StandardCharsets.UTF_8)));
            }
            if (version != null) {
                if (!first) typeStr.append(',');
                first = false;
                typeStr.append("string version");
                abiTypes.add(AbiType.BYTES32);
                vals.add(Keccak.hash(version.getBytes(StandardCharsets.UTF_8)));
            }
            if (chainId != null) {
                if (!first) typeStr.append(',');
                first = false;
                typeStr.append("uint256 chainId");
                abiTypes.add(AbiType.UINT256);
                vals.add(BigInteger.valueOf(chainId));
            }
            if (verifyingContract != null) {
                if (!first) typeStr.append(',');
                first = false;
                typeStr.append("address verifyingContract");
                abiTypes.add(AbiType.ADDRESS);
                vals.add(verifyingContract);
            }
            if (salt != null) {
                if (!first) typeStr.append(',');
                typeStr.append("bytes32 salt");
                abiTypes.add(AbiType.BYTES32);
                vals.add(salt);
            }
            typeStr.append(')');

            byte[] domainTypeHash =
                    Keccak.hash(typeStr.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encodedVals = AbiCodec.encode(abiTypes.toArray(AbiType[]::new), vals.toArray());
            return Keccak.hash(concat(domainTypeHash, encodedVals));
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            String name;
            String version;
            Long chainId;
            String verifyingContract;
            byte[] salt;

            public Builder name(String v) {
                this.name = v;
                return this;
            }

            public Builder version(String v) {
                this.version = v;
                return this;
            }

            public Builder chainId(long v) {
                this.chainId = v;
                return this;
            }

            public Builder verifyingContract(String v) {
                this.verifyingContract = v;
                return this;
            }

            @SuppressWarnings("unused")
            public Builder salt(byte[] v) {
                this.salt = v;
                return this;
            }

            public Domain build() {
                return new Domain(this);
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] toBe32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        if (raw.length >= 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] toBytes(Object v) {
        if (v instanceof byte[] b) return b;
        if (v instanceof String s) return Hex.decode(s);
        throw new EIP712Exception("Cannot convert to bytes: " + v);
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof BigInteger bi) return bi.signum() != 0;
        if (v instanceof Integer i) return i != 0;
        throw new EIP712Exception("Cannot convert to boolean: " + v);
    }

    private static Object[] toArray(Object v) {
        if (v instanceof Object[] a) return a;
        if (v instanceof List<?> l) return l.toArray();
        throw new EIP712Exception("Cannot convert to array: " + v);
    }
}
