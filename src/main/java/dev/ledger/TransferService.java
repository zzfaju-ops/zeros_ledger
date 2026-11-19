package dev.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {

    public record TransferResult(UUID id, UUID fromAccountId, UUID toAccountId, long amountCents, boolean replayed) {}

    private record LockedAccount(UUID id, boolean allowNegative) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public TransferService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Moves money exactly once per idempotency key.
     * Deliberately NOT @Transactional: we need to catch a unique-key violation
     * AFTER the failed transaction has rolled back, then replay the stored result.
     */
    public TransferResult transfer(String key, UUID from, UUID to, long amountCents) {
        if (from.equals(to)) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "cannot transfer to the same account");
        }
        Optional<TransferResult> existing = find(key);
        if (existing.isPresent()) {
            return replay(existing.get(), from, to, amountCents);
        }
        try {
            return tx.execute(status -> execute(key, from, to, amountCents));
        } catch (DuplicateKeyException e) {
            // Another request with the same key won the race (e.g. same key, different accounts locked).
            return replay(find(key).orElseThrow(), from, to, amountCents);
        }
    }

    private TransferResult execute(String key, UUID from, UUID to, long amountCents) {
        // 1. Lock both accounts in a fixed order (by id) so two opposite transfers can't deadlock.
        List<LockedAccount> locked = jdbc.sql(
                        "select id, allow_negative from accounts where id in (:ids) order by id for update")
                .param("ids", List.of(from, to))
                .query((rs, n) -> new LockedAccount(rs.getObject("id", UUID.class), rs.getBoolean("allow_negative")))
                .list();
        if (locked.size() != 2) {
            throw new LedgerException(HttpStatus.NOT_FOUND, "unknown account");
        }

        // 2. Re-check the key now that we hold the locks. A concurrent retry that queued behind
        //    the first request must see its committed result and replay it, not fail on funds.
        Optional<TransferResult> again = find(key);
        if (again.isPresent()) {
            return replay(again.get(), from, to, amountCents);
        }

        // 3. With the sender row locked, no other transfer can change this balance underneath us.
        boolean senderMayGoNegative = locked.stream()
                .filter(a -> a.id().equals(from)).findFirst().orElseThrow().allowNegative();
        if (!senderMayGoNegative && balanceOf(from) < amountCents) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient funds");
        }

        // 4. Write the transfer, the two ledger entries, and the outbox event atomically.
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into transfers (id, idempotency_key, from_account, to_account, amount_cents)
                        values (:id, :key, :from, :to, :amount)""")
                .param("id", id).param("key", key).param("from", from).param("to", to).param("amount", amountCents)
                .update();
        entry(id, from, -amountCents);
        entry(id, to, amountCents);

        String payload = String.format(
                "{\"transferId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"amountCents\":%d}",
                id, from, to, amountCents);
        jdbc.sql("insert into outbox_events (type, payload) values ('TransferCompleted', cast(:p as jsonb))")
                .param("p", payload).update();

        return new TransferResult(id, from, to, amountCents, false);
    }

    private void entry(UUID transferId, UUID accountId, long signedCents) {
        jdbc.sql("insert into ledger_entries (transfer_id, account_id, amount_cents) values (:t, :a, :c)")
                .param("t", transferId).param("a", accountId).param("c", signedCents).update();
    }

    private long balanceOf(UUID accountId) {
        return jdbc.sql("select coalesce(sum(amount_cents), 0)::bigint from ledger_entries where account_id = :id")
                .param("id", accountId).query(Long.class).single();
    }

    private Optional<TransferResult> find(String key) {
        return jdbc.sql("select id, from_account, to_account, amount_cents from transfers where idempotency_key = :k")
                .param("k", key)
                .query((rs, n) -> new TransferResult(
                        rs.getObject("id", UUID.class),
                        rs.getObject("from_account", UUID.class),
                        rs.getObject("to_account", UUID.class),
                        rs.getLong("amount_cents"),
                        true))
                .optional();
    }

    /** Same key + same request = return the original result. Same key + different request = reject. */
    private TransferResult replay(TransferResult stored, UUID from, UUID to, long amountCents) {
        if (!stored.fromAccountId().equals(from) || !stored.toAccountId().equals(to)
                || stored.amountCents() != amountCents) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key was already used with a different request");
        }
        return stored;
    }
}
