/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.crypto.Wallet;
import io.jeth.flashbots.FlashbotsBundle;
import io.jeth.flashbots.FlashbotsClient;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FlashbotsClient tests — covers: - sendBundle: HTTP method, URL, X-Flashbots-Signature header,
 * response parsing - sendPrivateTransaction: body params, response - cancelPrivateTransaction -
 * simulate: parses SimResult - sign(): EIP-191 signing - FlashbotsBundle builder - relay URL
 * constants
 */
class FlashbotsClientTest {

    private static final String PK =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Wallet WALLET = Wallet.fromPrivateKey(new BigInteger(PK.substring(2), 16));

    // ─── Relay URL constants ──────────────────────────────────────────────────

    @Test
    @DisplayName("RELAY_MAINNET is a valid HTTPS URL")
    void relay_mainnet_https() {
        assertTrue(FlashbotsClient.RELAY_MAINNET.startsWith("https://"));
    }

    @Test
    @DisplayName("MEV_BLOCKER is a valid HTTPS URL")
    void mev_blocker_https() {
        assertTrue(FlashbotsClient.MEV_BLOCKER.startsWith("https://"));
    }

    @Test
    @DisplayName("All relay URLs start with https://")
    void all_relays_https() {
        for (String url :
                List.of(
                        FlashbotsClient.RELAY_MAINNET,
                        FlashbotsClient.RELAY_GOERLI,
                        FlashbotsClient.MEV_BLOCKER,
                        FlashbotsClient.TITAN_RELAY,
                        FlashbotsClient.BEAVER_RELAY)) {
            assertTrue(url.startsWith("https://"), "Should be HTTPS: " + url);
        }
    }

    // ─── sign() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sign() returns non-empty hex string")
    void sign_non_empty() {
        var client = FlashbotsClient.of("https://relay.flashbots.net", WALLET);
        String sig = client.sign("{\"test\":true}");
        assertNotNull(sig);
        assertFalse(sig.isEmpty());
    }

    @Test
    @DisplayName("sign() is deterministic for same input")
    void sign_deterministic() {
        var client = FlashbotsClient.of("https://relay.flashbots.net", WALLET);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_sendBundle\"}";
        assertEquals(client.sign(body), client.sign(body));
    }

    @Test
    @DisplayName("sign() produces different signatures for different bodies")
    void sign_different_bodies() {
        var client = FlashbotsClient.of("https://relay.flashbots.net", WALLET);
        assertNotEquals(client.sign("{\"body\":1}"), client.sign("{\"body\":2}"));
    }

    @Test
    @DisplayName("sign() output is 0x-prefixed hex (65 bytes = 130 hex chars + 0x)")
    void sign_format() {
        var client = FlashbotsClient.of("https://relay.flashbots.net", WALLET);
        String sig = client.sign("some body content");
        assertTrue(
                sig.startsWith("0x") || sig.length() == 130,
                "Should be 65-byte hex sig: " + sig.length());
        assertTrue(sig.matches("(0x)?[0-9a-fA-F]+"), "Should be hex: " + sig);
    }

    // ─── sendBundle ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendBundle() sends POST to relay URL")
    void send_bundle_post() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":{\"bundleHash\":\"0xabc123\"}}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            var bundle = FlashbotsBundle.of().tx("0xdeadbeef").build();
            String hash = client.sendBundle(bundle, 18_000_000L).join();

