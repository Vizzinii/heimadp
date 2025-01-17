package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意对象序列化为JSON并存储到string类型的key中，并且设置TTL，用于处理缓存穿透
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意对象序列化为JSON并存储到string类型的key中，并且设置逻辑过期时间，用于处理缓存击穿
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;

        // 在redis中查询商户
        String jsonObj = stringRedisTemplate.opsForValue().get(key);

        // 存在，则返回
        if (StrUtil.isNotBlank(jsonObj)){
            return JSONUtil.toBean(jsonObj, type);
        }
        // 如果redis中为""空字符串,返回null
        if (jsonObj != null){
            return null;
        }

        // 不存在,在mysql数据库中查询
        R r = dbFallback.apply(id);
        //不存在，返回错误信息
        if (r == null){
            //把空值写到redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，将查询的数据放到redis中
        this.set(key,r,time,unit);

        //返回信息
        return r;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbfunction
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbfunction,Long time,TimeUnit unit){
        String key = keyPrefix + id;

        // 在redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);

        // 未命中则返回null
        if (StrUtil.isBlank(json)){
            return null;
        }

        // 命中,将json反序列为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        // 判断缓存逻辑是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期,直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 已过期，缓存重建
        String lockKey = LOCK_SHOP_KEY+id;

        // 获取锁
        boolean isLock = trylock(lockKey);
        // 获取到锁，开启独立线程，实现缓存重建
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbfunction.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 返回信息
        return r;
    }

    public <R,ID> R queryWithMutex(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbfunction,Long time,TimeUnit unit){
        String key = keyPrefix + id;

        // 1.在redis中查询商户
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        // 2.1.存在，则返回
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        // 2.2如果redis中为""空字符串,返回失败
        if (json != null){
            return null;
        }

        // 3.redis中不存在该数据，在mysql数据库中查询
        // 3.1.获取锁
        String lockKey = LOCK_SHOP_KEY+id;
        R r = null;
        try {
            // 先获取互斥锁
            boolean isLocked = trylock(lockKey);

            // 3.2.判断是否获取
            if (!isLocked) {
                // 3.3.获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbfunction,time,unit);
            }

            // 3.4.成功获取，开始重建
            // 3.5.获取锁后判断缓存是否能命中，即之前获取锁的进程是否修改redis
            String s = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(s)){
                r = JSONUtil.toBean(s, type);
                return r;
            }
            // 如果redis中为""空字符串,返回失败
            if (s != null){
                return null;
            }

            // 3.6.redis中的确仍不存在,在mysql数据库中查询
            // 4.进行缓存重建
            r = dbfunction.apply(id);

            // 4.2.模拟延迟
            Thread.sleep(200);

            // 4.3.数据库中不存在，返回错误信息
            if (r == null){
                // 把空值写到redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",time,unit);
                return null;
            }

            // 4.4.存在，将查询的数据放到redis中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r), time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 5.释放互斥锁
            unlock(lockKey);
        }

        // 6.返回信息
        return r;
    }

    /**
     * 互斥锁上锁
     * @param key
     * @return
     */
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 互斥锁释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
