-- 限流脚本：一次调用同时完成"全局限流"与"单用户限流"，减少 Redis 往返
-- KEYS[1] = 单用户计数 key（无用户时传空串）
-- KEYS[2] = 全局计数 key
-- ARGV[1] = 单用户每秒上限
-- ARGV[2] = 全局每秒上限
-- ARGV[3] = 窗口长度（毫秒）
-- 返回: 1=放行  0=触发全局限流  -1=触发单用户限流

local userKey   = KEYS[1]
local globalKey = KEYS[2]
local userLimit = tonumber(ARGV[1])
local globalLimit = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local globalCount = redis.call('INCR', globalKey)
if globalCount == 1 then
    redis.call('PEXPIRE', globalKey, ttl)
end
if globalCount > globalLimit then
    return 0
end

if userKey ~= '' then
    local userCount = redis.call('INCR', userKey)
    if userCount == 1 then
        redis.call('PEXPIRE', userKey, ttl)
    end
    if userCount > userLimit then
        return -1
    end
end

return 1
