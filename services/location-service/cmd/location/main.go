// location-service: courier proximity on Redis GEO.
//
// Redis GEO is geohash-in-a-sorted-set: GEOADD encodes (lon,lat) into a
// 52-bit geohash stored as a ZSET score; GEOSEARCH walks the neighbor cells
// and filters by true distance. Same trick as Alex Xu's proximity chapter,
// productionized by Redis.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"
)

const geoKey = "courier:locations"

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	port := envOr("LOCATION_PORT", "8093")
	rdb := redis.NewClient(&redis.Options{Addr: envOr("LOCATION_REDIS_ADDR", "localhost:6380")})
	defer rdb.Close()

	mux := http.NewServeMux()

	// POST /api/v1/location/{courier}  {"lat": .., "lon": ..}
	mux.HandleFunc("POST /api/v1/location/{courier}", func(w http.ResponseWriter, r *http.Request) {
		var body struct{ Lat, Lon float64 }
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, `{"error":"bad body"}`, 400)
			return
		}
		courier := r.PathValue("courier")
		if err := rdb.GeoAdd(r.Context(), geoKey, &redis.GeoLocation{
			Name: courier, Longitude: body.Lon, Latitude: body.Lat,
		}).Err(); err != nil {
			http.Error(w, `{"error":"redis"}`, 500)
			return
		}
		// Liveness TTL per courier: location without heartbeat goes stale.
		rdb.Set(r.Context(), "courier:alive:"+courier, "1", 2*time.Minute)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"courier": courier, "ok": true})
	})

	// GET /api/v1/nearby?lat=..&lon=..&radius_m=3000&limit=5
	mux.HandleFunc("GET /api/v1/nearby", func(w http.ResponseWriter, r *http.Request) {
		lat, _ := strconv.ParseFloat(r.URL.Query().Get("lat"), 64)
		lon, _ := strconv.ParseFloat(r.URL.Query().Get("lon"), 64)
		radius, _ := strconv.ParseFloat(r.URL.Query().Get("radius_m"), 64)
		if radius == 0 {
			radius = 3000
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit == 0 {
			limit = 5
		}
		start := time.Now()
		locs, err := rdb.GeoSearchLocation(r.Context(), geoKey, &redis.GeoSearchLocationQuery{
			GeoSearchQuery: redis.GeoSearchQuery{
				Longitude: lon, Latitude: lat,
				Radius: radius, RadiusUnit: "m",
				Sort: "ASC", Count: limit,
			},
			WithCoord: true, WithDist: true,
		}).Result()
		if err != nil {
			http.Error(w, `{"error":"redis"}`, 500)
			return
		}
		type hit struct {
			Courier string  `json:"courier"`
			DistM   float64 `json:"dist_m"`
			Lat     float64 `json:"lat"`
			Lon     float64 `json:"lon"`
		}
		hits := make([]hit, 0, len(locs))
		for _, l := range locs {
			hits = append(hits, hit{l.Name, l.Dist, l.Latitude, l.Longitude})
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"nearby": hits, "took_us": time.Since(start).Microseconds(),
		})
	})

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := rdb.Ping(r.Context()).Err(); err != nil {
			http.Error(w, `{"status":"down"}`, 503)
			return
		}
		w.Write([]byte(`{"status":"up"}`))
	})

	srv := &http.Server{Addr: ":" + port, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		slog.Info("location-service listening", "port", port)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("serve", "err", err)
			stop()
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

func envOr(k, f string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return f
}
