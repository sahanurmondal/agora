-- Sliding window log, atomic check-and-update.
--
-- KEYS[1] = window state (sorted set: member = unique request id, score = ms timestamp)
-- ARGV[1] = limit     (max requests per window)
-- ARGV[2] = window_ms (window length)
-- ARGV[3] = now       (unix ms)
-- ARGV[4] = member    (unique id for this request, supplied by the caller)
--
-- Returns {allowed(0|1), remaining, retry_after_ms}.
--
-- Exact algorithm: every allowed request's timestamp is kept for one window,
-- so the count is precise at any instant (no fixed-window boundary burst).
-- Cost: O(limit) memory per key vs the bucket's O(1) — see ADR-001.

local key    = KEYS[1]
local limit  = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now    = tonumber(ARGV[3])
local member = ARGV[4]

-- Drop entries that fell out of the window.
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

local count = redis.call('ZCARD', key)
local allowed = 0
local retry_after_ms = 0

if count < limit then
  allowed = 1
  redis.call('ZADD', key, now, member)
  count = count + 1
else
  -- Denied: a slot frees when the oldest entry ages out of the window.
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  if oldest[2] then
    retry_after_ms = math.max(1, math.ceil(tonumber(oldest[2]) + window - now))
  else
    retry_after_ms = window
  end
end

redis.call('PEXPIRE', key, window)

return {allowed, limit - count, retry_after_ms}
