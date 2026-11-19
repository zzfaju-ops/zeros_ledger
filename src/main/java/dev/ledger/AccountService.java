package dev.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    public record Account(UUID id, String name) {}

    public record Entry(long id, UUID transferId, long amountCents, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public AccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account create(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into accounts (id, name) values (:id, :name)")
                .param("id", id).param("name", name).update();
        return new Account(id, name);
    }

    /** Balance is derived from the ledger, never stored. */
    public long balance(UUID accountId) {
        requireExists(accountId);
        return jdbc.sql("select coalesce(sum(amount_cents), 0)::bigint from ledger_entries where account_id = :id")
                .param("id", accountId).query(Long.class).single();
    }

    /** Keyset pagination: pass the smallest entry id from the previous page as {@code before}. */
    public List<Entry> entries(UUID accountId, int limit, Long before) {
        requireExists(accountId);
        long cursor = before == null ? Long.MAX_VALUE : before;
        return jdbc.sql("""
                        select id, transfer_id, amount_cents, created_at
                        from ledger_entries
                        where account_id = :id and id < :before
                        order by id desc
                        limit :limit""")
                .param("id", accountId).param("before", cursor).param("limit", limit)
                .query((rs, n) -> new Entry(
                        rs.getLong("id"),
                        rs.getObject("transfer_id", UUID.class),
                        rs.getLong("amount_cents"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private void requireExists(UUID accountId) {
        boolean exists = jdbc.sql("select exists(select 1 from accounts where id = :id)")
                .param("id", accountId).query(Boolean.class).single();
        if (!exists) {
            throw new LedgerException(HttpStatus.NOT_FOUND, "unknown account");
        }
    }
}
