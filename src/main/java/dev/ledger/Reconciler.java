package dev.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Double-entry invariant: across the whole ledger, debits and credits cancel out to exactly zero. */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final JdbcClient jdbc;

    public Reconciler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long ledgerTotal() {
        return jdbc.sql("select coalesce(sum(amount_cents), 0)::bigint from ledger_entries")
                .query(Long.class).single();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        long total = ledgerTotal();
        if (total != 0) {
            log.error("LEDGER DRIFT detected: entries sum to {} cents (expected 0)", total);
        } else {
            log.info("Reconciliation OK: ledger sums to zero");
        }
    }
}
