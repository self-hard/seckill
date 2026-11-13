package com.ran.seckill.controller;

import com.ran.seckill.common.Result;
import com.ran.seckill.dto.SeckillResponse;
import com.ran.seckill.entity.SeckillGoods;
import com.ran.seckill.entity.SeckillOrder;
import com.ran.seckill.service.GoodsService;
import com.ran.seckill.service.OrderService;
import com.ran.seckill.service.SeckillService;
import com.ran.seckill.support.OrderQueueProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final GoodsService goodsService;
    private final OrderService orderService;
    private final OrderQueueProducer orderQueueProducer;

    /** 秒杀商品列表 */
    @GetMapping("/list")
    public Result<List<SeckillGoods>> list() {
        return Result.ok(goodsService.listSeckillGoods());
    }

    /** 查询某秒杀商品的实时库存（读 Redis） */
    @GetMapping("/{id}/stock")
    public Result<Integer> stock(@PathVariable("id") Long id) {
        return Result.ok(seckillService.currentStock(id));
    }

    /** 秒杀下单：核心接口 */
    @PostMapping("/{id}")
    public Result<SeckillResponse> seckill(@PathVariable("id") Long id,
                                           @RequestHeader("X-User-Id") Long userId) {
        return Result.ok(seckillService.seckill(userId, id));
    }

    /** 运行状态：库存 / 订单数 / 队列积压，压测后直接看这里对账 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seckillGoodsId1RedisStock", seckillService.currentStock(1L));
        map.put("seckillGoodsId2RedisStock", seckillService.currentStock(2L));
        map.put("orderCount", orderService.countAll());
        map.put("queueSize", orderQueueProducer.queueSize());
        return Result.ok(map);
    }
}
