-- Find idle sessions
local activeSet, prefix, cutoff = KEYS[1], KEYS[2], ARGV[1]

local ids = redis.call("SMEMBERS", activeSet)
local out = {}

for _, sid in ipairs(ids) do
  local key = prefix .. sid
  local raw = redis.call("GET", key)
  if raw and raw:find('"lastActivityAt":') then
    local ts = raw:match('"lastActivityAt":(%d+)')
    if ts and tonumber(ts) < tonumber(cutoff) then
      table.insert(out, sid)
    end
  end
end

return out
