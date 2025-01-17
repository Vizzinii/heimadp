package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    // 缓存重建所用的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询店铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 调用缓存穿透方法
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
        Shop shop = cacheClient
                .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回信息
        return Result.ok(shop);
    }

    /**
     * 解决缓存穿透的方法
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        //在redis中查询商户
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //存在，则返回
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 如果redis中为""即数据库存的空值，那么返回失败
        // 上一个if判断redis中是否为空，这个if判断redis是否为""
        if (shopJson != null){
            return null;
        }

        //不存在,在mysql数据库中查询
        Shop shop = getById(id);
        //不存在，返回错误信息
        if (shop == null){
            // 将空值放入redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        // 存在，将查询的数据放到redis中
        // 并同时设置 redis 缓存的过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回信息
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1.在redis中查询商户
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        // 2.1.存在，则返回
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 2.2如果redis中为""空字符串,返回失败
        if (shopJson != null){
            return null;
        }

        // 3.redis中不存在该数据，在mysql数据库中查询
        // 3.1.获取锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            // 先获取互斥锁
            boolean isLocked = trylock(lockKey);

            // 3.2.判断是否获取
            if (!isLocked) {
                // 3.3.获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 3.4.成功获取，开始重建
            // 3.5.获取锁后判断缓存是否能命中，即之前获取锁的进程是否修改redis
            String s = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(s)){
                shop = JSONUtil.toBean(s, Shop.class);
                return shop;
            }
            // 如果redis中为""空字符串,返回失败
            if (s != null){
                return null;
            }

            // 3.6.redis中的确仍不存在,在mysql数据库中查询
            // 4.进行缓存重建
            shop = getById(id);

            // 4.2.模拟延迟
            Thread.sleep(200);

            // 4.3.数据库中不存在，返回错误信息
            if (shop == null){
                // 把空值写到redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            // 4.4.存在，将查询的数据放到redis中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 5.释放互斥锁
            unlock(lockKey);
        }

        // 6.返回信息
        return shop;
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



    /**
     * 缓存店铺数据及其逻辑过期时间
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        // 数据库中查询商户
        Shop shop = getById(id);
        Thread.sleep(200);

        // 将商户信息和逻辑过期时间封装
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 写到redis，此时没有设置ttl
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1.在redis中查询商户
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断redis中是否存在
        // 2.1.未命中则返回null
        if (StrUtil.isBlank(redisDataJson)){
            return null;
        }

        // 若命中，要考虑的事就多了，首先要检验expireTime是否过期
        // 2.2.命中,将json反序列为对象
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        // 3.判断缓存逻辑是否过期
        LocalDateTime expireTime = redisData.getExpireTime();

        // 3.1.未过期,直接返回shop
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 4.已过期，缓存重建
        String lockKey = LOCK_SHOP_KEY+id;

        // 4.1.获取互斥锁
        boolean isLock = trylock(lockKey);

        // 4.2.获取到锁，开启独立线程，实现缓存重建
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 4.3.释放锁
                    unlock(lockKey);
                }
            });
        }

        // 5.返回信息
        return shop;
    }


    /**
     * 缓存主动更新的方法
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null)return Result.fail("商户id为空");

        // 这里先“更新数据库”再“删除缓存”，降低多线程读缓存时出现缓存与数据库数据不一致的问题。
        // 更新数据库
        updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }
}