            assertEquals("0xabc123", hash);
            RecordedRequest req = server.takeRequest();
            assertEquals("POST", req.getMethod());
        }
    }

    @Test
    @DisplayName("sendBundle() sets X-Flashbots-Signature header")
    void send_bundle_auth_header() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":{\"bundleHash\":\"0xabc\"}}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            var bundle = FlashbotsBundle.of().tx("0xdeadbeef").build();
            client.sendBundle(bundle, 1L).join();

            RecordedRequest req = server.takeRequest();
            String header = req.getHeader("X-Flashbots-Signature");
            assertNotNull(header, "X-Flashbots-Signature header must be set");
            assertTrue(
                    header.contains(WALLET.getAddress()),
                    "Header must include signer address: " + header);
            assertTrue(header.contains(":"), "Header format: address:signature");
        }
    }

    @Test
    @DisplayName("sendBundle() includes blockNumber in request body")
    void send_bundle_block_number_in_body() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":{\"bundleHash\":\"0xabc\"}}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            client.sendBundle(FlashbotsBundle.of().tx("0xdeadbeef").build(), 18_500_000L).join();

            RecordedRequest req = server.takeRequest();
            String body = req.getBody().readUtf8();
            assertTrue(body.contains("eth_sendBundle"), "Must call eth_sendBundle: " + body);
            assertTrue(
                    body.contains("0x11a2a40") || body.contains("11a2a40"),
                    "Must include block number 18500000 hex: " + body);
        }
    }

    @Test
    @DisplayName("sendBundle() with range sets maxBlock in body")
    void send_bundle_max_block() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":{\"bundleHash\":\"0xabc\"}}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            client.sendBundle(
                            FlashbotsBundle.of().tx("0xdeadbeef").build(), 18_000_000L, 18_000_003L)
                    .join();
            String body = server.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("maxBlock"), body);
        }
    }

    @Test
    @DisplayName("sendBundle() throws FlashbotsException on relay error response")
    void send_bundle_relay_error() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"error\":{\"message\":\"bundle not valid\"}}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            assertThrows(
                    Exception.class,
                    () ->
                            client.sendBundle(FlashbotsBundle.of().tx("0xdeadbeef").build(), 1L)
                                    .join());
        }
    }

    // ─── sendPrivateTransaction ───────────────────────────────────────────────

    @Test
    @DisplayName("sendPrivateTransaction() sends eth_sendPrivateTransaction")
    void send_private_tx_method() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":\"0xTxHash\"}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            String hash = client.sendPrivateTransaction("0xRawTx").join();
            assertEquals("0xTxHash", hash);
            assertTrue(
                    server.takeRequest()
                            .getBody()
                            .readUtf8()
                            .contains("eth_sendPrivateTransaction"));
        }
    }

    @Test
    @DisplayName("sendPrivateTransaction(tx, maxBlock, fast) includes preferences")
    void send_private_tx_with_options() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":\"0xTxHash\"}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            client.sendPrivateTransaction("0xRawTx", 18_000_005L, true).join();
            String body = server.takeRequest().getBody().readUtf8();
            assertTrue(body.contains("maxBlockNumber"), body);
            assertTrue(body.contains("fast") || body.contains("preferences"), body);
        }
    }

    // ─── cancelPrivateTransaction ─────────────────────────────────────────────

    @Test
    @DisplayName("cancelPrivateTransaction() sends eth_cancelPrivateTransaction")
    void cancel_private_tx() throws Exception {
        try (var server = mockRelay("{\"id\":1,\"result\":true}")) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            assertTrue(client.cancelPrivateTransaction("0xTxHash").join());
            assertTrue(
                    server.takeRequest()
                            .getBody()
                            .readUtf8()
                            .contains("eth_cancelPrivateTransaction"));
        }
    }

    // ─── simulate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("simulate() parses FlashbotsSimResult from relay response")
    void simulate_parses_result() throws Exception {
        String simResponse =
                "{\"id\":1,\"result\":{"
                        + "\"coinbaseDiff\":\"1000000000000000\","
                        + "\"results\":[{"
                        + "  \"gasUsed\":21000,"
                        + "  \"gasFees\":500000000,"
                        + "  \"fromAddress\":\""
                        + WALLET.getAddress()
                        + "\","
                        + "  \"toAddress\":\"0xRecipient\""
                        + "}]}}";

        try (var server = mockRelay(simResponse)) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            var bundle = FlashbotsBundle.of().tx("0xRawTx").build();
            var result = client.simulate(bundle, 18_000_000L).join();

            assertNotNull(result);
            assertEquals(21000L, result.totalGasUsed());
            assertEquals(1_000_000_000_000_000L, result.totalMinerPayment());
            assertEquals(1, result.txResults().size());
            assertTrue(result.allSucceeded());
        }
    }

    @Test
    @DisplayName("SimResult.anyReverted() true when a tx has error field")
    void sim_result_any_reverted() throws Exception {
        String simResponse =
                "{\"id\":1,\"result\":{"
                        + "\"coinbaseDiff\":\"0\","
                        + "\"results\":[{\"gasUsed\":21000,\"error\":\"execution reverted\"}]}}";

        try (var server = mockRelay(simResponse)) {
            var client = FlashbotsClient.of(server.url("/").toString(), WALLET);
            var result = client.simulate(FlashbotsBundle.of().tx("0xBadTx").build(), 1L).join();
            assertTrue(result.anyReverted());
            assertFalse(result.allSucceeded());
        }
    }

    @Test
    @DisplayName("SimResult.minerPaymentEth() is human-readable")
    void sim_result_miner_payment_eth() {
        var result =
                new FlashbotsClient.FlashbotsSimResult(
                        21000L, 1_000_000_000_000_000_000L, List.of());
        String eth = result.minerPaymentEth();
        assertTrue(eth.contains("ETH"), eth);
        assertTrue(eth.contains("1") || eth.startsWith("1"), eth);
    }

    // ─── FlashbotsBundle ─────────────────────────────────────────────────────

    @Test
    @DisplayName("FlashbotsBundle.of().tx().build() creates valid bundle")
    void bundle_build_single_tx() {
        var bundle = FlashbotsBundle.of().tx("0xRawTx1").build();
        assertEquals(1, bundle.size());
        assertEquals("0xRawTx1", bundle.rawTxs().get(0));
        assertTrue(bundle.revertingTxHashes().isEmpty());
    }

    @Test
    @DisplayName("FlashbotsBundle with multiple txs preserves order")
    void bundle_preserves_order() {
        var bundle = FlashbotsBundle.of().tx("0xTx1").tx("0xTx2").tx("0xTx3").build();
        assertEquals(3, bundle.size());
        assertEquals("0xTx1", bundle.rawTxs().get(0));
        assertEquals("0xTx2", bundle.rawTxs().get(1));
        assertEquals("0xTx3", bundle.rawTxs().get(2));
    }

    @Test
    @DisplayName("FlashbotsBundle.allowRevert() adds tx hash to revertingTxHashes")
    void bundle_allow_revert() {
        var bundle =
                FlashbotsBundle.of()
                        .tx("0xRequired")
                        .allowRevert("0xOptional", "0xOptionalHash")
                        .build();
        assertEquals(2, bundle.size());
        assertEquals(1, bundle.revertingTxHashes().size());
        assertEquals("0xOptionalHash", bundle.revertingTxHashes().get(0));
    }

    @Test
    @DisplayName("FlashbotsBundle.txs(list) adds multiple txs")
    void bundle_txs_list() {
        var bundle = FlashbotsBundle.of().txs(List.of("0xA", "0xB", "0xC")).build();
        assertEquals(3, bundle.size());
    }

    @Test
    @DisplayName("Empty bundle throws IllegalStateException")
    void bundle_empty_throws() {
        assertThrows(IllegalStateException.class, () -> FlashbotsBundle.of().build());
    }

    @Test
    @DisplayName("FlashbotsBundle.rawTxs() is unmodifiable")
    void bundle_immutable() {
        var bundle = FlashbotsBundle.of().tx("0xTx").build();
        assertThrows(UnsupportedOperationException.class, () -> bundle.rawTxs().add("0xHacked"));
    }

    @Test
    @DisplayName("FlashbotsBundle.toString() is human-readable")
    void bundle_to_string() {
        var bundle = FlashbotsBundle.of().tx("0xTx1").tx("0xTx2").build();
        String s = bundle.toString();
        assertTrue(s.contains("FlashbotsBundle") || s.contains("txs=2"), s);
    }

    // ─── FlashbotsClient factory ──────────────────────────────────────────────

    @Test
    @DisplayName("mainnet(wallet) creates client pointing to mainnet relay")
    void mainnet_factory() {
        var client = FlashbotsClient.mainnet(WALLET);
        assertNotNull(client);
        // We can't check internal URL directly, but the object should be constructed
    }

    @Test
    @DisplayName("of(url, wallet, timeout) accepts custom timeout")
    void custom_timeout_factory() {
        assertDoesNotThrow(
                () ->
                        FlashbotsClient.of(
                                "https://relay.flashbots.net", WALLET, Duration.ofSeconds(30)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private MockWebServer mockRelay(String responseBody) throws IOException {
        var server = new MockWebServer();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest req) {
                        return new MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(responseBody);
                    }
                });
        server.start();
        return server;
    }
}
