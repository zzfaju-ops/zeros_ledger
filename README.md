# Ledger Service

A double-entry ledger REST API in Java 21 and Spring Boot with PostgreSQL. Transfers are idempotent,
safe under concurrency, and publish events through a transactional outbox.

## Run

    docker compose up -d
    mvn spring-boot:run

## Test

    mvn verify        # needs Docker running (Testcontainers starts Postgres)

## Design decisions

TODO: write these in your own words (see GUIDE.md for the questions to answer).

## What I'd do next

TODO
