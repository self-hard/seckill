package com.ran.seckill.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis Bitmap 的布隆过滤器，用于拦截不存在商品 ID 的请求，防缓存穿透。
 * <p>
 * 这里用双重哈希 + 线性组合构造 3 个哈希函数，误判率约 1/1000 量级，
 * 对"商品详情"这种低基数场景足够。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsBloomFilter {

    private static final String KEY = "seckill:bloom:goods";
    /** 位图长度：2^24 = 16M bit = 2MB */
    private static final long BIT_SIZE = 1L << 24;
    private static final int HASH_COUNT = 3;

    private final StringRedisTemplate redis;

    private long[] offsets(long id) {
        long h1 = id * 0x9E3779B97F4A7C15L;
        long h2 = id * 0xC2B2AE3D27D4EB4FL;
        long[] result = new long[HASH_COUNT];
        for (int i = 0; i < HASH_COUNT; i++) {
            long h = h1 + i * h2;
            result[i] = Math.floorMod(h, BIT_SIZE);
        }
        return result;
    }

    public void add(long id) {
        for (long offset : offsets(id)) {
            redis.opsForValue().setBit(KEY, offset, true);
        }
    }

    public boolean mightContain(long id) {
        for (long offset : offsets(id)) {
            if (!Boolean.TRUE.equals(redis.opsForValue().getBit(KEY, offset))) {
                return false;
            }
        }
        return true;
    }

    public void reset() {
        redis.delete(KEY);
        log.info("布隆过滤器已重置");
    }
}
