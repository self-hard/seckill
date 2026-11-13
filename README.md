# 限时秒杀系统（seckill）

面向高并发场景的秒杀后端实践项目：覆盖商品查询、秒杀下单、异步落库、限流防刷，
重点解决**超卖、重复下单、缓存穿透、流量削峰、消息可靠性**五类问题。

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.2.5、Spring MVC |
| 持久层 | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 缓存 / 队列 | Redis（Lua 脚本、List 队列、Bitmap） |
| 压测 | 自研 JDK HttpClient 并发压测器（零依赖） |

## 核心设计

| 问题 | 方案 |
|---|---|
| 超卖 | Redis + Lua 原子预减库存，`DECR` 与判重在同一脚本内完成；数据库 `stock > 0` 条件更新兜底，两层都不会把库存扣成负数 |
| 重复下单 | Lua 内 `SISMEMBER` 拦截 + 数据库唯一索引 `uk_user_seckill` 最终兜底 |
| 缓存穿透 | 启动时把商品 ID 灌入 Redis Bitmap 布隆过滤器，不存在的 ID 直接返回，不查库 |
| 缓存雪崩 | 商品缓存 TTL 加 0～300 秒随机抖动，避免同一时刻集体过期 |
| 流量削峰 | 秒杀请求只扣 Redis 并把下单任务投递到 Redis List，消费线程按数据库能承受的节奏落库 |
| 消息可靠性 | `RPOPLPUSH` 把任务原子移入 processing 列表，落库成功才确认；失败重投，超限转死信队列；进程重启自动回收未确认任务 |
| 防脚本刷单 | 限流拦截器单次 Lua 调用同时校验全局限流与单用户限流 |
| 削峰后对账 | `/api/seckill/stats` 输出库存、订单数、队列积压，压测后可一键核对 |

## 压测结果

环境：本机 Windows，客户端与服务端同机运行（共享 CPU），JDK 21 + MySQL 8.0 + Redis。

```
库存 1000，2000 个不同用户并发抢购

[阶段 A] 同一用户连下 5 单      成功 1 次 / 重复拦截 4 次        PASS
[阶段 B] 200 并发 2000 次请求   成功 999 / 售罄 1001 / 异常 0
[阶段 C] 库存耗尽后新用户        返回“已售罄”                  PASS

最终订单数 1000 = 初始库存 1000
Redis 库存 0，数据库库存 0，队列积压 0

[PASS] 零超卖  [PASS] 重复下单防护  [PASS] 售罄拦截
```

复现：

```powershell
java tools/LoadTest.java 2000 200 1000 1
#                总请求数 并发数 库存 秒杀商品ID
```

## 快速开始

```bash
# 1. 建表（会重建 seckill 库）
mysql -u root -p < sql/schema.sql

# 2. 启动 Redis（默认 6379）
redis-server

# 3. 修改 src/main/resources/application.yml 中的数据库账号密码

# 4. 构建并启动
mvn clean package -DskipTests
java -jar target/seckill-1.0.0.jar
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/goods` | 商品列表 |
| GET | `/api/goods/{id}` | 商品详情（布隆过滤器 + 缓存旁路） |
| GET | `/api/seckill/list` | 秒杀商品列表 |
| GET | `/api/seckill/{id}/stock` | 实时库存（读 Redis） |
| POST | `/api/seckill/{id}` | 秒杀下单，需请求头 `X-User-Id` |
| GET | `/api/seckill/stats` | 库存 / 订单数 / 队列积压 |
| GET | `/api/order/{orderNo}` | 订单查询 |
| GET | `/api/order/list` | 我的订单，需请求头 `X-User-Id` |
| POST | `/api/admin/reset` | 重置数据，便于反复压测 |

## 目录结构

```
src/main/java/com/ran/seckill/
├── common/       统一响应体、业务异常、全局异常处理
├── config/       Redis 脚本注册、拦截器注册
├── controller/   4 个 Controller，共 9 个接口
├── dto/          下单响应
├── entity/       Goods / SeckillGoods / SeckillOrder
├── interceptor/  限流拦截器
├── mapper/       MyBatis-Plus Mapper
├── service/      秒杀、商品、订单、落库事务
└── support/      库存预热、布隆过滤器、下单队列生产/消费
```

## 踩坑记录

开发过程中真实遇到并修复的三个问题，也是面试常问的点：

1. **乐观锁重试必须在独立事务中**
   最初在同一个 `@Transactional` 方法里循环重试，`UPDATE ... WHERE version = ?` 一直失败。
   原因是 MySQL 默认 REPEATABLE READ：同一事务内反复 `SELECT` 读到的是同一份快照，
   version 永远不变。改为每次尝试走独立事务后才正常。

2. **阻塞式 BRPOP 会拖垮整个连接池**
   消费端最初用 `BRPOP` 阻塞 800ms 等消息，高并发下它占住连接，
   后面的限流 Lua 脚本排队超过 2 秒直接超时。改为非阻塞轮询后消失。

3. **单行库存上的乐观锁冲突率过高会丢单**
   1000 笔订单压在一条库存记录上，version 冲突率极高，重试上限内约 1/4 订单拿不到锁而被丢弃。
   最终改为 `UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0` 条件更新：
   行锁天然串行化，只要有库存就一定扣减成功，既不超过也不漏。
   **同时发现"任务出队即失联"的隐患**，引入 `RPOPLPUSH` + processing 列表做到至少一次投递。
