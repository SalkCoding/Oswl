-- ddl-auto reference copy of V23__onboarding_progress.sql — kept in sync manually.
create table onboarding_progress (
    id bigint not null,
    completed_at timestamp not null,
    primary key (id)
);
