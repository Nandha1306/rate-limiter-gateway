-- Refills the token bucket at KEYS[1] for the time elapsed on the Redis clock, takes one token if available, and
-- returns {allowed (1 or 0), whole tokens remaining, retry-after in ms}. ARGV[1] = capacity, ARGV[2] = tokens per second.

local MAX_TTL_MS = 2592000000
local MAX_RETRY_MS = 9007199254740991

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate_per_ms = tonumber(ARGV[2]) / 1000

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + tonumber(time[2]) / 1000

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

if now > ts then
  tokens = tokens + (now - ts) * rate_per_ms
  ts = now
end
tokens = math.min(capacity, tokens)

local allowed = 0
local retry_after = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after = math.min(MAX_RETRY_MS, math.ceil((1 - tokens) / rate_per_ms))
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, math.max(1, math.min(MAX_TTL_MS, math.ceil(2 * capacity / rate_per_ms))))

return { allowed, math.floor(tokens), retry_after }
