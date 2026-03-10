/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.core;

import java.util.Arrays;
import java.util.Optional;

/**
 * Well-known EVM chain definitions: chain ID, name, native currency, RPC templates, public RPC
 * endpoints, and block explorer URLs.
 *
 * <p>Use {@link #rpc(String)} when your RPC provider requires an API key in the URL (Infura,
 * Alchemy). Use {@link #publicRpc()} for free public endpoints — these are rate-limited and
 * suitable for development or low-traffic use only.
 *
 * <pre>
 * // With API key
 * var client = EthClient.of(Chain.MAINNET.rpc("YOUR_INFURA_KEY"));
 * var client = EthClient.of(Chain.ARBITRUM.rpc("YOUR_ALCHEMY_KEY"));
 *
 * // Free public endpoint
 * var client = EthClient.of(Chain.BASE.publicRpc());
 *
 * // Lookup by chain ID at runtime
 * Chain.fromId(8453).ifPresent(c -> System.out.println(c.getName())); // "Base"
 * </pre>
 *
 * <p>Any EVM-compatible chain works with jeth — just pass the RPC URL directly to {@link
 * io.jeth.core.EthClient#of(String)}. {@code Chain} constants are a convenience for well-known
 * networks; they are not required.
 */
public enum Chain {

    // ─── Ethereum ─────────────────────────────────────────────────────────────
    MAINNET(
            1,
            "Ethereum",
            "ETH",
            18,
            "https://mainnet.infura.io/v3/%s",
            "https://cloudflare-eth.com",
            "https://etherscan.io"),
    SEPOLIA(
            11155111,
            "Ethereum Sepolia",
            "ETH",
            18,
            "https://sepolia.infura.io/v3/%s",
            "https://rpc.sepolia.org",
            "https://sepolia.etherscan.io"),
    HOLESKY(
            17000,
            "Ethereum Holesky",
            "ETH",
            18,
            "https://holesky.infura.io/v3/%s",
            "https://rpc.holesky.ethpandaops.io",
            "https://holesky.etherscan.io"),

    // ─── L2s ──────────────────────────────────────────────────────────────────
    ARBITRUM(
            42161,
            "Arbitrum One",
            "ETH",
            18,
            "https://arb-mainnet.g.alchemy.com/v2/%s",
            "https://arb1.arbitrum.io/rpc",
            "https://arbiscan.io"),
    ARBITRUM_SEPOLIA(
            421614,
            "Arbitrum Sepolia",
            "ETH",
            18,
            "https://arb-sepolia.g.alchemy.com/v2/%s",
            "https://sepolia-rollup.arbitrum.io/rpc",
            "https://sepolia.arbiscan.io"),
    OPTIMISM(
            10,
            "OP Mainnet",
            "ETH",
            18,
            "https://opt-mainnet.g.alchemy.com/v2/%s",
            "https://mainnet.optimism.io",
            "https://optimistic.etherscan.io"),
    OP_SEPOLIA(
            11155420,
            "OP Sepolia",
            "ETH",
            18,
            "https://opt-sepolia.g.alchemy.com/v2/%s",
            "https://sepolia.optimism.io",
            "https://sepolia-optimism.etherscan.io"),
    BASE(
            8453,
            "Base",
            "ETH",
            18,
            "https://base-mainnet.g.alchemy.com/v2/%s",
            "https://mainnet.base.org",
            "https://basescan.org"),
    BASE_SEPOLIA(
            84532,
            "Base Sepolia",
            "ETH",
            18,
            "https://base-sepolia.g.alchemy.com/v2/%s",
            "https://sepolia.base.org",
            "https://sepolia.basescan.org"),
    ZKSYNC(
            324,
            "zkSync Era",
            "ETH",
            18,
            null,
            "https://mainnet.era.zksync.io",
            "https://explorer.zksync.io"),
    LINEA(59144, "Linea", "ETH", 18, null, "https://rpc.linea.build", "https://lineascan.build"),
    SCROLL(534352, "Scroll", "ETH", 18, null, "https://rpc.scroll.io", "https://scrollscan.com"),
    BLAST(81457, "Blast", "ETH", 18, null, "https://rpc.blast.io", "https://blastscan.io"),

