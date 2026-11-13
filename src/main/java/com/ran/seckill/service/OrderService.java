package com.ran.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ran.seckill.common.BusinessException;
import com.ran.seckill.entity.SeckillOrder;
import com.ran.seckill.mapper.SeckillOrderMapper;
import com.ran.seckill.support.OrderTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单落库：异步链路终点。
 * <p>
 * 一致性由三层保证：
 * 1) Redis Lua 中 SISMEMBER 判重，挡掉绝大多数重复请求
 * 2) 数据库唯一索引 uk_user_seckill 最终兜底
 * 3) stock > 0 条件更新，库存不会被扣成负数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderTxService orderTxService;
    private final SeckillOrderMapper seckillOrderMapper;

    public boolean createOrder(OrderTask task) {
        try {
            orderTxService.createOrderInTx(task);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("重复下单被唯一索引拦截 userId={} seckillGoodsId={}",
                    task.getUserId(), task.getSeckillGoodsId());
            return false;
        } catch (BusinessException e) {
            log.error("落库失败 orderNo={} 原因={}", task.getOrderNo(), e.getMessage());
            return false;
        }
    }

    public SeckillOrder getByOrderNo(String orderNo) {
        return seckillOrderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getOrderNo, orderNo));
    }

    public List<SeckillOrder> listByUser(Long userId) {
        return seckillOrderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getUserId, userId)
                .orderByDesc(SeckillOrder::getId));
    }

    public long countAll() {
        return seckillOrderMapper.selectCount(new LambdaQueryWrapper<>());
    }
}
