package com.ran.seckill.service;

import com.ran.seckill.common.BusinessException;
import com.ran.seckill.common.ResultCode;
import com.ran.seckill.entity.SeckillOrder;
import com.ran.seckill.mapper.SeckillGoodsMapper;
import com.ran.seckill.mapper.SeckillOrderMapper;
import com.ran.seckill.support.OrderTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单笔订单落库：扣库存与写订单在同一事务内，任一失败整体回滚。
 */
@Service
@RequiredArgsConstructor
public class OrderTxService {

    private final SeckillGoodsMapper seckillGoodsMapper;
    private final SeckillOrderMapper seckillOrderMapper;

    @Transactional(rollbackFor = Exception.class)
    public void createOrderInTx(OrderTask task) {
        int updated = seckillGoodsMapper.deductStockAtomic(task.getSeckillGoodsId());
        if (updated == 0) {
            // Redis 已放行但库里没库存，说明两层数据不一致，属于需要告警的异常
            throw new BusinessException(ResultCode.SOLDOUT, "数据库库存已耗尽");
        }

        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(task.getOrderNo());
        order.setUserId(task.getUserId());
        order.setGoodsId(task.getGoodsId());
        order.setSeckillGoodsId(task.getSeckillGoodsId());
        order.setAmount(task.getAmount());
        order.setStatus(0);
        seckillOrderMapper.insert(order);
    }
}
