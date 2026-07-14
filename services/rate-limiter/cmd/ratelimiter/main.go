package main

import (
	"context"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"

	"github.com/sahanurmondal/agora/services/rate-limiter/gen/ratelimitpb"
	"github.com/sahanurmondal/agora/services/rate-limiter/internal/limiter"
	"github.com/sahanurmondal/agora/services/rate-limiter/internal/server"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	port := envOr("RATELIMIT_GRPC_PORT", "9095")
	redisAddr := envOr("RL_REDIS_ADDR", "localhost:6380") // host port of agora-redis (6379 is a local Homebrew Redis)
	algorithm := envOr("RL_ALGORITHM", limiter.AlgTokenBucket)
	rate, err := strconv.ParseFloat(envOr("RL_RATE", "50"), 64)
	if err != nil {
		slog.Error("bad RL_RATE", "err", err)
		os.Exit(1)
	}
	burst, err := strconv.ParseInt(envOr("RL_BURST", "100"), 10, 64)
	if err != nil {
		slog.Error("bad RL_BURST", "err", err)
		os.Exit(1)
	}

	shutdownTracer := initTracer(ctx)
	defer shutdownTracer()

	rdb := redis.NewClient(&redis.Options{Addr: redisAddr})
	defer rdb.Close()

	lim, err := limiter.New(rdb, limiter.Config{
		Algorithm: algorithm,
		Rate:      rate,
		Burst:     burst,
		WindowMs:  1000, // sliding window is fixed at 1s; RL_RATE is then the per-second limit
	})
	if err != nil {
		slog.Error("limiter config", "err", err)
		os.Exit(1)
	}

	lis, err := net.Listen("tcp", ":"+port)
	if err != nil {
		slog.Error("listen", "err", err)
		os.Exit(1)
	}

	grpcServer := grpc.NewServer(
		grpc.StatsHandler(otelgrpc.NewServerHandler()),
	)
	ratelimitpb.RegisterRateLimiterServer(grpcServer, server.New(lim))

	// Standard gRPC health service. Overall status tracks Redis reachability
	// so orchestrators/gateway health checks see backend loss early; the
	// Check RPC itself still fails fast per-request regardless.
	healthServer := health.NewServer()
	healthpb.RegisterHealthServer(grpcServer, healthServer)
	setHealth := func(up bool) {
		st := healthpb.HealthCheckResponse_SERVING
		if !up {
			st = healthpb.HealthCheckResponse_NOT_SERVING
		}
		healthServer.SetServingStatus("", st)
		healthServer.SetServingStatus(ratelimitpb.RateLimiter_ServiceDesc.ServiceName, st)
	}
	setHealth(rdb.Ping(ctx).Err() == nil)
	go func() {
		t := time.NewTicker(5 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				pingCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
				setHealth(rdb.Ping(pingCtx).Err() == nil)
				cancel()
			}
		}
	}()

	// Reflection: lets grpcurl/k6 discover the service without proto flags.
	reflection.Register(grpcServer)

	go func() {
		slog.Info("rate-limiter listening", "port", port,
			"algorithm", algorithm, "rate", rate, "burst", burst, "redis", redisAddr)
		if err := grpcServer.Serve(lis); err != nil {
			slog.Error("serve", "err", err)
			stop()
		}
	}()

	<-ctx.Done()
	done := make(chan struct{})
	go func() { grpcServer.GracefulStop(); close(done) }()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		grpcServer.Stop()
	}
	slog.Info("rate-limiter stopped")
}

// initTracer wires OTLP/HTTP export to the local otel-lgtm stack. Tracing is
// best-effort: if the collector is down the service still runs.
func initTracer(ctx context.Context) func() {
	exp, err := otlptracehttp.New(ctx) // honors OTEL_EXPORTER_OTLP_ENDPOINT, defaults to localhost:4318
	if err != nil {
		slog.Warn("otel exporter init failed, tracing disabled", "err", err)
		return func() {}
	}
	res, _ := sdkresource.Merge(sdkresource.Default(), sdkresource.NewWithAttributes(
		semconv.SchemaURL,
		semconv.ServiceName("rate-limiter"),
	))
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{}, propagation.Baggage{},
	))
	return func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = tp.Shutdown(shutdownCtx)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
