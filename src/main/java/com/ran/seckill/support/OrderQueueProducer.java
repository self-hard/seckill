package com.ran.seckill.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.seckill.common.BusinessException;
import com.ran.seckill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 下单任务生产者：用 Redis List 做异步队列（LPUSH 入、RPOP 出，先进先出）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueueProducer {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${seckill.queue-key:seckill:order:queue}")
    private String queueKey;

    public void submit(OrderTask task) {
        try {
            redis.opsForList().leftPush(queueKey, objectMapper.writeValueAsString(task));
        } catch (JsonProcessingException e) {
            log.error("下单任务序列化失败 orderNo={}", task.getOrderNo(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
    }

    public Long queueSize() {
        return redis.opsForList().size(queueKey);
    }
}
