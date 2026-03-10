# jeth — Claude Instructions

Java Ethereum library. Pure async (`CompletableFuture`), two runtime deps (Jackson, BouncyCastle).

---

## Build commands

```bash
./gradlew build              # compile + all unit tests
./gradlew test               # unit tests only
./gradlew integrationTest    # E2E tests — requires Docker
./gradlew spotlessApply      # auto-fix code style (run before committing)
./gradlew spotlessCheck      # same check CI runs
./gradlew dokkaHtml          # generate API docs → build/dokka/html/

# One-time setup (commit the outputs):
./gradlew fetchBip39Wordlist # BIP-39 word list for HdWallet
./gradlew bundleKzgSetup     # KZG trusted setup for EIP-4844 blobs
```

If `./gradlew` fails with `ClassNotFoundException: GradleWrapperMain`, the wrapper jar is missing.
Fix: `./scripts/setup-gradle.sh` (downloads it), or it auto-downloads on next `./gradlew` run.

---

## Project layout

```
src/main/java/io/jeth/
├── aa/          BundlerClient, UserOperation              ERC-4337
├── abi/         AbiCodec, AbiType, Function, HumanAbi, RevertDecoder
├── codegen/     ContractGenerator, ContractGeneratorCli, AbiJson
├── contract/    Contract, ContractProxy, ContractEvents, ContractFunction, DynamicContract, ERC20
├── core/        EthClient, Chain, EthException            ← central RPC client
├── crypto/      Wallet, TransactionSigner, Signature, Rlp, Eip7702Signer
├── defi/        UniswapV3, AaveV3
├── eip4844/     BlobTransaction, Blob, Kzg, Bls12381, KzgTrustedSetup, BlobEncoder
├── eip712/      TypedData
├── ens/         EnsResolver, CcipRead
├── event/       EventDef, EventDecoder
├── events/      ContractEvents (WebSocket subscriptions)
├── flashbots/   FlashbotsClient
├── gas/         GasEstimator
├── middleware/  MiddlewareProvider
├── model/       EthModels, RpcModels
├── multicall/   Multicall3
├── price/       PriceOracle                               Chainlink + Uniswap V3 TWAP
├── provider/    HttpProvider, BatchProvider
├── safe/        GnosisSafe
├── scan/        BlockScanner
├── simulate/    SimulationClient
├── storage/     StorageLayout
├── subscribe/   (WebSocket event subscriptions)
├── token/       ERC721, ERC1155
├── trace/       TraceClient
├── util/        Hex, Keccak, Address, Units
├── wallet/      HdWallet, Keystore, MnemonicException
└── ws/          WsProvider

src/test/java/io/jeth/   — mirrors main layout, one *Test.java per *Source.java
src/main/resources/io/jeth/
├── wallet/bip39-english.txt   (downloaded via fetchBip39Wordlist)
└── eip4844/kzg-setup.bin      (downloaded via bundleKzgSetup)
```

---

## Code conventions

**Java version:** 17. No preview features. No records-only constructs that break 17.

**Async:** All public methods that do I/O return `CompletableFuture<T>`. Chain with `.thenApply` / `.thenCompose`. Never block with `.get()` in library code.

**Error handling:** Throw `EthException` (unchecked) for Ethereum-layer errors. Throw `IllegalArgumentException` for bad inputs. Never swallow exceptions.

**Style — enforced by Spotless (Google Java Format, AOSP 4-space):**
- `spotlessApply` auto-fixes everything; run it before pushing
- 4-space indent, LF line endings, final newline, no trailing whitespace
- Import ordering handled by GJF automatically — don't sort by hand

**Style — not enforced, but match the codebase:**
- `var` where the right-hand side makes the type obvious
- Opening brace on the same line, always
- Static factories (`of`, `from`, `create`) over public constructors where readable
- `CompletableFuture` chains, not nested callbacks
- Max line length is soft ~160 chars (`.editorconfig`); split when it aids readability

**License header** — every `.java` file must start with:
```java
/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
```
Spotless checks this. Missing headers fail CI.

**Javadoc:** Required on all public API classes and methods. Minimum: one-line summary sentence. Any class a user instantiates directly needs a `<pre>` usage example.

---

## Dependencies

**Runtime (two only — this is a hard rule):**
- `com.fasterxml.jackson.core:jackson-databind:2.17.2`
- `org.bouncycastle:bcprov-jdk18on:1.78.1`

Do not add runtime dependencies without opening a discussion issue first. "It's small" is not a sufficient argument.

**Test only:**
- JUnit 5 (`junit-jupiter`)
- OkHttp + MockWebServer (for HTTP transport tests)
- Testcontainers (for integration tests with a real node)

---

## Testing

**Unit test conventions:**
- One `*Test.java` per source file, same package, placed in `src/test/java/`
- Test class is package-private (`class FooTest`, not `public class FooTest`)
- Use JUnit 5 (`@Test`, `assertThrows`, `assertEquals`, etc.) — no JUnit 4
- Mock HTTP via `MockWebServer` from OkHttp — see `CoreTest.java` for the pattern
- Test method names: plain English describing the scenario (`void transferEmitsCorrectTopics()`)
- Use known-good constants / vectors rather than live RPC calls in unit tests

**What needs tests:**
- Every non-trivial public method
- Every edge case that caused a bug (regression tests)
- New features need tests before the PR is mergeable

**Integration tests** (`src/test/java/.../*IntegrationTest.java`) require Docker and a local Anvil/Hardhat node. They are excluded from the normal `./gradlew test` run; use `./gradlew integrationTest` explicitly.

---

## EIP-4844 / KZG specifics

Blobs require the Ethereum KZG trusted setup (`kzg-setup.bin`). It must be built once:

```bash
./gradlew bundleKzgSetup   # downloads trusted_setup.txt, compresses → src/main/resources/
```

Commit the resulting `src/main/resources/io/jeth/eip4844/kzg-setup.bin`.

At runtime, override with: `export JETH_KZG_SETUP=/path/to/trusted_setup.txt`

**Key KZG constants (do not change without re-verifying):**
- `ROOT_OF_UNITY_2_32 = pow(7, (r-1)/2^32, r) = 0x16a2a19e...`
- `omega_4096` derived by squaring it 20 times
- Fiat-Shamir: `SHA256(domain_16 ‖ N_as_32LE ‖ blob_131072 ‖ commitment_48)` then `mod r`
- Commitment: `MSM(g1Lagrange, poly)` — evaluation-form polynomial × Lagrange setup points
- Proof: `MSM(g1Lagrange, quotient_eval_form)` — same basis

The pure-Java KZG is ~8–15s per blob. For high-throughput use, supply precomputed values via
`Blob.of(data, commitment, proof)` from native c-kzg-4844 JNI bindings.

---

## Adding new packages

1. Create `src/main/java/io/jeth/<package>/` with source files
2. Create `src/test/java/io/jeth/<package>/` with `*Test.java` files
3. Add the package to the structure table in this file and in `CONTRIBUTING.md`
4. All public API needs Javadoc with at least one `<pre>` usage example
5. Run `./gradlew spotlessApply && ./gradlew test` before opening a PR

---

## CI pipeline

1. **Style** (`spotlessCheck`) — runs first, fast. Fix with `./gradlew spotlessApply`.
2. **Unit tests** — Java 17 and 21 matrix.
3. **Integration tests** — Docker required, runs on merge to `main` only.
4. **Publish** — triggered on `v*` tags. Set `RELEASE_VERSION` env var or tag with `v1.2.0`.

All three must be green before merging.
