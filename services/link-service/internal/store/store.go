package store

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

var ErrNotFound = errors.New("link not found")

var tracer = otel.Tracer("link-service/store")

const (
	cacheKeyPrefix = "link:"
	cacheTTL       = time.Hour
	// ttlJitter spreads expirations so a burst of inserts doesn't expire as a
	// burst of misses (mini thundering-herd defense; the full treatment lives
	// in catalog-service).
	ttlJitterFrac = 0.10
)

type Store struct {
	db  *pgxpool.Pool
	rdb *redis.Client
}

func New(db *pgxpool.Pool, rdb *redis.Client) *Store {
	return &Store{db: db, rdb: rdb}
}

func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.db.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS links (
			id         BIGINT PRIMARY KEY,
			code       TEXT NOT NULL UNIQUE,
			url        TEXT NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`)
	return err
}

func (s *Store) Create(ctx context.Context, id int64, code, url string) error {
	ctx, span := tracer.Start(ctx, "store.Create")
	defer span.End()
	_, err := s.db.Exec(ctx,
		`INSERT INTO links (id, code, url) VALUES ($1, $2, $3)`, id, code, url)
	return err
}

// GetURL is the cache-aside read path: Redis first, Postgres on miss, then
// populate Redis with a jittered TTL.
func (s *Store) GetURL(ctx context.Context, code string) (url string, cacheHit bool, err error) {
	ctx, span := tracer.Start(ctx, "store.GetURL")
	defer span.End()

	key := cacheKeyPrefix + code
	if v, err := s.rdb.Get(ctx, key).Result(); err == nil {
		span.SetAttributes(attribute.Bool("cache.hit", true))
		return v, true, nil
	}
	span.SetAttributes(attribute.Bool("cache.hit", false))

	err = s.db.QueryRow(ctx, `SELECT url FROM links WHERE code = $1`, code).Scan(&url)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, ErrNotFound
	}
	if err != nil {
		return "", false, fmt.Errorf("query link: %w", err)
	}

	jitter := time.Duration(rand.Int63n(int64(float64(cacheTTL) * ttlJitterFrac)))
	if err := s.rdb.Set(ctx, key, url, cacheTTL+jitter).Err(); err != nil {
		// Cache population failure must not fail the read.
		span.RecordError(err)
	}
	return url, false, nil
}

func (s *Store) Healthy(ctx context.Context) error {
	if err := s.db.Ping(ctx); err != nil {
		return fmt.Errorf("postgres: %w", err)
	}
	if err := s.rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("redis: %w", err)
	}
	return nil
}
