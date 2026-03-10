/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jeth.core.EthException;
import io.jeth.model.RpcModels;
import io.jeth.provider.Provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Production-grade provider middleware: retry, rate-limit, cache, fallback, metrics.
 *
 * <pre>
 * var provider = MiddlewareProvider.wrap(HttpProvider.of(url))
 *     .withRetry(3, Duration.ofMillis(300))
 *     .withRateLimit(100)
 *     .withCache(Duration.ofSeconds(12))
 *     .withLogging()
 *     .build();
 *
 * // Fallback across multiple RPC endpoints
 * var provider = MiddlewareProvider.withFallback(
 *     HttpProvider.of(infuraUrl),
 *     HttpProvider.of(alchemyUrl),
 *     HttpProvider.of(quicknodeUrl)
 * );
 *
 * // Check metrics
 * var m = ((MiddlewareProvider) provider).getMetrics();
 * System.out.println(m); // Metrics{requests=1000, cacheHits=412, retries=3, ...}
 * </pre>
 */
public class MiddlewareProvider implements Provider {

    private static final Logger log = Logger.getLogger(MiddlewareProvider.class.getName());

    // Only cache methods whose results never change once deployed.
    // eth_call, eth_getBalance, eth_getStorageAt are intentionally excluded:
    // they depend on block state and "latest" advances every ~12s, so caching them
    // causes stale reads that are difficult to debug.
    // eth_getCode is included because deployed bytecode is immutable.
    private static final Set<String> CACHEABLE_METHODS = Set.of(
        "eth_chainId", "net_version", "eth_getCode"
    );

    private final Provider inner;
    private final RetryConfig retry;
    private final RateLimitConfig rateLimit;
    private final CacheConfig cache;
    private final boolean loggingEnabled;
    private final Metrics metrics = new Metrics();

    // Cache
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    // Token bucket rate limiter
    private final AtomicLong tokens;
    private final AtomicLong lastRefill = new AtomicLong(System.currentTimeMillis());

    private MiddlewareProvider(Builder b) {
        this.inner          = b.inner;
        this.retry          = b.retry;
        this.rateLimit      = b.rateLimit;
        this.cache          = b.cache;
        this.loggingEnabled = b.loggingEnabled;
        this.tokens         = b.rateLimit != null
                ? new AtomicLong(b.rateLimit.maxPerSecond)
                : new AtomicLong(Long.MAX_VALUE);
    }

