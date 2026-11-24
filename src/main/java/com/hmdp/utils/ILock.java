package com.hmdp.utils;

/**
 * 锁的操作约定，实现类负责完成加锁、设置有效期和解锁。
 */
public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁持有的有效期，单位为秒，过期后自动释放
     * @return true 代表获取锁成功；false 代表获取锁失败
     */
    // 接口中的普通抽象方法默认是 public abstract，实现类需提供 public 方法体。
    boolean tryLock(long timeoutSec);

    /**
     * 释放当前持有者获取的锁，由实现类校验锁的归属。
     */
    void unlock();
}
