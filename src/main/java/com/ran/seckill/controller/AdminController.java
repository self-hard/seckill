package com.ran.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ran.seckill.common.Result;
import com.ran.seckill.entity.SeckillGoods;
import com.ran.seckill.mapper.SeckillGoodsMapper;
import com.ran.seckill.mapper.SeckillOrderMapper;
import com.ran.seckill.service.SeckillService;
import com.ran.seckill.support.StockPreloader;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维接口：重置数据，方便反复跑压测。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SeckillGoodsMapper seckillGoodsMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StockPreloader stockPreloader;
    private final SeckillService seckillService;
    private final StringRedisTemplate redis;

    @Value("${seckill.queue-key:seckill:order:queue}")
    private String queueKey;

    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(
            @RequestParam(value = "seckillGoodsId", defaultValue = "1") Long seckillGoodsId,
            @RequestParam(value = "stock", defaultValue = "1000") Integer stock) {

        long deleted = seckillOrderMapper.delete(new LambdaQueryWrapper<>());
        redis.delete(queueKey);
        seckillGoodsMapper.update(null, new LambdaUpdateWrapper<SeckillGoods>()
                .eq(SeckillGoods::getId, seckillGoodsId)
                .set(SeckillGoods::getStock, stock)
                .set(SeckillGoods::getVersion, 0));
        seckillService.evictGoodsCache(seckillGoodsId);
        stockPreloader.preload();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deletedOrders", deleted);
        map.put("seckillGoodsId", seckillGoodsId);
        map.put("stock", seckillService.currentStock(seckillGoodsId));
        return Result.ok(map);
    }
}
