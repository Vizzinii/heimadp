package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);
}
