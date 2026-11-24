-- KEYS[1] 是锁的 Redis 键名，ARGV[1] 是 Java 传入的持有者标识。
-- Lua 数组下标从 1 开始。整个脚本执行期间，其他客户端命令无法穿插执行。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    -- 锁值匹配才删除，DEL 返回删除的键数量，成功删除一个键时为 1。
    return redis.call('DEL', KEYS[1])
end
-- 锁已过期或已被其他持有者获取时，保留当前数据并返回 0。
return 0
