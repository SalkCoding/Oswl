-- Persist per-user UI theme preference (light / dark / follow system).
alter table users add column theme varchar(20) not null default 'SYSTEM';