    // ─── Sidechains ───────────────────────────────────────────────────────────
    POLYGON(
            137,
            "Polygon PoS",
            "MATIC",
            18,
            "https://polygon-mainnet.g.alchemy.com/v2/%s",
            "https://polygon-rpc.com",
            "https://polygonscan.com"),
    POLYGON_AMOY(
            80002,
            "Polygon Amoy",
            "MATIC",
            18,
            null,
            "https://rpc-amoy.polygon.technology",
            "https://amoy.polygonscan.com"),
    BNB(
            56,
            "BNB Smart Chain",
            "BNB",
            18,
            null,
            "https://bsc-dataseed1.binance.org",
            "https://bscscan.com"),
    AVALANCHE(
            43114,
            "Avalanche C-Chain",
            "AVAX",
            18,
            null,
            "https://api.avax.network/ext/bc/C/rpc",
            "https://snowtrace.io"),
    GNOSIS(
            100,
            "Gnosis Chain",
            "xDAI",
            18,
            null,
            "https://rpc.gnosischain.com",
            "https://gnosisscan.io"),
    FANTOM(
            250,
            "Fantom Opera",
            "FTM",
            18,
            null,
            "https://rpcapi.fantom.network",
            "https://ftmscan.com"),
    CELO(42220, "Celo", "CELO", 18, null, "https://forno.celo.org", "https://celoscan.io"),
    MANTLE(
            5000,
            "Mantle",
            "MNT",
            18,
            null,
            "https://rpc.mantle.xyz",
            "https://explorer.mantle.xyz"),
    MODE(
            34443,
            "Mode Network",
            "ETH",
            18,
            null,
            "https://mainnet.mode.network",
            "https://modescan.io"),
    TAIKO(
            167000,
            "Taiko Mainnet",
            "ETH",
            18,
            null,
            "https://rpc.mainnet.taiko.xyz",
            "https://taikoscan.io"),
    POLYGON_ZKEVM(
            1101,
            "Polygon zkEVM",
            "ETH",
            18,
            null,
            "https://zkevm-rpc.com",
            "https://zkevm.polygonscan.com"),
    ZKSYNC_SEPOLIA(
            300,
            "zkSync Era Sepolia",
            "ETH",
            18,
            null,
            "https://sepolia.era.zksync.dev",
            "https://sepolia.explorer.zksync.io"),
    ARBITRUM_NOVA(
            42170,
            "Arbitrum Nova",
            "ETH",
            18,
            null,
            "https://nova.arbitrum.io/rpc",
            "https://nova.arbiscan.io");

    private final long chainId;
    private final String name;
    private final String nativeCurrency;
    private final int nativeDecimals;
    private final String rpcTemplate; // null if no API key template
    private final String publicRpc;
    private final String explorer;

    Chain(
            long id,
            String name,
            String currency,
            int decimals,
            String rpcTemplate,
            String publicRpc,
            String explorer) {
        this.chainId = id;
        this.name = name;
        this.nativeCurrency = currency;
        this.nativeDecimals = decimals;
        this.rpcTemplate = rpcTemplate;
        this.publicRpc = publicRpc;
        this.explorer = explorer;
    }

    /** Get RPC URL with API key interpolated (e.g. Infura, Alchemy). */
    public String rpc(String apiKey) {
        if (rpcTemplate != null && apiKey != null) return String.format(rpcTemplate, apiKey);
        return publicRpc;
    }

    /** Get the public (no-key) RPC URL. */
    public String publicRpc() {
        return publicRpc;
    }

    /** Get the block explorer base URL. */
    public String explorer() {
        return explorer;
    }

    /** Get the explorer URL for a specific transaction. */
    public String txUrl(String txHash) {
        return explorer + "/tx/" + txHash;
    }

    /** Get the explorer URL for a specific address. */
    public String addressUrl(String address) {
        return explorer + "/address/" + address;
    }

    public long id() {
        return chainId;
    }

    public String getName() {
        return name;
    }

    public String nativeCurrency() {
        return nativeCurrency;
    }

    public int nativeDecimals() {
        return nativeDecimals;
    }

    public boolean isL2() {
        return chainId != 1
                && chainId != 56
                && chainId != 137
                && chainId != 43114
                && chainId != 100
                && chainId != 250
                && chainId != 42220;
    }

    public boolean isTestnet() {
        return name.contains("Sepolia") || name.contains("Holesky") || name.contains("Amoy");
    }

    /** Lookup chain by chain ID. Returns empty Optional if not found. */
    public static Optional<Chain> fromId(long chainId) {
        return Arrays.stream(values()).filter(c -> c.chainId == chainId).findFirst();
    }

    /** Lookup chain by ID, throw if not found. */
    public static Chain requireId(long chainId) {
        return fromId(chainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown chainId: " + chainId));
    }

    @Override
    public String toString() {
        return name + " (id=" + chainId + ", " + nativeCurrency + ")";
    }
}
