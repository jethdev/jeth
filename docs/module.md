# Module jeth

Java Ethereum library. Two runtime dependencies (Jackson + BouncyCastle).

## Key entry points

| Class | What it does |
|---|---|
| [`EthClient`](io.jeth.core/-eth-client/index.html) | JSON-RPC client — start here |
| [`Wallet`](io.jeth.crypto/-wallet/index.html) | Key generation and signing |
| [`HdWallet`](io.jeth.wallet/-hd-wallet/index.html) | BIP-39 mnemonic / BIP-44 derivation |
| [`Contract`](io.jeth.contract/-contract/index.html) | Low-level contract calls |
| [`ContractProxy`](io.jeth.contract/-contract-proxy/index.html) | Typed proxy from a Java interface |
| [`ERC20`](io.jeth.contract/-e-r-c20/index.html) | ERC-20 with EIP-2612 Permit |
| [`Multicall3`](io.jeth.multicall/-multicall3/index.html) | Batch N reads into 1 RPC call |
| [`PriceOracle`](io.jeth.price/-price-oracle/index.html) | Chainlink feeds + Uniswap V3 TWAP |
| [`BlockScanner`](io.jeth.scan/-block-scanner/index.html) | Historical event scanning with resume |
| [`FlashbotsClient`](io.jeth.flashbots/-flashbots-client/index.html) | MEV protection via private mempool |
| [`GnosisSafe`](io.jeth.safe/-gnosis-safe/index.html) | Multisig execution |
| [`MiddlewareProvider`](io.jeth.middleware/-middleware-provider/index.html) | Retry, cache, fallback, metrics |

## Install

```kotlin
// Gradle
implementation("io.jeth:jeth:1.2.0")

// Maven
// <dependency><groupId>io.jeth</groupId><artifactId>jeth</artifactId><version>1.2.0</version></dependency>
```

Full usage examples and guides: [README on GitHub](https://github.com/jeth-io/jeth#readme)
