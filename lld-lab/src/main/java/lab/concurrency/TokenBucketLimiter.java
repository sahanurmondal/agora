package lab.concurrency;

import java.util.function.LongSupplier;

/**
 * Exercise 04 — token bucket, single node.
 *
 * Traps: (1) the read-refill-decide-write sequence must be atomic — here one
 * monitor; the distributed twin (services/rate-limiter) gets the same
 * atomicity from a Redis Lua script. (2) Wall-clock (currentTimeMillis) steps
 * backwards under NTP — use a monotonic source (nanoTime), injected as a
 * LongSupplier so tests are deterministic. (3) Lazy refill beats a background
 * refill thread: no scheduler, no drift, refill computed from elapsed time at
 * acquire.
 */
public final class TokenBucketLimiter {

    private final double ratePerSec;
    private final double burst;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketLimiter(double ratePerSec, double burst, LongSupplier nanoClock) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.nanoClock = nanoClock;
        this.tokens = burst; // start full: allow an initial burst
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public TokenBucketLimiter(double ratePerSec, double burst) {
        this(ratePerSec, burst, System::nanoTime);
    }

    /** @return true if a token was taken; false = rate limited. */
    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(burst, tokens + elapsedSec * ratePerSec);
            lastRefillNanos = now;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized double availableTokens() {
        return tokens;
    }
}
