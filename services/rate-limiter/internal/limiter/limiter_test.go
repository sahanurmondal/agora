package limiter

import (
	"context"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

// newTestLimiter wires a Limiter to an in-process miniredis and replaces the
// clock with a manually advanced one, so refill math is fully deterministic.
func newTestLimiter(t *testing.T, cfg Config) (*Limiter, *int64) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })

	lim, err := New(rdb, cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	now := int64(1_700_000_000_000) // arbitrary fixed epoch ms
	lim.nowMs = func() int64 { return now }
	return lim, &now
}

func TestTokenBucketRefillMath(t *testing.T) {
	// Each step either advances the injected clock or fires one Check;
	// expectations follow the lazy-refill formula:
	//   tokens = min(burst, tokens + elapsed_ms*rate/1000)
	type step struct {
		advanceMs     int64
		wantAllowed   bool
		wantRemaining int64
		wantRetryMs   int64
	}
	tests := []struct {
		name  string
		rate  float64
		burst int64
		steps []step
	}{
		{
			name: "fresh key starts with full burst", rate: 5, burst: 10,
			steps: []step{
				{wantAllowed: true, wantRemaining: 9},
				{wantAllowed: true, wantRemaining: 8},
			},
		},
		{
			name: "burst exhaustion denies with refill-based retry_after", rate: 5, burst: 3,
			steps: []step{
				{wantAllowed: true, wantRemaining: 2},
				{wantAllowed: true, wantRemaining: 1},
				{wantAllowed: true, wantRemaining: 0},
				// Empty: 1 token refills in 1000/5 = 200ms.
				{wantAllowed: false, wantRemaining: 0, wantRetryMs: 200},
			},
		},
		{
			name: "elapsed time refills exactly rate*dt tokens", rate: 5, burst: 10,
			steps: []step{
				// Drain 10.
				{wantAllowed: true, wantRemaining: 9},
				{wantAllowed: true, wantRemaining: 8},
				{wantAllowed: true, wantRemaining: 7},
				{wantAllowed: true, wantRemaining: 6},
				{wantAllowed: true, wantRemaining: 5},
				{wantAllowed: true, wantRemaining: 4},
				{wantAllowed: true, wantRemaining: 3},
				{wantAllowed: true, wantRemaining: 2},
				{wantAllowed: true, wantRemaining: 1},
				{wantAllowed: true, wantRemaining: 0},
				// 600ms * 5/s = 3 tokens back; spend 1, floor(2) remain.
				{advanceMs: 600, wantAllowed: true, wantRemaining: 2},
			},
		},
		{
			name: "refill caps at burst capacity", rate: 100, burst: 5,
			steps: []step{
				{wantAllowed: true, wantRemaining: 4},
				// An hour refills 360k tokens; cap = burst 5. Spend 1 → 4.
				{advanceMs: 3_600_000, wantAllowed: true, wantRemaining: 4},
			},
		},
		{
			name: "partial refill below one token still denies", rate: 10, burst: 1,
			steps: []step{
				{wantAllowed: true, wantRemaining: 0},
				// 50ms * 10/s = 0.5 tokens: not enough. Deficit 0.5 → 50ms.
				{advanceMs: 50, wantAllowed: false, wantRemaining: 0, wantRetryMs: 50},
				// Another 50ms completes the token.
				{advanceMs: 50, wantAllowed: true, wantRemaining: 0},
			},
		},
		{
			name: "fractional rate", rate: 0.5, burst: 1,
			steps: []step{
				{wantAllowed: true, wantRemaining: 0},
				// 1 token at 0.5/s = 2000ms away.
				{wantAllowed: false, wantRemaining: 0, wantRetryMs: 2000},
				{advanceMs: 2000, wantAllowed: true, wantRemaining: 0},
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			lim, now := newTestLimiter(t, Config{
				Algorithm: AlgTokenBucket, Rate: tc.rate, Burst: tc.burst,
			})
			for i, s := range tc.steps {
				*now += s.advanceMs
				d, err := lim.Check(context.Background(), "k", "r")
				if err != nil {
					t.Fatalf("step %d: Check: %v", i, err)
				}
				if d.Allowed != s.wantAllowed {
					t.Errorf("step %d: allowed = %v, want %v", i, d.Allowed, s.wantAllowed)
				}
				if d.Remaining != s.wantRemaining {
					t.Errorf("step %d: remaining = %d, want %d", i, d.Remaining, s.wantRemaining)
				}
				if s.wantRetryMs > 0 && d.RetryAfterMs != s.wantRetryMs {
					t.Errorf("step %d: retry_after_ms = %d, want %d", i, d.RetryAfterMs, s.wantRetryMs)
				}
				if !d.Allowed && d.RetryAfterMs <= 0 {
					t.Errorf("step %d: denied but retry_after_ms = %d, want > 0", i, d.RetryAfterMs)
				}
				if d.Algorithm != AlgTokenBucket {
					t.Errorf("step %d: algorithm = %q", i, d.Algorithm)
				}
			}
		})
	}
}

