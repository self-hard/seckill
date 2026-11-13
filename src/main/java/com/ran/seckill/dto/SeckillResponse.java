package com.ran.seckill.dto;

/**
 * 秒杀下单响应：异步链路下不返回完整订单，只返回订单号与排队状态，
 * 前端凭订单号轮询 /api/order/{orderNo} 查询最终结果。
 */
public record SeckillResponse(String orderNo, String status, String message) {
}
