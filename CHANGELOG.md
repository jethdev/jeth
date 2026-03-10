# Changelog

All notable changes to jeth are documented here.
Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) and [Semantic Versioning](https://semver.org/).

---

## [1.3.0] — 2026-03-01

### Added
- **Real KZG commitments** — `Blob.from()` computes valid EIP-4844 KZG commitment and proof using
  the Ethereum trusted setup; valid on Sepolia, Holesky, and Mainnet (no native deps)
- **`BlobEncoder`** — field-element-safe encoding for arbitrary binary data into blobs (31 bytes/chunk)
- **`Bls12381`** — pure-Java BLS12-381 G1 field arithmetic, point compression/decompression
- **`Kzg`** — `blobToCommitment`, `computeBlobKzgProof`, barycentric evaluation, FFT over Fr
- **`KzgTrustedSetup`** — loads Ethereum KZG ceremony setup; bundled in jar, overridable via `JETH_KZG_SETUP`
- **`BundleKzgSetup`** — build-time CLI to download and compress trusted setup into jar resource
- **`CLAUDE.md`** — project instructions for Claude Code CLI / VS Code extension
- **`.claude/settings.json`** — pre-approved tool permissions for Claude Code

### Changed
- `BlobTransaction.Builder.blob(Blob)` — new recommended API; takes a `Blob` object with real KZG
- `BlobTransaction.Builder.blobRaw(byte[])` — deprecated; placeholder KZG, devnet-only
- `Blob.from(byte[])` — now computes real KZG (was placeholder in 1.2.0)
- Gradle wrapper (`gradlew`) auto-downloads `gradle-wrapper.jar` if missing

### Fixed
- `gradle-wrapper.jar` was excluded from `.gitignore` — removed the exclusion
- Fiat-Shamir challenge: degree field is now 32-byte little-endian (was 8-byte big-endian)
- `ROOT_OF_UNITY_2_32` corrected to `pow(7, (r-1)/2^32, r)` — previous value failed the primitivity check

---

## [1.2.0] — 2026-03-01

### Added
- **EIP-7702 (Pectra)** — `Eip7702Signer`: sign type-4 "set code for EOA" transactions and authorization tuples
- **EIP-4844 blobs** — `BlobTransaction` and `Blob` for type-3 blob-carrying transactions (EIP-4844 / Dencun)
- **Account Abstraction** — `BundlerClient` and `UserOperation` for ERC-4337 bundler interaction (Pimlico, Stackup, Alchemy)
- **Storage layout** — `StorageLayout`: compute Solidity storage slots for mappings and arrays without an archive node
- **GnosisSafe multisig** — `GnosisSafe`: build, sign, and execute Safe transactions with sorted-signature packing
- **Uniswap V3** — `UniswapV3`: typed `swapExactInputSingle` via the SwapRouter
- **Aave V3** — `AaveV3`: supply, borrow, repay, and `getUserAccountData` with health factor helpers
- **ENS extended** — `EnsResolver`: `getText`, `getAvatar`, `getContenthash`, reverse lookup
- **EthClient advanced RPC** — `getStorageAt(address, slot, block)`, `getBlobBaseFee`, `traceTransaction`, `traceCall`, `createAccessList`, `callWithOverride` with state overrides
- **Multicall3 results** — `executeWithResults()` returning `Result<T>` with `success` flag; `getEthBalances`, `getTokenBalances`
- **Middleware** — `MiddlewareProvider.withRetry`, `withLogging`, `withCache`, `avgLatencyMs`
- **ERC-2612 permit** — `ERC20.permit()`: full gasless approval flow with EIP-712 signing
- **ERC-721** — `ERC721`: `ownerOf`, `tokenURI`, `getApproved`, `setApprovalForAll`, `transferFrom`
- **Price oracle** — `PriceOracle`: Chainlink `roundData`, Uniswap V3 TWAP `spot`/`twap` with decimal scaling
- **Gas estimator** — `GasEstimator`: `estimateGasCost`, `FeeEstimate.priorityFeeGwei()`
- **Flash loans** — `FlashbotsClient`: `sendBundle`, `simulateBundle`, `getBundleStats`
- **ContractGenerator** — `ContractGenerator`: generate typed Java wrappers from ABI JSON
- **ContractProxy** — `ContractProxy`: runtime proxy from ABI JSON + Java interface; `DynamicContract` for untyped access
- **RevertDecoder** — decode 4-byte custom error selectors from OpenZeppelin, Uniswap V3, Aave V3, ERC-20/721/1155
- **HD wallets** — `HdWallet`: BIP-39 mnemonic generation/restoration, BIP-44 derivation, `Keystore` import/export
- **Event subscriptions** — `ContractEvents.on/once/onRaw/onAny` via WebSocket
- **Typed event decoding** — `EventDef` and `EventDecoder` for structured log parsing
- **CCIP-Read (EIP-3668)** — `CcipRead`: transparent off-chain lookup support in ENS resolution
- **Chain enum** — `Chain`: 30+ chains with public RPCs, block explorers, `isL2()`, `isTestnet()`

### Changed
- `AbiType.of()` now parses all `uintN`/`intN`/`bytesN` sizes generically (no hardcoded list)
- `AbiCodec.decodeSignedInt` correctly sign-extends negative values for all `intN` types
- `EthClient.getLogs` auto-chunks large block ranges (> 2000 blocks) to avoid RPC size limits
- `ContractFunction.send` uses 1.2× gas buffer; `Contract.deploy` uses 1.3×
- `Wallet.sign` returns `v` as raw recovery bit (0 or 1); callers add 27 explicitly

### Fixed
- `AbiCodec.encodeUint256` no longer corrupts values > 2^255 (stripped sign byte correctly)
- `Rlp.encode(BigInteger.ZERO)` encodes as `0x80` (empty string), not `0x00`
- `TransactionSigner.recoverSigner` handles EIP-155 `v` values correctly for all chain IDs
- `TypedData.sign` normalises `v` to 27/28 consistently
- `EnsResolver.namehashHex` matches spec for empty label (`eth`, `vitalik.eth` test vectors pass)
- `StorageLayout.readNestedMapping` uses `keccak256(key2 ++ keccak256(key1 ++ slot))` correctly
- `GnosisSafe.packSorted` sorts signatures by signer address (ascending) as the Safe contract requires
- `EthClient.waitForTransaction` uses non-blocking `delayedExecutor` (no thread pinning)
- `BundlerClient.waitForUserOperationReceipt` uses same non-blocking polling pattern

### Security
- `Wallet` private key is stored as `BigInteger` (not String) to reduce heap string interning risk
- `Keystore` uses scrypt KDF with N=131072, r=8, p=1 by default (matches MetaMask)

---

## [1.1.0] — 2026-02-25

### Added
- Initial public release
- Core JSON-RPC client (`EthClient`) with full `eth_*` / `net_*` / `debug_*` coverage
- EIP-1559 and legacy transaction signing (`TransactionSigner`)
- ABI encoder/decoder for all Solidity types (`AbiCodec`, `AbiType`, `Function`)
- Human-readable ABI parsing (`HumanAbi`)
- `Contract` / `ContractFunction` / `ContractProxy` wrappers
- ERC-20 full read/write (`ERC20`)
- Multicall3 batching (`Multicall3`)
- WebSocket provider (`WsProvider`) with auto-reconnect and heartbeat
- HTTP provider (`HttpProvider`) backed by OkHttp
- `Wallet`: secp256k1 key generation, RFC 6979 deterministic signing, EIP-55 addresses
- Utility classes: `Hex`, `Keccak`, `Address`, `Units`
- `Chain` enum with Ethereum, Arbitrum, Optimism, Base, Polygon, and more

---

## Versioning policy

- **Patch** (1.x.y → 1.x.y+1): bug fixes, no API changes
- **Minor** (1.x → 1.x+1): new features, backwards-compatible API additions
- **Major** (1.x → 2.x): breaking API changes (announced 30 days in advance)
