package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * shop对象与新增的expireTime组成了一个新的class
 * 装饰器模式：通过将对象放入包含行为的特殊包装类中来为原始对象动态添加新行为，这种模式是继承的一种替代方案，可以灵活拓展对象的功能。
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
