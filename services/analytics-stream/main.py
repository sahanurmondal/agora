"""analytics-stream: the ad-click-aggregation chapter, applied to orders.

Consumes the order outbox topic and maintains:
- tumbling 10s windows keyed by EVENT time (not processing time)
- a watermark = max_event_ts - 5s; windows older than the watermark are
  sealed. Events landing behind the watermark are LATE: within 30s allowed
  lateness they still merge (window reopens as 'corrected'), beyond that
  they're counted as dropped_late — the lambda-reconciliation talking point.
- HyperLogLog unique buyers per window (Redis PFADD — 12KB per counter
  regardless of cardinality)
- all-time revenue leaderboard (Redis ZINCRBY by buyer)

Read API (FastAPI :8094): /windows /uniques/{w} /leaderboard /stats
"""
import json
import os
import threading
import time
from collections import defaultdict

import redis as redislib
import uvicorn
from confluent_kafka import Consumer
from fastapi import FastAPI

WINDOW_MS = 10_000
WATERMARK_LAG_MS = 5_000
ALLOWED_LATENESS_MS = 30_000

r = redislib.Redis(host=os.getenv("REDIS_HOST", "localhost"),
                   port=int(os.getenv("REDIS_PORT", "6380")), decode_responses=True)

state = {
    "windows": defaultdict(lambda: {"orders": 0, "revenue_cents": 0, "late_merged": 0}),
    "sealed": set(),
    "max_event_ts": 0,
    "dropped_late": 0,
    "consumed": 0,
}
lock = threading.Lock()


def window_of(ts_ms: int) -> int:
    return ts_ms - (ts_ms % WINDOW_MS)


def consume_loop():
    consumer = Consumer({
        "bootstrap.servers": os.getenv("KAFKA_BOOTSTRAP", "localhost:19092"),
        "group.id": "analytics-stream",
        "auto.offset.reset": "earliest",
    })
    consumer.subscribe([os.getenv("ANALYTICS_TOPIC", "orders.public.outbox")])
    while True:
        msg = consumer.poll(1.0)
        if msg is None or msg.error():
            continue
        try:
            after = json.loads(msg.value()).get("payload", {}).get("after")
            if not after or after.get("event_type") != "OrderConfirmed":
                continue
            payload = json.loads(after["payload"])
            # Debezium JSON converter: TIMESTAMPTZ arrives as ISO-8601 string
            # (e.g. "2026-07-14T17:00:00.123456Z"); micros-int if schema differs.
            raw_ts = after.get("created_at", 0)
            if isinstance(raw_ts, str):
                from datetime import datetime
                event_ts = int(datetime.fromisoformat(raw_ts.replace("Z", "+00:00")).timestamp() * 1000)
            else:
                event_ts = int(raw_ts / 1000) if raw_ts > 10**14 else int(raw_ts)
            w = window_of(event_ts)
            with lock:
                state["consumed"] += 1
                state["max_event_ts"] = max(state["max_event_ts"], event_ts)
                watermark = state["max_event_ts"] - WATERMARK_LAG_MS
                is_late = w + WINDOW_MS < watermark
                if is_late and (watermark - (w + WINDOW_MS)) > ALLOWED_LATENESS_MS:
                    state["dropped_late"] += 1
                    continue
                win = state["windows"][w]
                win["orders"] += 1
                win["revenue_cents"] += payload.get("amountCents", 0)
                if is_late:
                    win["late_merged"] += 1
                for sealed_w in [k for k in state["windows"] if k + WINDOW_MS < watermark]:
                    state["sealed"].add(sealed_w)
            r.pfadd(f"analytics:uniques:{w}", payload.get("buyerId", "?"))
            r.zincrby("analytics:leaderboard:spend", payload.get("amountCents", 0),
                      payload.get("buyerId", "?"))
        except Exception as e:  # poison tolerance: log & move on
            print("skip bad message:", e)


app = FastAPI(title="analytics-stream")


@app.get("/api/v1/analytics/windows")
def windows():
    with lock:
        return {
            "watermark": state["max_event_ts"] - WATERMARK_LAG_MS,
            "dropped_late": state["dropped_late"],
            "windows": [
                {"window_start": k, "sealed": k in state["sealed"], **v}
                for k, v in sorted(state["windows"].items())
            ][-20:],
        }


@app.get("/api/v1/analytics/uniques/{window_start}")
def uniques(window_start: int):
    return {"window_start": window_start,
            "unique_buyers_hll": r.pfcount(f"analytics:uniques:{window_start}")}


@app.get("/api/v1/analytics/leaderboard")
def leaderboard(top: int = 10):
    rows = r.zrevrange("analytics:leaderboard:spend", 0, top - 1, withscores=True)
    return {"top_spenders": [{"buyer": b, "spend_cents": int(s)} for b, s in rows]}


@app.get("/healthz")
def healthz():
    return {"status": "up", "consumed": state["consumed"]}


if __name__ == "__main__":
    threading.Thread(target=consume_loop, daemon=True).start()
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("ANALYTICS_PORT", "8094")))
