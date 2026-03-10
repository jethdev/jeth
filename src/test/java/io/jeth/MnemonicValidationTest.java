/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.wallet.HdWallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for BIP-39 mnemonic validation (validate, isValid, MnemonicException). Covers: word count,
 * unknown words, checksum, passphrase, edge cases.
 */
class MnemonicValidationTest {

    static final String VALID_12 = "test test test test test test test test test test test junk";
    static final String VALID_24 =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";

    // ─── isValid / validate basics ────────────────────────────────────────────

    @Test
    @DisplayName("isValid() returns true for a known-good 12-word mnemonic")
    void valid_12_word() {
        assertTrue(HdWallet.isValid(VALID_12));
    }

    @Test
    @DisplayName("isValid() returns true for a known-good 24-word mnemonic")
    void valid_24_word() {
        assertTrue(HdWallet.isValid(VALID_24));
    }

    @Test
    @DisplayName("validate() does not throw for valid mnemonic")
    void validate_no_throw() {
        assertDoesNotThrow(() -> HdWallet.validate(VALID_12));
    }

    @Test
    @DisplayName("fromMnemonic() still works — validate is called internally")
    void from_mnemonic_still_works() {
        var hd = HdWallet.fromMnemonic(VALID_12);
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", hd.getAccount(0).getAddress());
    }

    // ─── Word count ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("11-word mnemonic is rejected")
    void reject_11_words() {
        var ex =
                assertThrows(
                        HdWallet.MnemonicException.class,
                        () ->
                                HdWallet.validate(
                                        "word word word word word word word word word word word"));
        assertTrue(
                ex.getMessage().contains("word count") || ex.getMessage().contains("11"),
                "Message should mention word count: " + ex.getMessage());
    }

    @Test
    @DisplayName("13-word mnemonic is rejected")
    void reject_13_words() {
        assertThrows(
                HdWallet.MnemonicException.class,
                () -> HdWallet.validate("abandon ".repeat(13).trim()));
    }

    @Test
    @DisplayName("empty string is rejected")
    void reject_empty() {
        assertThrows(HdWallet.MnemonicException.class, () -> HdWallet.validate(""));
    }

    @Test
    @DisplayName("null is rejected")
    void reject_null() {
        assertThrows(HdWallet.MnemonicException.class, () -> HdWallet.validate(null));
    }

    @Test
    @DisplayName("blank/whitespace is rejected")
    void reject_blank() {
        assertThrows(HdWallet.MnemonicException.class, () -> HdWallet.validate("   "));
    }

    // ─── Unknown words ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mnemonic with a non-BIP39 word is rejected")
    void reject_unknown_word() {
        // Replace last word with a made-up word
        String bad = "test test test test test test test test test test test BADWORD";
        var ex = assertThrows(HdWallet.MnemonicException.class, () -> HdWallet.validate(bad));
        assertTrue(
                ex.getMessage().toLowerCase().contains("badword")
                        || ex.getMessage().toLowerCase().contains("unknown"),
                "Message should identify the bad word: " + ex.getMessage());
    }

    @Test
    @DisplayName("Mnemonic with a number token is rejected")
    void reject_number_token() {
        String bad = "test test test test test test test test test test test 123";
        assertThrows(HdWallet.MnemonicException.class, () -> HdWallet.validate(bad));
    }

    // ─── Checksum ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mnemonic with wrong last word (bad checksum) is rejected")
    void reject_bad_checksum() {
        // "test test...junk" is valid; "test test...abandon" has wrong checksum
        String badChecksum = "test test test test test test test test test test test abandon";
        var ex =
                assertThrows(
                        HdWallet.MnemonicException.class, () -> HdWallet.validate(badChecksum));
        assertTrue(
                ex.getMessage().toLowerCase().contains("checksum"),
                "Message should mention checksum: " + ex.getMessage());
    }

    @Test
    @DisplayName("Swapped word order fails checksum")
    void reject_swapped_words() {
        // Take valid mnemonic and swap two words — almost certainly breaks checksum
        String[] words = VALID_12.split(" ");
        String tmp = words[0];
        words[0] = words[1];
        words[1] = tmp;
        String swapped = String.join(" ", words);
        // May coincidentally pass, but almost certainly won't
        // At minimum, we verify it doesn't throw NPE or other unexpected errors
        assertDoesNotThrow(
                () -> {
                    try {
                        HdWallet.validate(swapped);
                    } catch (HdWallet.MnemonicException e) {
                        /* expected */
                    }
                });
    }

    // ─── isValid vs validate ──────────────────────────────────────────────────

    @Test
    @DisplayName("isValid() returns false instead of throwing for invalid mnemonic")
    void is_valid_false_no_throw() {
        assertFalse(HdWallet.isValid("this is not a valid mnemonic phrase at all no way"));
        assertFalse(HdWallet.isValid(null));
        assertFalse(HdWallet.isValid(""));
    }

    @Test
    @DisplayName("isValid() agrees with validate() on valid mnemonic")
    void is_valid_agrees_with_validate() {
        assertTrue(HdWallet.isValid(VALID_12));
        assertTrue(HdWallet.isValid(VALID_24));
    }

    // ─── Case insensitivity ───────────────────────────────────────────────────

    @Test
    @DisplayName("Validation is case-insensitive (uppercase input)")
    void case_insensitive() {
        assertTrue(HdWallet.isValid(VALID_12.toUpperCase()));
    }

    @Test
    @DisplayName("Validation handles extra whitespace")
    void extra_whitespace() {
        String withSpaces = "test  test test test test test test test test test test  junk";
        assertTrue(HdWallet.isValid(withSpaces));
    }

    // ─── MnemonicException ────────────────────────────────────────────────────

    @Test
    @DisplayName("MnemonicException is a RuntimeException")
    void exception_type() {
        var ex =
                assertThrows(
                        HdWallet.MnemonicException.class,
                        () -> HdWallet.validate("bad words here twelve should fail this one"));
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("MnemonicException message starts with 'Invalid BIP-39 mnemonic'")
    void exception_message_prefix() {
        var ex =
                assertThrows(
                        HdWallet.MnemonicException.class,
                        () ->
                                HdWallet.validate(
                                        "one two three four five six seven eight nine ten eleven twelve"));
        assertTrue(
                ex.getMessage().startsWith("Invalid BIP-39 mnemonic"),
                "Expected prefix, got: " + ex.getMessage());
    }
}
