-- Singleton row set once the post-setup onboarding wizard is finished or dismissed.
create table onboarding_progress (
    id bigint not null,
    completed_at timestamp not null,
    primary key (id)
);
