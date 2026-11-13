package com.ran.seckill.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.seckill.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下单任务消费者：固定数量工作线程轮询 Redis 队列。
 * <p>
 * 把瞬时并发流量"摊平"成数据库能承受的写入速率，即削峰。
 * <p>
 * 可靠性保障（至少一次投递）：
 * <ol>
 *   <li>出队不是 RPOP，而是 RPOPLPUSH 原子地把任务移入 processing 列表，
 *       任务在此刻有"正在处理"的明确状态，不会凭空消失</li>
 *   <li>落库成功后从 processing 中确认删除</li>
 *   <li>落库失败则重新入队；超过重试上限转入死信队列</li>
 *   <li>进程重启时把 processing 中未确认的任务搬回队列，防止宕机丢单</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueueConsumer implements ApplicationRunner {

    private static final int MAX_RETRY = 3;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    @Value("${seckill.queue-key:seckill:order:queue}")
    private String queueKey;

    @Value("${seckill.consumer-threads:16}")
    private int consumerThreads;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private String processingKey() {
        return queueKey + ":processing";
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
        start();
    }

    /** 启动时回收上次未确认的任务 */
    private void recover() {
        int recovered = 0;
        while (Boolean.TRUE.equals(redis.opsForList()
                .rightPopAndLeftPush(processingKey(), queueKey) != null)) {
            recovered++;
        }
        if (recovered > 0) {
            log.warn("回收未确认订单任务 {} 笔，已重新入队", recovered);
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        for (int i = 0; i < consumerThreads; i++) {
            Thread t = new Thread(this::loop, "order-consumer-" + i);
            t.setDaemon(true);
            t.start();
        }
        log.info("订单消费线程已启动，线程数={}", consumerThreads);
    }

    public void stop() {
        running.set(false);
        started.set(false);
    }

    private void loop() {
        while (running.get()) {
            String payload = null;
            try {
                payload = redis.opsForList().rightPopAndLeftPush(queueKey, processingKey());
                if (payload == null) {
                    Thread.sleep(50);
                    continue;
                }
                OrderTask task = objectMapper.readValue(payload, OrderTask.class);
                boolean success = orderService.createOrder(task);
                // 成功或"确定无需重试"（重复下单）都视为已处理，确认出队
                redis.opsForList().remove(processingKey(), 1, payload);
                if (!success) {
                    log.warn("订单未落库（重复或库存异常） orderNo={}", task.getOrderNo());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("订单消费失败，准备重投 payload={}", payload, e);
                requeue(payload);
            }
        }
        log.info("{} 退出", Thread.currentThread().getName());
    }

    private void requeue(String payload) {
        if (payload == null) {
            return;
        }
        try {
            redis.opsForList().remove(processingKey(), 1, payload);
            OrderTask task = objectMapper.readValue(payload, OrderTask.class);
            if (task.getRetry() >= MAX_RETRY) {
                redis.opsForList().leftPush(queueKey + ":dlq", payload);
                log.error("超过最大重试次数，转入死信队列 orderNo={}", task.getOrderNo());
                return;
            }
            task.setRetry(task.getRetry() + 1);
            redis.opsForList().leftPush(queueKey, objectMapper.writeValueAsString(task));
        } catch (Exception e) {
            log.error("任务重投失败，转入死信队列", e);
            redis.opsForList().leftPush(queueKey + ":dlq", payload);
        }
    }
}
