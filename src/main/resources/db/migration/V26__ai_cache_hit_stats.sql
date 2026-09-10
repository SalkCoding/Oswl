-- Persistent hit/miss counters for the AI context-hash cache, aggregated per day + provider.
alter table ai_daily_usage add column if not exists cache_hits integer not null default 0;
alter table ai_daily_usage add column if not exists cache_misses integer not null default 0;
