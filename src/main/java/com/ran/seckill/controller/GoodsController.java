package com.ran.seckill.controller;

import com.ran.seckill.common.Result;
import com.ran.seckill.entity.Goods;
import com.ran.seckill.service.GoodsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    @GetMapping
    public Result<List<Goods>> list() {
        return Result.ok(goodsService.listGoods());
    }

    @GetMapping("/{id}")
    public Result<Goods> detail(@PathVariable("id") Long id) {
        return Result.ok(goodsService.getGoods(id));
    }
}
