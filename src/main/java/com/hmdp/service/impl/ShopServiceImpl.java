package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //在redis中查询商户
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在，则返回
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //不存在,在mysql数据库中查询
        Shop shop = getById(id);
        //不存在，返回错误信息
        if (shop == null){
            return Result.fail("商户不存在");
        }
        //存在，将查询的数据放到redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        //返回信息
        return Result.ok(shop);
    }
}
