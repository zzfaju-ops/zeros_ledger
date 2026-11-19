-- Money is stored as integer cents. Never use floating point for money.
create table accounts (
    id             uuid primary key,
    name           text not null,
    allow_negative boolean not null default false,  -- only the system EXTERNAL account may go negative
    created_at     timestamptz not null default now()
);

-- One row per business transfer. The unique key is what makes retries safe.
create table transfers (
    id              uuid primary key,
    idempotency_key text not null unique,
    from_account    uuid not null references accounts(id),
    to_account      uuid not null references accounts(id),
    amount_cents    bigint not null check (amount_cents > 0),
    created_at      timestamptz not null default now()
);

-- Append-only. Balance = sum of entries. Each transfer has exactly two entries summing to zero.
create table ledger_entries (
    id           bigserial primary key,
    transfer_id  uuid not null references transfers(id),
    account_id   uuid not null references accounts(id),
    amount_cents bigint not null check (amount_cents <> 0),  -- negative = debit, positive = credit
    created_at   timestamptz not null default now()
);
create index ledger_entries_account_idx on ledger_entries(account_id, id);

-- Transactional outbox: written in the same transaction as the transfer.
create table outbox_events (
    id           bigserial primary key,
    type         text not null,
    payload      jsonb not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);
create index outbox_unpublished_idx on outbox_events(id) where published_at is null;

-- System account used to fund user accounts (deposits are transfers from EXTERNAL).
insert into accounts (id, name, allow_negative)
values ('00000000-0000-0000-0000-000000000001', 'EXTERNAL', true);
