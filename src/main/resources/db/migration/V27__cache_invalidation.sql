-- One row per logical JVM-local (Caffeine) cache. Services bump `version` inside the same
-- transaction as a settings mutation; every instance polls this table and evicts its local
-- cache when a version changes, so multi-instance deployments converge without shared infra.
create table if not exists cache_invalidation (
    cache_name varchar(100) primary key,
    version bigint not null,
    updated_at timestamp not null
);
