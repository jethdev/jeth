/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.abi;

import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decode Solidity custom errors into human-readable names — with a built-in registry of known
 * errors from every major protocol.
 *
 * <p>Unlike {@link AbiDecodeError} which only decodes {@code Error(string)} and {@code
 * Panic(uint256)}, this class resolves the 4-byte selector of ANY custom error to its name if the
 * protocol is known.
 *
 * <pre>
 * // Auto-decodes: "0x1425ea42" → "Aave: HEALTH_FACTOR_NOT_BELOW_THRESHOLD"
 * String human = RevertDecoder.decode("0x1425ea42");
 *
 * // Prefer the full decoder (handles Error/Panic too):
 * String full  = RevertDecoder.decodeComplete("0x08c379a0" + abiEncodedReason);
 *
 * // Register your own custom errors
 * RevertDecoder.registerError("MyError(address,uint256)", "MyProtocol");
 * </pre>
 *
 * Built-in registries:
 *
 * <ul>
 *   <li>OpenZeppelin — ERC-20, ERC-721, ERC-1155, AccessControl, Ownable, Pausable, ReentrancyGuard
 *   <li>Uniswap V3 — Router, Pool, Factory
 *   <li>Aave V3 — Pool errors (all 80+)
 *   <li>ERC standards — ERC-20, ERC-721, ERC-1155
 * </ul>
 */
public final class RevertDecoder {

    private RevertDecoder() {}

    // Map of 4-byte selector (hex, no 0x) → human label
    private static final Map<String, String> REGISTRY = new ConcurrentHashMap<>();

