-- Delete session atomically
local sessionKey, channelKey, imeiKey, activeSet, metricsKey =
  KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]

local id, auth = ARGV[1], ARGV[2]

redis.call("DEL", sessionKey)
if channelKey ~= "idx:channel:" then
  redis.call("DEL", channelKey)
end
if imeiKey ~= "idx:imei:" then
  redis.call("DEL", imeiKey)
end

redis.call("SREM", activeSet, id)
redis.call("HINCRBY", metricsKey, "total:sessions", "-1")
if auth == "1" then
  redis.call("HINCRBY", metricsKey, "authenticated:sessions", "-1")
end

return 1
