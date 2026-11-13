package com.ran.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ran.seckill.common.BusinessException;
import com.ran.seckill.common.ResultCode;
import com.ran.seckill.entity.Goods;
import com.ran.seckill.entity.SeckillGoods;
import com.ran.seckill.mapper.GoodsMapper;
import com.ran.seckill.mapper.SeckillGoodsMapper;
import com.ran.seckill.support.GoodsBloomFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品查询：演示布隆过滤器 + 缓存旁路的读路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsService {

    private static final String DETAIL_CACHE_PREFIX = "seckill:detail:";

    private final GoodsMapper goodsMapper;
    private final SeckillGoodsMapper seckillGoodsMapper;
    private final GoodsBloomFilter bloomFilter;
    private final StringRedisTemplate redis;

    public Goods getGoods(Long id) {
        // 布隆过滤器先挡一层：确认不存在的 ID 直接返回，不查库也不写缓存，防缓存穿透
        if (!bloomFilter.mightContain(id)) {
            log.debug("布隆过滤器拦截不存在的商品 id={}", id);
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }

        String key = DETAIL_CACHE_PREFIX + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            Goods g = new Goods();
            g.setId(id);
            g.setName(cached);
            return g;
        }

        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        long ttl = 300 + ThreadLocalRandom.current().nextInt(120);
        redis.opsForValue().set(key, goods.getName(), Duration.ofSeconds(ttl));
        return goods;
    }

    public List<Goods> listGoods() {
        return goodsMapper.selectList(new LambdaQueryWrapper<>());
    }

    public List<SeckillGoods> listSeckillGoods() {
        return seckillGoodsMapper.selectList(new LambdaQueryWrapper<>());
    }
}
