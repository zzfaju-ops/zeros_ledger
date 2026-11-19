package dev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class TransferConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired Reconciler reconciler;
    @Autowired JdbcClient jdbc;

    @Test
    void concurrentAndRetriedTransfersNeverBreakTheLedger() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = accounts.create("acct-" + i).id();
            transfers.transfer("fund-" + id, EXTERNAL, id, 10_000);
            ids.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            UUID from = ids.get(rnd.nextInt(ids.size()));
            UUID to = ids.get(rnd.nextInt(ids.size()));
            if (from.equals(to)) continue;
            long amount = rnd.nextLong(1, 4_000);
            String key = UUID.randomUUID().toString();
            int copies = rnd.nextInt(4) == 0 ? 3 : 1; // ~25% of requests are retried concurrently
            for (int c = 0; c < copies; c++) {
                futures.add(pool.submit(() -> {
                    try {
                        transfers.transfer(key, from, to, amount);
                    } catch (LedgerException e) {
                        if (e.status() != HttpStatus.UNPROCESSABLE_ENTITY) throw e; // insufficient funds is fine
                    }
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Invariant 1: the whole ledger sums to zero.
        assertEquals(0L, reconciler.ledgerTotal());

        // Invariant 2: no account was overdrawn, and money only moved between the five accounts.
        long sum = 0;
        for (UUID id : ids) {
            long balance = accounts.balance(id);
            assertTrue(balance >= 0, "account went negative: " + balance);
            sum += balance;
        }
        assertEquals(50_000L, sum);

        // Invariant 3: every transfer has exactly two entries and they cancel out.
        long broken = jdbc.sql("""
                        select count(*) from (
                          select transfer_id from ledger_entries
                          group by transfer_id
                          having count(*) <> 2 or sum(amount_cents) <> 0) x""")
                .query(Long.class).single();
        assertEquals(0L, broken);
    }
}