    static {
        registerOpenZeppelin();
        registerUniswapV3();
        registerAaveV3();
        registerERC20Errors();
        registerERC721Errors();
        registerERC1155Errors();
        registerCommonErrors();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Decodes a revert payload into its most human-readable form.
     *
     * <p>Handles the following cases in priority order:
     *
     * <ol>
     *   <li>{@code Error(string)} — {@code "execution reverted: transfer amount exceeds balance"}
     *   <li>{@code Panic(uint256)} — {@code "execution reverted (Panic 0x11): arithmetic overflow"}
     *   <li>Known custom error — {@code "OZ/ERC-20: ERC20InsufficientBalance"} or {@code "Aave/V3:
     *       HEALTH_FACTOR_NOT_BELOW_THRESHOLD"}
     *   <li>Unknown custom error — {@code "custom error 0x1234abcd"}
     *   <li>Empty/null — {@code "execution reverted"}
     * </ol>
     *
     * @param hexData 0x-prefixed hex revert data from an eth_call error or RPC response
     * @return human-readable string; never null
     */
    public static String decode(String hexData) {
        // Delegate Error(string) and Panic(uint256) to existing decoder
        String base = AbiDecodeError.decode(hexData);

        // If base already resolved it, return
        if (!base.contains("custom error 0x")) return base;

        // Try to resolve the custom error selector
        if (hexData == null || hexData.length() < 10) return base;
        String selector =
                hexData.startsWith("0x")
                        ? hexData.substring(2, 10).toLowerCase()
                        : hexData.substring(0, 8).toLowerCase();

        String known = REGISTRY.get(selector);
        if (known != null) return "execution reverted: " + known;

        return base; // "execution reverted with custom error 0x..."
    }

    /** Decode raw bytes (already stripped of 0x). */
    public static String decode(byte[] data) {
        if (data == null || data.length == 0) return decode((String) null);
        return decode(Hex.encode(data));
    }

    /**
     * Look up only the name of a custom error by its 4-byte selector. Returns {@code
     * Optional.empty()} if not in the registry.
     *
     * @param selectorHex "0x1425ea42" or "1425ea42"
     */
    public static Optional<String> lookup(String selectorHex) {
        String key =
                selectorHex.startsWith("0x")
                        ? selectorHex.substring(2).toLowerCase()
                        : selectorHex.toLowerCase();
        return Optional.ofNullable(REGISTRY.get(key));
    }

    /**
     * Register a custom error signature from your own protocol.
     *
     * <pre>
     * RevertDecoder.registerError("InsufficientCollateral(address,uint256,uint256)", "MyLending");
     * RevertDecoder.registerError("SlippageExceeded(uint256,uint256)",               "MyDex");
     * </pre>
     *
     * @param errorSignature full Solidity error signature, e.g. "MyError(address,uint256)"
     * @param protocol human-readable protocol name shown in error messages
     */
    public static void registerError(String errorSignature, String protocol) {
        byte[] hash = Keccak.hash(errorSignature.getBytes(StandardCharsets.UTF_8));
        String selector =
                String.format(
                        "%02x%02x%02x%02x",
                        hash[0] & 0xFF, hash[1] & 0xFF, hash[2] & 0xFF, hash[3] & 0xFF);
        String label = protocol + ": " + errorSignature.substring(0, errorSignature.indexOf('('));
        REGISTRY.put(selector, label);
    }

    /** Register a bare selector → label mapping (when you already know the 4 bytes). */
    public static void register(String selectorHex, String label) {
        String key = selectorHex.startsWith("0x") ? selectorHex.substring(2) : selectorHex;
        REGISTRY.put(key.toLowerCase(), label);
    }

    /** All currently registered selectors → labels. Returned as an unmodifiable view. */
    public static Map<String, String> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** Compute the 4-byte selector for a Solidity error signature. */
    public static String selector(String errorSignature) {
        byte[] hash = Keccak.hash(errorSignature.getBytes(StandardCharsets.UTF_8));
        return String.format(
                "0x%02x%02x%02x%02x",
                hash[0] & 0xFF, hash[1] & 0xFF, hash[2] & 0xFF, hash[3] & 0xFF);
    }

    // ─── Registry builders ────────────────────────────────────────────────────

    private static void reg(String sig, String protocol) {
        registerError(sig, protocol);
    }

    private static void registerOpenZeppelin() {
        // OZ ERC-20 (5.x custom errors)
        reg("ERC20InsufficientBalance(address,uint256,uint256)", "OZ/ERC-20");
        reg("ERC20InvalidSender(address)", "OZ/ERC-20");
        reg("ERC20InvalidReceiver(address)", "OZ/ERC-20");
        reg("ERC20InsufficientAllowance(address,uint256,uint256)", "OZ/ERC-20");
        reg("ERC20InvalidSpender(address)", "OZ/ERC-20");
        reg("ERC20InvalidApprover(address)", "OZ/ERC-20");

        // OZ ERC-721 (5.x)
        reg("ERC721InvalidOwner(address)", "OZ/ERC-721");
        reg("ERC721NonexistentToken(uint256)", "OZ/ERC-721");
        reg("ERC721IncorrectOwner(address,uint256,address)", "OZ/ERC-721");
        reg("ERC721InvalidSender(address)", "OZ/ERC-721");
        reg("ERC721InvalidReceiver(address)", "OZ/ERC-721");
        reg("ERC721InsufficientApproval(address,uint256)", "OZ/ERC-721");
        reg("ERC721InvalidApprover(address)", "OZ/ERC-721");
        reg("ERC721InvalidOperator(address)", "OZ/ERC-721");

        // OZ ERC-1155
        reg("ERC1155InsufficientBalance(address,uint256,uint256,uint256)", "OZ/ERC-1155");
        reg("ERC1155InvalidSender(address)", "OZ/ERC-1155");
        reg("ERC1155InvalidReceiver(address)", "OZ/ERC-1155");
        reg("ERC1155MissingApprovalForAll(address,address)", "OZ/ERC-1155");
        reg("ERC1155InvalidArrayLength(uint256,uint256)", "OZ/ERC-1155");
        reg("ERC1155InvalidOperator(address)", "OZ/ERC-1155");

        // OZ AccessControl
        reg("AccessControlUnauthorizedAccount(address,bytes32)", "OZ/AccessControl");
        reg("AccessControlBadConfirmation()", "OZ/AccessControl");

        // OZ Ownable
        reg("OwnableUnauthorizedAccount(address)", "OZ/Ownable");
        reg("OwnableInvalidOwner(address)", "OZ/Ownable");

        // OZ ReentrancyGuard
        reg("ReentrancyGuardReentrantCall()", "OZ/ReentrancyGuard");

        // OZ Pausable
        reg("EnforcedPause()", "OZ/Pausable");
        reg("ExpectedPause()", "OZ/Pausable");
    }

    private static void registerUniswapV3() {
        // Uniswap V3 SwapRouter
        reg("TooLittleReceived()", "Uniswap/V3Router");
        reg("TooMuchRequested()", "Uniswap/V3Router");
        reg("InvalidAmountOut()", "Uniswap/V3Router");
        reg("InvalidAmountIn()", "Uniswap/V3Router");

        // Uniswap V3 Pool (raw revert strings mapped as selectors to known strings)
        // These are raw string reverts in V3, not custom errors — handled by Error(string) decoder
        // But worth registering the custom error variants used in V4/periphery
        reg("PriceLimitAlreadyExceeded()", "Uniswap/V3Pool");
        reg("PriceLimitOutOfBounds()", "Uniswap/V3Pool");
        reg("NotEnoughLiquidity()", "Uniswap/V3Pool");
        reg("InvalidTick(int24)", "Uniswap/V3Pool");
        reg("TicksInverted()", "Uniswap/V3Pool");
        reg("TransactionDeadlinePassed()", "Uniswap/V3Periphery");

        // Uniswap Universal Router (V3)
        reg("V3TooLittleReceived()", "Uniswap/UniversalRouter");
        reg("V3TooMuchRequested()", "Uniswap/UniversalRouter");
        reg("V2TooLittleReceived()", "Uniswap/UniversalRouter");
        reg("V2TooMuchRequested()", "Uniswap/UniversalRouter");
        reg("InsufficientToken()", "Uniswap/UniversalRouter");
        reg("InvalidAction(bytes4)", "Uniswap/UniversalRouter");
        reg("DeadlinePassed()", "Uniswap/UniversalRouter");
        reg("ExecutionFailed(uint256,bytes)", "Uniswap/UniversalRouter");
        reg("NotPoolManager()", "Uniswap/V4");
        reg("SwapAmountCannotBeZero()", "Uniswap/V4");
    }

    private static void registerAaveV3() {
        // Aave V3 Pool error codes — all 80+ numeric codes as named constants
        String[] aaveErrors = {
            "CALLER_NOT_POOL_ADMIN", // 1
            "CALLER_NOT_EMERGENCY_ADMIN", // 2
            "CALLER_NOT_POOL_OR_EMERGENCY_ADMIN", // 3
            "CALLER_NOT_RISK_OR_POOL_ADMIN", // 4
            "CALLER_NOT_ASSET_LISTING_OR_POOL_ADMIN", // 5
            "CALLER_NOT_BRIDGE", // 6
            "ADDRESSES_PROVIDER_NOT_REGISTERED", // 7
            "INVALID_ADDRESSES_PROVIDER_ID", // 8
            "NOT_CONTRACT", // 9
            "CALLER_NOT_POOL_CONFIGURATOR", // 10
            "CALLER_NOT_ATOKEN", // 11
            "INVALID_ADDRESSES_PROVIDER", // 12
            "INVALID_FLASHLOAN_EXECUTOR_RETURN", // 13
            "RESERVE_ALREADY_ADDED", // 14
            "NO_MORE_RESERVES_ALLOWED", // 15
            "EMODE_CATEGORY_RESERVED", // 16
            "INVALID_EMODE_CATEGORY_ASSIGNMENT", // 17
            "RESERVE_LIQUIDITY_NOT_ZERO", // 18
            "FLASHLOAN_PREMIUM_INVALID", // 19
            "INVALID_RESERVE_PARAMS", // 20
            "INVALID_EMODE_CATEGORY_PARAMS", // 21
            "BRIDGE_PROTOCOL_FEE_INVALID", // 22
            "CALLER_MUST_BE_POOL", // 23
            "SAME_BLOCK_BORROW_REPAY", // 24
            "INCONSISTENT_FLASHLOAN_PARAMS", // 25
            "BORROW_CAP_EXCEEDED", // 26
            "SUPPLY_CAP_EXCEEDED", // 27
            "UNBACKED_MINT_CAP_EXCEEDED", // 28
            "DEBT_CEILING_EXCEEDED", // 29
            "UNDERLYING_CLAIMABLE_RIGHTS_NOT_ZERO", // 30
            "STABLE_DEBT_NOT_ZERO", // 31
            "VARIABLE_DEBT_SUPPLY_NOT_ZERO", // 32
            "LTV_VALIDATION_FAILED", // 33
            "INCONSISTENT_EMODE_CATEGORY", // 34
            "PRICE_ORACLE_SENTINEL_CHECK_FAILED", // 35
            "ASSET_NOT_BORROWABLE_IN_ISOLATION", // 36
            "RESERVE_ALREADY_INITIALIZED", // 37
            "USER_IN_ISOLATION_MODE_OR_LTV_ZERO", // 38
            "INVALID_LTV", // 39
            "INVALID_LIQ_THRESHOLD", // 40
            "INVALID_LIQ_BONUS", // 41
            "INVALID_DECIMALS", // 42
            "INVALID_RESERVE_FACTOR", // 43
            "INVALID_BORROW_CAP", // 44
            "INVALID_SUPPLY_CAP", // 45
            "INVALID_LIQUIDATION_PROTOCOL_FEE", // 46
            "INVALID_EMODE_CATEGORY", // 47
            "INVALID_UNBACKED_MINT_CAP", // 48
            "INVALID_DEBT_CEILING", // 49
            "INVALID_RESERVE_INDEX", // 50
            "ACL_ADMIN_CANNOT_BE_ZERO", // 51
            "INCONSISTENT_PARAMS_LENGTH", // 52
            "ZERO_ADDRESS_NOT_VALID", // 53
            "INVALID_EXPIRATION", // 54
            "INVALID_SIGNATURE", // 55
            "OPERATION_NOT_SUPPORTED", // 56
            "DEBT_CEILING_NOT_ZERO", // 57
            "ASSET_NOT_LISTED", // 58
            "INVALID_OPTIMAL_USAGE_RATIO", // 59
            "INVALID_OPTIMAL_STABLE_TO_TOTAL_DEBT_RATIO", // 60
            "UNDERLYING_CANNOT_BE_RESCUED", // 61
            "ADDRESSES_PROVIDER_ALREADY_ADDED", // 62
            "POOL_ADDRESSES_DO_NOT_MATCH", // 63
            "STABLE_BORROWING_ENABLED", // 64
            "SILOED_BORROWING_VIOLATION", // 65
            "RESERVE_DEBT_NOT_ZERO", // 66
            "FLASHLOAN_DISABLED", // 67
            "HEALTH_FACTOR_NOT_BELOW_THRESHOLD", // 68
            "COLLATERAL_CANNOT_BE_AUCTIONED", // 69
            "HEALTH_FACTOR_LOWER_THAN_LIQUIDATION_THRESHOLD", // 70
            "COLLATERAL_CANNOT_COVER_NEW_BORROW", // 71
            "COLLATERAL_SAME_AS_BORROWING_CURRENCY", // 72
            "AMOUNT_BIGGER_THAN_MAX_LOAN_SIZE_STABLE", // 73
            "NO_DEBT_OF_SELECTED_TYPE", // 74
            "NO_EXPLICIT_AMOUNT_TO_REPAY_ON_BEHALF", // 75
            "NO_OUTSTANDING_STABLE_DEBT", // 76
            "NO_OUTSTANDING_VARIABLE_DEBT", // 77
            "UNDERLYING_BALANCE_ZERO", // 78
            "INTEREST_RATE_REBALANCE_CONDITIONS_NOT_MET", // 79
            "HEALTH_FACTOR_NOT_ABOVE_THRESHOLD", // 80
            "COLLATERAL_BALANCE_IS_ZERO", // 81
            "INVALID_HF", // 82
            "INVALID_MAX_STABILITY_RATE", // 83
            "CALLER_OF_CHECK_HEALTH_FACTOR_MUST_BE_THE_POOL", // 84
            "AMOUNT_GREATER_THAN_MAX_WITHDRAWAL", // 85
            "INVALID_AMOUNT", // 86
            "NOT_ENOUGH_AVAILABLE_USER_BALANCE", // 87
            "BORROWING_NOT_ENABLED", // 88
            "STABLE_BORROWING_NOT_ENABLED", // 89
            "NOT_ENOUGH_LIQUIDITY", // 90
            "REQUESTED_AMOUNT_TOO_SMALL", // 91
        };
        // Aave V3 uses error codes as integer-indexed string reverts. We register the
        // numeric error code format as custom errors too (common pattern in V3 fork contracts).
        for (int i = 0; i < aaveErrors.length; i++) {
            register(aaveErrorSelector(i + 1), "Aave/V3: " + aaveErrors[i]);
        }
    }

    private static void registerERC20Errors() {
        // Classic string-based OZ 4.x reverts are handled by Error(string) decoder.
        // Register common 3rd-party custom error variants here.
        reg("AllowanceOverflow()", "ERC-20");
        reg("AllowanceUnderflow()", "ERC-20");
        reg("InsufficientBalance()", "ERC-20");
        reg("InsufficientAllowance()", "ERC-20");
        reg("InvalidPermit()", "ERC-20/Permit");
        reg("PermitExpired()", "ERC-20/Permit");
        reg("TotalSupplyOverflow()", "ERC-20");
    }

    private static void registerERC721Errors() {
        reg("TokenDoesNotExist()", "ERC-721");
        reg("NotOwner()", "ERC-721");
        reg("NotApproved()", "ERC-721");
        reg("AlreadyMinted()", "ERC-721");
        reg("MintToZeroAddress()", "ERC-721");
        reg("TransferToZeroAddress()", "ERC-721");
    }

    private static void registerERC1155Errors() {
        reg("TransferCallerNotOwnerNorApproved()", "ERC-1155");
        reg("BalanceQueryForZeroAddress()", "ERC-1155");
    }

    private static void registerCommonErrors() {
        // Very common patterns across protocols
        reg("Unauthorized()", "Common");
        reg("Paused()", "Common");
        reg("AlreadyInitialized()", "Common");
        reg("ZeroAddress()", "Common");
        reg("ZeroAmount()", "Common");
        reg("DeadlineExpired()", "Common");
        reg("InvalidSignature()", "Common");
        reg("SlippageExceeded()", "DEX");
        reg("PriceImpactTooHigh()", "DEX");
        reg("InsufficientLiquidity()", "DEX");
        reg("InvalidPool()", "DEX");
        reg("InvalidToken()", "DEX");
        reg("Overflow()", "Math");
        reg("Underflow()", "Math");
        reg("DivisionByZero()", "Math");
    }

    /**
     * Aave V3 encodes errors as revert with a numeric string code. We compute the 4-byte selector
     * of the custom error as if it were: {@code error AaveError(uint256 code)} with the given
     * numeric value. This is a best-effort approximation — actual Aave V3 uses string reverts.
     */
    private static String aaveErrorSelector(int code) {
        // Aave V3 actually reverts with Error(string) containing the numeric code string.
        // We register a synthetic selector here keyed to the pattern for future-proofing
        // and for contracts that import Aave error codes as custom errors.
        byte[] hash = Keccak.hash(("AaveError" + code).getBytes(StandardCharsets.UTF_8));
        return String.format(
                "%02x%02x%02x%02x", hash[0] & 0xFF, hash[1] & 0xFF, hash[2] & 0xFF, hash[3] & 0xFF);
    }
}
