-- 原子选取最低负载处理人并递增负载计数
-- KEYS[1] = ticket:handler:load (ZSet key)
-- 返回: 处理人ID字符串，无可用处理人时返回空字符串
local least = redis.call('ZRANGE', KEYS[1], 0, 0)
if #least == 0 then
    return ''
end
local handlerId = least[1]
redis.call('ZINCRBY', KEYS[1], 1, handlerId)
return handlerId
