package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁，使用带有效期的 SET NX 加锁，使用 Lua 校验归属并解锁。
 * 当前实现采用固定有效期，业务执行期间没有自动续期机制。
 */
public class SimpleRedisLock implements ILock {
    // static final 表示类级常量，所有锁对象共用这个键名前缀。
    private static final String KEY_PREFIX = "lock:";
    // 泛型 Long 表示脚本返回的 Redis 整数结果在 Java 中映射为 Long。
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();

    // 静态代码块在类初始化时执行一次，所有锁对象共用脚本配置。
    static {
        // 从运行时类路径加载 src/main/resources/unlock.lua。
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // final 字段在构造后保持引用不变，key 保存完整 Redis 锁名。
    private final String key;
    // 每个锁对象生成一个 UUID 前缀，与线程 ID 组合标识本次使用该对象的持有者。
    private final String ownerPrefix = UUID.randomUUID().toString() + "-";
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @param name 业务锁名，例如 order:123，最终 Redis 键名为 lock:order:123
     * @param stringRedisTemplate 调用方传入的 Redis 客户端
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.key = KEY_PREFIX + name;
        // this 指向当前锁对象，右侧同名变量是构造方法参数。
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 一次命令同时完成“键不存在才写入”和“设置有效期”，成功返回 true。
        // timeoutSec 是持锁有效期，本方法只尝试一次，获取失败立即返回。
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, ownerId(), timeoutSec, TimeUnit.SECONDS);
        // Boolean 包装类型可能为 null，使用 equals 安全地转换为 boolean 判断结果。
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // singletonList 创建只含一个锁键的列表，对应 Lua 中的 KEYS[1]。
        // ownerId() 作为普通脚本参数传入，对应 ARGV[1]。
        // Redis 原子执行归属判断和删除，返回 1 表示已删除，0 表示未删除。
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), ownerId());
    }

    private String ownerId() {
        // 加锁与解锁需由同一线程使用同一个锁对象，才能得到相同的持有者标识。
        return ownerPrefix + Thread.currentThread().getId();
    }
}