    @Override
    public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest request) {
        long start = System.currentTimeMillis();
        metrics.totalRequests.incrementAndGet();

        // Cache lookup
        if (cache != null && CACHEABLE_METHODS.contains(request.method)) {
            String key = request.method + ":" + request.params;
            CacheEntry entry = responseCache.get(key);
            if (entry != null && !entry.isExpired()) {
                metrics.cacheHits.incrementAndGet();
                if (loggingEnabled) log.fine("CACHE " + request.method);
                return CompletableFuture.completedFuture(entry.response);
            }
        }

        // Rate limiting
        if (rateLimit != null) applyRateLimit();

        if (loggingEnabled) log.fine("→ " + request.method);

        return sendWithRetry(request, 0).whenComplete((resp, ex) -> {
            long ms = System.currentTimeMillis() - start;
            metrics.totalMs.addAndGet(ms);
            if (ex != null) {
                metrics.errors.incrementAndGet();
                if (loggingEnabled) log.warning("✗ " + request.method + " (" + ms + "ms): " + ex.getMessage());
            } else {
                if (loggingEnabled) log.fine("← " + request.method + " (" + ms + "ms)");
                if (cache != null && CACHEABLE_METHODS.contains(request.method) && resp != null && !resp.hasError()) {
                    String key = request.method + ":" + request.params;
                    responseCache.put(key, new CacheEntry(resp, System.currentTimeMillis() + cache.ttlMs));
                }
            }
        });
    }

    private CompletableFuture<RpcModels.RpcResponse> sendWithRetry(RpcModels.RpcRequest req, int attempt) {
        return inner.send(req).thenCompose(resp -> {
            if (retry != null && resp.hasError() && attempt < retry.maxAttempts && isRetryable(resp)) {
                metrics.retries.incrementAndGet();
                long delay = retry.baseDelayMs << Math.min(attempt, 5);
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> sendWithRetry(req, attempt + 1));
            }
            return CompletableFuture.completedFuture(resp);
        }).exceptionallyCompose(ex -> {
            if (retry != null && attempt < retry.maxAttempts && isRetryableEx(ex)) {
                metrics.retries.incrementAndGet();
                long delay = retry.baseDelayMs << Math.min(attempt, 5);
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> sendWithRetry(req, attempt + 1));
            }
            CompletableFuture<RpcModels.RpcResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    ex instanceof RuntimeException re ? re : new EthException("RPC failed after retries", ex));
            return failed;
        });
    }

    private void applyRateLimit() {
        // Spin until a token is available, refilling at most once per second.
        // This blocks the calling thread — use withRateLimit() only on a
        // dedicated executor or in a context where short blocking is acceptable.
        while (true) {
            long now  = System.currentTimeMillis();
            long last = lastRefill.get();
            if (now - last >= 1000 && lastRefill.compareAndSet(last, now)) {
                tokens.set(rateLimit.maxPerSecond);
            }
            if (tokens.decrementAndGet() >= 0) return; // acquired a token
            // Restore the token we over-decremented, then wait for the next window
            tokens.incrementAndGet();
            long sleepMs = Math.max(1, 1000 - (System.currentTimeMillis() - lastRefill.get()));
            try { Thread.sleep(Math.min(sleepMs, 50)); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isRetryable(RpcModels.RpcResponse r) {
        if (r.error == null) return false;
        String msg = r.error.toString().toLowerCase();
        return msg.contains("rate") || msg.contains("limit") || msg.contains("timeout")
                || msg.contains("503") || msg.contains("429");
    }

    private boolean isRetryableEx(Throwable ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection") || msg.contains("reset");
    }

    @Override public ObjectMapper getObjectMapper() { return inner.getObjectMapper(); }
    @Override public void close() { inner.close(); }
    public Metrics getMetrics() { return metrics; }

    /** Evict all cached entries. */
    public void clearCache() { responseCache.clear(); }

    // ─── Fallback ─────────────────────────────────────────────────────────────

    public static Provider withFallback(Provider... providers) {
        if (providers.length == 0) throw new IllegalArgumentException("Need at least one provider");
        return new FallbackProvider(List.of(providers));
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    public static Builder wrap(Provider inner) { return new Builder(inner); }

    public static class Builder {
        private final Provider inner;
        private RetryConfig retry;
        private RateLimitConfig rateLimit;
        private CacheConfig cache;
        private boolean loggingEnabled;

        Builder(Provider inner) { this.inner = inner; }

        public Builder withRetry(int maxAttempts, Duration baseDelay) {
            this.retry = new RetryConfig(maxAttempts, baseDelay.toMillis()); return this;
        }
        public Builder withRateLimit(int maxPerSecond) {
            this.rateLimit = new RateLimitConfig(maxPerSecond); return this;
        }
        public Builder withCache(Duration ttl) {
            this.cache = new CacheConfig(ttl.toMillis()); return this;
        }
        public Builder withLogging() { this.loggingEnabled = true; return this; }
        public MiddlewareProvider build() { return new MiddlewareProvider(this); }
    }

    // ─── Types ────────────────────────────────────────────────────────────────

    record RetryConfig(int maxAttempts, long baseDelayMs) {}
    record RateLimitConfig(int maxPerSecond) {}
    record CacheConfig(long ttlMs) {}
    private record CacheEntry(RpcModels.RpcResponse response, long expiryMs) {
        boolean isExpired() { return System.currentTimeMillis() > expiryMs; }
    }

    public static class Metrics {
        public final AtomicLong totalRequests = new AtomicLong();
        public final AtomicLong cacheHits     = new AtomicLong();
        public final AtomicLong retries       = new AtomicLong();
        public final AtomicLong errors        = new AtomicLong();
        public final AtomicLong totalMs       = new AtomicLong();

        public double avgLatencyMs() {
            long n = totalRequests.get();
            return n == 0 ? 0.0 : (double) totalMs.get() / n;
        }
        public double cacheHitRate() {
            long n = totalRequests.get();
            return n == 0 ? 0.0 : 100.0 * cacheHits.get() / n;
        }

        @Override public String toString() {
            return String.format("Metrics{requests=%d, cacheHits=%d (%.1f%%), retries=%d, errors=%d, avgLatency=%.1fms}",
                totalRequests.get(), cacheHits.get(), cacheHitRate(), retries.get(), errors.get(), avgLatencyMs());
        }
    }

    static class FallbackProvider implements Provider {
        private final List<Provider> providers;
        FallbackProvider(List<Provider> p) { this.providers = p; }

        @Override
        public CompletableFuture<RpcModels.RpcResponse> send(RpcModels.RpcRequest req) {
            return tryNext(req, 0);
        }
        private static final Logger fallbackLog = Logger.getLogger(FallbackProvider.class.getName());

        private CompletableFuture<RpcModels.RpcResponse> tryNext(RpcModels.RpcRequest req, int i) {
            if (i >= providers.size())
                return CompletableFuture.failedFuture(new EthException("All providers failed: " + req.method));
            return providers.get(i).send(req)
                    .exceptionally(ex -> {
                        fallbackLog.warning("Provider " + i + " failed for " + req.method + ": " + ex.getMessage());
                        return null;
                    })
                    .thenCompose(r -> r != null && !r.hasError()
                            ? CompletableFuture.completedFuture(r)
                            : tryNext(req, i + 1));
        }
        @Override public ObjectMapper getObjectMapper() { return providers.get(0).getObjectMapper(); }
        @Override public void close() { providers.forEach(Provider::close); }
    }
}
