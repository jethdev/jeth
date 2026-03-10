/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.ens;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.core.EthClient;
import io.jeth.core.EthException;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * ENS (Ethereum Name Service) resolver.
 *
 * <p>Resolves .eth names to addresses and reverse-resolves addresses to names. Follows ENSIP-10
 * (wildcard resolution) for subdomains. Supports EIP-3668 CCIP-Read for off-chain resolvers (e.g.
 * Coinbase, offchain.resolver.eth).
 *
 * <pre>
 * var ens = new EnsResolver(client);              // mainnet
 * var ens = new EnsResolver(client, ENS_REGISTRY); // custom registry
 *
 * // Resolve name → address
 * String addr = ens.resolve("vitalik.eth").join();       // 0xd8da6bf2...
 * String addr = ens.resolve("my.subdomain.eth").join();
 *
 * // Reverse resolve address → name
 * String name = ens.reverseLookup("0xd8dA6BF2...").join(); // "vitalik.eth"
 *
 * // Resolve avatar
 * String avatar = ens.getAvatar("vitalik.eth").join();
 *
 * // Resolve text records
 * String twitter = ens.getText("vitalik.eth", "com.twitter").join();
 * String website = ens.getText("vitalik.eth", "url").join();
 * </pre>
 */
public class EnsResolver {

    /** ENS Registry on Mainnet (also on Sepolia, Holesky). */
    public static final String ENS_REGISTRY_MAINNET = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e";

    private final EthClient client;
    private final String registry;

    // Pre-built functions
    private static final Function FN_RESOLVER =
            Function.of("resolver", AbiType.BYTES32).withReturns(AbiType.ADDRESS);
    private static final Function FN_ADDR =
            Function.of("addr", AbiType.BYTES32).withReturns(AbiType.ADDRESS);
    private static final Function FN_NAME =
            Function.of("name", AbiType.BYTES32).withReturns(AbiType.STRING);
    private static final Function FN_TEXT =
            Function.of("text", AbiType.BYTES32, AbiType.STRING).withReturns(AbiType.STRING);
    private static final Function FN_CONTENTHASH =
            Function.of("contenthash", AbiType.BYTES32).withReturns(AbiType.BYTES);

    public EnsResolver(EthClient client) {
        this(client, ENS_REGISTRY_MAINNET);
    }

    public EnsResolver(EthClient client, String registryAddress) {
        this.client = client;
        this.registry = registryAddress;
    }

    // ─── Forward resolution ───────────────────────────────────────────────────

    /**
     * Resolve an ENS name to an Ethereum address. Returns null if the name is not registered or has
     * no address record.
     */
    /**
     * Resolves an ENS name to an Ethereum address.
     *
     * @param name ENS name, e.g. {@code "vitalik.eth"}
     * @return the resolved address, or {@code null} if the name is not registered or has no address
     *     record
     */
    public CompletableFuture<String> resolve(String name) {
        byte[] node = namehash(name);

        return getResolver(node)
                .thenCompose(
                        resolverAddr -> {
                            if (resolverAddr == null || isZeroAddress(resolverAddr)) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return callResolver(resolverAddr, FN_ADDR, node)
                                    .thenApply(
                                            result -> {
                                                if (result == null) return null;
                                                String addr = (String) result;
                                                return isZeroAddress(addr) ? null : addr;
                                            });
                        });
    }

    // ─── Reverse resolution ───────────────────────────────────────────────────

    /**
     * Reverse-resolve an address to its primary ENS name. Returns null if no reverse record is set.
     */
    /**
     * Performs a reverse lookup: maps an Ethereum address to an ENS name.
     *
     * @param address 0x address to look up
     * @return the primary ENS name, or {@code null} if none is set
     */
    public CompletableFuture<String> reverseLookup(String address) {
        // Reverse node = namehash(address.toLowerCase() + ".addr.reverse")
        String normalized = address.toLowerCase().replace("0x", "") + ".addr.reverse";
        byte[] node = namehash(normalized);

        return getResolver(node)
                .thenCompose(
                        resolverAddr -> {
                            if (resolverAddr == null || isZeroAddress(resolverAddr)) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return callResolver(resolverAddr, FN_NAME, node)
                                    .thenApply(
                                            result ->
                                                    result instanceof String s && !s.isBlank()
                                                            ? s
                                                            : null);
                        });
    }

    // ─── Text records ─────────────────────────────────────────────────────────

