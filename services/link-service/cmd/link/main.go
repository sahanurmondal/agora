package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"

	"github.com/sahanurmondal/agora/services/link-service/internal/api"
	"github.com/sahanurmondal/agora/services/link-service/internal/ids"
	"github.com/sahanurmondal/agora/services/link-service/internal/store"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	port := envOr("LINK_PORT", "8081")
	dbURL := envOr("LINK_DB_URL", "postgres://agora:agora_local@localhost:5432/linkdb?sslmode=disable")
	redisAddr := envOr("LINK_REDIS_ADDR", "localhost:6379")
	machineID, _ := strconv.ParseInt(envOr("LINK_MACHINE_ID", "1"), 10, 64)

	shutdownTracer := initTracer(ctx)
	defer shutdownTracer()

	db, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		slog.Error("postgres connect", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	rdb := redis.NewClient(&redis.Options{Addr: redisAddr})
	defer rdb.Close()

	st := store.New(db, rdb)
	if err := st.Migrate(ctx); err != nil {
		slog.Error("migrate", "err", err)
		os.Exit(1)
	}

	snow, err := ids.NewSnowflake(machineID)
	if err != nil {
		slog.Error("snowflake", "err", err)
		os.Exit(1)
	}

	mux := http.NewServeMux()
	api.New(st, snow, "http://localhost:"+port).Register(mux)

	// X-Version lets canary/blue-green demos count which track served a
	// request; the value comes from env so one image serves every track.
	version := envOr("VERSION", "stable")
	versioned := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Version", version)
		mux.ServeHTTP(w, r)
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           otelhttp.NewHandler(versioned, "link-service"),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		slog.Info("link-service listening", "port", port)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("serve", "err", err)
			stop()
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
	slog.Info("link-service stopped")
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
		semconv.ServiceName("link-service"),
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
