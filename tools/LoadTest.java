import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 秒杀系统压测器（JDK 21 单文件，无需任何第三方依赖）。
 *
 * 用法:
 *   java tools/LoadTest.java [总请求数] [并发数] [库存] [seckillGoodsId]
 *   java tools/LoadTest.java 2000 500 1000 1
 *
 * 三个阶段:
 *   A 重复下单 —— 同一用户连发 5 次，只允许成功 1 次
 *   B 并发抢购 —— N 个不同用户同时抢，验证零超卖
 *   C 售罄校验 —— 库存归零后新用户必须被拒
 */
public class LoadTest {

    private static final Pattern CODE = Pattern.compile("\"code\":\\s*(-?\\d+)");
    private static final Pattern ORDER_COUNT = Pattern.compile("\"orderCount\":\\s*(\\d+)");
    private static final Pattern QUEUE_SIZE = Pattern.compile("\"queueSize\":\\s*(\\d+)");
    private static final Pattern STOCK_1 = Pattern.compile("\"seckillGoodsId1RedisStock\":\\s*(-?\\d+)");

    private static String base = "http://127.0.0.1:8080";
    private static long seckillGoodsId = 1L;
    private static HttpClient client;

    public static void main(String[] args) throws Exception {
        int total = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        int stock = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        seckillGoodsId = args.length > 3 ? Long.parseLong(args[3]) : 1L;

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        line();
        System.out.println("限时秒杀系统 压测");
        System.out.printf("  接口    : %s/api/seckill/%d%n", base, seckillGoodsId);
        System.out.printf("  库存    : %d%n", stock);
        System.out.printf("  并发    : %d 个用户，共 %d 次请求%n", concurrency, total);
        line();

        // ---------- 准备：重置数据 ----------
        String resetBody = post(base + "/api/admin/reset?seckillGoodsId=" + seckillGoodsId + "&stock=" + stock, null);
        System.out.println("[准备] 数据已重置，Redis 库存 = " + resetBody);

        // ---------- 阶段 A：重复下单 ----------
        System.out.println();
        System.out.println("[阶段 A] 同一用户连续下单 5 次");
        List<Integer> dupCodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            dupCodes.add(seckill(1_000_001L));
        }
        long dupOk = dupCodes.stream().filter(c -> c == 0).count();
        long dupRepeat = dupCodes.stream().filter(c -> c == 1002).count();
        System.out.println("  返回码: " + dupCodes);
        boolean dupPass = dupOk == 1 && dupRepeat == 4;
        System.out.printf("  成功 %d 次 / 重复拦截 %d 次 -> %s%n", dupOk, dupRepeat, dupPass ? "PASS" : "FAIL");

        // ---------- 阶段 B：并发抢购 ----------
        System.out.println();
        System.out.printf("[阶段 B] %d 个不同用户并发抢购（剩余库存 %d）%n", total, stock - 1);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<Integer>> futures = new ArrayList<>(total);
        long[] latencies = new long[total];
        long begin = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            final long userId = 2L + i; // 从 2 开始，避免和阶段 A 的用户重合
            final int idx = i;
            futures.add(pool.submit(() -> {
                long start = System.nanoTime();
                int code = seckill(userId);
                latencies[idx] = (System.nanoTime() - start) / 1_000_000L;
                return code;
            }));
        }

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldout = new AtomicInteger();
        AtomicInteger repeat = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        for (Future<Integer> f : futures) {
            int code = f.get();
            switch (code) {
                case 0 -> ok.incrementAndGet();
                case 1001 -> soldout.incrementAndGet();
                case 1002 -> repeat.incrementAndGet();
                default -> limited.incrementAndGet();
            }
        }
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdown();

        double seconds = elapsed / 1000.0;
        System.out.printf("  耗时 %.2fs，吞吐 %.1f req/s%n", seconds, total / Math.max(seconds, 0.001));
        printLatency(latencies);
        System.out.printf("  抢购成功 %d / 已售罄 %d / 重复下单 %d / 限流或异常 %d%n",
                ok.get(), soldout.get(), repeat.get(), limited.get());

        // ---------- 阶段 C：售罄校验 ----------
        System.out.println();
        System.out.println("[阶段 C] 库存耗尽后再来新用户");
        int afterCode = seckill(9_999_999L);
        boolean soldoutPass = afterCode == 1001;
        System.out.printf("  返回码: %d (%s)%n", afterCode, soldoutPass ? "已售罄，符合预期" : "异常");

        // ---------- 等待异步落库 ----------
        System.out.println();
        System.out.println("[收尾] 等待异步下单队列消化...");
        String stats = "";
        int orderCount = -1;
        int redisStock = -1;
        int queueSize = -1;
        int stable = 0;
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            stats = get(base + "/api/seckill/stats");
            int q = extract(QUEUE_SIZE, stats, -1);
            int oc = extract(ORDER_COUNT, stats, -1);
            redisStock = extract(STOCK_1, stats, -1);
            if (q == 0 && oc == orderCount) {
                stable++;
            } else {
                stable = 0;
            }
            queueSize = q;
            orderCount = oc;
            if (stable >= 8) {
                break;
            }
            Thread.sleep(400);
        }

        // ---------- 汇总 ----------
        boolean noOversell = orderCount == stock && redisStock == 0 && queueSize == 0;
        line();
        System.out.println("结果汇总");
        System.out.printf("  预期订单数 : %d%n", stock);
        System.out.printf("  实际订单数 : %d%n", orderCount);
        System.out.printf("  Redis 库存 : %d%n", redisStock);
        System.out.printf("  队列积压   : %d%n", queueSize);
        System.out.println();
        if (noOversell) {
            System.out.println("  [PASS] 零超卖：订单数与初始库存严格一致，Redis 库存归零");
        } else {
            System.out.println("  [FAIL] 存在不一致，请检查订单数与库存");
        }
        System.out.printf("  [%s] 重复下单防护%n", dupPass ? "PASS" : "FAIL");
        System.out.printf("  [%s] 售罄拦截%n", soldoutPass ? "PASS" : "FAIL");
        line();

        System.exit(noOversell && dupPass && soldoutPass ? 0 : 1);
    }

    private static int seckill(long userId) {
        return extract(CODE, post(base + "/api/seckill/" + seckillGoodsId, userId), -999);
    }

    private static String post(String url, Long userId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (userId != null) {
            builder.header("X-User-Id", String.valueOf(userId));
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "{\"code\":-999}";
        }
    }

    private static String get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET().build();
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static int extract(Pattern p, String json, int defaultValue) {
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    private static void printLatency(long[] latencies) {
        long[] sorted = latencies.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        double avg = java.util.Arrays.stream(sorted).average().orElse(0);
        System.out.printf("  响应时间: 平均 %.0fms / P50 %dms / P95 %dms / P99 %dms / 最大 %dms%n",
                avg, sorted[(int) (n * 0.50)], sorted[(int) (n * 0.95)],
                sorted[(int) (n * 0.99)], sorted[n - 1]);
    }

    private static void line() {
        System.out.println("=================================================");
    }
}
