-- ddl-auto reference for V27__cache_invalidation.sql
-- One row per logical JVM-local (Caffeine) cache; version bumps propagate invalidations
-- across instances via polling.
create table if not exists cache_invalidation (
    cache_name varchar(100) primary key,
    version bigint not null,
    updated_at timestamp not null
);
