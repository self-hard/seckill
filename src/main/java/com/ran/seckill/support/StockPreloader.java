package com.ran.seckill.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ran.seckill.entity.SeckillGoods;
import com.ran.seckill.mapper.SeckillGoodsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动预热：把秒杀库存写入 Redis，并把商品 ID 灌入布隆过滤器。
 * 秒杀期间所有扣减只打 Redis，数据库零压力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockPreloader implements ApplicationRunner {

    public static final String STOCK_KEY_PREFIX = "seckill:stock:";
    public static final String USERS_KEY_PREFIX = "seckill:users:";

    private final SeckillGoodsMapper seckillGoodsMapper;
    private final StringRedisTemplate redis;
    private final GoodsBloomFilter bloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        preload();
    }

    public void preload() {
        List<SeckillGoods> list = seckillGoodsMapper.selectList(new LambdaQueryWrapper<>());
        bloomFilter.reset();
        for (SeckillGoods goods : list) {
            String stockKey = STOCK_KEY_PREFIX + goods.getId();
            redis.opsForValue().set(stockKey, String.valueOf(goods.getStock()));
            redis.delete(USERS_KEY_PREFIX + goods.getId());
            bloomFilter.add(goods.getId());
            bloomFilter.add(goods.getGoodsId());
            log.info("预热完成 seckillGoodsId={} stock={}", goods.getId(), goods.getStock());
        }
        log.info("库存预热结束，共 {} 个秒杀商品", list.size());
    }
}
