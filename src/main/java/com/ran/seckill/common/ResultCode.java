package com.ran.seckill.common;

/**
 * 业务状态码。
 */
public enum ResultCode {

    SUCCESS(0, "成功"),
    SOLDOUT(1001, "商品已售罄"),
    REPEAT_ORDER(1002, "请勿重复下单"),
    STOCK_NOT_WARMED(1003, "库存未就绪，请稍后再试"),
    NOT_STARTED(1004, "秒杀未开始"),
    ENDED(1005, "秒杀已结束"),
    GOODS_NOT_FOUND(1006, "商品不存在"),
    RATE_LIMITED(1007, "请求过于频繁，请稍后再试"),
    ORDER_NOT_FOUND(1008, "订单不存在"),
    PARAM_INVALID(1009, "参数不合法"),
    BUSINESS_ERROR(2000, "业务异常"),
    SYSTEM_ERROR(5000, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
