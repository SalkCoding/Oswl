-- ddl-auto reference for V20__user_theme.sql
-- Adds the per-user UI theme column to the users table.
alter table users add column theme varchar(20) not null default 'SYSTEM';
