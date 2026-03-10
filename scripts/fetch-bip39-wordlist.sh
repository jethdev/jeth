#!/bin/bash
# Download the official BIP-39 English wordlist (2048 words)
# Source: https://github.com/trezor/python-mnemonic
set -e
TARGET="src/main/resources/io/jeth/wallet/bip39-english.txt"
mkdir -p "$(dirname "$TARGET")"
echo "Downloading BIP-39 English wordlist..."
curl -fsSL \
  "https://raw.githubusercontent.com/trezor/python-mnemonic/master/src/mnemonic/wordlist/english.txt" \
  -o "$TARGET"
COUNT=$(wc -l < "$TARGET")
echo "✅ $COUNT words written to $TARGET"
[ "$COUNT" -eq 2048 ] || { echo "❌ Expected 2048 words, got $COUNT"; exit 1; }
