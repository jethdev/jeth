# jeth

A Java Ethereum library. Two runtime dependencies.

[![CI](https://github.com/jeth-io/jeth/actions/workflows/ci.yml/badge.svg)](https://github.com/jeth-io/jeth/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.jeth/jeth.svg)](https://central.sonatype.com/artifact/io.jeth/jeth)
[![Javadoc](https://img.shields.io/badge/docs-jeth--io.github.io-6366f1)](https://jeth-io.github.io/jeth/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.org/)

```java
var client = EthClient.of("https://mainnet.infura.io/v3/YOUR_KEY");

// Batch 50 token balances in 1 RPC call
List<BigInteger> balances = Multicall3.getTokenBalances(client, "0xUSDC", addresses).join();

// Read ETH/USD from Chainlink — no API key, pure on-chain
BigDecimal price = new PriceOracle(client).chainlink(PriceOracle.ETH_USD).join();

// Scan historical events with progress and resume
BlockScanner.of(client).scan("0xUSDC", Transfer, 6_082_465L, 19_000_000L, (events, p) -> {
    System.out.println(p); // ScanProgress{47.3%, blocks=470000/1000000}
    return true;
}).join();
```

---

## Install

jeth is published to **Maven Central** (`io.jeth:jeth`).

**Gradle:**
```gradle
dependencies {
    implementation 'io.jeth:jeth:1.3.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>io.jeth</groupId>
    <artifactId>jeth</artifactId>
    <version>1.3.0</version>
</dependency>
```

No extra repository block needed — Maven Central is the default for both Gradle and Maven.

**Runtime dependencies (only these two, pulled in transitively):**
```
com.fasterxml.jackson.core:jackson-databind:2.17.2
org.bouncycastle:bcprov-jdk18on:1.78.1
```

**One-time setup** for HD wallet support (downloads BIP-39 wordlist):
```bash
./gradlew fetchBip39Wordlist
```

---

## Table of Contents

- [Connect](#connect)
- [Wallets](#wallets)
- [Send Transactions](#send-transactions)
- [Read Chain Data](#read-chain-data)
- [Contracts](#contracts)
- [Tokens — ERC-20 / ERC-721 / ERC-1155](#tokens)
- [Events](#events)
- [Multicall3](#multicall3)
- [ENS](#ens)
- [Price Oracle](#price-oracle)
- [Block Scanner](#block-scanner)
- [Gas Estimation](#gas-estimation)
- [ABI Encoding](#abi-encoding)
- [Revert Decoding](#revert-decoding)
- [Transaction Simulation](#transaction-simulation)
- [Transaction Traces](#transaction-traces)
- [Storage Layout](#storage-layout)
- [EIP-712 Typed Signing](#eip-712-typed-signing)
- [EIP-4844 Blobs](#eip-4844-blobs)
- [EIP-7702 — Set Code for EOA](#eip-7702)
- [ERC-4337 Account Abstraction](#erc-4337-account-abstraction)
- [Gnosis Safe](#gnosis-safe)
- [Flashbots](#flashbots)
- [DeFi — Uniswap V3](#uniswap-v3)
- [DeFi — Aave V3](#aave-v3)
- [Provider Middleware](#provider-middleware)
- [WebSocket](#websocket)
- [Supported Chains](#supported-chains)
- [Units & Formatting](#units--formatting)
- [What jeth does NOT do](#what-jeth-does-not-do)
- [Architecture](#architecture)

---

## Connect

```java
// HTTP
var client = EthClient.of("https://mainnet.infura.io/v3/YOUR_KEY");

// Built-in chain constants
var client = EthClient.of(Chain.MAINNET.rpc("YOUR_INFURA_KEY"));
var client = EthClient.of(Chain.BASE.publicRpc()); // free public endpoint

// WebSocket (for subscriptions and lower latency)
var ws     = WsProvider.connect("wss://mainnet.infura.io/ws/v3/YOUR_KEY");
var client = EthClient.of(ws);

// Production: retry + rate-limit + block cache + logging
var provider = MiddlewareProvider.wrap(HttpProvider.of(rpcUrl))
    .withRetry(3, Duration.ofMillis(500))   // exponential backoff on errors
    .withRateLimit(100)                      // cap at 100 req/s
    .withCache(Duration.ofSeconds(12))       // cache stable responses (chainId, code, etc.)
    .withLogging()
    .build();
var client = EthClient.of(provider);

// Automatic failover across multiple RPC endpoints
var provider = MiddlewareProvider.withFallback(
    HttpProvider.of(infuraUrl),
    HttpProvider.of(alchemyUrl),
    HttpProvider.of(quicknodeUrl)
);
```

---

## Wallets

```java
Wallet wallet = Wallet.create();                             // random
Wallet wallet = Wallet.fromPrivateKey("0xac0974bec3...");
Wallet wallet = Wallet.fromPrivateKey(privateKeyBigInt);

System.out.println(wallet.getAddress());    // 0xf39Fd6... (EIP-55 checksummed)
System.out.println(wallet.getPrivateKey()); // BigInteger

Signature sig = wallet.sign(hash32bytes);   // sig.r, sig.s, sig.v
```

### HD Wallet (BIP-39 / BIP-44)

```java
HdWallet hd = HdWallet.generate();
System.out.println(hd.getMnemonic()); // "word1 word2 ... word12"

Wallet account0 = hd.getAccount(0);  // m/44'/60'/0'/0/0
Wallet account1 = hd.getAccount(1);  // m/44'/60'/0'/0/1

// Restore
HdWallet hd = HdWallet.fromMnemonic("word1 word2 ... word12");

// Custom path
Wallet custom = hd.derive("m/44'/60'/1'/0/5");

// Validate before use
boolean valid = HdWallet.isValidMnemonic("word1 word2 ... word12");
```

### Keystore V3 (MetaMask-compatible)

```java
// Encrypt
String json = Keystore.encrypt(wallet, "password");       // production (N=262144, ~5s)
String json = Keystore.encryptLight(wallet, "password");  // dev/testing (N=4096, fast)
Files.writeString(Path.of(Keystore.filename(wallet)), json);

// Decrypt — works with MetaMask, Geth, MyCrypto, MyEtherWallet
Wallet restored = Keystore.decrypt(json, "password");
Wallet restored = Keystore.decrypt(Files.readString(keystorePath), "password");
```

> Supports both scrypt (MetaMask default) and pbkdf2 key derivation.

---

## Send Transactions

```java
// Send ETH
String txHash = Contract.sendEth(client, wallet, "0xRecipient", Units.toWei("0.1")).join();

// Wait for confirmation
TransactionReceipt receipt = client.waitForTransaction(txHash).join();
System.out.println(receipt.isSuccess() ? "✓ block " + receipt.blockNumber : "✗ reverted");

// EIP-1559 transaction with explicit fees
var tx = EthModels.TransactionRequest.builder()
    .from(wallet.getAddress()).to("0xRecipient")
    .value(Units.toWei("0.05"))
    .gas(BigInteger.valueOf(21_000))
    .maxFeePerGas(Units.gweiToWei(30))
    .maxPriorityFeePerGas(Units.gweiToWei(2))
    .nonce(nonce).chainId(1L)
    .build();

String signed = TransactionSigner.signEip1559(tx, wallet);
String txHash = client.sendRawTransaction(signed).join();
```

---

## Read Chain Data

```java
// Balance and nonce
BigInteger wei   = client.getBalance("0xAddress").join();
long       nonce = client.getTransactionCount(wallet.getAddress()).join();

// Blocks
EthModels.Block block     = client.getBlock("latest").join();
EthModels.Block block     = client.getBlockByNumber(19_000_000L).join();
EthModels.Block fullBlock = client.getBlockByNumber(19_000_000L, true).join(); // includes txs

// Transactions
EthModels.Transaction        tx      = client.getTransaction("0xHash").join();
EthModels.TransactionReceipt receipt = client.getTransactionReceipt("0xHash").join();

// All receipts in a block at once (eth_getBlockReceipts)
List<EthModels.TransactionReceipt> receipts = client.getBlockReceipts("latest").join();

// Contract code
String  code       = client.getCode("0xAddress").join(); // "0x" if EOA
boolean isContract = client.isContract("0xAddress").join();

// Raw storage slot
BigInteger slotValue = client.getStorageAt("0xAddress", BigInteger.ZERO).join();

// Network
long   chainId = client.getChainId().join();
String netId   = client.getNetworkId().join();

// Logs
List<EthModels.Log> logs = client.getLogs(Map.of(
    "address",   "0xUSDC",
    "topics",    List.of("0xddf252ad..."),
    "fromBlock", "0x1200000",
    "toBlock",   "latest"
)).join(); // auto-chunks if range exceeds 2000 blocks
```

---

## Contracts

### Low-level — no ABI file needed

```java
var c = new Contract("0xAddress", client);

// Call (read)
BigInteger bal = c.fn("balanceOf(address)").returns("uint256")
                  .call("0xOwner").as(BigInteger.class).join();

// Send (write)
String txHash = c.fn("transfer(address,uint256)")
                 .send(wallet, "0xTo", amount).join();

// Payable
String txHash = c.fn("deposit()").send(wallet, Units.toWei("0.1")).join();

// Simulate before sending — catches reverts without spending gas
boolean ok = c.fn("transfer(address,uint256)")
              .simulate(wallet, "0xTo", amount).join();
```

### Runtime proxy — typed interface, zero codegen

No ABI CLI tool needed. Define a Java interface, get a typed object back.

```java
interface IERC20 {
    CompletableFuture<String>     name();
    CompletableFuture<BigInteger> totalSupply();
    CompletableFuture<BigInteger> balanceOf(String account);
    CompletableFuture<String>     transfer(Wallet wallet, String to, BigInteger amount);
    CompletableFuture<String>     approve(Wallet wallet, String spender, BigInteger amount);
}

String abi  = Files.readString(Path.of("IERC20.json")); // Hardhat/Foundry artifact or raw ABI
IERC20 usdc = ContractProxy.load(IERC20.class, "0xUSDC", abi, client);

BigInteger balance = usdc.balanceOf("0xUser").join();
String     txHash  = usdc.transfer(wallet, "0xTo", BigInteger.valueOf(1_000_000)).join();
```

**Multi-return functions** map ABI outputs to a plain interface by position:

```java
interface PoolState {
    BigInteger sqrtPriceX96();
    Integer    tick();
    Boolean    unlocked();
}
interface IPool {
    CompletableFuture<PoolState> slot0();
}

IPool     pool  = ContractProxy.load(IPool.class, "0xPool", abiJson, client);
PoolState state = pool.slot0().join();
```

### Dynamic — no interface needed

```java
var contract = ContractProxy.loadDynamic("0xAddress", abiJson, client);
Object result = contract.call("getGreeting").join();
String txHash = contract.send("setGreeting", wallet, "hello").join();
```

### Deploy

```java
String address = Contract.deploy(
    client, wallet,
    bytecodeHex,
    new AbiType[]{ AbiType.STRING },
    new Object[]{ "Hello, World!" }
).join();
```

### Human-readable ABI

Parse Solidity-style function signatures without an ABI file:

```java
Function transfer = HumanAbi.parseFunction(
    "function transfer(address to, uint256 amount) returns (bool)");

List<Function> fns = HumanAbi.parseFunctions("""
    function totalSupply() view returns (uint256)
    function balanceOf(address account) view returns (uint256)
    function transfer(address to, uint256 amount) returns (bool)
""");

// Shorthand (no 'function' keyword, no param names)
Function fn = HumanAbi.parseFunction("transfer(address,uint256)");
```

---

## Tokens

### ERC-20

```java
var usdc = new ERC20("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", client);

BigInteger raw = usdc.balanceOf("0xAddress").join();            // raw (6 decimals)
BigDecimal fmt = usdc.balanceOfFormatted("0xAddress").join();   // 1234.56
String symbol  = usdc.symbol().join();    // "USDC"
int    dec     = usdc.decimals().join();  // 6
BigInteger allowance = usdc.allowance("0xOwner", "0xSpender").join();

String txHash = usdc.transfer(wallet, "0xTo", BigInteger.valueOf(1_000_000)).join();
String txHash = usdc.approve(wallet, "0xSpender", Units.MAX_UINT256).join();

// ERC-2612 Permit — signs and calls permit() in one step
String txHash = usdc.permit(wallet, "0xSpender", amount, deadline).join();
```

### ERC-721

```java
var nft    = new ERC721("0xBAYC", client);
String owner  = nft.ownerOf(BigInteger.valueOf(1234)).join();
String uri    = nft.tokenURI(BigInteger.valueOf(1234)).join();
String txHash = nft.safeTransferFrom(wallet, "0xFrom", "0xTo", tokenId).join();
```

### ERC-1155

```java
var token = new ERC1155("0xAddress", client);
BigInteger balance = token.balanceOf("0xUser", tokenId).join();
String     uri     = token.uri(tokenId).join();
String     txHash  = token.safeTransferFrom(wallet, "0xFrom", "0xTo", tokenId, amount, new byte[0]).join();
```

---

## Events

### Live subscriptions (WebSocket)

```java
var ws     = WsProvider.connect("wss://mainnet.infura.io/ws/v3/YOUR_KEY");
var events = new ContractEvents("0xUSDC", ws);

var Transfer = EventDef.of("Transfer",
    EventDef.indexed("from",  "address"),
    EventDef.indexed("to",    "address"),
    EventDef.data(   "value", "uint256"));

events.on(Transfer, e -> {
    System.out.printf("%s → %s  %s USDC%n",
        e.address("from"), e.address("to"),
        Units.formatToken(e.uint("value"), 6));
});

events.once(Transfer, e -> System.out.println("First: " + e));
events.onAny(log -> System.out.println(log.topics.get(0))); // raw
events.removeAllListeners();
```

### Decode from receipts

```java
// Built-in
EventDecoder.ERC20_TRANSFER   // Transfer(address indexed, address indexed, uint256)
EventDecoder.ERC20_APPROVAL
EventDecoder.ERC721_TRANSFER

// Custom
var Swap = EventDecoder.of(
    "Swap(address indexed sender, uint256 amount0In, uint256 amount1In, " +
    "uint256 amount0Out, uint256 amount1Out, address indexed to)");

for (EthModels.Log log : receipt.logs) {
    if (Swap.matches(log)) {
        Map<String, Object> data = Swap.decode(log);
        BigInteger amountIn = (BigInteger) data.get("amount0In");
    }
}

List<Map<String, Object>> transfers = EventDecoder.ERC20_TRANSFER.decodeAll(receipt.logs);
```

---

## Multicall3

Batch N contract reads into one `eth_call`. Deployed at the same address on 50+ chains.

```java
var mc        = new Multicall3(client);
var balanceOf = Function.of("balanceOf", AbiType.ADDRESS).withReturns(AbiType.UINT256);
var symbol    = Function.of("symbol").withReturns(AbiType.STRING);

int i0 = mc.add("0xUSDC", balanceOf, "0xUser");
int i1 = mc.add("0xDAI",  balanceOf, "0xUser");
int i2 = mc.add("0xUSDC", symbol);

List<Object> results = mc.execute().join(); // 1 HTTP request
BigInteger usdcBal = (BigInteger) results.get(i0);
String     sym     = (String)     results.get(i2);

// Convenience methods
List<BigInteger> ethBals   = Multicall3.getEthBalances(client, addresses).join();
List<BigInteger> tokenBals = Multicall3.getTokenBalances(client, "0xUSDC", addresses).join();

// Optional calls — failures return null, don't revert the whole batch
mc.addOptional("0xMaybeContract", balanceOf, "0xUser");
List<Multicall3.TryResult> results = mc.tryExecute().join();
results.forEach(r -> System.out.println(r.success() ? r.value() : "failed"));
```

---

## ENS

Forward/reverse lookups plus CCIP-Read (EIP-3668) for off-chain resolvers.

```java
var ens = new EnsResolver(client);

String addr    = ens.resolve("vitalik.eth").join();          // 0xd8dA6BF2...
String name    = ens.reverseLookup("0xd8dA6BF2...").join();  // "vitalik.eth"
String avatar  = ens.getAvatar("vitalik.eth").join();
String twitter = ens.getText("vitalik.eth", "com.twitter").join();
byte[] content = ens.getContenthash("vitalik.eth").join();
```

---

## Price Oracle

Reads prices directly from on-chain contracts — no API keys, no off-chain services.

```java
var oracle = new PriceOracle(client);
```

### Chainlink

```java
BigDecimal ethUsd  = oracle.chainlink(PriceOracle.ETH_USD).join();  // e.g. 3412.58
BigDecimal btcUsd  = oracle.chainlink(PriceOracle.BTC_USD).join();

// Batch multiple feeds in a single Multicall3 call
Map<String, BigDecimal> prices = oracle.batchChainlink(List.of(
    PriceOracle.ETH_USD, PriceOracle.BTC_USD, PriceOracle.LINK_USD
)).join();

// Full round data
PriceOracle.ChainlinkRound round = oracle.roundData(PriceOracle.ETH_USD).join();
round.price();        // 3412.58
round.ageSeconds();   // 238
round.isStale(3600);  // false

// Staleness check — always do this before acting on a price
if (oracle.isStale(PriceOracle.ETH_USD, 3600).join()) {
    throw new RuntimeException("Price feed stale");
}
```

**Built-in Chainlink feeds (Ethereum mainnet):** `ETH_USD`, `BTC_USD`, `USDC_USD`, `USDT_USD`, `DAI_USD`, `LINK_USD`, `WBTC_USD`, `SOL_USD`, `MATIC_USD`

For other feeds, pass the aggregator address directly: `oracle.chainlink("0xFeedAddress")`.

### Uniswap V3

```java
// Spot price from pool slot0 — can be manipulated within a block, use for display only
BigDecimal spot = oracle.spot(PriceOracle.WETH, PriceOracle.USDC, 3000).join();

// TWAP — time-weighted average, use for any financial decision
BigDecimal twap = oracle.twap(PriceOracle.WETH, PriceOracle.USDC, 3000, 1800).join(); // 30-min

String pool = oracle.getPool(PriceOracle.WETH, PriceOracle.USDC, 3000).join();
// Fee tiers: 100 (0.01%), 500 (0.05%), 3000 (0.3%), 10000 (1%)
```

---

## Block Scanner

Scan historical events across arbitrary block ranges with chunking, progress callbacks, resume cursors, and early exit.

```java
var scanner  = BlockScanner.of(client);              // default 2000-block chunks
var scanner  = BlockScanner.of(client, 500);         // smaller chunks (slower nodes)
var scanner  = BlockScanner.of(client, 2000, 4);     // 4 concurrent chunks

var Transfer = EventDef.of("Transfer",
    EventDef.indexed("from",  "address"),
    EventDef.indexed("to",    "address"),
    EventDef.data(   "value", "uint256"));
```

### Scan with progress

```java
scanner.scan("0xUSDC", Transfer, 6_082_465L, 19_000_000L, (events, progress) -> {
    events.forEach(e -> process(e.address("from"), e.address("to"), e.uint("value")));
    System.out.println(progress); // ScanProgress{47.3%, chunk=8525000..8526999, events=284312}
    return true; // return false to stop early
}).join();
```

### Resume from a saved cursor

```java
long resumeBlock = db.load("usdc_scan_cursor");
var cursor = BlockScanner.ScanCursor.at(resumeBlock);

scanner.scan("0xUSDC", Transfer, cursor, latestBlock, (events, progress) -> {
    process(events);
    db.save("usdc_scan_cursor", progress.cursor().nextBlock());
    return true;
}).join();
```

### Collect / count

```java
List<EventDef.DecodedEvent> all  = scanner.collect("0xUSDC", Transfer, from, to).join();
long                        n    = scanner.count("0xUSDC", Transfer, from, to).join();
```

### Scan result

```java
BlockScanner.ScanResult result = scanner.scan(...).join();
result.isComplete();    // true if fully scanned
result.isEarlyExit();   // true if handler returned false
result.totalEvents();   // total events found
result.cursor();        // ScanCursor to resume from
```

### Multiple contracts and events

```java
scanner.scanMulti(
    List.of("0xUSDC", "0xDAI"),
    List.of(Transfer, Approval),
    fromBlock, toBlock,
    (events, progress) -> { ... }
).join();
```

---

## Gas Estimation

EIP-1559 fee suggestions with low / medium / high tiers.

```java
var gas  = GasEstimator.of(client);
var fees = gas.suggest().join();

fees.low().maxFeeGwei()         // "22.5"
fees.medium().maxFeeGwei()      // "28.0"
fees.high().maxFeeGwei()        // "35.0"

// Use directly in a transaction
tx.maxFeePerGas(fees.medium().maxFeePerGas())
  .maxPriorityFeePerGas(fees.medium().maxPriorityFeePerGas());
```

---

## ABI Encoding

Full Solidity type support: all primitives, fixed/dynamic arrays, nested arrays, tuples.

```java
// Type system
AbiType.UINT256  AbiType.UINT8   AbiType.INT256   AbiType.ADDRESS
AbiType.BOOL     AbiType.BYTES32 AbiType.BYTES    AbiType.STRING

AbiType.arrayOf(AbiType.UINT256)            // uint256[]
AbiType.arrayOf(AbiType.ADDRESS, 5)         // address[5]
AbiType.tuple(AbiType.ADDRESS, AbiType.UINT256)   // (address,uint256)
AbiType.of("(address,uint256)[]")           // parsed from string
AbiType.of("uint256[][]")                   // nested

// Encode / decode
byte[]   encoded = AbiCodec.encode(types, values);
Object[] decoded = AbiCodec.decode(types, encodedBytes);

// Function calldata
Function fn  = Function.of("transfer", AbiType.ADDRESS, AbiType.UINT256)
                       .withReturns(AbiType.BOOL);
String   cd  = fn.encode("0xTo", amount);      // "0xa9059cbb..."
String   sel = fn.getSelectorHex();             // "0xa9059cbb"
Object[] ret = fn.decodeReturn(returnHexBytes);
```

---

## Revert Decoding

`AbiDecodeError` handles `Error(string)` and `Panic(uint256)`. `RevertDecoder` adds a protocol registry that maps 4-byte custom error selectors to human-readable names.

```java
// In a catch block
String reason = AbiDecodeError.decode(revertData);
// "execution reverted: ERC20: transfer amount exceeds balance"
// "execution reverted (Panic 0x11): arithmetic overflow/underflow"

// Protocol-aware — resolves custom error selectors
String reason = RevertDecoder.decode("0x1425ea42");
// "execution reverted: Aave/V3: HEALTH_FACTOR_NOT_BELOW_THRESHOLD"

String reason = RevertDecoder.decode("0xfb8f41b2");
// "execution reverted: OZ/ERC-20: ERC20InsufficientBalance"

// Look up a selector
Optional<String> name = RevertDecoder.lookup("0xfb8f41b2");

// Register your own errors
RevertDecoder.registerError("InsufficientCollateral(address,uint256,uint256)", "MyLending");

// Compute a selector
String sel = RevertDecoder.selector("Transfer(address,address,uint256)"); // "0xddf252ad"
```

**Built-in registry:** OpenZeppelin 5.x (ERC-20, ERC-721, ERC-1155, AccessControl, Ownable, Pausable, ReentrancyGuard), Uniswap V3 (SwapRouter, UniversalRouter, Pool), Aave V3 (all ~90 pool error codes), and common generic patterns.

---

## Transaction Simulation

Dry-run transactions before submitting. Uses `eth_simulateV1` on nodes that support it, falls back to `eth_call` state overrides.

```java
var sim     = SimulateBundle.of(client);
var results = sim.simulate(List.of(
    SimulateBundle.tx("0xSender", "0xUSDC", approveCalldata),
    SimulateBundle.tx("0xSender", "0xRouter", swapCalldata)
)).join();

results.forEach(r -> {
    if (r.success()) System.out.println("✓ gas: " + r.gasUsed());
    else             System.out.println("✗ " + r.revertReason());
});
```

> Requires a node with `debug` / `eth_simulateV1` support (Infura, Alchemy, QuickNode).

---

## Transaction Traces

Decode `debug_traceTransaction` into a human-readable call tree.

```java
var tracer = TxTracer.of(client);

TxTracer.CallTrace trace = tracer.trace("0xTxHash").join();
System.out.println(trace.render());
// CALL  0xRouter → 0xPool     [exactInputSingle]  0.1 ETH  ✓
//   CALL  0xPool  → 0xUSDC   [transfer]           0 ETH    ✓

trace.success();      // true/false
trace.revertReason(); // "execution reverted: ..." if false
trace.gasUsed();
trace.calls();        // List<CallTrace> — the call tree
```

> Requires an archive node with `debug_traceTransaction` support.

---

## Storage Layout

Read EVM storage slots directly, following Solidity layout rules.

```java
var storage = StorageLayout.of(client, "0xUSDC");

BigInteger value    = storage.readSlot(0).join();
BigInteger balance  = storage.readMapping(9, "0xAddress").join();         // balances[address]
BigInteger allowed  = storage.readNestedMapping(10, "0xOwner", "0xSpender").join();
BigInteger element  = storage.readArrayElement(5, 3).join();              // array[3] at slot 5
```

---

## EIP-712 Typed Signing

```java
var domain = TypedData.Domain.builder()
    .name("MyApp").version("1").chainId(1L)
    .verifyingContract("0xContractAddress")
    .build();

var types = Map.of("Order", List.of(
    new TypedData.Field("from",     "address"),
    new TypedData.Field("to",       "address"),
    new TypedData.Field("amount",   "uint256"),
    new TypedData.Field("deadline", "uint256")
));

var message = Map.of(
    "from",     wallet.getAddress(),
    "to",       "0xRecipient",
    "amount",   BigInteger.valueOf(1_000_000L),
    "deadline", BigInteger.valueOf(9_999_999_999L)
);

Signature sig    = TypedData.sign(domain, "Order", types, message, wallet);
String    sigHex = TypedData.signHex(domain, "Order", types, message, wallet);
```

### ERC-2612 Permit (gasless ERC-20 approval)

```java
var usdc = new ERC20("0xUSDC", client);
// Signs the EIP-712 Permit message and calls permit() — one tx instead of two
String txHash = usdc.permit(wallet, "0xSpender", amount, deadline).join();
```

---

## EIP-4844 Blobs

Type 0x03 blob-carrying transactions (Dencun / proto-danksharding), valid on Sepolia, Holesky, and Mainnet.

jeth computes **real KZG commitments and proofs** using the Ethereum trusted setup from the [KZG ceremony](https://ceremony.ethereum.org), implemented in pure Java (no native dependencies). The setup is bundled in the jar and loaded automatically.

### Simple blob submission

```java
// Blob.from() computes real KZG commitment + proof — valid on Mainnet
Blob blob = Blob.from("my rollup batch data".getBytes());

String rawTx = BlobTransaction.builder()
    .to("0xRecipient")
    .blob(blob)                          // pass the Blob object (not raw bytes)
    .maxFeePerGas(Units.gweiToWei(30))
    .maxPriorityFeePerGas(Units.gweiToWei(2))
    .maxFeePerBlobGas(Units.gweiToWei(1))
    .nonce(nonce).chainId(1L)
    .sign(wallet);

String txHash = client.sendRawTransaction(rawTx).join();

// Check current blob gas price before sending
BigInteger blobBaseFee = client.getBlobBaseFee().join();
```

### Large payloads (auto-split across multiple blobs)

```java
// BlobEncoder safely encodes arbitrary binary data into field-element-safe blobs
byte[] rollupBatch = /* your calldata */;
Blob[] blobs = BlobEncoder.encode(rollupBatch);  // up to 6 blobs = ~768 KB

BlobTransaction.Builder tx = BlobTransaction.builder()
    .to("0xRecipient")
    .maxFeePerGas(Units.gweiToWei(30))
    .maxPriorityFeePerGas(Units.gweiToWei(2))
    .maxFeePerBlobGas(Units.gweiToWei(1))
    .nonce(nonce).chainId(1L);

for (Blob blob : blobs) tx.blob(blob);
String rawTx = tx.sign(wallet);
```

### Trusted setup

The KZG trusted setup is bundled in the jar. First-time setup (build-time, commit to repo):

```bash
./gradlew bundleKzgSetup   # downloads trusted_setup.txt and compresses it into the jar
```

To supply your own trusted setup file (e.g. from [ethereum/consensus-spec-tests](https://github.com/ethereum/consensus-spec-tests)):

```bash
export JETH_KZG_SETUP=/path/to/trusted_setup.txt
```

### Performance

The pure-Java KZG implementation uses BigInteger arithmetic:
| Operation | Time |
|---|---|
| Setup load (first call) | ~300 ms (then cached) |
| Commitment per blob | ~8–15 s |
| Proof per blob | ~8–15 s |

For high-throughput rollup sequencers, use the native [c-kzg-4844 JNI bindings](https://github.com/ethereum/c-kzg-4844) (~1 ms/blob) and supply commitment + proof to `BlobTransaction.Builder.blob(byte[], byte[], byte[])` directly.

---

## EIP-7702

Type 4 transactions — lets an EOA temporarily delegate execution to a smart contract (Pectra hard fork, April 2025). This is how Account Abstraction works without deploying a smart contract wallet.

```java
// Alice authorizes her EOA to behave like 0xMultisig for this one transaction
Eip7702Signer.Authorization auth = Eip7702Signer.signAuthorization(
    1L,                          // chainId (0 = any chain)
    "0xMultisigImplementation",  // contract to delegate to
    aliceNonce,
    aliceWallet
);

// Bob pays the gas; Alice's EOA executes as the Multisig
String rawTx = Eip7702Signer.sign(
    EthModels.TransactionRequest.builder()
        .from(bobWallet.getAddress())
        .to(aliceWallet.getAddress())
        .gas(BigInteger.valueOf(100_000))
        .maxFeePerGas(Units.gweiToWei(30))
        .maxPriorityFeePerGas(Units.gweiToWei(2))
        .nonce(bobNonce).chainId(1L)
        .data(calldata)
        .build(),
    List.of(auth),
    bobWallet
);

String txHash = client.sendRawTransaction(rawTx).join();
```

---

## ERC-4337 Account Abstraction

Send `UserOperation`s to any ERC-4337 bundler.

```java
var bundler = BundlerClient.of("https://api.pimlico.io/v1/sepolia/rpc?apikey=KEY");

UserOperation op = UserOperation.builder()
    .sender("0xSmartWallet").nonce(BigInteger.ZERO)
    .callData(calldata)
    .callGasLimit(BigInteger.valueOf(100_000))
    .verificationGasLimit(BigInteger.valueOf(150_000))
    .preVerificationGas(BigInteger.valueOf(21_000))
    .maxFeePerGas(Units.gweiToWei(30))
    .maxPriorityFeePerGas(Units.gweiToWei(2))
    .build();

// Estimate gas from bundler
var gas    = bundler.estimateUserOperationGas(op, UserOperation.ENTRY_POINT_V06).join();

// Sign and send
UserOperation signed    = op.sign(ownerWallet, chainId, UserOperation.ENTRY_POINT_V06);
String        userOpHash = bundler.sendUserOperation(signed, UserOperation.ENTRY_POINT_V06).join();

// Wait for inclusion
JsonNode receipt = bundler.waitForUserOperationReceipt(userOpHash).join();
System.out.println(receipt.get("receipt").get("transactionHash").asText());

// Gasless via paymaster
BundlerClient.PaymasterData pm = bundler.sponsorUserOperation(op, entryPoint, policyId).join();
```

---

## Gnosis Safe

Sign and execute Gnosis Safe multisig transactions on-chain, without the Safe API.

```java
var safe = new GnosisSafe("0xSafeAddress", client);

int          threshold = safe.getThreshold().join();   // e.g. 2
List<String> owners    = safe.getOwners().join();
BigInteger   nonce     = safe.getNonce().join();

GnosisSafe.SafeTx tx = GnosisSafe.SafeTx.builder()
    .to("0xRecipient").value(Units.toWei("0.5")).data("0x").nonce(nonce).build();

Signature sig1 = safe.signTransaction(tx, chainId, owner1Wallet);
Signature sig2 = safe.signTransaction(tx, chainId, owner2Wallet);

String txHash = safe.executeTransaction(tx, List.of(sig1, sig2), executorWallet).join();
```

---

## Flashbots

Send private transactions and bundles to block builders, bypassing the public mempool. Use this to protect against frontrunning and sandwich attacks.

```java
// Any relay — the wallet is just for signing auth headers, no ETH needed from it
var fb = FlashbotsClient.mainnet(anyWallet);
var fb = FlashbotsClient.of(FlashbotsClient.MEV_BLOCKER,   wallet);
var fb = FlashbotsClient.of(FlashbotsClient.TITAN_RELAY,   wallet);
var fb = FlashbotsClient.of(FlashbotsClient.BEAVER_RELAY,  wallet);
```

### Private single transaction

```java
// Simplest form — no bundle, just bypasses the mempool
String txHash = fb.sendPrivateTransaction(signedRawTx).join();

// With options
String txHash = fb.sendPrivateTransaction(signedRawTx, targetBlock + 25, true).join();

fb.cancelPrivateTransaction(txHash).join();
```

### Atomic bundles

All transactions in a bundle are included together, or none at all.

```java
var bundle = FlashbotsBundle.of()
    .tx(signedApproveRawTx)                    // must succeed
    .tx(signedSwapRawTx)                       // must succeed
    .allowRevert(signedOptionalTx, txHash)     // allowed to fail
    .build();

// Simulate before sending
FlashbotsClient.FlashbotsSimResult sim = fb.simulate(bundle, targetBlock).join();
System.out.printf("gas: %d  miner payment: %s%n", sim.totalGasUsed(), sim.minerPaymentEth());

if (sim.anyReverted()) {
    sim.txResults().stream()
       .filter(r -> !r.success())
       .forEach(r -> System.out.println("will revert: " + r.revertReason()));
    return;
}

// Send to a range of blocks (increases inclusion probability)
String bundleHash = fb.sendBundle(bundle, targetBlock, targetBlock + 3).join();

// Stats
JsonNode stats = fb.getBundleStats(bundleHash, targetBlock).join();
JsonNode user  = fb.getUserStats().join();
```

---

## Uniswap V3

```java
var uni = new UniswapV3(client);

// Quote
BigInteger out = uni.quoteExactInputSingle(
    PriceOracle.WETH, PriceOracle.USDC,
    3000,
    Units.toWei("1")
).join();
System.out.println("1 ETH → " + Units.formatToken(out, 6) + " USDC");

// Pool state
UniswapV3.PoolState pool = uni.getPoolState(PriceOracle.WETH, PriceOracle.USDC, 3000).join();

// Swap
String txHash = uni.swapExactInputSingle(
    wallet,
    PriceOracle.WETH, PriceOracle.USDC, 3000,
    Units.toWei("0.1"),
    minAmountOut
).join();
```

Fee tier constants: `UniswapV3.FEE_LOWEST` (0.01%), `FEE_LOW` (0.05%), `FEE_MEDIUM` (0.3%), `FEE_HIGH` (1%)

---

## Aave V3

```java
var aave = new AaveV3(client);                          // mainnet default
var aave = new AaveV3(client, AaveV3.POOL_ARBITRUM);    // other chains

// Account health
AaveV3.AccountData data = aave.getUserAccountData("0xAddress").join();
System.out.println("Health factor: " + data.healthFactorEther());    // e.g. "2.34"
System.out.println("Available to borrow: " + data.availableBorrowsBaseEth());

// Protocol interactions
aave.supply  (wallet, "0xUSDC", Units.parseToken("1000", 6)).join();
aave.borrow  (wallet, "0xWETH", Units.toWei("0.1"), 2).join();  // 2 = variable rate
aave.repay   (wallet, "0xWETH", Units.toWei("0.1"), 2).join();
aave.withdraw(wallet, "0xUSDC", Units.parseToken("500", 6)).join();
```

Pool constants: `POOL_MAINNET`, `POOL_ARBITRUM`, `POOL_OPTIMISM`, `POOL_BASE`, `POOL_POLYGON`

---

## Provider Middleware

```java
var provider = MiddlewareProvider.wrap(HttpProvider.of(rpcUrl))
    .withRetry(3, Duration.ofMillis(300))   // retry 3× with exponential backoff
    .withRateLimit(100)                      // max 100 req/s
    .withCache(Duration.ofSeconds(12))       // cache chainId, getCode, etc.
    .withLogging()                           // logs method + duration
    .build();

// Automatic failover — tries providers left-to-right, switches on error
var provider = MiddlewareProvider.withFallback(
    HttpProvider.of(infuraUrl),
    HttpProvider.of(alchemyUrl),
    HttpProvider.of(quicknodeUrl)
);

// Inspect what's happened
var m = ((MiddlewareProvider) provider).getMetrics();
System.out.println(m);
// Metrics{requests=1000, cacheHits=412, retries=3, errors=1, avgLatencyMs=48}
```

---

## WebSocket

```java
var ws = WsProvider.builder("wss://mainnet.infura.io/ws/v3/YOUR_KEY")
    .maxReconnectAttempts(10)
    .reconnectDelay(Duration.ofSeconds(2))
    .connect();

var client = EthClient.of(ws);

ws.onNewBlock(block ->
    System.out.println("Block #" + block.get("number").asText()));

ws.onPendingTransaction(hash ->
    System.out.println("Pending: " + hash));

ws.onLogs(
    Map.of("address", "0xUSDC", "topics", List.of(TRANSFER_TOPIC)),
    log -> System.out.println(EventDecoder.ERC20_TRANSFER.decode(log)));

ws.close();
```

---

## Supported Chains

Any EVM chain works with any RPC URL. Built-in `Chain` constants for common networks:

| Constant | Chain | Chain ID |
|---|---|---|
| `MAINNET` | Ethereum | 1 |
| `SEPOLIA` | Ethereum Sepolia | 11155111 |
| `ARBITRUM` | Arbitrum One | 42161 |
| `ARBITRUM_SEPOLIA` | Arbitrum Sepolia | 421614 |
| `OPTIMISM` | OP Mainnet | 10 |
| `OP_SEPOLIA` | OP Sepolia | 11155420 |
| `BASE` | Base | 8453 |
| `BASE_SEPOLIA` | Base Sepolia | 84532 |
| `POLYGON` | Polygon PoS | 137 |
| `POLYGON_AMOY` | Polygon Amoy | 80002 |
| `POLYGON_ZKEVM` | Polygon zkEVM | 1101 |
| `BNB` | BNB Smart Chain | 56 |
| `AVALANCHE` | Avalanche C-Chain | 43114 |
| `ZKSYNC` | zkSync Era | 324 |
| `LINEA` | Linea | 59144 |
| `SCROLL` | Scroll | 534352 |
| `GNOSIS` | Gnosis Chain | 100 |
| `FANTOM` | Fantom Opera | 250 |

```java
var client = EthClient.of(Chain.MAINNET.rpc("YOUR_INFURA_KEY"));
var client = EthClient.of(Chain.BASE.publicRpc());

Chain chain = Chain.fromId(8453);
chain.getName();         // "Base"
chain.getNativeToken();  // "ETH"
chain.getExplorerUrl();  // "https://basescan.org"
```

---

## Units & Formatting

```java
// ETH ↔ Wei
BigInteger wei = Units.toWei("1.5");                    // 1_500_000_000_000_000_000
String     eth = Units.formatEther(wei);                // "1.5"
String     eth = Units.formatEtherTrimmed(wei, 4);      // "1.5000"

// ERC-20 tokens
BigInteger raw = Units.parseToken("100.50", 6);         // 100_500_000
BigDecimal amt = Units.fromWei(raw, 6);                 // 100.50
String     fmt = Units.formatToken(raw, 6, 2);          // "100.50"

// ethers.js-compatible aliases
BigInteger raw = Units.parseUnits("100.50", 6);
String     fmt = Units.formatUnits(raw, 6);

// Gas
BigInteger gwei    = Units.gweiToWei(30);
String     gasCost = Units.formatGasCostEth(21_000, gwei); // "0.00063 ETH"
String     gweiStr = Units.formatGwei(gwei);               // "30 gwei"

// Max uint256 (unlimited approval)
BigInteger unlimited = Units.MAX_UINT256;
```

---

## What jeth does NOT do

| | Status |
|---|---|
| Etherscan / block explorer APIs | Not included. Use their REST API directly. |
| IPFS / content fetching | Not included. Use ipfs-http-client. |
| Uniswap V2 / V4 | Not included. Use the low-level `Contract` API. |
| L2 withdrawal proofs | Not included. Use chain-specific SDKs. |
| Hardware wallets (Ledger, Trezor) | Not included. |
| ENS registration / name management | Read-only. |
| Contract verification | Not included. Use Foundry / Hardhat. |
| Solidity compilation | Not included. |
| Production KZG proofs for blobs | Placeholder commitments only. Integrate [c-kzg-4844-java](https://github.com/ethereum/c-kzg-4844) for production. |
| Android | Not tested, not supported. |
| ERC-4337 smart wallet factory deployment | Bring your own factory calldata. The `UserOperation` API handles the operation itself. |
| Flashbots MEV strategy | Relay client only. Strategy logic is up to you. |

---

## Architecture

```
io.jeth
├── core/           EthClient (50+ RPC methods), Chain (18 networks)
├── provider/       Provider interface, HttpProvider, BatchProvider
├── ws/             WsProvider — WebSocket, eth_subscribe, auto-reconnect
├── middleware/     MiddlewareProvider — retry, rate-limit, cache, fallback, metrics
├── model/          EthModels, RpcModels, hex deserializers
│
├── abi/            AbiCodec (all Solidity types), AbiType, Function
│                   HumanAbi — parse human-readable signatures
│                   AbiDecodeError — Error(string) + Panic(uint256)
│                   RevertDecoder — custom error registry
│
├── contract/       Contract, ContractFunction (low-level)
│                   ContractProxy — runtime typed proxy
│                   ContractEvents — live subscriptions
│                   ERC20 (with ERC-2612 Permit)
│
├── token/          ERC721, ERC1155
├── events/         EventDecoder, EventDef
├── multicall/      Multicall3
│
├── price/          PriceOracle — Chainlink feeds + Uniswap V3 spot/TWAP
├── scan/           BlockScanner — typed event scanning with progress + resume
├── flashbots/      FlashbotsClient, FlashbotsBundle
│
├── gas/            GasEstimator — EIP-1559 Low/Medium/High
├── simulate/       SimulateBundle — eth_simulateV1 / state overrides
├── trace/          TxTracer — debug_traceTransaction → call tree
├── storage/        StorageLayout — raw slot, mapping, array reads
│
├── eip712/         TypedData — EIP-712 signing, ERC-2612 Permit
├── crypto/         Wallet, TransactionSigner (EIP-1559 / legacy), Eip7702Signer, Rlp
├── wallet/         HdWallet (BIP-39 / BIP-44), Keystore V3
├── ens/            EnsResolver — forward, reverse, text, contenthash, CCIP-Read
│
├── eip4844/        BlobTransaction (type 0x03), Blob
├── aa/             UserOperation (ERC-4337), BundlerClient
├── safe/           GnosisSafe (v1.3.0+)
├── defi/           UniswapV3, AaveV3
│
├── codegen/        ContractGenerator (optional CLI codegen from ABI JSON)
└── util/           Hex, Keccak, Address (EIP-55 checksum), Units
```

---

## Test vectors

Cryptographic primitives are verified against external specifications:

| Algorithm | Source |
|---|---|
| Keccak-256 | NIST / ethereum/tests |
| RLP encoding | Ethereum Yellow Paper |
| secp256k1 address derivation | Hardhat accounts #0–#9 |
| EIP-55 checksum | EIP-55 spec |
| ENS namehash | EIP-137 spec |
| ABI encoding / decoding | Solidity documentation |
| EIP-712 domain separator | EIP-712 spec |
| ERC-2612 Permit typehash | ERC-2612 spec |
| BIP-39 mnemonic generation | BIP-39 test vectors |
| BIP-44 key derivation | BIP-44 test vectors |
| EIP-1559 transaction signing | On-chain verification |
| EIP-7702 type 4 signing | Pectra devnet |

---

## Build

> **First-time setup:** the Gradle wrapper jar is not bundled in the repository.
> `./gradlew` will download it automatically on first run (requires curl or wget).
> Alternatively run `./scripts/setup-gradle.sh` explicitly.

```bash
./gradlew build                     # compile + unit tests
./gradlew test                      # unit tests only
./gradlew integrationTest           # E2E tests (requires Docker)
./gradlew fetchBip39Wordlist        # one-time: download BIP-39 word list

# Code style
./gradlew spotlessCheck             # check for violations (what CI runs)
./gradlew spotlessApply             # fix violations in-place

# API docs
./gradlew dokkaHtml                 # generate HTML to build/dokka/html/
open build/dokka/html/index.html    # preview locally

# Optional: generate a typed Java wrapper from an ABI JSON file
./gradlew generateContract -Pabi=MyToken.json -Pname=MyToken -Ppackage=com.example
```

## API Reference

Full API reference at **[https://jeth-io.github.io/jeth/](https://jeth-io.github.io/jeth/)** — generated by Dokka from the source Javadoc, updated automatically on every push to `main`.

## Release

```bash
git tag v1.3.0
git push origin v1.3.0
# CI builds, runs all tests, signs artifacts, and publishes to Maven Central + GitHub Packages.
# GitHub Release is created automatically with release notes.
```

---

## License

[MIT](LICENSE)
