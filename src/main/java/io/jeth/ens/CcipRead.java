/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.ens;

import io.jeth.core.EthClient;
import io.jeth.core.EthException;
import io.jeth.model.EthModels;
import io.jeth.util.Hex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * EIP-3668 CCIP-Read (Cross-Chain Interoperability Protocol — off-chain lookup).
 *
 * <p>When an ENS resolver reverts with {@code OffchainLookup(address,string[],bytes,bytes4,bytes)},
 * this class handles the gateway HTTP call and re-submits the result on-chain.
 *
 * <p>The full flow:
 *
 * <ol>
 *   <li>eth_call → resolver reverts with OffchainLookup
 *   <li>CcipRead parses the revert data, picks a gateway URL
 *   <li>HTTP GET/POST to the gateway with the calldata
 *   <li>eth_call again with the callback selector + gateway response
 * </ol>
 *
 * Called automatically by {@link EnsResolver} — you don't need to use this directly.
 */
public final class CcipRead {

    /**
     * OffchainLookup(address sender, string[] urls, bytes callData, bytes4 callbackFunction, bytes
     * extraData) Selector = keccak256("OffchainLookup(address,string[],bytes,bytes4,bytes)")[0..4]
     */
    static final String OFFCHAIN_LOOKUP_SELECTOR = "0x556f1830";

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private CcipRead() {}

    /**
     * Execute an eth_call, and if it reverts with OffchainLookup, resolve via CCIP-Read. Retries up
     * to {@code maxDepth} times (to handle chained redirects).
     *
     * @param client Ethereum client
     * @param to contract address (resolver)
     * @param calldata encoded calldata
     * @param maxDepth maximum redirect depth (default 4 per EIP-3668)
     * @return resolved call result
     */
    public static CompletableFuture<String> call(
            EthClient client, String to, String calldata, int maxDepth) {

        EthModels.CallRequest req = EthModels.CallRequest.builder().to(to).data(calldata).build();

        return client.send("eth_call", List.of(req, "latest"))
                .thenCompose(
                        resp -> {
                            if (!resp.hasError()) {
                                return CompletableFuture.completedFuture(resp.resultAsText());
                            }

                            String revertData = resp.revertData();
                            if (revertData == null
                                    || !revertData.startsWith(OFFCHAIN_LOOKUP_SELECTOR)) {
                                // Not a CCIP-Read revert — propagate as normal error
                                throw new EthException(
                                        resp.errorMessage() != null
                                                ? resp.errorMessage()
                                                : "eth_call failed",
                                        revertData);
                            }

                            if (maxDepth <= 0) {
                                throw new EthException(
                                        "CCIP-Read: maximum redirect depth exceeded");
                            }

                            // Parse OffchainLookup revert
                            OffchainLookup lookup = parseOffchainLookup(revertData);
                            return fetchGateway(lookup)
                                    .thenCompose(
                                            gatewayResponse -> {
                                                // Re-call with callbackFunction(bytes response,
                                                // bytes extraData)
                                                String callbackCalldata =
                                                        buildCallbackCalldata(
                                                                lookup.callbackFunction,
                                                                gatewayResponse,
                                                                lookup.extraData);
                                                return call(
                                                        client, to, callbackCalldata, maxDepth - 1);
                                            });
                        });
    }

    public static CompletableFuture<String> call(EthClient client, String to, String calldata) {
        return call(client, to, calldata, 4);
    }

    // ─── Gateway fetch ────────────────────────────────────────────────────────

    private static CompletableFuture<String> fetchGateway(OffchainLookup lookup) {
        // Try each URL in order, return first success
        return tryGateway(lookup, 0);
    }

    private static CompletableFuture<String> tryGateway(OffchainLookup lookup, int urlIndex) {
        if (urlIndex >= lookup.urls.length) {
            return CompletableFuture.failedFuture(
                    new EthException("CCIP-Read: all gateway URLs failed"));
        }

        String url =
                lookup.urls[urlIndex]
                        .replace("{sender}", lookup.sender)
                        .replace("{data}", lookup.callData);

        boolean isGet = url.contains("{data}") || !url.contains("{sender}");

        CompletableFuture<String> request;
        if (isGet || !lookup.callData.isEmpty()) {
            // EIP-3668 GET: URL with {sender} and {data} substituted
            // POST: send JSON body {"data": "0x...", "sender": "0x..."}
            if (url.contains("{data}") || url.contains("{sender}")) {
                // GET with substitutions already done above
                request = httpGet(url);
            } else {
                String body =
                        String.format(
                                "{\"data\":\"%s\",\"sender\":\"%s\"}",
                                lookup.callData, lookup.sender);
                request = httpPost(url, body);
            }
        } else {
            request = httpGet(url);
        }

        return request.thenApply(CcipRead::extractDataFromResponse)
                .exceptionallyCompose(ex -> tryGateway(lookup, urlIndex + 1));
    }

