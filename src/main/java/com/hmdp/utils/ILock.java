package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁（因为是非阻塞式，仅仅尝试一次，不会等待不会阻塞）
     * @param timeoutSec 锁的有效期，过期后自动释放
     * @return  true 代表获取锁成功， false 代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();

}
