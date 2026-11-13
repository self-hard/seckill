package com.ran.seckill.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.seckill.common.Result;
import com.ran.seckill.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 限流拦截器。
 * <p>
 * 两个维度在同一段 Lua 里完成，一次 Redis 往返：
 * 1) 全局维度 —— 保护后端整体不被瞬时流量打垮
 * 2) 单用户维度 —— 拦截同一账号的脚本刷单
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String USER_KEY_PREFIX = "seckill:rl:user:";
    private static final String GLOBAL_KEY_PREFIX = "seckill:rl:global:";
    private static final String WINDOW_MS = "1000";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final ObjectMapper objectMapper;

    @Value("${seckill.user-qps:5}")
    private int userQps;

    @Value("${seckill.global-qps:3000}")
    private int globalQps;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        long window = System.currentTimeMillis() / 1000;
        String userId = request.getHeader("X-User-Id");

        String userKey = (userId == null || userId.isBlank())
                ? ""
                : USER_KEY_PREFIX + userId + ":" + window;

        Long allowed = redis.execute(rateLimitScript,
                List.of(userKey, GLOBAL_KEY_PREFIX + window),
                String.valueOf(userQps), String.valueOf(globalQps), WINDOW_MS);

        if (allowed == null) {
            return true;
        }
        if (allowed == 0L) {
            reject(response, "系统繁忙，请稍后再试");
            return false;
        }
        if (allowed == -1L) {
            reject(response, "单用户请求过于频繁，请稍后再试");
            return false;
        }
        return true;
    }

    private void reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ResultCode.RATE_LIMITED, message)));
    }
}