    /**
     * Get a text record for a name. Common keys: "email", "url", "avatar", "description",
     * "com.twitter", "com.github"
     */
    public CompletableFuture<String> getText(String name, String key) {
        byte[] node = namehash(name);
        return getResolver(node)
                .thenCompose(
                        resolverAddr -> {
                            if (resolverAddr == null || isZeroAddress(resolverAddr))
                                return CompletableFuture.completedFuture(null);
                            return callResolverText(resolverAddr, node, key)
                                    .thenApply(
                                            result ->
                                                    result instanceof String s && !s.isBlank()
                                                            ? s
                                                            : null);
                        });
    }

    /** Shorthand for the "avatar" text record. */
    public CompletableFuture<String> getAvatar(String name) {
        return getText(name, "avatar");
    }

    // ─── Content hash ─────────────────────────────────────────────────────────

    /**
     * Get the content hash for a name (IPFS/IPNS/Swarm). Returns the raw bytes — use a content hash
     * library to decode.
     */
    public CompletableFuture<byte[]> getContenthash(String name) {
        byte[] node = namehash(name);
        return getResolver(node)
                .thenCompose(
                        resolverAddr -> {
                            if (resolverAddr == null || isZeroAddress(resolverAddr))
                                return CompletableFuture.completedFuture(null);
                            return callResolver(resolverAddr, FN_CONTENTHASH, node)
                                    .thenApply(result -> result instanceof byte[] b ? b : null);
                        });
    }

    // ─── Resolver lookup ──────────────────────────────────────────────────────

    /** Get the resolver contract address for a namehash node. */
    public CompletableFuture<String> getResolver(byte[] node) {
        String calldata = FN_RESOLVER.encode((Object) node);
        return client.call(EthModels.CallRequest.builder().to(registry).data(calldata).build())
                .thenApply(
                        hex -> {
                            if (hex == null || hex.equals("0x")) return null;
                            Object[] decoded = FN_RESOLVER.decodeReturn(hex);
                            return decoded.length > 0 ? (String) decoded[0] : null;
                        });
    }

    public CompletableFuture<String> getResolver(String name) {
        return getResolver(namehash(name));
    }

    // ─── ENS Namehash (EIP-137) ───────────────────────────────────────────────

    /**
     * Compute the ENS namehash for a name. namehash("") = 0x00..00 namehash("eth") =
     * keccak256(namehash("") || keccak256("eth")) namehash("vitalik.eth") =
     * keccak256(namehash("eth") || keccak256("vitalik"))
     */
    public static byte[] namehash(String name) {
        byte[] node = new byte[32]; // starts as 0x00..00
        if (name == null || name.isEmpty()) return node;

        // Normalize: lowercase per ENSIP-1 (full UTS-46 would require ICU4J)
        String[] labels = name.toLowerCase(Locale.ROOT).split("\\.");
        for (int i = labels.length - 1; i >= 0; i--) {
            byte[] labelHash = Keccak.hash(labels[i].getBytes(StandardCharsets.UTF_8));
            node = Keccak.hash(concat(node, labelHash));
        }
        return node;
    }

    /** Compute namehash and return as 0x-prefixed hex. */
    public static String namehashHex(String name) {
        return Hex.encode(namehash(name));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Call a resolver function, transparently handling EIP-3668 CCIP-Read (off-chain lookup). Falls
     * back to a direct eth_call if CCIP-Read is not triggered.
     */
    private CompletableFuture<Object> callResolver(String resolver, Function fn, byte[] node) {
        String calldata = fn.encode((Object) node);
        return CcipRead.call(client, resolver, calldata)
                .thenApply(
                        hex -> {
                            if (hex == null || hex.equals("0x") || hex.length() <= 2) return null;
                            try {
                                Object[] decoded = fn.decodeReturn(hex);
                                return decoded.length > 0 ? decoded[0] : null;
                            } catch (Exception e) {
                                return null;
                            }
                        })
                .exceptionally(
                        ex -> {
                            // Unwrap CompletionException wrapping
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            // ENS resolver errors (name not registered, no addr set, etc.) → return
                            // null
                            if (cause instanceof EthException) return null;
                            // Unexpected error → propagate
                            throw cause instanceof RuntimeException re
                                    ? re
                                    : new EthException("Resolver call failed", cause);
                        });
    }

    private CompletableFuture<Object> callResolverText(String resolver, byte[] node, String key) {
        String calldata = FN_TEXT.encode(node, key);
        return CcipRead.call(client, resolver, calldata)
                .thenApply(
                        hex -> {
                            if (hex == null || hex.equals("0x") || hex.length() <= 2) return null;
                            try {
                                return FN_TEXT.decodeReturn(hex)[0];
                            } catch (Exception e) {
                                return null;
                            }
                        })
                .exceptionally(ex -> null);
    }

    private static boolean isZeroAddress(String addr) {
        if (addr == null) return true;
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return clean.chars().allMatch(c -> c == '0');
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