func TestTokenBucketKeysAreIndependent(t *testing.T) {
	lim, _ := newTestLimiter(t, Config{Algorithm: AlgTokenBucket, Rate: 1, Burst: 1})
	ctx := context.Background()

	if d, _ := lim.Check(ctx, "alice", "r"); !d.Allowed {
		t.Fatal("alice's first request should pass")
	}
	if d, _ := lim.Check(ctx, "alice", "r"); d.Allowed {
		t.Fatal("alice's second request should be limited")
	}
	if d, _ := lim.Check(ctx, "bob", "r"); !d.Allowed {
		t.Fatal("bob must not be affected by alice's bucket")
	}
	// Same key under a different rule = separate bucket.
	if d, _ := lim.Check(ctx, "alice", "other-rule"); !d.Allowed {
		t.Fatal("alice under another rule must have her own bucket")
	}
}

func TestSlidingWindowLimitAndSlide(t *testing.T) {
	lim, now := newTestLimiter(t, Config{
		Algorithm: AlgSlidingWindow, Rate: 3, WindowMs: 1000,
	})
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		d, err := lim.Check(ctx, "k", "r")
		if err != nil {
			t.Fatalf("Check %d: %v", i, err)
		}
		if !d.Allowed {
			t.Fatalf("request %d should be allowed", i+1)
		}
		if want := int64(3 - i - 1); d.Remaining != want {
			t.Errorf("request %d: remaining = %d, want %d", i+1, d.Remaining, want)
		}
	}

	// 4th within the same window: denied; oldest entry ages out in 1000ms.
	d, err := lim.Check(ctx, "k", "r")
	if err != nil {
		t.Fatal(err)
	}
	if d.Allowed {
		t.Fatal("4th request in window should be denied")
	}
	if d.RetryAfterMs != 1000 {
		t.Errorf("retry_after_ms = %d, want 1000 (oldest entry age-out)", d.RetryAfterMs)
	}

	// Slide 400ms: all 3 timestamps still inside the 1s window → still denied.
	*now += 400
	if d, _ := lim.Check(ctx, "k", "r"); d.Allowed {
		t.Fatal("still 3 requests in trailing window, should deny")
	}

	// Slide past the first timestamp (601ms more → 1001ms elapsed) → one slot free.
	*now += 601
	if d, _ := lim.Check(ctx, "k", "r"); !d.Allowed {
		t.Fatal("oldest entry left the window, should allow")
	}
}

func TestConfigValidation(t *testing.T) {
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })

	for _, cfg := range []Config{
		{Algorithm: "leaky_bucket", Rate: 1, Burst: 1},          // unknown algorithm
		{Algorithm: AlgTokenBucket, Rate: 0, Burst: 1},          // zero rate
		{Algorithm: AlgTokenBucket, Rate: 1, Burst: 0},          // zero burst
		{Algorithm: AlgSlidingWindow, Rate: 1, WindowMs: 0},     // zero window
	} {
		if _, err := New(rdb, cfg); err == nil {
			t.Errorf("New(%+v): expected error, got nil", cfg)
		}
	}
}
