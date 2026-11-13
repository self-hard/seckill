package com.ran.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ran.seckill.entity.SeckillGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {

    /**
     * 条件更新（CAS）扣减库存：只允许在 stock > 0 时扣减。
     * <p>
     * 为什么不用 version 乐观锁重试？
     * 1000 笔订单压在<b>同一行</b>库存上，version 冲突率极高，
     * 每个消费者线程都要反复"读 version → 更新 → 失败 → 重来"，
     * 实测有约 1/4 的订单在重试上限内拿不到锁而丢失。
     * <p>
     * 把"扣减"和"判断是否有货"合并成一条 WHERE 条件后，
     * 数据库行锁天然串行化，只要还有库存就一定扣减成功，
     * 配合 Redis 预减，既不会超卖也不会漏单。
     */
    @Update("""
            UPDATE seckill_goods
               SET stock = stock - 1,
                   version = version + 1
             WHERE id = #{id}
               AND stock > 0
            """)
    int deductStockAtomic(@Param("id") Long id);
}
