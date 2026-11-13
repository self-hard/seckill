-- 秒杀预减库存脚本（原子操作）
-- KEYS[1] = 库存 key        seckill:stock:{seckillGoodsId}
-- KEYS[2] = 已购用户集合    seckill:users:{seckillGoodsId}
-- ARGV[1] = userId
-- 返回: 1=扣减成功  0=已售罄  -1=重复下单  -2=库存未预热

local stockKey = KEYS[1]
local userKey  = KEYS[2]
local userId   = ARGV[1]

local stock = redis.call('GET', stockKey)
if not stock then
    return -2
end

-- 先判重再判库存：已买过的用户不应再消耗库存
if redis.call('SISMEMBER', userKey, userId) == 1 then
    return -1
end

if tonumber(stock) <= 0 then
    return 0
end

redis.call('DECR', stockKey)
redis.call('SADD', userKey, userId)
return 1
