# Contributing to jeth

Thanks for your interest. Contributions are welcome — bug fixes, new features, tests, and documentation improvements.

---

## Ground rules

- **No new runtime dependencies.** jeth has two: Jackson and BouncyCastle. Any addition needs a very strong argument and prior discussion in an issue.
- **Keep it Java 17.** No preview features, no records-only constructs that break 17.
- **All async returns `CompletableFuture`.** Don't introduce reactive streams or callbacks.
- **Tests are not optional.** New code needs tests. Bug fixes need a test that fails before the fix and passes after.
- **One concern per PR.** A PR that adds a feature AND refactors three unrelated things will be asked to split.

---

## Getting started

```bash
git clone https://github.com/jeth-io/jeth
cd jeth
./gradlew fetchBip39Wordlist   # one-time: downloads the BIP-39 word list
./gradlew build                # compiles + runs all unit tests
```

Requirements: **Java 17+**, **Docker** (for integration tests only).

## Code style

Code style is enforced by [Spotless](https://github.com/diffplug/spotless) using
[Google Java Format](https://github.com/google/google-java-format) at 4-space (AOSP) indent width.
All source files must carry the MIT license header.

```bash
# Check for violations (same command CI runs)
./gradlew spotlessCheck

# Fix violations in-place
./gradlew spotlessApply
```

Run `spotlessApply` before pushing. The CI style job runs before tests and will block the PR
if violations are found.

**IDE setup:** Install the _google-java-format_ plugin for IntelliJ or VS Code, then configure
it to use AOSP style (4-space indent). The `.editorconfig` at the repo root configures indent
size and line endings automatically in any EditorConfig-aware editor.

**What Spotless checks:**
- Import ordering (Google Java Format handles this automatically)
- Trailing whitespace
- Missing final newline
- License header on every `.java` file
- 4-space indent (via Google Java Format AOSP mode)

**What Spotless does NOT enforce:** line length. Lines above 120 characters are allowed when
splitting them would reduce readability (e.g. one-liner method declarations in `EthClient`).

Beyond what Spotless checks, match what's already in the codebase:

- `var` where the type is obvious from the right-hand side
- Opening brace on same line, always
- `CompletableFuture<T>` for all async returns — chain with `.thenApply`, `.thenCompose`
- Static factory methods (`of`, `create`, `from…`) over public constructors where it reads better
- Javadoc on public API: one-line summary, `@param` for non-obvious args, a `<pre>` example for any class a user would instantiate directly

---

## Project structure

```
src/main/java/io/jeth/
├── core/        EthClient, Chain
├── abi/         AbiCodec, AbiType, Function, HumanAbi, RevertDecoder
├── contract/    Contract, ContractProxy, ContractEvents, ERC20
├── token/       ERC721, ERC1155
├── multicall/   Multicall3
├── price/       PriceOracle          ← Chainlink + Uniswap V3 TWAP
├── scan/        BlockScanner         ← typed historical event scanning
├── flashbots/   FlashbotsClient, FlashbotsBundle
├── gas/         GasEstimator
├── simulate/    SimulateBundle
├── trace/       TxTracer
├── storage/     StorageLayout
├── eip712/      TypedData
├── eip4844/     BlobTransaction
├── crypto/      Wallet, TransactionSigner, Eip7702Signer, Rlp
├── wallet/      HdWallet, Keystore
├── ens/         EnsResolver
├── aa/          UserOperation, BundlerClient
├── safe/        GnosisSafe
├── defi/        UniswapV3, AaveV3
├── middleware/  MiddlewareProvider
├── ws/          WsProvider
└── util/        Hex, Keccak, Address, Units

src/test/java/io/jeth/
└── *Test.java   mirrors the same structure; RpcMock.java is the mock server
```

---

## Running tests

```bash
# Unit tests (no network, no Docker — fast)
./gradlew test

# Integration tests (requires Docker — spins up a local Besu node)
./gradlew integrationTest

# Single test class
./gradlew test --tests "io.jeth.PriceOracleTest"

# With output
./gradlew test --info
```

Integration tests are tagged `@Tag("integration")` and excluded from the default `test` task. They require Docker. CI runs both.

---

## Writing tests

Use `RpcMock` for anything that needs an RPC node:

```java
@Test
void my_new_feature() throws Exception {
    try (var rpc = new RpcMock()) {
        rpc.enqueueStr("0x0000..."); // ABI-encoded return value
        var result = new MyFeature(rpc.client()).doSomething().join();
        assertEquals(expected, result);
    }
}
```

`RpcMock` starts a real HTTP server on a random port. It returns queued responses in order. If the queue runs out it returns an error response — which will make your test fail loudly rather than silently hang.

For things that touch real networks (ENS, Flashbots, Chainlink), write an integration test with `@Tag("integration")`. These only run in CI with a live node.

Test naming convention: `method_does_what_when_condition`. E.g. `chainlink_returns_stale_when_feed_older_than_max_age`.

---

## What to contribute

Good starting points:

- **Bug reports** — open an issue with a minimal reproduction. A failing test is ideal.
- **Missing RPC methods** — `EthClient` wraps most of `eth_*` but not all. Add what you need.
- **More chain constants** — add to `Chain.java` with chainId, name, token symbol, public RPC, and explorer URL.
- **Additional Chainlink feeds** — add mainnet feed addresses to `PriceOracle`.
- **More known error signatures** — extend `RevertDecoder`'s registry with errors from protocols you use.
- **Better Javadoc** — a sentence explaining *why* beats restating *what* the signature already says.

Things that need an issue before a PR:

- New top-level features (new package)
- Changes to public API signatures
- Anything touching crypto primitives (Keccak, Rlp, secp256k1)

Things that won't be accepted:

- New runtime dependencies
- Android-specific code paths
- Breaking changes to public API without a deprecation path
- Features that belong in application code rather than a library (e.g. retry logic for a specific protocol, hardcoded private keys in tests)

---

## Building the docs locally

API docs are generated by [Dokka](https://github.com/Kotlin/dokka) and published to
GitHub Pages automatically on every push to `main`.

```bash
# Generate Dokka HTML
./gradlew dokkaHtml

# Open in browser
open build/dokka/html/index.html          # macOS
xdg-open build/dokka/html/index.html     # Linux
```

The live docs site is at **https://jeth-io.github.io/jeth/**.

Dokka reads your Javadoc comments directly. The more Javadoc you write, the better
the generated docs become. See the existing Javadoc in `EthClient`, `PriceOracle`,
and `BlockScanner` for the style to follow.

## Submitting a PR

1. Fork the repo and create a branch: `git checkout -b feat/my-thing`
2. Make your changes with tests
3. Run `./gradlew test` — must pass cleanly
4. Push and open a PR against `main`
5. Fill in the PR description: what it does, why it's needed, how to test it

PR titles follow conventional commits loosely: `feat: add X`, `fix: Y when Z`, `docs: clarify X`, `test: coverage for Y`.

Small PRs get reviewed faster. If you're unsure whether something is worth doing, open an issue first.

---

## Releasing (maintainers only)

```bash
git tag v1.3.0
git push origin v1.3.0
```

CI will build, run all tests, sign the artifacts, publish to the configured registry, and create a GitHub Release with auto-generated notes.

Version format: `MAJOR.MINOR.PATCH`. No `-SNAPSHOT` suffixes in tags — snapshots are only produced from untagged builds.

---

## License

By contributing you agree your code will be released under the [MIT License](LICENSE).