    private static CompletableFuture<String> httpGet(String url) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        r -> {
                            if (r.statusCode() != 200)
                                throw new EthException(
                                        "CCIP-Read gateway returned HTTP " + r.statusCode());
                            return r.body();
                        });
    }

    private static CompletableFuture<String> httpPost(String url, String body) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        r -> {
                            if (r.statusCode() != 200)
                                throw new EthException(
                                        "CCIP-Read gateway returned HTTP " + r.statusCode());
                            return r.body();
                        });
    }

    /** Extract the "data" field from the JSON gateway response. */
    private static String extractDataFromResponse(String json) {
        // Minimal JSON parsing: look for "data":"0x..."
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx < 0) throw new EthException("CCIP-Read: gateway response missing 'data' field");
        int colon = json.indexOf(':', dataIdx + 6);
        int quote1 = json.indexOf('"', colon + 1);
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote1 < 0 || quote2 < 0)
            throw new EthException("CCIP-Read: could not parse gateway response data");
        return json.substring(quote1 + 1, quote2);
    }

    // ─── ABI parsing ─────────────────────────────────────────────────────────

    /**
     * Parse the OffchainLookup revert data.
     *
     * <p>ABI encoding of: OffchainLookup(address sender, string[] urls, bytes callData, bytes4
     * callbackFunction, bytes extraData) after stripping the 4-byte selector.
     */
    static OffchainLookup parseOffchainLookup(String revertHex) {
        byte[] data = Hex.decode(revertHex.substring(10)); // skip "0x" + 4-byte selector

        // Decode sender (address, slot 0)
        String sender = "0x" + Hex.encodeNoPrefx(Arrays.copyOfRange(data, 12, 32));

        // urls offset (slot 1) — dynamic, points to length + elements
        int urlsOffset = (int) decodeLong(data, 32);
        // callData offset (slot 2)
        int callDataOffset = (int) decodeLong(data, 64);
        // callbackFunction (slot 3) — bytes4, left-padded to 32 bytes
        String callbackFunction = "0x" + Hex.encodeNoPrefx(Arrays.copyOfRange(data, 96, 100));
        // extraData offset (slot 4)
        int extraDataOffset = (int) decodeLong(data, 128);

        // Decode urls array
        int urlCount = (int) decodeLong(data, urlsOffset);
        String[] urls = new String[urlCount];
        for (int i = 0; i < urlCount; i++) {
            int strOffset = urlsOffset + 32 + (int) decodeLong(data, urlsOffset + 32 + i * 32);
            int strLen = (int) decodeLong(data, strOffset);
            urls[i] =
                    new String(
                            Arrays.copyOfRange(data, strOffset + 32, strOffset + 32 + strLen),
                            StandardCharsets.UTF_8);
        }

        // Decode callData bytes
        int callDataLen = (int) decodeLong(data, callDataOffset);
        String callData =
                "0x"
                        + Hex.encodeNoPrefx(
                                Arrays.copyOfRange(
                                        data,
                                        callDataOffset + 32,
                                        callDataOffset + 32 + callDataLen));

        // Decode extraData bytes
        int extraDataLen = (int) decodeLong(data, extraDataOffset);
        String extraData =
                "0x"
                        + Hex.encodeNoPrefx(
                                Arrays.copyOfRange(
                                        data,
                                        extraDataOffset + 32,
                                        extraDataOffset + 32 + extraDataLen));

        return new OffchainLookup(sender, urls, callData, callbackFunction, extraData);
    }

    /** Build the callback calldata: callbackFunction(bytes response, bytes extraData) */
    static String buildCallbackCalldata(
            String callbackFunction, String gatewayResponse, String extraData) {
        // callbackFunction is bytes4 (4 bytes)
        // ABI encode: (bytes response, bytes extraData) — two dynamic bytes params
        byte[] resp = Hex.decode(gatewayResponse);
        byte[] extra = Hex.decode(extraData);

        // ABI encoding: offset1=64, offset2=64+32+alignedLen(resp), data1, data2
        int respPaddedLen = ((resp.length + 31) / 32) * 32;
        int extraPaddedLen = ((extra.length + 31) / 32) * 32;

        byte[] encoded = new byte[4 + 32 + 32 + 32 + respPaddedLen + 32 + extraPaddedLen];
        // selector
        byte[] sel = Hex.decode(callbackFunction);
        System.arraycopy(sel, 0, encoded, 0, 4);
        // offset to resp = 64
        encodeLong(encoded, 4, 64);
        // offset to extra = 64 + 32 + respPaddedLen
        encodeLong(encoded, 36, 64 + 32 + respPaddedLen);
        // resp length
        encodeLong(encoded, 68, resp.length);
        // resp data
        System.arraycopy(resp, 0, encoded, 100, resp.length);
        // extra length
        int extraLenOffset = 100 + respPaddedLen;
        encodeLong(encoded, extraLenOffset, extra.length);
        // extra data
        System.arraycopy(extra, 0, encoded, extraLenOffset + 32, extra.length);

        return Hex.encode(encoded);
    }

    private static long decodeLong(byte[] data, int offset) {
        if (offset + 32 > data.length) return 0;
        long v = 0;
        for (int i = offset + 24; i < offset + 32; i++) {
            v = (v << 8) | (data[i] & 0xFF);
        }
        return v;
    }

    private static void encodeLong(byte[] data, int offset, long value) {
        for (int i = offset + 31; i >= offset; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    // ─── Data class ───────────────────────────────────────────────────────────

    record OffchainLookup(
            String sender,
            String[] urls,
            String callData,
            String callbackFunction,
            String extraData) {}
}
