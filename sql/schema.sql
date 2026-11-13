-- ============================================================
-- 限时秒杀系统 建库建表脚本
-- 用法: mysql -u root -p < sql/schema.sql
-- ============================================================

DROP DATABASE IF EXISTS seckill;
CREATE DATABASE seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE seckill;

-- 商品表
CREATE TABLE goods (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name       VARCHAR(128) NOT NULL COMMENT '商品名称',
    price      DECIMAL(10, 2) NOT NULL COMMENT '原价',
    cover      VARCHAR(255) DEFAULT NULL COMMENT '封面图',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';

-- 秒杀商品表：version 字段用于乐观锁兜底扣减
CREATE TABLE seckill_goods (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '秒杀商品ID',
    goods_id      BIGINT       NOT NULL COMMENT '商品ID',
    seckill_price DECIMAL(10, 2) NOT NULL COMMENT '秒杀价',
    stock         INT          NOT NULL COMMENT '剩余库存',
    start_time    DATETIME     NOT NULL COMMENT '开始时间',
    end_time      DATETIME     NOT NULL COMMENT '结束时间',
    version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_id (goods_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀商品表';

-- 秒杀订单表：uk_user_seckill 唯一索引在数据库层兜底防重复下单
CREATE TABLE seckill_order (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no        VARCHAR(64)  NOT NULL COMMENT '订单号',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    goods_id        BIGINT       NOT NULL COMMENT '商品ID',
    seckill_goods_id BIGINT      NOT NULL COMMENT '秒杀商品ID',
    amount          DECIMAL(10, 2) NOT NULL COMMENT '成交价',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=已创建 1=已支付',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_seckill (user_id, seckill_goods_id),
    KEY idx_seckill_goods (seckill_goods_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀订单表';

-- 初始化数据：1000 件库存，便于压测验证"零超卖"
INSERT INTO goods (id, name, price, cover)
VALUES (1, '机械键盘 87 键 客制化版', 1299.00, '/img/keyboard.png'),
       (2, '2K 显示器 27 英寸', 1499.00, '/img/monitor.png');

INSERT INTO seckill_goods (id, goods_id, seckill_price, stock, start_time, end_time, version)
VALUES (1, 1, 99.00, 1000, '2026-01-01 00:00:00', '2030-12-31 23:59:59', 0),
       (2, 2, 199.00, 500, '2026-01-01 00:00:00', '2030-12-31 23:59:59', 0);
