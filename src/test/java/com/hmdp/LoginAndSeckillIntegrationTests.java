package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "integration", matches = "true")
class LoginAndSeckillIntegrationTests {
    private static final String ORDER_STREAM = "test:stream:orders:seckill:" + UUID.randomUUID();

    @DynamicPropertySource
    static void useIsolatedStream(DynamicPropertyRegistry registry) {
        registry.add("hmdp.seckill.stream", () -> ORDER_STREAM);
    }

    @AfterAll
    static void cleanupStream(@Autowired VoucherOrderServiceImpl orders, @Autowired StringRedisTemplate redis) {
        orders.stop();
        redis.delete(ORDER_STREAM);
    }

    @Resource private TestRestTemplate http;
    @Resource private StringRedisTemplate redis;
    @Resource private JdbcTemplate db;

    private final List<String> redisKeys = new ArrayList<>();
    private final List<Long> vouchers = new ArrayList<>();
    private String phone;

    @AfterEach
    void cleanup() {
        for (Long id : vouchers) {
            db.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", id);
            db.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", id);
            db.update("DELETE FROM tb_voucher WHERE id = ?", id);
        }
        if (phone != null) {
            db.update("DELETE FROM tb_user WHERE phone = ?", phone);
        }
        if (!redisKeys.isEmpty()) {
            redis.delete(redisKeys);
        }
    }

    @Test
    void logoutRevokesOnlyTheCurrentToken() {
        String token = loginNewUser();
        String otherToken = loginWithIssuedCode();
        assertEquals(HttpStatus.OK, request("/user/me", HttpMethod.GET, token).getStatusCode());

        Result response = request("/user/logout", HttpMethod.POST, token).getBody();

        assertNotNull(response);
        assertTrue(response.getSuccess(), response.getErrorMsg());
        assertFalse(redis.hasKey("login:token:" + token));
        assertEquals(HttpStatus.UNAUTHORIZED, request("/user/me", HttpMethod.GET, token).getStatusCode());
        assertEquals(HttpStatus.OK, request("/user/me", HttpMethod.GET, otherToken).getStatusCode());
    }

    @Test
    void activityWindowIsCheckedBeforeRedisReservation() {
        String token = loginNewUser();
        LocalDateTime now = LocalDateTime.now();
        long expired = createVoucher(now.minusHours(2), now.minusHours(1));
        long upcoming = createVoucher(now.plusHours(1), now.plusHours(2));
        long streamSize = redis.opsForStream().size(ORDER_STREAM);

        verifyTimeRejection(expired, token, "秒杀已经结束！");
        verifyTimeRejection(upcoming, token, "秒杀尚未开始！");
        assertEquals(streamSize, redis.opsForStream().size(ORDER_STREAM));
    }

    @Test
    void activeVoucherIsConsumedAndAcknowledged() throws InterruptedException {
        String token = loginNewUser();
        LocalDateTime now = LocalDateTime.now();
        long voucher = createVoucher(now.minusHours(1), now.plusHours(1));
        long streamSize = redis.opsForStream().size(ORDER_STREAM);

        Result accepted = seckill(voucher, token);
        assertTrue(accepted.getSuccess(), accepted.getErrorMsg());
        long orderId = ((Number) accepted.getData()).longValue();

        await(() -> orderPersisted(orderId, voucher) && pendingCount() == 0, "订单应落库并确认");
        assertEquals(1, db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucher));
        assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucher));
        assertEquals("不能重复下单", seckill(voucher, token).getErrorMsg());
        assertEquals(streamSize + 1, redis.opsForStream().size(ORDER_STREAM));
    }

    @Test
    void pendingOrderRetriesAfterDatabaseStockIsRestored() throws InterruptedException {
        String token = loginNewUser();
        LocalDateTime now = LocalDateTime.now();
        long voucher = createVoucher(now.minusHours(1), now.plusHours(1));
        db.update("UPDATE tb_seckill_voucher SET stock = 0 WHERE voucher_id = ?", voucher);

        Result accepted = seckill(voucher, token);
        assertTrue(accepted.getSuccess(), accepted.getErrorMsg());
        long orderId = ((Number) accepted.getData()).longValue();
        try {
            await(() -> {
                PendingMessages pending = redis.opsForStream().pending(ORDER_STREAM, "g1", Range.unbounded(), 1);
                return pending.size() == 1 && pending.get(0).getTotalDeliveryCount() >= 2;
            }, "失败订单应保留在 Pending List 并被重试");
            assertFalse(orderPersisted(orderId, voucher));
        } finally {
            db.update("UPDATE tb_seckill_voucher SET stock = 2 WHERE voucher_id = ?", voucher);
        }
        await(() -> orderPersisted(orderId, voucher) && pendingCount() == 0, "重试成功后应落库并确认");
        assertEquals(1, db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucher));
        assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucher));
    }

    private boolean orderPersisted(long orderId, long voucherId) {
        return db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id = ? AND voucher_id = ?",
                Integer.class, orderId, voucherId) == 1;
    }

    private long pendingCount() {
        return redis.opsForStream().pending(ORDER_STREAM, "g1").getTotalPendingMessages();
    }

    private void await(BooleanSupplier condition, String message) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }

    private void verifyTimeRejection(long id, String token, String message) {
        Result rejected = seckill(id, token);
        assertFalse(rejected.getSuccess());
        assertEquals(message, rejected.getErrorMsg());
        assertEquals("2", redis.opsForValue().get("seckill:stock:" + id));
        assertFalse(redis.hasKey("seckill:order:" + id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, id));
    }

    private String loginNewUser() {
        String candidate;
        do {
            candidate = String.format("139%08d", ThreadLocalRandom.current().nextInt(100000000));
        } while (db.queryForObject("SELECT COUNT(*) FROM tb_user WHERE phone = ?", Integer.class, candidate) != 0
                || Boolean.TRUE.equals(redis.hasKey("login:code:" + candidate)));
        phone = candidate;
        redisKeys.add("login:code:" + phone);
        Result issued = http.postForObject("/user/code?phone=" + phone, null, Result.class);
        assertTrue(issued.getSuccess());
        return loginWithIssuedCode();
    }

    private String loginWithIssuedCode() {
        String code = redis.opsForValue().get("login:code:" + phone);
        Result result = http.postForObject("/user/login", Map.of("phone", phone, "code", code), Result.class);
        assertTrue(result.getSuccess(), result.getErrorMsg());
        String token = (String) result.getData();
        redisKeys.add("login:token:" + token);
        return token;
    }

    private long createVoucher(LocalDateTime beginTime, LocalDateTime endTime) {
        Long shop = db.queryForObject("SELECT MIN(id) FROM tb_shop", Long.class);
        Voucher row = new Voucher().setShopId(shop).setTitle("test-" + UUID.randomUUID().toString().substring(0, 8))
                .setSubTitle("test").setRules("test").setPayValue(100L).setActualValue(200L).setType(1).setStatus(1)
                .setStock(2).setBeginTime(beginTime).setEndTime(endTime);
        Result added = http.postForObject("/voucher/seckill", row, Result.class);
        assertTrue(added.getSuccess(), added.getErrorMsg());
        long id = ((Number) added.getData()).longValue();
        vouchers.add(id);
        redisKeys.add("seckill:stock:" + id);
        redisKeys.add("seckill:order:" + id);
        return id;
    }

    private Result seckill(long id, String token) {
        return request("/voucher-order/seckill/" + id, HttpMethod.POST, token).getBody();
    }

    private ResponseEntity<Result> request(String url, HttpMethod method, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", token);
        return http.exchange(url, method, new HttpEntity<>(headers), Result.class);
    }
}
