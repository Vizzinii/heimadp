package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryList();
}
