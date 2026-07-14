-- Token bucket, atomic check-and-update.
--
-- KEYS[1] = bucket state (Redis hash: tokens, last_refill_ms)
-- ARGV[1] = rate  (tokens per second, may be fractional)
-- ARGV[2] = burst (bucket capacity)
-- ARGV[3] = now   (unix ms — passed in so the script is deterministic/testable)
-- ARGV[4] = cost  (tokens this request consumes, normally 1)
--
-- Returns {allowed(0|1), remaining, retry_after_ms}.
--
-- Refill is lazy: instead of a background ticker per key, each call credits
-- elapsed_ms * rate / 1000 tokens, capped at burst. Read+refill+spend happen
-- inside one EVAL, so concurrent callers can never both spend the last token.

local key   = KEYS[1]
local rate  = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local now   = tonumber(ARGV[3])
local cost  = tonumber(ARGV[4])

local state  = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local tokens = tonumber(state[1])
local last   = tonumber(state[2])

if tokens == nil or last == nil then
  -- New key: bucket starts full (burst tolerance is the point of the algorithm).
  tokens = burst
  last = now
end

-- Lazy refill from elapsed wall time. Clamp negative elapsed (clock skew
-- between callers' clocks is impossible here — `now` comes from one server
-- process — but defend anyway).
local elapsed = now - last
if elapsed > 0 then
  tokens = math.min(burst, tokens + (elapsed * rate / 1000.0))
  last = now
end

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
  allowed = 1
  tokens = tokens - cost
else
  -- Time until the deficit refills at `rate` tokens/sec.
  retry_after_ms = math.ceil((cost - tokens) * 1000.0 / rate)
end

redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', last)

-- Idle keys self-clean: TTL = time to refill an empty bucket, doubled, >= 1s.
local ttl_ms = math.max(1000, math.ceil(burst * 1000.0 / rate) * 2)
redis.call('PEXPIRE', key, ttl_ms)

-- Lua->Redis converts numbers to integers by truncation; floor explicitly.
return {allowed, math.floor(tokens), retry_after_ms}
