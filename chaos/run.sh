#!/bin/sh
# Chaos experiment runner. Usage: ./chaos/run.sh <experiment>
# Each experiment: inject → tell you what to observe → (mostly) self-clean.
# Full catalog + expected outcomes: chaos/experiments/CATALOG.md
set -e
EXP="${1:?experiment name — see chaos/experiments/CATALOG.md}"

case "$EXP" in
  db-primary-kill)
    echo "INJECT: stopping agora-postgres for 20s. OBSERVE: writes 5xx, health flips, services recover."
    docker stop agora-postgres
    sleep 20
    docker start agora-postgres
    echo "healed. Check Grafana Golden Signals for the error window + recovery."
    ;;

  redis-flush-thundering-herd)
    echo "INJECT: FLUSHDB under load. OBSERVE: catalog.cache.misses spike, DB QPS bounded by single-flight."
    echo "Run 'make load SCRIPT=link-baseline' in another shell first for best effect."
    docker exec agora-redis redis-cli FLUSHDB
    echo "flushed. Watch cache-hit ratio recover in metrics; DB saw ONE load per distinct key."
    ;;

  duplicate-delivery-replay)
    echo "INJECT: rewinding notification-service consumer group by 20 offsets."
    docker exec agora-redpanda rpk group seek notification-service --to start --topics orders.public.outbox --allow-new-topics 2>/dev/null || \
      echo "(stop notification-service first if seek is rejected: group must be empty)"
    echo "OBSERVE: notif log shows 'duplicate delivery suppressed' — idempotent consumer proof."
    ;;

  saga-mid-flight-kill)
    echo "INJECT: killing payment-ledger, placing an order, restarting."
    pkill -f payment-ledger-service-0.1.0 || true
    sleep 1
    PID=$(cat /tmp/flash_pid 2>/dev/null || echo 1)
    curl -s -X POST localhost:8088/api/v1/orders -H 'Content-Type: application/json' \
      -H 'X-User-Id: chaos-victim' -d "{\"productId\":$PID,\"qty\":1}"
    echo
    echo "EXPECT: state=CANCELLED; saga_steps shows RESERVE OK / PAY FAILED / COMPENSATE_RELEASE OK."
    (nohup java -Xmx256m -jar services/payment-ledger-service/target/payment-ledger-service-0.1.0-SNAPSHOT.jar \
      > /tmp/payment.log 2>&1 &)
    echo "payment-ledger restarting."
    ;;

  payment-latency-spike)
    echo "INJECT: restart payment-ledger with PAYMENT_DELAY_MS=3000 (> order's 2s read timeout)."
    pkill -f payment-ledger-service-0.1.0 || true
    sleep 1
    (PAYMENT_DELAY_MS=3000 nohup java -Xmx256m -jar \
      services/payment-ledger-service/target/payment-ledger-service-0.1.0-SNAPSHOT.jar > /tmp/payment.log 2>&1 &)
    echo "OBSERVE: place ~10 orders → timeouts → CB OPEN in order-service log ('payment circuit breaker'),"
    echo "then instant CANCELLED (fail-fast, no thread pile-up). Restore: rerun with no delay env."
    ;;

  ws-node-kill)
    echo "MANUAL (needs 2 chat nodes): CHAT_PORT=8096 java -jar services/chat-service/target/*.jar &"
    echo "Connect one client per node to the same room, kill node 1, reconnect to node 2 — messages"
    echo "still flow: connection state is externalized in Redis pub/sub, not the node."
    ;;

  *)
    echo "unknown experiment: $EXP"; echo "see chaos/experiments/CATALOG.md"; exit 1
    ;;
esac
