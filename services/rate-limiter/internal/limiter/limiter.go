// Package limiter implements distributed rate limiting over Redis. Both
// algorithms live in Lua scripts executed server-side by Redis: the
// read-decide-write cycle is a single atomic EVAL, so N service replicas can
// share one limit with no race between check and update (the reason app-side
// GET-then-SET or WATCH/MULTI were rejected — see ADR-001).
package limiter

import (
	"context"
	"fmt"
	"strconv"
	"sync/atomic"
	"time"

	_ "embed"

	"github.com/redis/go-redis/v9"
)

// Algorithm names, as selected by RL_ALGORITHM and echoed in CheckResponse.
const (
	AlgTokenBucket   = "token_bucket"
	AlgSlidingWindow = "sliding_window"
)

//go:embed token_bucket.lua
var tokenBucketSrc string

//go:embed sliding_window.lua
var slidingWindowSrc string

// Decision is the outcome of one rate-limit check.
type Decision struct {
	Allowed      bool
	Remaining    int64
	RetryAfterMs int64
	Algorithm    string
}

// Config carries the single rule this instance enforces (per-rule config
// arrives with flagd integration; the wire contract already carries `rule`).
type Config struct {
	Algorithm string  // AlgTokenBucket | AlgSlidingWindow
	Rate      float64 // tokens/sec (bucket) or requests/window (sliding)
	Burst     int64   // bucket capacity; unused by sliding window
	WindowMs  int64   // sliding window length; unused by token bucket
}

// Limiter evaluates rate-limit checks against Redis.
type Limiter struct {
	rdb redis.Scripter
	cfg Config

	tokenBucket   *redis.Script // EVALSHA with EVAL fallback, handled by go-redis
	slidingWindow *redis.Script

	// nowMs returns the current unix time in milliseconds. A field (not
	// time.Now inline) so tests inject a deterministic clock; the Lua
	// scripts take `now` as an argument for the same reason.
	nowMs func() int64

	seq atomic.Int64 // uniquifies sorted-set members within one millisecond
}

// New builds a Limiter. cfg.Algorithm must be one of the Alg* constants.
func New(rdb redis.Scripter, cfg Config) (*Limiter, error) {
	if cfg.Algorithm != AlgTokenBucket && cfg.Algorithm != AlgSlidingWindow {
		return nil, fmt.Errorf("unknown algorithm %q (want %s|%s)", cfg.Algorithm, AlgTokenBucket, AlgSlidingWindow)
	}
	if cfg.Rate <= 0 {
		return nil, fmt.Errorf("rate must be > 0, got %v", cfg.Rate)
	}
	if cfg.Algorithm == AlgTokenBucket && cfg.Burst <= 0 {
		return nil, fmt.Errorf("burst must be > 0, got %d", cfg.Burst)
	}
	if cfg.Algorithm == AlgSlidingWindow && cfg.WindowMs <= 0 {
		return nil, fmt.Errorf("window must be > 0, got %dms", cfg.WindowMs)
	}
	return &Limiter{
		rdb:           rdb,
		cfg:           cfg,
		tokenBucket:   redis.NewScript(tokenBucketSrc),
		slidingWindow: redis.NewScript(slidingWindowSrc),
		nowMs:         func() int64 { return time.Now().UnixMilli() },
	}, nil
}

// Check runs one atomic check-and-update for (key, rule). Any Redis error is
// returned as-is: this service fails FAST, the gateway decides to fail open.
func (l *Limiter) Check(ctx context.Context, key, rule string) (Decision, error) {
	now := l.nowMs()
	redisKey := fmt.Sprintf("rl:%s:%s:%s", l.cfg.Algorithm, rule, key)

	var res interface{}
	var err error
	switch l.cfg.Algorithm {
	case AlgTokenBucket:
		res, err = l.tokenBucket.Run(ctx, l.rdb, []string{redisKey},
			l.cfg.Rate, l.cfg.Burst, now, 1).Result()
	case AlgSlidingWindow:
		member := strconv.FormatInt(now, 10) + "-" + strconv.FormatInt(l.seq.Add(1), 10)
		res, err = l.slidingWindow.Run(ctx, l.rdb, []string{redisKey},
			l.cfg.Rate, l.cfg.WindowMs, now, member).Result()
	}
	if err != nil {
		return Decision{}, fmt.Errorf("redis eval: %w", err)
	}

	triple, ok := res.([]interface{})
	if !ok || len(triple) != 3 {
		return Decision{}, fmt.Errorf("unexpected script reply %T %v", res, res)
	}
	allowed, _ := triple[0].(int64)
	remaining, _ := triple[1].(int64)
	retryAfter, _ := triple[2].(int64)
	return Decision{
		Allowed:      allowed == 1,
		Remaining:    remaining,
		RetryAfterMs: retryAfter,
		Algorithm:    l.cfg.Algorithm,
	}, nil
}
