package com.ran.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_goods")
public class SeckillGoods {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;

    private BigDecimal seckillPrice;

    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**
     * 乐观锁版本号：DB 兜底扣减时使用，防止 Redis 与 DB 不一致导致超卖。
     */
    private Integer version;
}
