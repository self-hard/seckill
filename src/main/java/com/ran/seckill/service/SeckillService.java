package com.ran.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.seckill.common.BusinessException;
import com.ran.seckill.common.ResultCode;
import com.ran.seckill.dto.SeckillResponse;
import com.ran.seckill.entity.SeckillGoods;
import com.ran.seckill.mapper.SeckillGoodsMapper;
import com.ran.seckill.support.OrderQueueProducer;
import com.ran.seckill.support.OrderTask;
import com.ran.seckill.support.StockPreloader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀核心链路。
 * <p>
 * 请求进来后只做三件事：读缓存判断活动状态 → Lua 原子扣减 Redis 库存 → 投递异步下单任务。
 * 整条链路不碰数据库，数据库压力由消费线程按自身节奏消化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private static final String GOODS_CACHE_PREFIX = "seckill:goods:";
    private static final int MAX_STOCK_GET_RETRY = 2;
    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SeckillGoodsMapper seckillGoodsMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> stockDeductScript;
    private final OrderQueueProducer orderQueueProducer;
    private final ObjectMapper objectMapper;

    public SeckillResponse seckill(Long userId, Long seckillGoodsId) {
        SeckillGoods goods = loadGoods(seckillGoodsId);
        if (goods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        if (goods.getStartTime() != null && now.isBefore(goods.getStartTime())) {
            throw new BusinessException(ResultCode.NOT_STARTED);
        }
        if (goods.getEndTime() != null && now.isAfter(goods.getEndTime())) {
            throw new BusinessException(ResultCode.ENDED);
        }

        String stockKey = StockPreloader.STOCK_KEY_PREFIX + seckillGoodsId;
        String usersKey = StockPreloader.USERS_KEY_PREFIX + seckillGoodsId;

        Long result = redis.execute(stockDeductScript, List.of(stockKey, usersKey), String.valueOf(userId));
        if (result == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
        switch (result.intValue()) {
            case 1 -> { /* 扣减成功，继续 */ }
            case 0 -> throw new BusinessException(ResultCode.SOLDOUT);
            case -1 -> throw new BusinessException(ResultCode.REPEAT_ORDER);
            case -2 -> throw new BusinessException(ResultCode.STOCK_NOT_WARMED);
            default -> throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }

        String orderNo = nextOrderNo();
        OrderTask task = new OrderTask(orderNo, userId, goods.getGoodsId(),
                seckillGoodsId, goods.getSeckillPrice());
        orderQueueProducer.submit(task);

        return new SeckillResponse(orderNo, "QUEUING", "抢购成功，订单处理中");
    }

    /**
     * 缓存旁路（Cache Aside）：先读 Redis，未命中再查库并回填。
     * 回填时加随机 TTL，避免同一时刻大批 key 集体过期造成缓存雪崩。
     */
    public SeckillGoods loadGoods(Long seckillGoodsId) {
        String key = GOODS_CACHE_PREFIX + seckillGoodsId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, SeckillGoods.class);
            }
        } catch (Exception e) {
            log.warn("读取商品缓存失败，降级查库 key={}", key, e);
        }

        SeckillGoods goods = seckillGoodsMapper.selectById(seckillGoodsId);
        if (goods != null) {
            try {
                long ttl = 600 + ThreadLocalRandom.current().nextInt(300);
                redis.opsForValue().set(key, objectMapper.writeValueAsString(goods), Duration.ofSeconds(ttl));
            } catch (Exception e) {
                log.warn("回填商品缓存失败 key={}", key, e);
            }
        }
        return goods;
    }

    public int currentStock(Long seckillGoodsId) {
        String value = redis.opsForValue().get(StockPreloader.STOCK_KEY_PREFIX + seckillGoodsId);
        return value == null ? -1 : Integer.parseInt(value);
    }

    public void evictGoodsCache(Long seckillGoodsId) {
        redis.delete(GOODS_CACHE_PREFIX + seckillGoodsId);
    }

    private String nextOrderNo() {
        return "SK" + LocalDateTime.now().format(ORDER_NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
