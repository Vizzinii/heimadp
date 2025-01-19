package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    //全局唯一id生成器
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀活动是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀活动尚未开始！");
        }
        // 3.判断秒杀活动是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已然结束
            return Result.fail("秒杀已然结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足，不可下单。");
        }

        Long userId = UserHolder.getUser().getId();

        synchronized (userId.toString().intern()) {
            // 原本是this.的方式调用的方法；但事务想要生效，还需要利用代理来生效
            // 获取代理对象（事务）
            // 获取锁后创建事务，释放锁前提交事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.实现一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();

        // 给每个用户加一把锁，而不是所有用户共用一把锁。
        // 那就要根据用户id来加锁。但是toString()底层是每次都new一个String对象。
        // 所以intern()方法在字符串池里面寻找“值一样的字符串”并返回那一个的地址。
        //synchronized (userId.toString().intern()) {
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否已经购买过
        if (count > 0) {
            // 用户已经购买过
            return Result.fail("不可重复购买");
        }

        // 6.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId).update();

        // 版本号实现乐观锁，解决超卖问题
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1") //set stock = stock -1
//                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update(); //where id = ？ and stock = ?

        // 判断 stock>0 来保证不会扣减库存至负数，解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0).update(); //where id = ? and stock > 0

        if (!success) {
            // 扣减库存
            return Result.fail("库存不足，扣减失败！");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        return Result.ok(orderId);
        //}
    }

}
