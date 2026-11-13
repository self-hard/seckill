package com.ran.seckill.controller;

import com.ran.seckill.common.BusinessException;
import com.ran.seckill.common.Result;
import com.ran.seckill.common.ResultCode;
import com.ran.seckill.entity.SeckillOrder;
import com.ran.seckill.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 凭订单号查询（异步下单后前端轮询此接口） */
    @GetMapping("/{orderNo}")
    public Result<SeckillOrder> detail(@PathVariable("orderNo") String orderNo) {
        SeckillOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return Result.ok(order);
    }

    /** 我的订单列表 */
    @GetMapping("/list")
    public Result<List<SeckillOrder>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(orderService.listByUser(userId));
    }
}
