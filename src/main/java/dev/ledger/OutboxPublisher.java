package dev.ledger;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and "publishes" events (here: logs them; swap in a webhook or Kafka producer).
 * Delivery is at-least-once, so consumers must be idempotent.
 * SKIP LOCKED lets several instances poll the same table without blocking or double-publishing.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private record Row(long id, String type, String payload) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public OutboxPublisher(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        tx.executeWithoutResult(status -> {
            List<Row> rows = jdbc.sql("""
                            select id, type, payload::text as payload
                            from outbox_events
                            where published_at is null
                            order by id
                            limit 50
                            for update skip locked""")
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("type"), rs.getString("payload")))
                    .list();
            for (Row r : rows) {
                log.info("outbox publish id={} type={} payload={}", r.id(), r.type(), r.payload());
                jdbc.sql("update outbox_events set published_at = now() where id = :id")
                        .param("id", r.id()).update();
            }
        });
    }
}
