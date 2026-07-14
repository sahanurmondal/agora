// Package server adapts the limiter to the gRPC contract in
// contracts/proto/ratelimit.proto.
package server

import (
	"context"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/sahanurmondal/agora/services/rate-limiter/gen/ratelimitpb"
	"github.com/sahanurmondal/agora/services/rate-limiter/internal/limiter"
)

// RateLimiter serves ratelimit.v1.RateLimiter.
type RateLimiter struct {
	ratelimitpb.UnimplementedRateLimiterServer
	lim *limiter.Limiter
}

func New(lim *limiter.Limiter) *RateLimiter {
	return &RateLimiter{lim: lim}
}

// Check is called by edge-gateway on every routed request under a hard 50ms
// deadline. Contract: if Redis is unreachable we return UNAVAILABLE fast and
// the GATEWAY fails open — availability policy belongs to the caller, not
// here (see ratelimit.proto).
func (s *RateLimiter) Check(ctx context.Context, req *ratelimitpb.CheckRequest) (*ratelimitpb.CheckResponse, error) {
	if req.GetKey() == "" {
		return nil, status.Error(codes.InvalidArgument, "key must not be empty")
	}
	rule := req.GetRule()
	if rule == "" {
		rule = "default"
	}

	d, err := s.lim.Check(ctx, req.GetKey(), rule)
	if err != nil {
		return nil, status.Errorf(codes.Unavailable, "rate limit backend: %v", err)
	}
	return &ratelimitpb.CheckResponse{
		Allowed:      d.Allowed,
		Remaining:    d.Remaining,
		RetryAfterMs: d.RetryAfterMs,
		Algorithm:    d.Algorithm,
	}, nil
}
