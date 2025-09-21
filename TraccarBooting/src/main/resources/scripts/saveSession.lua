-- scripts/saveSession.lua

-- Save session atomically
local sessionKey   = KEYS[1]
local channelKey   = KEYS[2]
local imeiKey      = KEYS[3]
local activeSet    = KEYS[4]
local metricsKey   = KEYS[5]

local data         = ARGV[1]
local id           = ARGV[2]
local ttlSess      = tonumber(ARGV[3])   
local ttlIdx       = tonumber(ARGV[4])
local auth         = ARGV[5]

-- Set session data with TTL (ttlSess is now an integer)
redis.call("SET", sessionKey, data, "EX", ttlSess)

-- Set indices with TTL (ttlIdx is now an integer)
if channelKey ~= "idx:channel:" then
  redis.call("SET", channelKey, id, "EX", ttlIdx)
end
if imeiKey ~= "idx:imei:" then
  redis.call("SET", imeiKey, id, "EX", ttlIdx)
end

-- Maintain active sessions set
redis.call("SADD", activeSet, id)
redis.call("EXPIRE", activeSet, ttlIdx)

-- Update metrics
redis.call("HINCRBY", metricsKey, "total:sessions", 1)               
if auth == "1" then
  redis.call("HINCRBY", metricsKey, "authenticated:sessions", 1) 
end

return 1
