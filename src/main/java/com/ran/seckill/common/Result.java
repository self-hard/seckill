package com.ran.seckill.common;

import lombok.Data;

/**
 * 统一响应体：所有接口返回同一结构，便于前端与压测脚本统一处理。
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ResultCode rc) {
        Result<T> r = new Result<>();
        r.code = rc.getCode();
        r.message = rc.getMessage();
        return r;
    }

    public static <T> Result<T> fail(ResultCode rc, String message) {
        Result<T> r = fail(rc);
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
