package com.ran.seckill.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 异步下单任务：秒杀请求扣减 Redis 库存成功后投递到队列，
 * 由消费线程异步落库，把"抢"和"写"两件事解耦。
 * <p>
 * {@code retry} 用于失败重投计数：任务从队列弹出后若落库失败，
 * 必须重新入队，否则会出现"Redis 库存扣了、订单没生成"的丢单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTask {

    private String orderNo;
    private Long userId;
    private Long goodsId;
    private Long seckillGoodsId;
    private BigDecimal amount;
    private int retry;

    public OrderTask(String orderNo, Long userId, Long goodsId, Long seckillGoodsId, BigDecimal amount) {
        this(orderNo, userId, goodsId, seckillGoodsId, amount, 0);
    }
}
