package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Vizzini
 * @since 2025-01-15
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    //全局唯一id生成器
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 定义代理对象，提前定义后面会用到
    private IVoucherOrderService proxy;

    // 注入脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建阻塞队列  这个阻塞队列特点：当一个线程尝试从队列获取元素的时候，如果没有元素该线程阻塞，直到队列中有元素才会被唤醒获取
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);//初始化阻塞队列的大小

    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.获取锁(可重入),自定义锁名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if (!isLock){
            // 获取锁失败，返回错误或重试
            log.error("您已购买过该商品，不能重复购买");
            return;
        }
        try {
            // 获取代理对象（事务）
            //使用代理对象，最后用于提交事务
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 0.获取用户
        Long userId = UserHolder.getUser().getId();

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), // 这里形参是是key数组，没有key，就传的一个空集合
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是0
        int r = result.intValue();// Long型转为int型，便于下面比较
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "优惠券已售罄" : "不能重复购买");

        }
        // 2.2.为0，代表有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.保存到阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象，执行创建订单
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok();
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀活动是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀活动尚未开始！");
//        }
//        // 3.判断秒杀活动是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已然结束
//            return Result.fail("秒杀已然结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足，不可下单。");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        // 创建锁对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
////        boolean isLock = simpleRedisLock.tryLock(1000);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            // 这个线程获取不到锁，说明有线程已经在下单了。
//            return Result.fail("不允许重复下单。");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
////            simpleRedisLock.unlock();
//            lock.unlock();
//        }
//
//
////        synchronized (userId.toString().intern()) {
////            // 原本是this.的方式调用的方法；但事务想要生效，还需要利用代理来生效
////            // 获取代理对象（事务）
////            // 获取锁后创建事务，释放锁前提交事务
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.实现一人一单逻辑
        // 5.1.用户id
        Long userId = voucherOrder.getUserId();
        // 5.2.查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        // 5.2.判断是否已经购买过
        if (count > 0) {
            // 用户已经购买过
            log.error("不可重复购买");
        }

        // 判断 stock>0 来保证不会扣减库存至负数，解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder).gt("stock", 0).update(); //where id = ? and stock > 0

        if (!success) {
            // 扣减库存
            log.error("库存不足，扣减失败！");
        }

        save(voucherOrder);

    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5.实现一人一单逻辑
//        // 5.1.用户id
//        Long userId = UserHolder.getUser().getId();
//
//        // 给每个用户加一把锁，而不是所有用户共用一把锁。
//        // 那就要根据用户id来加锁。但是toString()底层是每次都new一个String对象。
//        // 所以intern()方法在字符串池里面寻找“值一样的字符串”并返回那一个的地址。
//        //synchronized (userId.toString().intern()) {
//        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        // 5.2.判断是否已经购买过
//        if (count > 0) {
//            // 用户已经购买过
//            return Result.fail("不可重复购买");
//        }
//
//        // 6.扣减库存
////        boolean success = seckillVoucherService.update()
////                .setSql("stock= stock -1")
////                .eq("voucher_id", voucherId).update();
//
//        // 版本号实现乐观锁，解决超卖问题
////        boolean success = seckillVoucherService.update()
////                .setSql("stock= stock -1") //set stock = stock -1
////                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update(); //where id = ？ and stock = ?
//
//        // 判断 stock>0 来保证不会扣减库存至负数，解决超卖问题
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId).gt("stock", 0).update(); //where id = ? and stock > 0
//
//        if (!success) {
//            // 扣减库存
//            return Result.fail("库存不足，扣减失败！");
//        }
//        // 7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 7.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 7.2.用户id
//        voucherOrder.setUserId(userId);
//        // 7.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//        //}
//    }

}
