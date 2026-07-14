// rlcheck is a tiny demo client: fires N Check calls for one key and prints
// the allow/deny transition — the ≤5-command README demo without grpcurl.
//
//	go run ./cmd/rlcheck -n 120 -key demo-user
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/sahanurmondal/agora/services/rate-limiter/gen/ratelimitpb"
)

func main() {
	addr := flag.String("addr", "localhost:9095", "rate-limiter gRPC address")
	key := flag.String("key", "rlcheck-demo", "limit key (client id)")
	rule := flag.String("rule", "default", "rule name")
	n := flag.Int("n", 120, "number of Check calls to fire back-to-back")
	flag.Parse()

	conn, err := grpc.NewClient(*addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		fmt.Fprintln(os.Stderr, "connect:", err)
		os.Exit(1)
	}
	defer conn.Close()
	client := ratelimitpb.NewRateLimiterClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	allowed, denied := 0, 0
	var firstDeny *ratelimitpb.CheckResponse
	var firstDenyAt int
	for i := 1; i <= *n; i++ {
		resp, err := client.Check(ctx, &ratelimitpb.CheckRequest{Key: *key, Rule: *rule})
		if err != nil {
			fmt.Fprintf(os.Stderr, "call %d: %v\n", i, err)
			os.Exit(1)
		}
		if resp.GetAllowed() {
			allowed++
		} else {
			denied++
			if firstDeny == nil {
				firstDeny = resp
				firstDenyAt = i
			}
		}
		if i <= 3 || !resp.GetAllowed() && denied <= 3 {
			fmt.Printf("call %3d: allowed=%-5v remaining=%-4d retry_after_ms=%-5d algorithm=%s\n",
				i, resp.GetAllowed(), resp.GetRemaining(), resp.GetRetryAfterMs(), resp.GetAlgorithm())
		}
	}

	fmt.Printf("\n%d calls for key=%q: %d allowed, %d denied\n", *n, *key, allowed, denied)
	if firstDeny != nil {
		fmt.Printf("first denial at call %d with retry_after_ms=%d\n", firstDenyAt, firstDeny.GetRetryAfterMs())
	}
}
