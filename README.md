## ZeroSum Ledger 
ZeroSum is a double-entry ledger API built with Java 21, Spring Boot, and PostgreSQL. Idempotent transfers and ordered locking keep balances correct under concurrency, verified by a Testcontainers stress test.

##ZeroSum demo
The recording shows a deposit, the same request repeated (the second response has "replayed": true and moves no money), a balance check, and an overdraw attempt rejected with insufficient funds.

##Why I built it
Payment and banking systems have to stay correct when requests retry, race, and fail halfway. I wanted to build the core of that kind of system and prove it holds up with tests, instead of building another CRUD app.

##Features
Double-entry accounting. Every transfer writes a debit and a credit in one transaction. Balances come from an append-only entries table, and the whole ledger always sums to zero.
Idempotent transfers. POST /transfers requires an Idempotency-Key header. Retrying the same request returns the original result and moves money once. Reusing a key with a different request is rejected.
Concurrency safety. Account rows are locked in a fixed order inside the transfer transaction, so simultaneous transfers cannot overdraw an account or deadlock.
Transactional outbox. Each transfer also writes an event row in the same transaction. A background poller publishes it (currently to the log) using FOR UPDATE SKIP LOCKED.
Reconciliation job. A scheduled task checks that the ledger sums to zero and logs an error if it drifts.
Statement endpoint with keyset pagination.under the same idempotency key to simulate client retries. It then asserts that:
The whole ledger still sums to zero.
No account went negative, and the total money in the five accounts is unchanged.
Every transfer has exactly two entries, and they cancel out.
Tech stack
Java 21, Spring Boot 3.5, Spring JDBC (JdbcClient), PostgreSQL 16, Flyway, JUnit 5, Testcontainers, Docker 

##Proof it works
TransferConcurrencyTest runs against a real PostgreSQL container (Testcontainers). It funds five accounts, then fires hundreds of random transfers from 32 threads, with about a quarter of them submitted three times at once Compose, GitHub Actions.
